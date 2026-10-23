"""Opt-in live Gemini + BlueAI quota test; requires the isolated H2/worker fixtures."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time

import httpx


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated", action="store_true", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    client = httpx.Client(base_url="http://127.0.0.1:8088/api/v1", timeout=50)
    token = None

    def api(path, method="GET", body=None, **kwargs):
        response = client.request(method, path, json=body,
                                  headers={"Authorization": f"Bearer {token}"} if token else {}, **kwargs)
        if response.status_code >= 300:
            raise RuntimeError(f"Isolated fixture step {path} returned {response.status_code}")
        return response.json()

    login = api("/auth/login", "POST", {"email": "rate-limit-test@example.test", "password": "LocalRateTest-2026-Only"})
    token = login["accessToken"]
    document = api("/documents", "POST", files={"file": ("rate-limit-synthetic.txt",
        "Synthetic test shop only. Opening hours are 08:00 to 22:00 every day. This is test data, not a real company policy.", "text/plain")})
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        state = api(f"/documents/{document['id']}")
        if state["status"] == "READY":
            break
        if state["status"] == "FAILED":
            raise RuntimeError("Synthetic document ingestion failed")
        time.sleep(2)
    else:
        raise RuntimeError("Synthetic document did not become READY")
    config = api("/studio/bots", "POST", {"name": f"Gemini live quota {int(time.time())}",
        "locale": "vi-VN", "timezone": "Asia/Ho_Chi_Minh", "includeStarterTemplate": True})
    bot = config["bot"]
    bot_id = bot["id"]
    customization = dict(config["customization"])
    customization["answerLength"] = "short"
    api(f"/studio/bots/{bot_id}/configuration", "PUT", {
        "expectedRevision": bot["draftRevision"], "name": bot["name"], "locale": bot["locale"],
        "timezone": bot["timezone"], "blueAiCoreEnabled": True, "dialogEnabled": False,
        "customInstructions": "Only answer the synthetic shop opening hours; keep the answer to one short sentence.",
        "intents": config["intents"], "entities": config["entities"], "dialogNodes": config["dialogNodes"],
        "customization": customization, "widgetDocumentIds": [document["id"]]})
    api(f"/studio/bots/{bot_id}/api-key-settings", "PUT", {"requestsPerMinute": 2})
    api(f"/studio/bots/{bot_id}/publish", "POST")
    key_settings = api(f"/studio/bots/{bot_id}/api-key")
    bot_key = key_settings["apiKey"]
    report = {"atUtc": datetime.now(timezone.utc).isoformat(), "isolation": "H2 memory + separate FAISS + real Gemini",
        "publishedLimit": 2, "attempts": [], "checks": {}}

    def usage():
        return api("/system/gemini-usage")["lifetime"]

    def chat(number):
        response = client.post("/ai/tasks/chat", headers={"Authorization": f"Bearer {bot_key}"}, json={
            "userId": f"rate-test-customer-{number}", "conversationId": f"rate-test-{number}",
            "messageId": f"test-message-{number}", "channel": "cep-test",
            "message": "Cửa hàng thử nghiệm mở cửa từ mấy giờ đến mấy giờ?"})
        data = response.json()
        entry = {"number": number, "status": response.status_code}
        if response.status_code == 200:
            if "08:00" not in data["answer"] or "22:00" not in data["answer"]:
                raise RuntimeError("Live answer did not contain the synthetic source facts")
            entry["answer"] = data["answer"]
        else:
            entry["errorCode"] = data.get("error", {}).get("code")
            entry["retryAfter"] = response.headers.get("Retry-After")
        report["attempts"].append(entry)
        return entry

    before = usage()
    for number in (1, 2):
        assert chat(number)["status"] == 200
    after_two = usage()
    assert after_two["requests"] - before["requests"] == 2
    blocked = chat(3)
    assert blocked["status"] == 429 and blocked["errorCode"] == "API_KEY_RATE_LIMITED"
    assert usage() == after_two
    report["checks"]["blockedBeforeGemini"] = True
    report["usageAfterTwo"] = after_two

    draft = api(f"/studio/bots/{bot_id}/api-key-settings", "PUT", {"requestsPerMinute": 3})
    assert draft["requestsPerMinute"] == 3 and draft["publishedRequestsPerMinute"] == 2
    assert chat(4)["status"] == 429
    report["checks"]["draftDoesNotChangePublishedQuota"] = True
    api(f"/studio/bots/{bot_id}/publish", "POST")
    updated = api(f"/studio/bots/{bot_id}/api-key")
    assert updated["apiKey"] == bot_key and updated["publishedRequestsPerMinute"] == 3
    assert chat(5)["status"] == 200
    report["checks"]["republishAppliesQuotaAndKeepsKey"] = True
    blocked = chat(6)
    assert blocked["status"] == 429
    after_three = usage()
    report["checks"]["blockedDoesNotIncreaseTokenUsage"] = after_three["requests"] - before["requests"] == 3
    delay = int(blocked["retryAfter"])
    print(f"PASS: live Gemini responses, 429 before provider, draft/publish. Waiting {delay + 1}s for the quota window.", flush=True)
    time.sleep(delay + 1)
    assert chat(7)["status"] == 200
    report["checks"]["windowResets"] = True
    report["usageFinal"] = usage()
    assert report["usageFinal"]["requests"] - before["requests"] == 4
    report["result"] = "PASS"
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    # Only outputs explicitly selected public fields; token and bot/provider keys stay in memory.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    client.close()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, AssertionError, httpx.HTTPError) as error:
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(f"Live isolated rate-limit test failed: {detail}. No credentials logged.")
        raise SystemExit(1) from None
