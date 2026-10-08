"""Compose index storage and AI services behind the existing runtime interface."""

from app.models.schemas import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    RetrievalMatch,
)
from app.repositories.faiss_repository import FaissRepository
from app.services.completion import CompletionService
from app.services.retrieval import RetrievalService


class RagRuntime(FaissRepository):
    def __init__(self) -> None:
        super().__init__()
        self.retrieval = RetrievalService(self)
        self.completion = CompletionService()

    def search(
        self,
        user_id: str,
        query: str,
        limit: int,
        allowed_document_ids: list[str] | None = None,
    ) -> list[RetrievalMatch]:
        return self.retrieval.search(user_id, query, limit, allowed_document_ids)

    def complete(self, request: ChatCompletionRequest) -> ChatCompletionResponse:
        return self.completion.complete(request)


runtime = RagRuntime()
