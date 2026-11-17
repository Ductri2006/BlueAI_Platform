import logging

from fastapi import APIRouter, Header, HTTPException

from app.core.security import _require_worker_api_key
from app.models.schemas import RetrievalRequest, RetrievalResponse
from app.services.runtime import runtime
from app.services.embedding_usage import query_usage
from app.services.provider_error import provider_http_error

LOGGER = logging.getLogger("blueai.rag")
router = APIRouter()


@router.post("/v1/retrieval/search", response_model=RetrievalResponse)
def retrieval_search(
    request: RetrievalRequest,
    x_worker_api_key: str | None = Header(default=None),
) -> RetrievalResponse:
    _require_worker_api_key(x_worker_api_key)
    try:
        with query_usage() as usage:
            if request.allowedDocumentIds is None:
                matches = runtime.search(request.userId, request.query.strip(), request.limit)
            else:
                matches = runtime.search(request.userId, request.query.strip(), request.limit, request.allowedDocumentIds)
        tokens = sum(value.tokens for value in usage) if all(value.tokens is not None for value in usage) else None
        method = "COUNT_TOKENS" if any(value.source == "COUNT_TOKENS" for value in usage) else "USAGE_METADATA"
        return RetrievalResponse(matches=matches, embeddingTokens=tokens, embeddingCountMethod=method)
    except Exception as exception:
        raise provider_http_error(exception, 503) from exception
