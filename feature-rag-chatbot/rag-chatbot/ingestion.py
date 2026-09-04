import asyncio
import io
import os
from collections.abc import Callable

import httpx
from docx import Document as DocxDocument
from pypdf import PdfReader

Indexer = Callable[[str, str, str, str, str, int, int], int]


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
        except Exception as exception:
            result = {"status": "FAILED", "failureReason": str(exception)[:1000]}
        result_response = await client.patch(
            f"{base_url}/api/v1/internal/documents/{document_id}/result", headers=headers, json=result,
        )
        result_response.raise_for_status()
    return len(documents)


async def worker_loop(indexer: Indexer) -> None:
    base_url = os.getenv("JAVA_API_BASE_URL", "").rstrip("/")
    api_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not base_url or not api_key:
        return
    interval = max(2, int(os.getenv("DOCUMENT_WORKER_INTERVAL_SECONDS", "5")))
    async with httpx.AsyncClient(timeout=60) as client:
        while True:
            try:
                processed = await run_worker_once(client, base_url, api_key, indexer)
                await asyncio.sleep(0.2 if processed else interval)
            except asyncio.CancelledError:
                raise
            except Exception:
                await asyncio.sleep(interval)


def start_worker(indexer: Indexer) -> asyncio.Task | None:
    if not os.getenv("JAVA_API_BASE_URL") or not os.getenv("JAVA_WORKER_API_KEY"):
        return None
    return asyncio.create_task(worker_loop(indexer))
