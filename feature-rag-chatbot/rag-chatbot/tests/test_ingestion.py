import asyncio
import io
import unittest
from unittest.mock import patch

from app.workers import ingestion


class FakeResponse:
    def __init__(self, body=None, content=b"", status_code=200):
        self._body = body
        self.content = content
        self.status_code = status_code

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


class FakeClient:
    def __init__(self):
        self.result = None
        self.purge_calls = 0
        self.content_headers = None
        self.result_headers = None

    async def post(self, url, *_args, **_kwargs):
        if url.endswith("/documents/purge"):
            self.purge_calls += 1
            return FakeResponse({"purged": 0})
        return FakeResponse([{
            "id": "doc-1", "ownerId": "owner-1", "contentType": "text/markdown",
            "sourceType": "ENTERPRISE_DOCUMENT",
            "originalFilename": "guide.md", "requestedChunkSize": 800, "requestedChunkOverlap": 100,
            "attemptId": "attempt-1",
        }])

    async def get(self, *_args, **kwargs):
        self.content_headers = kwargs.get("headers")
        return FakeResponse(content=b"# Real uploaded guide")

    async def patch(self, *_args, **kwargs):
        self.result = kwargs["json"]
        self.result_headers = kwargs.get("headers")
        return FakeResponse()


class IngestionTest(unittest.IsolatedAsyncioTestCase):
    def test_extracts_text_from_a_valid_pdf(self):
        # Build a tiny PDF in memory so this exercises the installed pypdf
        # version without relying on an external fixture or service.
        stream = b"BT /F1 12 Tf 50 250 Td (BlueAI PDF smoke test) Tj ET"
        objects = [
            b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] "
            b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream),
        ]
        pdf = io.BytesIO(b"%PDF-1.4\n")
        pdf.seek(0, io.SEEK_END)
        offsets = [0]
        for number, value in enumerate(objects, start=1):
            offsets.append(pdf.tell())
            pdf.write(b"%d 0 obj\n%s\nendobj\n" % (number, value))
        xref_offset = pdf.tell()
        pdf.write(b"xref\n0 6\n0000000000 65535 f \n")
        for offset in offsets[1:]:
            pdf.write(b"%010d 00000 n \n" % offset)
        pdf.write(b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % xref_offset)

        text = ingestion.extract_text(pdf.getvalue(), "application/pdf", "guide.pdf")
        self.assertIn("BlueAI PDF smoke test", text)

    def test_extracts_utf8_text(self):
        self.assertEqual("Xin chào", ingestion.extract_text("Xin chào".encode(), "text/plain", "note.txt"))

    def test_extracts_csv_rows_with_column_labels(self):
        content = "\ufeffsku,name,refund_hours\nSB47,\"Sao Băng, 47\",24\n".encode("utf-8")

        text = ingestion.extract_text(content, "text/csv", "products.csv")

        self.assertIn("CSV row 1", text)
        self.assertIn("sku: SB47", text)
        self.assertIn("name: Sao Băng, 47", text)
        self.assertIn("refund_hours: 24", text)

    def test_rejects_csv_without_data_rows(self):
        with self.assertRaisesRegex(ValueError, "at least one data row"):
            ingestion.extract_text(b"sku,name\n", "text/csv", "products.csv")

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
        self.assertEqual({"attemptId": "attempt-1", "status": "READY", "producedChunkCount": 2}, client.result)
        self.assertEqual("attempt-1", client.content_headers["X-Worker-Attempt-Id"])
        self.assertEqual("attempt-1", client.result_headers["X-Worker-Attempt-Id"])
        self.assertEqual(1, client.purge_calls)

    async def test_worker_reports_failure_with_the_same_attempt_token(self):
        client = FakeClient()

        def failing_indexer(*_args):
            raise ValueError("parser failed")

        await ingestion.run_worker_once(client, "http://java", "worker-key", failing_indexer)
        self.assertEqual({
            "attemptId": "attempt-1", "status": "FAILED", "failureReason": "Document format or content is invalid.",
        }, client.result)
        self.assertEqual("attempt-1", client.result_headers["X-Worker-Attempt-Id"])

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
