import hashlib
import hmac
import json
import logging
import os
import re
import shutil
import threading
import tempfile
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from dotenv import load_dotenv
from langchain_community.vectorstores import FAISS
from langchain_community.vectorstores.utils import DistanceStrategy
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.documents import Document
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pydantic import BaseModel, Field

from chatbot import load_documents

# Load the RAG service's local configuration before reading model settings.
load_dotenv(Path(__file__).with_name(".env"), override=True)
from ingestion import request_run, start_worker, worker_status

LOGGER = logging.getLogger("blueai.rag")

DEFAULT_CHAT_MODEL = "gemini-3.5-flash-lite"
DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001"
INDEX_METADATA_FILENAME = "index_meta.json"
WHITESPACE = re.compile(r"\s+")


def _int_env(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        return default
    return max(minimum, min(maximum, value))


def _float_env(name: str, default: float, minimum: float, maximum: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError:
        return default
    return max(minimum, min(maximum, value))


def _embedding_model() -> str:
    return os.getenv("GEMINI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)


def _chat_models() -> list[str]:
    primary = os.getenv("GEMINI_CHAT_MODEL", DEFAULT_CHAT_MODEL).strip()
    fallbacks = os.getenv("GEMINI_CHAT_FALLBACK_MODELS", "gemini-3.6-flash")
    configured = [primary, *(item.strip() for item in fallbacks.split(","))]
    # At most two 12-second provider attempts fit inside Java's 30-second read
    # timeout. Java owns request retries; nesting SDK retries would multiply cost.
    return list(dict.fromkeys(model for model in configured if model))[:2]


def _is_transient_completion_error(error: Exception) -> bool:
    message = str(error).upper()
    return any(marker in message for marker in (
        "429", "RESOURCE_EXHAUSTED", "500", "502", "503", "504", "UNAVAILABLE", "HIGH DEMAND",
        "TIMEOUT", "TIMED OUT", "CONNECTION RESET",
    ))


def _index_dir() -> Path:
    return Path(os.getenv("RAG_INDEX_DIR", ".rag-index"))


def _normalize_query(query: str) -> str:
    return WHITESPACE.sub(" ", query).strip()


def _relevance_from_distance(distance: float) -> float:
    """Map a FAISS squared-L2 distance to an approximate cosine similarity in [0, 1].

    Gemini embeddings are unit length at their default dimensionality, so
    squared_l2 = 2 - 2 * cosine. The mapping is monotonic either way, so ranking
    and thresholds stay consistent even if a future model returns unnormalized
    vectors.
    """
    return max(0.0, min(1.0, 1.0 - float(distance) / 2.0))


def _content_fingerprint(content: str) -> str:
    return hashlib.sha256(_normalize_query(content).encode("utf-8")).hexdigest()


def _extract_text(content: object) -> str:
    """Flatten a chat message body into plain text.

    Gemini 3 models answer with a list of content blocks, for example
    `[{"type": "text", "text": "...", "extras": {"signature": "..."}}]`, and may add
    reasoning blocks. Only text blocks belong in the answer; stringifying the list
    would leak its Python repr into the chat UI.
    """
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type", "text") == "text":
                text = block.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "".join(parts)
    return ""



class RetrievalRequest(BaseModel):
    userId: str = Field(min_length=1)
    query: str = Field(min_length=1)
    limit: int = Field(default=6, ge=1, le=20)
    # Optional allowlist used by machine integrations. Legacy callers retain
    # their owner-scoped behavior when this is omitted.
    allowedDocumentIds: list[str] | None = Field(default=None, max_length=100)


class RetrievalMatch(BaseModel):
    documentId: str
    chunkId: str
    content: str
    score: float
    filename: str
    sourceType: str
    approvalStatus: str


class RetrievalResponse(BaseModel):
    matches: list[RetrievalMatch]


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1)


class ChatCompletionRequest(BaseModel):
    model: str | None = None
    messages: list[ChatMessage] = Field(min_length=1)
    temperature: float = Field(default=0.2, ge=0, le=2)


class CompletionChoice(BaseModel):
    message: ChatMessage


class CompletionUsage(BaseModel):
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)


class ChatCompletionResponse(BaseModel):
    id: str
    model: str
    choices: list[CompletionChoice]
    usage: CompletionUsage | None = None


def _select_diverse(
    matches: list[RetrievalMatch], limit: int, per_document_limit: int
) -> list[RetrievalMatch]:
    """Keep the best matches while stopping one document from filling every slot.

    Matches arrive ordered by relevance. A first pass caps how many chunks each
    document may contribute; a second pass fills any remaining slots from the
    capped-out chunks so recall never drops when only one document matches.
    """
    selected: list[RetrievalMatch] = []
    deferred: list[RetrievalMatch] = []
    used_per_document: dict[str, int] = {}
    for match in matches:
        if len(selected) >= limit:
            break
        used = used_per_document.get(match.documentId, 0)
        if used >= per_document_limit:
            deferred.append(match)
            continue
        used_per_document[match.documentId] = used + 1
        selected.append(match)
    for match in deferred:
        if len(selected) >= limit:
            break
        selected.append(match)
    selected.sort(key=lambda match: match.score, reverse=True)
    return selected


class RagRuntime:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._vectorstore: FAISS | None = None

    def _embeddings(self) -> GoogleGenerativeAIEmbeddings:
        # task_type is left unset on purpose: langchain-google-genai then applies
        # RETRIEVAL_DOCUMENT when indexing and RETRIEVAL_QUERY when searching,
        # which is what Gemini recommends for RAG.
        return GoogleGenerativeAIEmbeddings(model=_embedding_model())

    @staticmethod
    def is_gemini_configured() -> bool:
        return bool(os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY"))

    @staticmethod
    def _read_index_model(index_dir: Path) -> str | None:
        metadata_path = index_dir / INDEX_METADATA_FILENAME
        if not metadata_path.exists():
            return None
        try:
            return json.loads(metadata_path.read_text(encoding="utf-8")).get("embeddingModel")
        except (OSError, ValueError) as error:
            LOGGER.warning("Unreadable index metadata, treating the index as current: %s", error)
            return None

    @staticmethod
    def _write_index_model(index_dir: Path) -> None:
        try:
            (index_dir / INDEX_METADATA_FILENAME).write_text(
                json.dumps({"embeddingModel": _embedding_model()}), encoding="utf-8"
            )
        except OSError as error:
            LOGGER.warning("Could not record index metadata: %s", error)

    def _get_vectorstore(self) -> FAISS:
        if self._vectorstore is not None:
            return self._vectorstore
        with self._lock:
            if self._vectorstore is None:
                index_dir = _index_dir()
                embeddings = self._embeddings()
                indexed_model = self._read_index_model(index_dir)
                reusable = index_dir.joinpath("index.faiss").exists() and indexed_model in (None, _embedding_model())
                if reusable:
                    # DistanceStrategy.COSINE does not change the FAISS metric here:
                    # langchain builds IndexFlatL2 for anything but MAX_INNER_PRODUCT,
                    # so raw scores are squared L2 distances (see _relevance_from_distance).
                    self._vectorstore = FAISS.load_local(
                        str(index_dir), embeddings, allow_dangerous_deserialization=True,
                        distance_strategy=DistanceStrategy.COSINE,
                    )
                    self._write_index_model(index_dir)
                else:
                    if indexed_model is not None:
                        LOGGER.warning(
                            "Embedding model changed from %s to %s: rebuilding the index. "
                            "Documents ingested earlier must be re-uploaded to reappear in search.",
                            indexed_model, _embedding_model(),
                        )
                    documents = load_documents()
                    if not documents:
                        raise RuntimeError("Knowledge base has no documents.")
                    splits = RecursiveCharacterTextSplitter(
                        chunk_size=1200,
                        chunk_overlap=200,
                        add_start_index=True,
                        strip_whitespace=True,
                    ).split_documents(documents)
                    self._vectorstore = FAISS.from_documents(
                        documents=splits,
                        embedding=embeddings,
                        distance_strategy=DistanceStrategy.COSINE,
                    )
                    index_dir.mkdir(parents=True, exist_ok=True)
                    self._vectorstore.save_local(str(index_dir))
                    self._write_index_model(index_dir)
        return self._vectorstore


    def search(self, user_id: str, query: str, limit: int, allowed_document_ids: list[str] | None = None) -> list[RetrievalMatch]:
        normalized_query = _normalize_query(query)
        if not normalized_query:
            return []
        if allowed_document_ids is not None and not allowed_document_ids:
            return []

        candidates = min(
            max(limit * _int_env("RAG_CANDIDATE_MULTIPLIER", 8, 1, 40), 40),
            _int_env("RAG_MAX_CANDIDATES", 200, limit, 1_000),
        )
        minimum_relevance = _float_env("RAG_MIN_RELEVANCE", 0.0, 0.0, 1.0)
        per_document_limit = _int_env("RAG_MAX_CHUNKS_PER_DOCUMENT", 3, 1, 20)

        with self._lock:
            store = self._get_vectorstore()
            # For machine tasks the caller's document allowlist must be applied
            # before top-k selection; otherwise unauthorized neighbors can fill
            # the candidate window. Search the indexed vectors and filter below.
            if allowed_document_ids is not None and store.index.ntotal > 100_000:
                raise RuntimeError("Integration retrieval index exceeds the 100,000-vector allowlist scan limit.")
            search_k = store.index.ntotal if allowed_document_ids is not None else candidates
            results = store.similarity_search_with_score(normalized_query, k=search_k)

        visible: list[RetrievalMatch] = []
        seen_chunks: set[str] = set()
        for index, (document, distance) in enumerate(results):
            metadata = document.metadata
            source_type = str(metadata.get("source_type") or "UNKNOWN")
            if source_type == "USER_DOCUMENT" and metadata.get("owner_id") != user_id:
                continue
            document_id = str(metadata.get("document_id") or metadata.get("source") or "unknown")
            if allowed_document_ids is not None and document_id not in allowed_document_ids:
                continue
            relevance = _relevance_from_distance(distance)
            if relevance < minimum_relevance:
                continue
            fingerprint = _content_fingerprint(document.page_content)
            if fingerprint in seen_chunks:
                continue
            seen_chunks.add(fingerprint)
            chunk_id = str(metadata.get("record_id") or f"{document_id}:{index}")
            visible.append(RetrievalMatch(
                documentId=document_id,
                chunkId=chunk_id,
                content=document.page_content,
                score=relevance,
                filename=str(metadata.get("filename") or metadata.get("citation_label") or document_id),
                sourceType=source_type,
                approvalStatus=str(metadata.get("approval_status") or "UNKNOWN"),
            ))

        return _select_diverse(visible, limit, per_document_limit)


    def add_user_document(self, document_id: str, owner_id: str, source_type: str, filename: str, text: str, chunk_size: int, chunk_overlap: int) -> int:
        chunks = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            add_start_index=True,
            strip_whitespace=True,
        ).split_documents([Document(page_content=text, metadata={
            "document_id": document_id,
            "owner_id": owner_id,
            "filename": filename,
            "source_type": source_type,
            "approval_status": "READY",
        })])
        if not chunks:
            raise RuntimeError("Document produced no text chunks.")
        texts = [chunk.page_content for chunk in chunks]
        metadatas = [chunk.metadata for chunk in chunks]
        # Embedding is a network call, so it stays outside the lock: ingesting a
        # large document must not block every concurrent search for its duration.
        vectors = self._embeddings().embed_documents(texts)
        with self._lock:
            vectorstore = self._get_vectorstore()
            # Java may retry the same claim after the durable index was saved but
            # before its result PATCH completed. Replace this document's chunks
            # while holding the same lock used by search/purge so a retry cannot
            # accumulate duplicate vectors. Embedding remains outside the lock.
            index_dir = _index_dir()
            try:
                self._delete_document_vectors(vectorstore, document_id)
                vectorstore.add_embeddings(list(zip(texts, vectors)), metadatas=metadatas)
                self._save_vectorstore_atomically(vectorstore, index_dir)
            except Exception:
                # Never expose unsaved chunks after a failed durable replacement.
                self._reload_durable_index_after_failure(index_dir)
                raise
            self._write_index_model(index_dir)
        return len(chunks)

    @staticmethod
    def _delete_document_vectors(vectorstore: FAISS, document_id: str) -> list[str]:
        docstore = getattr(vectorstore, "docstore", None)
        documents = getattr(docstore, "_dict", {})
        ids = [
            vector_id
            for vector_id, document in documents.items()
            if str(document.metadata.get("document_id") or "") == document_id
        ]
        if ids:
            delete = getattr(vectorstore, "delete", None)
            if not callable(delete):
                raise RuntimeError("The FAISS backend does not support safe document replacement.")
            delete(ids)
        return ids

    @staticmethod
    def _save_vectorstore_atomically(vectorstore: FAISS, index_dir: Path) -> None:
        """Stage in the writable index volume and swap each file with rollback.

        FAISS.save_local writes more than one file. Staging prevents a failed save
        from mutating the durable directory; the short file swap is serialized by
        RagRuntime's lock and rolls back if any replacement fails.
        """
        # Only the index volume is writable for the non-root runtime, not /app.
        # Staging inside it also keeps file renames on the same filesystem.
        # Never rename the mounted directory itself.
        index_dir.mkdir(parents=True, exist_ok=True)
        temp_dir = Path(tempfile.mkdtemp(prefix=".staging-", dir=index_dir))
        backup_dir = temp_dir / ".backup"
        try:
            vectorstore.save_local(str(temp_dir))
            staged_files = [path for path in temp_dir.iterdir() if path.is_file()]
            if not staged_files:
                raise RuntimeError("FAISS save produced no durable index files.")
            index_dir.mkdir(parents=True, exist_ok=True)
            backup_dir.mkdir()
            backups: list[tuple[Path, Path]] = []
            installed: list[Path] = []
            try:
                for staged in staged_files:
                    target = index_dir / staged.name
                    if target.exists():
                        backup = backup_dir / staged.name
                        os.replace(target, backup)
                        backups.append((target, backup))
                for staged in staged_files:
                    target = index_dir / staged.name
                    os.replace(staged, target)
                    installed.append(target)
            except Exception:
                for target in installed:
                    target.unlink(missing_ok=True)
                for target, backup in reversed(backups):
                    os.replace(backup, target)
                raise
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)

    def _reload_durable_index_after_failure(self, index_dir: Path) -> None:
        """Discard a mutated FAISS object and reload only from durable files."""
        self._vectorstore = None
        if not index_dir.joinpath("index.faiss").exists():
            return
        try:
            self._vectorstore = FAISS.load_local(
                str(index_dir),
                self._embeddings(),
                allow_dangerous_deserialization=True,
                distance_strategy=DistanceStrategy.COSINE,
            )
        except Exception:
            self._vectorstore = None
            LOGGER.exception("Could not reload the durable FAISS index after a failed mutation.")

    def purge_document(self, document_id: str) -> int:
        """Remove every FAISS/docstore entry for a Java document tombstone.

        The operation is serialized with search and ingestion, saves the compacted
        index only after FAISS accepts the deletion, and is idempotent when the
        document is already absent. A failure leaves the in-memory and on-disk
        index untouched so the Java retention loop can retry it.
        """
        with self._lock:
            index_dir = _index_dir()
            if self._vectorstore is None:
                if not index_dir.joinpath("index.faiss").exists():
                    return 0
                self._vectorstore = self._get_vectorstore()
            vectorstore = self._vectorstore
            docstore = getattr(vectorstore, "docstore", None)
            documents = getattr(docstore, "_dict", {})
            ids = [
                vector_id
                for vector_id, document in documents.items()
                if str(document.metadata.get("document_id") or "") == document_id
            ]
            if not ids:
                return 0
            delete = getattr(vectorstore, "delete", None)
            if not callable(delete):
                raise RuntimeError("The FAISS backend does not support safe document deletion.")
            delete(ids)
            try:
                self._save_vectorstore_atomically(vectorstore, index_dir)
                self._write_index_model(index_dir)
            except Exception:
                # delete() mutates the in-memory FAISS object before persistence.
                # Never report success from that object after save failure: restore
                # the durable view or force the next retry to reload/fail closed.
                self._reload_durable_index_after_failure(index_dir)
                raise
            return len(ids)

    def complete(self, request: ChatCompletionRequest) -> ChatCompletionResponse:
        message_types = {
            "system": SystemMessage,
            "user": HumanMessage,
            "assistant": AIMessage,
        }
        messages = [
            message_types[message.role](content=message.content)
            for message in request.messages
        ]
        models = _chat_models()
        for index, model_name in enumerate(models):
            try:
                # The Java client sends an OpenAI-compatible model name, which
                # may not be a valid Gemini model. This service owns the model
                # choices and only fails over on temporary provider errors.
                llm = ChatGoogleGenerativeAI(
                    model=model_name,
                    temperature=request.temperature,
                    max_tokens=2048,
                    max_retries=1,
                    timeout=12,
                )
                response = llm.invoke(messages)
                content = _extract_text(response.content)
                if not content.strip():
                    raise RuntimeError("Gemini returned an empty response.")
                metadata = response.usage_metadata or {}
                usage = None
                if all(key in metadata and metadata[key] is not None
                       for key in ("input_tokens", "output_tokens", "total_tokens")):
                    usage = CompletionUsage(
                        prompt_tokens=metadata["input_tokens"],
                        completion_tokens=metadata["output_tokens"],
                        total_tokens=metadata["total_tokens"],
                    )
                return ChatCompletionResponse(
                    id=f"chatcmpl-{uuid.uuid4()}",
                    model=model_name,
                    choices=[CompletionChoice(message=ChatMessage(role="assistant", content=content.strip()))],
                    usage=usage,
                )
            except Exception as error:
                has_fallback = index + 1 < len(models)
                if not has_fallback or not _is_transient_completion_error(error):
                    raise
                LOGGER.warning(
                    "Gemini chat model %s is temporarily unavailable; trying configured fallback %s.",
                    model_name,
                    models[index + 1],
                )
        raise RuntimeError("No Gemini chat model is configured.")


runtime = RagRuntime()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    worker_task = start_worker(runtime.add_user_document)
    try:
        yield
    finally:
        if worker_task is not None:
            worker_task.cancel()


app = FastAPI(title="BlueAI RAG Service", version="1.0.0", lifespan=lifespan)
DEVELOPMENT_WORKER_API_KEY = "local-worker-api-key-change-before-production"


def _require_worker_api_key(provided_api_key: str | None) -> None:
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key or hmac.compare_digest(expected_key, DEVELOPMENT_WORKER_API_KEY):
        raise HTTPException(status_code=503, detail="RAG service authentication is not configured.")
    if not hmac.compare_digest(provided_api_key or "", expected_key):
        raise HTTPException(status_code=401, detail="Worker API key is invalid.")


@app.get("/health")
def health() -> dict:
    index_dir = _index_dir()
    return {
        "status": "ok",
        "provider": "gemini",
        "geminiConfigured": runtime.is_gemini_configured(),
        "chatModel": os.getenv("GEMINI_CHAT_MODEL", DEFAULT_CHAT_MODEL),
        "embeddingModel": _embedding_model(),
        "indexPresent": index_dir.joinpath("index.faiss").exists(),
        "indexEmbeddingModel": RagRuntime._read_index_model(index_dir) or "unknown",
        "documentWorkerConfigured": bool(os.getenv("JAVA_API_BASE_URL") and os.getenv("JAVA_WORKER_API_KEY")),
        "documentWorker": worker_status(),
        "retrievalTuning": {
            "minRelevance": _float_env("RAG_MIN_RELEVANCE", 0.0, 0.0, 1.0),
            "maxChunksPerDocument": _int_env("RAG_MAX_CHUNKS_PER_DOCUMENT", 3, 1, 20),
            "candidateMultiplier": _int_env("RAG_CANDIDATE_MULTIPLIER", 8, 1, 40),
        },
    }


@app.post("/v1/internal/ingest/run", status_code=202)
def trigger_ingest(x_worker_api_key: str | None = Header(default=None)) -> dict[str, str]:
    """Woken by the web app right after an upload. Authenticated with the shared worker
    key, exactly like the Java side authenticates the worker's own calls."""
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key or not os.getenv("JAVA_API_BASE_URL"):
        raise HTTPException(status_code=503, detail="Document worker is not configured.")
    _require_worker_api_key(x_worker_api_key)
    request_run()
    return {"status": "queued"}


@app.post("/v1/internal/documents/{document_id}/purge")
def purge_document(document_id: str, x_worker_api_key: str | None = Header(default=None)) -> dict[str, object]:
    """Idempotently compact one deleted document out of FAISS and its docstore."""
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key:
        raise HTTPException(status_code=503, detail="Document worker is not configured.")
    _require_worker_api_key(x_worker_api_key)
    try:
        removed_chunks = runtime.purge_document(document_id)
    except Exception as exception:
        LOGGER.warning("Could not purge document %s from the vector store: %s", document_id, exception)
        raise HTTPException(status_code=503, detail="Document vector purge unavailable.") from exception
    return {"status": "purged", "documentId": document_id, "removedChunks": removed_chunks}


@app.get("/ready")
def readiness() -> dict[str, str]:
    # Startup gate only: auth-api depends on this before it can start, so do not
    # require a successful Java worker poll here (that would be a dependency cycle).
    if not runtime.is_gemini_configured():
        raise HTTPException(
            status_code=503,
            detail="Gemini is not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY.",
        )
    return {"status": "ready", "provider": "gemini"}


@app.get("/readiness")
def operational_readiness() -> dict[str, str]:
    """Post-start deployment gate, with no billable provider request."""
    readiness()
    worker = worker_status()
    if not worker.get("configured") or not worker.get("running"):
        raise HTTPException(status_code=503, detail="Document worker is not running or configured.")
    if int(worker.get("consecutiveFailures", 0)) >= 3:
        raise HTTPException(status_code=503, detail="Document worker has sustained polling failures.")
    try:
        last_success = datetime.fromisoformat(str(worker.get("lastSuccessAt")))
        if last_success.tzinfo is None:
            raise ValueError("Worker timestamp must include a timezone.")
        age = (datetime.now(timezone.utc) - last_success).total_seconds()
    except (TypeError, ValueError):
        raise HTTPException(status_code=503, detail="Document worker has not completed a successful poll.") from None
    max_age = max(180, int(worker.get("intervalSeconds", 5)) * 3)
    if age < -5 or age > max_age:
        raise HTTPException(status_code=503, detail="Document worker has no recent successful poll.")
    return {"status": "ready", "provider": "gemini", "documentWorker": "ready"}


@app.post("/v1/retrieval/search", response_model=RetrievalResponse)
def retrieval_search(
    request: RetrievalRequest,
    x_worker_api_key: str | None = Header(default=None),
) -> RetrievalResponse:
    _require_worker_api_key(x_worker_api_key)
    try:
        if request.allowedDocumentIds is None:
            matches = runtime.search(request.userId, request.query.strip(), request.limit)
        else:
            matches = runtime.search(request.userId, request.query.strip(), request.limit, request.allowedDocumentIds)
        return RetrievalResponse(matches=matches)
    except Exception as exception:
        raise HTTPException(status_code=503, detail=f"RAG retrieval unavailable: {exception}") from exception


@app.post("/v1/chat/completions", response_model=ChatCompletionResponse)
def chat_completions(
    request: ChatCompletionRequest,
    x_worker_api_key: str | None = Header(default=None),
) -> ChatCompletionResponse:
    _require_worker_api_key(x_worker_api_key)
    try:
        return runtime.complete(request)
    except Exception as exception:
        raise HTTPException(status_code=502, detail=f"Gemini completion failed: {exception}") from exception
