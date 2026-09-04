import unittest

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
