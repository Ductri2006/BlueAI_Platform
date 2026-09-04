import asyncio
import unittest
from unittest.mock import patch

import ingestion


class FakeResponse:
    def __init__(self, body=None, content=b""):
        self._body = body
        self.content = content

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


class FakeClient:
    def __init__(self):
        self.result = None

    async def post(self, *_args, **_kwargs):
        return FakeResponse([{
            "id": "doc-1", "ownerId": "owner-1", "contentType": "text/markdown",
            "sourceType": "ENTERPRISE_DOCUMENT",
            "originalFilename": "guide.md", "requestedChunkSize": 800, "requestedChunkOverlap": 100,
        }])

    async def get(self, *_args, **_kwargs):
        return FakeResponse(content=b"# Real uploaded guide")

    async def patch(self, *_args, **kwargs):
        self.result = kwargs["json"]
        return FakeResponse()


class IngestionTest(unittest.IsolatedAsyncioTestCase):
    def test_extracts_utf8_text(self):
        self.assertEqual("Xin chào", ingestion.extract_text("Xin chào".encode(), "text/plain", "note.txt"))

    async def test_worker_passes_owner_and_marks_document_ready(self):
        client = FakeClient()
        indexed = []

        def indexer(*args):
            indexed.append(args)
            return 2

        count = await ingestion.run_worker_once(client, "http://java", "worker-key", indexer)
        self.assertEqual(1, count)
        self.assertEqual("owner-1", indexed[0][1])
        self.assertEqual("ENTERPRISE_DOCUMENT", indexed[0][2])
        self.assertEqual({"status": "READY", "producedChunkCount": 2}, client.result)

    async def test_trigger_wakes_the_loop_before_the_interval_elapses(self):
        ingestion.request_run()
        # A 60s delay would block the loop; the pending trigger must return at once.
        await asyncio.wait_for(ingestion._sleep_or_trigger(60), timeout=1)
        self.assertFalse(ingestion._run_requested.is_set(), "the trigger must be consumed")

    async def test_sleep_returns_after_the_delay_when_no_trigger_arrives(self):
        ingestion._run_requested.clear()
        await asyncio.wait_for(ingestion._sleep_or_trigger(0.01), timeout=1)
        self.assertFalse(ingestion._run_requested.is_set())

    def test_worker_status_exposes_target_and_last_error_without_secrets(self):
        with patch.dict("os.environ", {
            "JAVA_API_BASE_URL": "http://127.0.0.1:8080",
            "JAVA_WORKER_API_KEY": "super-secret-key",
        }):
            status = ingestion.worker_status()
        self.assertTrue(status["configured"])
        self.assertEqual("http://127.0.0.1:8080", status["javaApiBaseUrl"])
        self.assertIn("lastError", status)
        self.assertIn("claimedTotal", status)
        self.assertNotIn("super-secret-key", str(status))

    def test_failures_are_recorded_instead_of_being_swallowed(self):
        ingestion._state["consecutiveFailures"] = 0
        ingestion._state["lastError"] = None
        ingestion._record_failure("POST http://auth-api:8080/claim -> HTTP 401")
        self.assertEqual(1, ingestion._state["consecutiveFailures"])
        self.assertIn("401", str(ingestion._state["lastError"]))
