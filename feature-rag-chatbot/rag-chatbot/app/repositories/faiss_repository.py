"""Thread-safe FAISS storage, index persistence and document replacement."""

import json
import logging
import os
import shutil
import tempfile
import threading
from pathlib import Path

from langchain_community.vectorstores import FAISS
from langchain_community.vectorstores.utils import DistanceStrategy
from langchain_core.documents import Document
from langchain_google_genai import GoogleGenerativeAIEmbeddings
from app.services.embedding_usage import MeteredEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.core.config import INDEX_METADATA_FILENAME, _embedding_model, _index_dir
from app.repositories.knowledge_base import load_documents

LOGGER = logging.getLogger("blueai.rag.index")


class FaissRepository:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._vectorstore: FAISS | None = None

    def _embeddings(self) -> GoogleGenerativeAIEmbeddings:
        # task_type is left unset on purpose: langchain-google-genai then applies
        # RETRIEVAL_DOCUMENT when indexing and RETRIEVAL_QUERY when searching,
        # which is what Gemini recommends for RAG.
        return MeteredEmbeddings(model=_embedding_model())

    @staticmethod
    def is_gemini_configured() -> bool:
        return bool(os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY"))

    @staticmethod
    def _read_index_model(index_dir: Path) -> str | None:
        metadata_path = index_dir / INDEX_METADATA_FILENAME
        if not metadata_path.exists():
            return None
        try:
            return json.loads(metadata_path.read_text(encoding="utf-8")).get(
                "embeddingModel"
            )
        except (OSError, ValueError) as error:
            LOGGER.warning(
                "Unreadable index metadata, treating the index as current: %s", error
            )
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
                reusable = index_dir.joinpath(
                    "index.faiss"
                ).exists() and indexed_model in (None, _embedding_model())
                if reusable:
                    # DistanceStrategy.COSINE does not change the FAISS metric here:
                    # langchain builds IndexFlatL2 for anything but MAX_INNER_PRODUCT,
                    # so raw scores are squared L2 distances (see _relevance_from_distance).
                    self._vectorstore = FAISS.load_local(
                        str(index_dir),
                        embeddings,
                        allow_dangerous_deserialization=True,
                        distance_strategy=DistanceStrategy.COSINE,
                    )
                    self._write_index_model(index_dir)
                else:
                    if indexed_model is not None:
                        LOGGER.warning(
                            "Embedding model changed from %s to %s: rebuilding the index. "
                            "Documents ingested earlier must be re-uploaded to reappear in search.",
                            indexed_model,
                            _embedding_model(),
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

    def add_user_document(
        self,
        document_id: str,
        owner_id: str,
        source_type: str,
        filename: str,
        text: str,
        chunk_size: int,
        chunk_overlap: int,
    ) -> int:
        chunks = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            add_start_index=True,
            strip_whitespace=True,
        ).split_documents(
            [
                Document(
                    page_content=text,
                    metadata={
                        "document_id": document_id,
                        "owner_id": owner_id,
                        "filename": filename,
                        "source_type": source_type,
                        "approval_status": "READY",
                    },
                )
            ]
        )
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
                vectorstore.add_embeddings(
                    list(zip(texts, vectors)), metadatas=metadatas
                )
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
                raise RuntimeError(
                    "The FAISS backend does not support safe document replacement."
                )
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
            LOGGER.exception(
                "Could not reload the durable FAISS index after a failed mutation."
            )

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
                raise RuntimeError(
                    "The FAISS backend does not support safe document deletion."
                )
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
