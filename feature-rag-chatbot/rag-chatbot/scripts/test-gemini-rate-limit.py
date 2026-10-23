"""Small opt-in provider probe. Reads the local key; never prints request headers/secrets."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from dotenv import load_dotenv


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=6, help="Maximum tiny requests (1-20); stops at the first error")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 1 <= args.requests <= 20:
        parser.error("--requests must be between 1 and 20")

    load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=True)
    key = os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY")
    if not key:
        print("Gemini API key is not configured.")
        return 2
    model = os.getenv("GEMINI_CHAT_MODEL", "gemini-3.5-flash-lite")
    endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{quote(model, safe='')}:generateContent"
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": "Reply with exactly OK."}]}],
                       "generationConfig": {"maxOutputTokens": 32, "temperature": 0}}).encode()
    report = {"atUtc": datetime.now(timezone.utc).isoformat(), "model": model,
              "maximumRequests": args.requests, "attempts": [], "quotaExhausted": False}
    for index in range(args.requests):
        started = time.monotonic()
        request = Request(endpoint, data=body, method="POST", headers={
            "Content-Type": "application/json", "x-goog-api-key": key})
        try:
            with urlopen(request, timeout=25) as response:
                status, headers, content = response.status, response.headers, response.read(65536)
        except HTTPError as error:
            status, headers, content = error.code, error.headers, error.read(65536)
        except (URLError, TimeoutError):
            report["attempts"].append({"number": index + 1, "status": "NETWORK_ERROR"})
            break
        attempt = {"number": index + 1, "status": status, "elapsedMs": round((time.monotonic() - started) * 1000)}
        try:
            data = json.loads(content)
        except (ValueError, UnicodeError):
            attempt["response"] = "INVALID_JSON"
            report["attempts"].append(attempt)
            break
        if status == 200:
            attempt["usage"] = data.get("usageMetadata", {})
            attempt["hasContent"] = bool(data.get("candidates"))
        else:
            error = data.get("error", {})
            attempt["errorStatus"] = error.get("status")
            attempt["errorMessage"] = str(error.get("message", "")).replace(key, "[REDACTED]")[:1200]
            attempt["retryAfter"] = headers.get("Retry-After")
            attempt["quota"] = []
            for detail in error.get("details", []):
                if detail.get("@type", "").endswith("QuotaFailure"):
                    for violation in detail.get("violations", []):
                        attempt["quota"].append({field: violation[field] for field in
                            ("quotaMetric", "quotaId", "quotaValue", "quotaDimensions") if field in violation})
                if detail.get("@type", "").endswith("RetryInfo"):
                    attempt["retryDelay"] = detail.get("retryDelay")
        report["attempts"].append(attempt)
        if status != 200:
            report["quotaExhausted"] = status == 429
            break
        time.sleep(0.2)
    report["successfulRequests"] = sum(row["status"] == 200 for row in report["attempts"])
    report["note"] = ("No 429 observed does not establish the project quota. Gemini quotas apply per project and model.")
    serialized = json.dumps(report, ensure_ascii=False, indent=2).replace(key, "[REDACTED]")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0 if report["successfulRequests"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
