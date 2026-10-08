"""Document polling, claim fencing, result reporting and worker telemetry."""

import asyncio
import logging
import os
from collections.abc import Callable
from datetime import datetime, timezone

import httpx

from app.services.document_parser import extract_text

LOGGER = logging.getLogger("blueai.rag.ingestion")

Indexer = Callable[[str, str, str, str, str, int, int], int]

# Set by the web app right after an upload so chunking starts immediately instead of
# waiting for the next poll. The pull model itself is unchanged: the loop still claims.
_run_requested = asyncio.Event()
_worker_task: asyncio.Task | None = None

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
        "configured": bool(
            os.getenv("JAVA_API_BASE_URL") and os.getenv("JAVA_WORKER_API_KEY")
        ),
        "running": _worker_task is not None and not _worker_task.done(),
        "javaApiBaseUrl": os.getenv("JAVA_API_BASE_URL") or "",
        "intervalSeconds": _interval(),
        **_state,
    }


def _record_failure(description: str) -> None:
    _state["lastError"] = description
    _state["consecutiveFailures"] = int(_state["consecutiveFailures"]) + 1
    LOGGER.warning(
        "Document worker poll failed (%s consecutive): %s",
        _state["consecutiveFailures"],
        description,
    )


async def _sleep_or_trigger(delay: float) -> None:
    try:
        await asyncio.wait_for(_run_requested.wait(), timeout=delay)
        _run_requested.clear()
    except asyncio.TimeoutError:
        pass


def _safe_failure_reason(error: Exception) -> str:
    """Return bounded public diagnostics, never provider bodies, URLs or credentials."""
    if isinstance(error, httpx.TimeoutException):
        return "Document service timed out."
    if isinstance(error, httpx.ConnectError):
        return "Document service connection failed."
    if isinstance(error, httpx.RequestError):
        return "Document service network request failed."
    if isinstance(error, httpx.HTTPStatusError):
        return f"Document service returned HTTP {error.response.status_code}."
    if isinstance(error, (ValueError, UnicodeError)):
        return "Document format or content is invalid."
    return "Document processing failed."


async def run_worker_once(
    client: httpx.AsyncClient, base_url: str, api_key: str, indexer: Indexer
) -> int:
    headers = {"X-Worker-Api-Key": api_key}
    purge_response = await client.post(
        f"{base_url}/api/v1/internal/documents/purge",
        params={"limit": 100},
        headers=headers,
    )
    # A rolling deployment may briefly run an older Java API without the purge
    # endpoint. Keep ingestion compatible during that window, but surface any
    # other failure so the loop's bounded telemetry records it.
    if getattr(purge_response, "status_code", 200) not in (200, 404):
        purge_response.raise_for_status()
    response = await client.post(
        f"{base_url}/api/v1/internal/documents/claim",
        params={"limit": 5},
        headers=headers,
    )
    response.raise_for_status()
    documents = response.json()
    for document in documents:
        document_id = document["id"]
        attempt_id = document["attemptId"]
        attempt_headers = {**headers, "X-Worker-Attempt-Id": attempt_id}
        try:
            content_response = await client.get(
                f"{base_url}/api/v1/internal/documents/{document_id}/content",
                headers=attempt_headers,
            )
            content_response.raise_for_status()
            # Parsers are blocking and must not stall health checks or other requests.
            text = await asyncio.to_thread(
                extract_text,
                content_response.content,
                document["contentType"],
                document["originalFilename"],
            )
            if not text.strip():
                raise ValueError("Document contains no extractable text.")
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
            result = {
                "attemptId": attempt_id,
                "status": "READY",
                "producedChunkCount": chunk_count,
            }
            LOGGER.info("Indexed document %s into %s chunks", document_id, chunk_count)
        except Exception as exception:
            if (
                isinstance(exception, httpx.HTTPStatusError)
                and exception.response.status_code == 409
            ):
                LOGGER.info("Skipping stale document claim %s", document_id)
                continue
            reason = _safe_failure_reason(exception)
            result = {
                "attemptId": attempt_id,
                "status": "FAILED",
                "failureReason": reason,
            }
            LOGGER.warning("Document %s failed: %s", document_id, reason)
        result_response = await client.patch(
            f"{base_url}/api/v1/internal/documents/{document_id}/result",
            headers=attempt_headers,
            json=result,
        )
        if getattr(result_response, "status_code", 200) == 409:
            LOGGER.info(
                "Document claim %s expired before its result was accepted", document_id
            )
            continue
        result_response.raise_for_status()
    return len(documents)


async def worker_loop(indexer: Indexer) -> None:
    base_url = os.getenv("JAVA_API_BASE_URL", "").rstrip("/")
    api_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not base_url or not api_key:
        LOGGER.warning(
            "Document worker disabled: JAVA_API_BASE_URL or JAVA_WORKER_API_KEY is missing."
        )
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
                _record_failure(f"Java document API: {_safe_failure_reason(error)}")
                delay = _interval()
            except httpx.ConnectError:
                _record_failure(
                    "Cannot connect to Java document API. Start the Java backend "
                    "and check JAVA_API_BASE_URL (local default: http://127.0.0.1:8080)."
                )
                delay = _interval()
            except Exception as error:
                _record_failure(_safe_failure_reason(error))
                delay = _interval()
            await _sleep_or_trigger(delay)


def start_worker(indexer: Indexer) -> asyncio.Task | None:
    global _worker_task
    if not os.getenv("JAVA_API_BASE_URL") or not os.getenv("JAVA_WORKER_API_KEY"):
        _worker_task = None
        return None
    _worker_task = asyncio.create_task(worker_loop(indexer))
    return _worker_task
