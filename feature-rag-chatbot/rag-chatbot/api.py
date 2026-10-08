"""Compatibility entry point. Prefer: uvicorn app.main:app."""

from app.main import app
from app.models.schemas import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    ChatMessage,
    CompletionChoice,
    CompletionUsage,
    RetrievalMatch,
    RetrievalRequest,
    RetrievalResponse,
)
from app.services.runtime import RagRuntime, runtime

__all__ = [
    "app",
    "runtime",
    "RagRuntime",
    "RetrievalRequest",
    "RetrievalMatch",
    "RetrievalResponse",
    "ChatMessage",
    "ChatCompletionRequest",
    "CompletionChoice",
    "CompletionUsage",
    "ChatCompletionResponse",
]
