import hashlib
import json
import logging
import os
import re
import threading
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from langchain_community.vectorstores import FAISS
from langchain_community.vectorstores.utils import DistanceStrategy
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.documents import Document
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pydantic import BaseModel, Field

from chatbot import load_documents
from ingestion import start_worker

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



class RetrievalRequest(BaseModel):
    userId: str = Field(min_length=1)
    query: str = Field(min_length=1)
    limit: int = Field(default=6, ge=1, le=20)


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


class ChatCompletionResponse(BaseModel):
    id: str
    model: str
    choices: list[CompletionChoice]


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


    def search(self, user_id: str, query: str, limit: int) -> list[RetrievalMatch]:
        normalized_query = _normalize_query(query)
        if not normalized_query:
            return []

        candidates = min(
            max(limit * _int_env("RAG_CANDIDATE_MULTIPLIER", 8, 1, 40), 40),
            _int_env("RAG_MAX_CANDIDATES", 200, limit, 1_000),
        )
        minimum_relevance = _float_env("RAG_MIN_RELEVANCE", 0.0, 0.0, 1.0)
        per_document_limit = _int_env("RAG_MAX_CHUNKS_PER_DOCUMENT", 3, 1, 20)

        with self._lock:
            results = self._get_vectorstore().similarity_search_with_score(
                normalized_query, k=candidates
            )

        visible: list[RetrievalMatch] = []
        seen_chunks: set[str] = set()
        for index, (document, distance) in enumerate(results):
            metadata = document.metadata
            source_type = str(metadata.get("source_type") or "UNKNOWN")
            if source_type == "USER_DOCUMENT" and metadata.get("owner_id") != user_id:
                continue
            relevance = _relevance_from_distance(distance)
            if relevance < minimum_relevance:
                continue
            fingerprint = _content_fingerprint(document.page_content)
            if fingerprint in seen_chunks:
                continue
            seen_chunks.add(fingerprint)
            document_id = str(metadata.get("document_id") or metadata.get("source") or "unknown")
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
            vectorstore.add_embeddings(list(zip(texts, vectors)), metadatas=metadatas)
            index_dir = _index_dir()
            index_dir.mkdir(parents=True, exist_ok=True)
            vectorstore.save_local(str(index_dir))
            self._write_index_model(index_dir)
        return len(chunks)

    def complete(self, request: ChatCompletionRequest) -> ChatCompletionResponse:
        configured_model = os.getenv("GEMINI_CHAT_MODEL", DEFAULT_CHAT_MODEL)
        model_name = request.model or configured_model
        llm = ChatGoogleGenerativeAI(
            model=model_name,
            temperature=request.temperature,
            max_tokens=2048,
            retries=2,
            request_timeout=60,
        )
        message_types = {
            "system": SystemMessage,
            "user": HumanMessage,
            "assistant": AIMessage,
        }
        response = llm.invoke([
            message_types[message.role](content=message.content)
            for message in request.messages
        ])
        content = response.content if isinstance(response.content, str) else str(response.content)
        if not content.strip():
            raise RuntimeError("Gemini returned an empty response.")
        return ChatCompletionResponse(
            id=f"chatcmpl-{uuid.uuid4()}",
            model=model_name,
            choices=[CompletionChoice(message=ChatMessage(role="assistant", content=content.strip()))],
        )


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


@app.get("/health")
def health() -> dict[str, str | bool | dict[str, float | int]]:
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
        "retrievalTuning": {
            "minRelevance": _float_env("RAG_MIN_RELEVANCE", 0.0, 0.0, 1.0),
            "maxChunksPerDocument": _int_env("RAG_MAX_CHUNKS_PER_DOCUMENT", 3, 1, 20),
            "candidateMultiplier": _int_env("RAG_CANDIDATE_MULTIPLIER", 8, 1, 40),
        },
    }


@app.get("/ready")
def readiness() -> dict[str, str]:
    if not runtime.is_gemini_configured():
        raise HTTPException(
            status_code=503,
            detail="Gemini is not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY.",
        )
    return {"status": "ready", "provider": "gemini"}


@app.post("/v1/retrieval/search", response_model=RetrievalResponse)
def retrieval_search(request: RetrievalRequest) -> RetrievalResponse:
    try:
        return RetrievalResponse(matches=runtime.search(request.userId, request.query.strip(), request.limit))
    except Exception as exception:
        raise HTTPException(status_code=503, detail=f"RAG retrieval unavailable: {exception}") from exception


@app.post("/v1/chat/completions", response_model=ChatCompletionResponse)
def chat_completions(request: ChatCompletionRequest) -> ChatCompletionResponse:
    try:
        return runtime.complete(request)
    except Exception as exception:
        raise HTTPException(status_code=502, detail=f"Gemini completion failed: {exception}") from exception
