"""Normalize, rank and filter retrieval matches before returning evidence."""

import hashlib
import re

from app.core.config import _float_env, _int_env
from app.models.schemas import RetrievalMatch
from app.repositories.faiss_repository import FaissRepository

WHITESPACE = re.compile(r"\s+")


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


class RetrievalService:
    def __init__(self, repository: FaissRepository) -> None:
        self.repository = repository

    def search(
        self,
        user_id: str,
        query: str,
        limit: int,
        allowed_document_ids: list[str] | None = None,
    ) -> list[RetrievalMatch]:
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

        with self.repository._lock:
            store = self.repository._get_vectorstore()
            # For machine tasks the caller's document allowlist must be applied
            # before top-k selection; otherwise unauthorized neighbors can fill
            # the candidate window. Search the indexed vectors and filter below.
            if allowed_document_ids is not None and store.index.ntotal > 100_000:
                raise RuntimeError(
                    "Integration retrieval index exceeds the 100,000-vector allowlist scan limit."
                )
            search_k = (
                store.index.ntotal if allowed_document_ids is not None else candidates
            )
            results = store.similarity_search_with_score(normalized_query, k=search_k)

        visible: list[RetrievalMatch] = []
        seen_chunks: set[str] = set()
        for index, (document, distance) in enumerate(results):
            metadata = document.metadata
            source_type = str(metadata.get("source_type") or "UNKNOWN")
            if source_type == "USER_DOCUMENT" and metadata.get("owner_id") != user_id:
                continue
            document_id = str(
                metadata.get("document_id") or metadata.get("source") or "unknown"
            )
            if (
                allowed_document_ids is not None
                and document_id not in allowed_document_ids
            ):
                continue
            relevance = _relevance_from_distance(distance)
            if relevance < minimum_relevance:
                continue
            fingerprint = _content_fingerprint(document.page_content)
            if fingerprint in seen_chunks:
                continue
            seen_chunks.add(fingerprint)
            chunk_id = str(metadata.get("record_id") or f"{document_id}:{index}")
            visible.append(
                RetrievalMatch(
                    documentId=document_id,
                    chunkId=chunk_id,
                    content=document.page_content,
                    score=relevance,
                    filename=str(
                        metadata.get("filename")
                        or metadata.get("citation_label")
                        or document_id
                    ),
                    sourceType=source_type,
                    approvalStatus=str(metadata.get("approval_status") or "UNKNOWN"),
                )
            )

        return _select_diverse(visible, limit, per_document_limit)
