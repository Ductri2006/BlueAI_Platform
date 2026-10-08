"""Gemini completion, bounded failover and token usage mapping."""

import logging
import uuid

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_google_genai import ChatGoogleGenerativeAI

from app.core.config import _chat_models
from app.models.schemas import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    ChatMessage,
    CompletionChoice,
    CompletionUsage,
)

LOGGER = logging.getLogger("blueai.rag.completion")


def _is_transient_completion_error(error: Exception) -> bool:
    message = str(error).upper()
    return any(
        marker in message
        for marker in (
            "429",
            "RESOURCE_EXHAUSTED",
            "500",
            "502",
            "503",
            "504",
            "UNAVAILABLE",
            "HIGH DEMAND",
            "TIMEOUT",
            "TIMED OUT",
            "CONNECTION RESET",
        )
    )


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


class CompletionService:
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
                if all(
                    key in metadata and metadata[key] is not None
                    for key in ("input_tokens", "output_tokens", "total_tokens")
                ):
                    usage = CompletionUsage(
                        prompt_tokens=metadata["input_tokens"],
                        completion_tokens=metadata["output_tokens"],
                        total_tokens=metadata["total_tokens"],
                    )
                return ChatCompletionResponse(
                    id=f"chatcmpl-{uuid.uuid4()}",
                    model=model_name,
                    choices=[
                        CompletionChoice(
                            message=ChatMessage(
                                role="assistant", content=content.strip()
                            )
                        )
                    ],
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
