"""Return bounded diagnostics without exposing provider payloads or credentials."""
from fastapi import HTTPException


def provider_http_error(error: Exception, fallback_status: int) -> HTTPException:
    message = str(error).upper()
    if "429" in message or "RESOURCE_EXHAUSTED" in message:
        return HTTPException(status_code=429, detail="AI provider rate limit reached.", headers={"Retry-After":"60"})
    return HTTPException(status_code=fallback_status, detail="AI provider request could not be completed.")
