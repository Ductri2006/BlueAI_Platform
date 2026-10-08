import logging

from fastapi import APIRouter, Header, HTTPException

from app.core.security import _require_worker_api_key
from app.models.schemas import ChatCompletionRequest, ChatCompletionResponse
from app.services.runtime import runtime

LOGGER = logging.getLogger("blueai.rag")
router = APIRouter()


@router.post("/v1/chat/completions", response_model=ChatCompletionResponse)
def chat_completions(
    request: ChatCompletionRequest,
    x_worker_api_key: str | None = Header(default=None),
) -> ChatCompletionResponse:
    _require_worker_api_key(x_worker_api_key)
    try:
        return runtime.complete(request)
    except Exception as exception:
        raise HTTPException(
            status_code=502, detail=f"Gemini completion failed: {exception}"
        ) from exception
