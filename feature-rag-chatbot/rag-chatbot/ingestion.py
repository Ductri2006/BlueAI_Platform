import asyncio
import io
import logging
import os
from collections.abc import Callable
from datetime import datetime, timezone

import httpx
from docx import Document as DocxDocument
from pypdf import PdfReader

LOGGER = logging.getLogger("blueai.rag.ingestion")

Indexer = Callable[[str, str, str, str, str, int, int], int]

# Set by the web app right after an upload so chunking starts immediately instead of
# waiting for the next poll. The pull model itself is unchanged: the loop still claims.
_run_requested = asyncio.Event()

_state: dict[str, object] = {
    "lastRunAt": None,
    "lastSuccessAt": None,
    "lastError": None,
    "claimedTotal": 0,
    "consecutiveFailures": 0,
}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _interval() -> int:
    try:
        return max(2, int(os.getenv("DOCUMENT_WORKER_INTERVAL_SECONDS", "5")))
    except ValueError:
        return 5


def request_run() -> None:
    """Wake the worker loop now. Safe to call from any coroutine on the same loop."""
    _run_requested.set()


def worker_status() -> dict[str, object]:
    """Non-secret worker telemetry for /health, so a misconfigured worker is visible."""
    return {
        "configured": bool(os.getenv("JAVA_API_BASE_URL") and os.getenv("JAVA_WORKER_API_KEY")),
        "javaApiBaseUrl": os.getenv("JAVA_API_BASE_URL") or "",
        "intervalSeconds": _interval(),
        **_state,
    }


def _record_failure(description: str) -> None:
    _state["lastError"] = description
    _state["consecutiveFailures"] = int(_state["consecutiveFailures"]) + 1
    LOGGER.warning("Document worker poll failed (%s consecutive): %s",
                   _state["consecutiveFailures"], description)


async def _sleep_or_trigger(delay: float) -> None:
    try:
        await asyncio.wait_for(_run_requested.wait(), timeout=delay)
        _run_requested.clear()
    except asyncio.TimeoutError:
        pass


def extract_text(content: bytes, content_type: str, filename: str) -> str:
    lowered = filename.lower()
    if content_type in {"text/plain", "text/markdown"} or lowered.endswith((".txt", ".md")):
        return content.decode("utf-8-sig")
    if content_type == "application/pdf" or lowered.endswith(".pdf"):
        return "\n".join(page.extract_text() or "" for page in PdfReader(io.BytesIO(content)).pages)
    if lowered.endswith(".docx"):
        return "\n".join(paragraph.text for paragraph in DocxDocument(io.BytesIO(content)).paragraphs)
    raise ValueError(f"Unsupported document type: {content_type}")


async def run_worker_once(client: httpx.AsyncClient, base_url: str, api_key: str, indexer: Indexer) -> int:
    headers = {"X-Worker-Api-Key": api_key}
    response = await client.post(f"{base_url}/api/v1/internal/documents/claim", params={"limit": 5}, headers=headers)
    response.raise_for_status()
    documents = response.json()
    for document in documents:
        document_id = document["id"]
        try:
            content_response = await client.get(f"{base_url}/api/v1/internal/documents/{document_id}/content", headers=headers)
            content_response.raise_for_status()
            text = extract_text(content_response.content, document["contentType"], document["originalFilename"])
            chunk_count = await asyncio.to_thread(
                indexer,
                document_id,
                document["ownerId"],
                document.get("sourceType", "USER_DOCUMENT"),
                document["originalFilename"],
                text,
                document["requestedChunkSize"],
                document["requestedChunkOverlap"],
            )
            result = {"status": "READY", "producedChunkCount": chunk_count}
            LOGGER.info("Indexed document %s into %s chunks", document_id, chunk_count)
        except Exception as exception:
            result = {"status": "FAILED", "failureReason": str(exception)[:1000]}
            LOGGER.warning("Document %s failed: %s", document_id, exception)
        result_response = await client.patch(
            f"{base_url}/api/v1/internal/documents/{document_id}/result", headers=headers, json=result,
        )
        result_response.raise_for_status()
    return len(documents)


async def worker_loop(indexer: Indexer) -> None:
    base_url = os.getenv("JAVA_API_BASE_URL", "").rstrip("/")
    api_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not base_url or not api_key:
        LOGGER.warning("Document worker disabled: JAVA_API_BASE_URL or JAVA_WORKER_API_KEY is missing.")
        return
    LOGGER.info("Document worker polling %s every %ss", base_url, _interval())
    async with httpx.AsyncClient(timeout=60) as client:
        while True:
            _state["lastRunAt"] = _now()
            try:
                processed = await run_worker_once(client, base_url, api_key, indexer)
                _state["claimedTotal"] = int(_state["claimedTotal"]) + processed
                _state["lastSuccessAt"] = _now()
                _state["lastError"] = None
                _state["consecutiveFailures"] = 0
                delay = 0.2 if processed else _interval()
            except asyncio.CancelledError:
                raise
            except httpx.HTTPStatusError as error:
                _record_failure(
                    f"{error.request.method} {error.request.url} -> HTTP {error.response.status_code}"
                )
                delay = _interval()
            except Exception as error:
                _record_failure(f"{type(error).__name__} calling {base_url}: {error}")
                delay = _interval()
            await _sleep_or_trigger(delay)


def start_worker(indexer: Indexer) -> asyncio.Task | None:
    if not os.getenv("JAVA_API_BASE_URL") or not os.getenv("JAVA_WORKER_API_KEY"):
        return None
    return asyncio.create_task(worker_loop(indexer))
