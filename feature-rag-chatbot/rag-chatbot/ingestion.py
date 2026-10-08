"""Compatibility exports; worker implementation lives in app.workers.ingestion."""

from app.services.document_parser import MAX_DOCUMENT_BYTES, extract_text
from app.workers.ingestion import (
    request_run,
    run_worker_once,
    start_worker,
    worker_loop,
    worker_status,
)

__all__ = [
    "MAX_DOCUMENT_BYTES",
    "extract_text",
    "request_run",
    "run_worker_once",
    "start_worker",
    "worker_loop",
    "worker_status",
]
