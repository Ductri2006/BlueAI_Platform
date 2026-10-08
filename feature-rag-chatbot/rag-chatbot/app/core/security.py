"""Authentication boundary for private Java-to-Python requests."""

import hmac
import os

from fastapi import HTTPException

DEVELOPMENT_WORKER_API_KEY = "local-worker-api-key-change-before-production"


def _require_worker_api_key(provided_api_key: str | None) -> None:
    expected_key = os.getenv("JAVA_WORKER_API_KEY", "")
    if not expected_key or hmac.compare_digest(
        expected_key, DEVELOPMENT_WORKER_API_KEY
    ):
        raise HTTPException(
            status_code=503, detail="RAG service authentication is not configured."
        )
    if not hmac.compare_digest(provided_api_key or "", expected_key):
        raise HTTPException(status_code=401, detail="Worker API key is invalid.")
