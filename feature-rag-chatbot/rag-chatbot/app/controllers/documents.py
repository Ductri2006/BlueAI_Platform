import logging
import os

from fastapi import APIRouter, Header, HTTPException

from app.core.security import _require_worker_api_key
from app.services.runtime import runtime
from app.workers.ingestion import request_run

LOGGER = logging.getLogger("blueai.rag")
router = APIRouter()


@router.post("/v1/internal/ingest/run", status_code=202)
def trigger_ingest(
    x_worker_api_key: str | None = Header(default=None),
) -> dict[str, str]:
    """Woken by the web app right after an upload. Authenticated with the shared worker
    key, exactly like the Java side authenticates the worker's own calls."""
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key or not os.getenv("JAVA_API_BASE_URL"):
        raise HTTPException(
            status_code=503, detail="Document worker is not configured."
        )
    _require_worker_api_key(x_worker_api_key)
    request_run()
    return {"status": "queued"}


@router.post("/v1/internal/documents/{document_id}/purge")
def purge_document(
    document_id: str, x_worker_api_key: str | None = Header(default=None)
) -> dict[str, object]:
    """Idempotently compact one deleted document out of FAISS and its docstore."""
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key:
        raise HTTPException(
            status_code=503, detail="Document worker is not configured."
        )
    _require_worker_api_key(x_worker_api_key)
    try:
        removed_chunks = runtime.purge_document(document_id)
    except Exception as exception:
        LOGGER.warning(
            "Could not purge document %s from the vector store: %s",
            document_id,
            exception,
        )
        raise HTTPException(
            status_code=503, detail="Document vector purge unavailable."
        ) from exception
    return {
        "status": "purged",
        "documentId": document_id,
        "removedChunks": removed_chunks,
    }
