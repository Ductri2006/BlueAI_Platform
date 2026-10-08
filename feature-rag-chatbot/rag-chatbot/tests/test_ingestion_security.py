import asyncio
import threading
import unittest
from unittest.mock import patch

import httpx
from app.workers import ingestion
from app.services import document_parser
from test_ingestion import FakeClient, FakeResponse


class IngestionSecurityTest(unittest.IsolatedAsyncioTestCase):
    async def test_provider_exception_does_not_expose_secrets_in_result_or_logs(self):
        client = FakeClient()

        def failing_indexer(*_args):
            raise RuntimeError("Authorization: Bearer secret-fixture password=private-fixture")

        with self.assertLogs(ingestion.LOGGER, level="WARNING") as logs:
            await ingestion.run_worker_once(client, "http://java", "worker-key", failing_indexer)
        self.assertEqual("FAILED", client.result["status"])
        self.assertEqual("Document processing failed.", client.result["failureReason"])
        self.assertNotIn("secret-fixture", str(client.result) + str(logs.output))
        self.assertNotIn("private-fixture", str(client.result) + str(logs.output))

    async def test_empty_parsed_document_never_calls_indexer_or_becomes_ready(self):
        client = FakeClient()
        calls = []
        with patch.object(ingestion, "extract_text", return_value=" \n\t"):
            await ingestion.run_worker_once(client, "http://java", "worker-key", lambda *args: calls.append(args))
        self.assertEqual([], calls)
        self.assertEqual("FAILED", client.result["status"])

    async def test_parser_runs_off_the_event_loop_thread(self):
        client = FakeClient()
        event_loop_thread = threading.get_ident()
        parser_threads = []

        def parser(*_args):
            parser_threads.append(threading.get_ident())
            return "safe fixture"

        with patch.object(ingestion, "extract_text", side_effect=parser):
            await ingestion.run_worker_once(client, "http://java", "worker-key", lambda *_args: 1)
        self.assertNotEqual(event_loop_thread, parser_threads[0])
        self.assertEqual("READY", client.result["status"])

    async def test_stale_download_is_skipped_without_indexing_or_reporting_ready(self):
        client = FakeClient()
        calls = []

        async def stale_get(*_args, **_kwargs):
            request = httpx.Request("GET", "http://java/content")
            response = httpx.Response(409, request=request)
            raise httpx.HTTPStatusError("stale", request=request, response=response)

        client.get = stale_get
        count = await ingestion.run_worker_once(client, "http://java", "worker-key", lambda *args: calls.append(args))
        self.assertEqual(1, count)
        self.assertEqual([], calls)
        self.assertIsNone(client.result)

    async def test_stale_result_does_not_abort_next_claimed_document(self):
        client = FakeClient()
        original_post = client.post
        processed = []

        async def post(url, *args, **kwargs):
            response = await original_post(url, *args, **kwargs)
            if url.endswith("/claim"):
                first = response.json()[0]
                return FakeResponse([first, {**first, "id": "doc-2", "attemptId": "attempt-2"}])
            return response

        async def result(url, **kwargs):
            processed.append(kwargs["json"])
            return FakeResponse(status_code=409 if "doc-1" in url else 200)

        client.post, client.patch = post, result
        count = await ingestion.run_worker_once(client, "http://java", "worker-key", lambda *_args: 1)
        self.assertEqual(2, count)
        self.assertEqual(["attempt-1", "attempt-2"], [item["attemptId"] for item in processed])

    def test_rejects_empty_and_oversized_documents_before_parsing(self):
        for content in (b"", b"a" * (document_parser.MAX_DOCUMENT_BYTES + 1)):
            with self.subTest(size=len(content)), self.assertRaisesRegex(ValueError, "size"):
                ingestion.extract_text(content, "text/plain", "fixture.txt")

    def test_failure_reason_never_includes_provider_response_body_or_url(self):
        request = httpx.Request("POST", "https://provider.invalid/?key=secret-fixture")
        response = httpx.Response(429, request=request, text="private-fixture")
        error = httpx.HTTPStatusError("private-fixture", request=request, response=response)
        self.assertEqual("Document service returned HTTP 429.", ingestion._safe_failure_reason(error))
        self.assertEqual("Document service timed out.", ingestion._safe_failure_reason(httpx.ReadTimeout("secret")))

    async def test_connection_failure_names_java_and_worker_recovers_next_poll(self):
        request = httpx.Request("POST", "http://java.invalid/?key=secret-fixture")
        error = httpx.ConnectError("password=private-fixture", request=request)
        self.assertEqual("Document service connection failed.", ingestion._safe_failure_reason(error))
        with patch.dict("os.environ", {"JAVA_API_BASE_URL": "http://java.invalid", "JAVA_WORKER_API_KEY": "worker-key"}), \
                patch.dict(ingestion._state, {"consecutiveFailures": 0, "lastError": None, "lastSuccessAt": None}), \
                patch.object(ingestion, "run_worker_once", side_effect=[error, 0]), \
                patch.object(ingestion, "_sleep_or_trigger", side_effect=[None, asyncio.CancelledError()]), \
                self.assertLogs(ingestion.LOGGER, level="WARNING") as logs:
            with self.assertRaises(asyncio.CancelledError):
                await ingestion.worker_loop(lambda *_args: 1)
            self.assertIn("Cannot connect to Java document API", str(logs.output))
            self.assertIn("JAVA_API_BASE_URL", str(logs.output))
            self.assertNotIn("secret-fixture", str(logs.output))
            self.assertNotIn("private-fixture", str(logs.output))
            self.assertEqual(0, ingestion._state["consecutiveFailures"])
            self.assertIsNone(ingestion._state["lastError"])
            self.assertIsNotNone(ingestion._state["lastSuccessAt"])

    async def test_poll_http_error_does_not_log_credentials_in_request_url(self):
        request = httpx.Request("POST", "http://java.invalid/?key=secret-fixture")
        response = httpx.Response(503, request=request, text="private-fixture")
        error = httpx.HTTPStatusError("private-fixture", request=request, response=response)
        with patch.dict("os.environ", {"JAVA_API_BASE_URL": "http://java.invalid", "JAVA_WORKER_API_KEY": "worker-key"}), \
                patch.dict(ingestion._state, {"consecutiveFailures": 0}), \
                patch.object(ingestion, "run_worker_once", side_effect=error), \
                patch.object(ingestion, "_sleep_or_trigger", side_effect=asyncio.CancelledError()), \
                self.assertLogs(ingestion.LOGGER, level="WARNING") as logs:
            with self.assertRaises(asyncio.CancelledError):
                await ingestion.worker_loop(lambda *_args: 1)
            self.assertIn("HTTP 503", str(logs.output))
            self.assertNotIn("secret-fixture", str(logs.output))
            self.assertNotIn("private-fixture", str(logs.output))
