import logging
import os
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.core.config import (
    DEFAULT_CHAT_MODEL,
    _embedding_model,
    _float_env,
    _index_dir,
    _int_env,
)
from app.services.runtime import RagRuntime, runtime
from app.workers.ingestion import worker_status

LOGGER = logging.getLogger("blueai.rag")
router = APIRouter()


@router.get("/health")
def health() -> dict:
    index_dir = _index_dir()
    return {
        "status": "ok",
        "provider": "gemini",
        "geminiConfigured": runtime.is_gemini_configured(),
        "chatModel": os.getenv("GEMINI_CHAT_MODEL", DEFAULT_CHAT_MODEL),
        "embeddingModel": _embedding_model(),
        "indexPresent": index_dir.joinpath("index.faiss").exists(),
        "indexEmbeddingModel": RagRuntime._read_index_model(index_dir) or "unknown",
        "documentWorkerConfigured": bool(
            os.getenv("JAVA_API_BASE_URL") and os.getenv("JAVA_WORKER_API_KEY")
        ),
        "documentWorker": worker_status(),
        "retrievalTuning": {
            "minRelevance": _float_env("RAG_MIN_RELEVANCE", 0.0, 0.0, 1.0),
            "maxChunksPerDocument": _int_env("RAG_MAX_CHUNKS_PER_DOCUMENT", 3, 1, 20),
            "candidateMultiplier": _int_env("RAG_CANDIDATE_MULTIPLIER", 8, 1, 40),
        },
    }


@router.get("/ready")
def readiness() -> dict[str, str]:
    # Startup gate only: auth-api depends on this before it can start, so do not
    # require a successful Java worker poll here (that would be a dependency cycle).
    if not runtime.is_gemini_configured():
        raise HTTPException(
            status_code=503,
            detail="Gemini is not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY.",
        )
    return {"status": "ready", "provider": "gemini"}


@router.get("/readiness")
def operational_readiness() -> dict[str, str]:
    """Post-start deployment gate, with no billable provider request."""
    readiness()
    worker = worker_status()
    if not worker.get("configured") or not worker.get("running"):
        raise HTTPException(
            status_code=503, detail="Document worker is not running or configured."
        )
    if int(worker.get("consecutiveFailures", 0)) >= 3:
        raise HTTPException(
            status_code=503, detail="Document worker has sustained polling failures."
        )
    try:
        last_success = datetime.fromisoformat(str(worker.get("lastSuccessAt")))
        if last_success.tzinfo is None:
            raise ValueError("Worker timestamp must include a timezone.")
        age = (datetime.now(timezone.utc) - last_success).total_seconds()
    except (TypeError, ValueError):
        raise HTTPException(
            status_code=503,
            detail="Document worker has not completed a successful poll.",
        ) from None
    max_age = max(180, int(worker.get("intervalSeconds", 5)) * 3)
    if age < -5 or age > max_age:
        raise HTTPException(
            status_code=503, detail="Document worker has no recent successful poll."
        )
    return {"status": "ready", "provider": "gemini", "documentWorker": "ready"}
