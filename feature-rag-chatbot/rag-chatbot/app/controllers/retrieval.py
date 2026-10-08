import logging

from fastapi import APIRouter, Header, HTTPException

from app.core.security import _require_worker_api_key
from app.models.schemas import RetrievalRequest, RetrievalResponse
from app.services.runtime import runtime

LOGGER = logging.getLogger("blueai.rag")
router = APIRouter()


@router.post("/v1/retrieval/search", response_model=RetrievalResponse)
def retrieval_search(
    request: RetrievalRequest,
    x_worker_api_key: str | None = Header(default=None),
) -> RetrievalResponse:
    _require_worker_api_key(x_worker_api_key)
    try:
        if request.allowedDocumentIds is None:
            matches = runtime.search(
                request.userId, request.query.strip(), request.limit
            )
        else:
            matches = runtime.search(
                request.userId,
                request.query.strip(),
                request.limit,
                request.allowedDocumentIds,
            )
        return RetrievalResponse(matches=matches)
    except Exception as exception:
        raise HTTPException(
            status_code=503, detail=f"RAG retrieval unavailable: {exception}"
        ) from exception
