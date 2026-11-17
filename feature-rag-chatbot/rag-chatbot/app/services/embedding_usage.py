"""Capture official embedding usage without leaking it between worker requests."""

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
import os
import time

import httpx
from google.genai.types import CountTokensConfig, HttpOptions, HttpRetryOptions
from langchain_google_genai import GoogleGenerativeAIEmbeddings

@dataclass
class Measurement:
    tokens: int | None
    source: str


_query_usage: ContextVar[list[Measurement] | None] = ContextVar("query_usage", default=None)


@contextmanager
def query_usage():
    values: list[Measurement] = []
    token = _query_usage.set(values)
    try:
        yield values
    finally:
        _query_usage.reset(token)


def _prompt_tokens(result) -> int | None:
    metadata = getattr(result, "usage_metadata", None)
    value = getattr(metadata, "prompt_token_count", None)
    return value if isinstance(value, int) and value >= 0 else None


def _worker_quota(path: str, body: dict):
    if os.getenv("AI_TOKEN_QUOTA_ENABLED", "false").lower() != "true":
        return None
    base = os.environ["JAVA_API_BASE_URL"].rstrip("/")
    # The destination is deployment configuration, never supplied by an HTTP caller.
    for attempt in range(3):
        response = httpx.post(
            f"{base}/api/v1/internal/documents/ai-quota/{path}", json=body,
            headers={"X-Worker-Api-Key": os.environ["JAVA_WORKER_API_KEY"]}, timeout=10,
            follow_redirects=False,
        )
        # Background indexing can wait for a minute window without replaying provider work.
        if path == "reserve" and response.status_code == 429 and attempt < 2:
            delay = int(response.headers.get("Retry-After", "60"))
            if 0 < delay <= 60:
                time.sleep(delay)
                continue
        response.raise_for_status()
        return response.json() if response.content else None


class MeteredEmbeddings(GoogleGenerativeAIEmbeddings):
    """Keep the same task types, dimensions and vectors as the existing adapter."""

    def _embed(self, contents, task_type, title=None, dimensions=None):
        options = HttpOptions(timeout=12_000, retry_options=HttpRetryOptions(attempts=1))
        texts = [contents] if isinstance(contents, str) else [content["parts"][0]["text"] for content in contents]
        counts = [self.client.models.count_tokens(model=self.model, contents=text,
                  config=CountTokensConfig(http_options=options)).total_tokens for text in texts]
        # embedding-001 silently truncates overlong input. Count the complete text before embedding.
        if self.model.removeprefix("models/") == "gemini-embedding-001" and any(count is None or count > 2040 for count in counts):
            raise ValueError("Embedding input exceeds the model's safe token limit.")
        config = self._build_config(task_type=task_type, title=title,
                                    output_dimensionality=dimensions or self.output_dimensionality)
        config.http_options = options
        result = self.client.models.embed_content(model=self.model, contents=contents, config=config)
        actual = _prompt_tokens(result)
        counted = sum(counts) if all(count is not None for count in counts) else None
        return result, Measurement(actual if actual is not None else counted,
                                   "USAGE_METADATA" if actual is not None else "COUNT_TOKENS")

    def embed_query(self, text: str, *, task_type=None, title=None, output_dimensionality=None):
        result, measurement = self._embed(text, task_type or self.task_type or "RETRIEVAL_QUERY", title, output_dimensionality)
        collector = _query_usage.get()
        if collector is not None:
            collector.append(measurement)
        return list(result.embeddings[0].values)

    def embed_documents(self, texts: list[str], *, batch_size=100, task_type=None, titles=None, output_dimensionality=None):
        vectors = []
        # Bound each batch below the demo embedding TPM ceiling. Never bill ingestion to a querying user.
        batches: list[list[str]] = []
        batch: list[str] = []
        size = 1024
        for text in texts:
            length = len(text.encode("utf-8"))
            if batch and (size + length > 20_000 or len(batch) >= min(batch_size, 5)):
                batches.append(batch)
                batch, size = [], 1024
            batch.append(text)
            size += length
        if batch:
            batches.append(batch)
        offset = 0
        for batch in batches:
            reservation = _worker_quota("reserve", {"inputBound": 1024 + sum(len(text.encode("utf-8")) for text in batch)})
            title = titles[offset] if titles and len(batch) == 1 else None
            result, measurement = self._embed([{"parts": [{"text": text}]} for text in batch],
                                 task_type or self.task_type or "RETRIEVAL_DOCUMENT", title, output_dimensionality)
            tokens = measurement.tokens
            if reservation and tokens is not None:
                _worker_quota("confirm", {"reservation": reservation, "promptTokens": tokens, "measurement": measurement.source})
            vectors.extend(list(embedding.values) for embedding in result.embeddings)
            offset += len(batch)
        return vectors
