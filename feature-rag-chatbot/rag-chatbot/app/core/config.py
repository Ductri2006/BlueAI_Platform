"""Environment configuration shared by the HTTP service and CLI."""

import os
from pathlib import Path

from dotenv import load_dotenv

PROJECT_DIR = Path(__file__).resolve().parents[2]
load_dotenv(PROJECT_DIR / ".env", override=True)

DEFAULT_CHAT_MODEL = "gemini-3.5-flash-lite"
DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001"
INDEX_METADATA_FILENAME = "index_meta.json"


def _int_env(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        return default
    return max(minimum, min(maximum, value))


def _float_env(name: str, default: float, minimum: float, maximum: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError:
        return default
    return max(minimum, min(maximum, value))


def _embedding_model() -> str:
    return os.getenv("GEMINI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)


def _chat_models() -> list[str]:
    primary = os.getenv("GEMINI_CHAT_MODEL", DEFAULT_CHAT_MODEL).strip()
    fallbacks = os.getenv("GEMINI_CHAT_FALLBACK_MODELS", "gemini-3.6-flash")
    configured = [primary, *(item.strip() for item in fallbacks.split(","))]
    # At most two 12-second provider attempts fit inside Java's 30-second read
    # timeout. Java owns request retries; nesting SDK retries would multiply cost.
    return list(dict.fromkeys(model for model in configured if model))[:2]


def _index_dir() -> Path:
    return Path(os.getenv("RAG_INDEX_DIR", ".rag-index"))
