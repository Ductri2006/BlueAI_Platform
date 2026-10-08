"""HTTP request/response contracts; field names match the Java clients."""

from typing import Literal

from pydantic import BaseModel, Field


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
