import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import api
from langchain_core.documents import Document
from langchain_core.messages import AIMessage


def _document(document_id, content, owner=None, source_type="USER_DOCUMENT", filename=None):
    metadata = {"document_id": document_id, "source_type": source_type, "approval_status": "READY"}
    if owner is not None:
        metadata["owner_id"] = owner
    if filename is not None:
        metadata["filename"] = filename
    return Document(page_content=content, metadata=metadata)


class StubVectorStore:
    """Returns squared-L2 distances, the same shape FAISS IndexFlatL2 produces."""

    def __init__(self, results):
        self.results = results
        self.k = None

    def similarity_search_with_score(self, _query, k):
        self.k = k
        return self.results


class RagApiTest(unittest.TestCase):
    def test_health(self):
        response = api.health()
        self.assertEqual("ok", response["status"])
        self.assertEqual("gemini", response["provider"])
        self.assertIn("geminiConfigured", response)
        self.assertIn("retrievalTuning", response)

    def test_health_reports_worker_target_so_a_misconfigured_worker_is_visible(self):
        with patch.dict("os.environ", {
            "JAVA_API_BASE_URL": "http://auth-api:8080",
            "JAVA_WORKER_API_KEY": "k",
        }):
            response = api.health()
        self.assertEqual("http://auth-api:8080", response["documentWorker"]["javaApiBaseUrl"])
        self.assertIn("lastError", response["documentWorker"])

    def test_ingest_trigger_requires_the_shared_worker_key(self):
        with patch.dict("os.environ", {
            "JAVA_API_BASE_URL": "http://127.0.0.1:8080",
            "JAVA_WORKER_API_KEY": "worker-key",
        }):
            with patch.object(api, "request_run") as request_run:
                with self.assertRaises(api.HTTPException) as wrong_key:
                    api.trigger_ingest(x_worker_api_key="nope")
                self.assertEqual(401, wrong_key.exception.status_code)
                with self.assertRaises(api.HTTPException) as no_key:
                    api.trigger_ingest(x_worker_api_key=None)
                self.assertEqual(401, no_key.exception.status_code)
                request_run.assert_not_called()

                self.assertEqual({"status": "queued"}, api.trigger_ingest(x_worker_api_key="worker-key"))
                request_run.assert_called_once_with()

    def test_ingest_trigger_reports_an_unconfigured_worker(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(api.HTTPException) as error:
                api.trigger_ingest(x_worker_api_key="anything")
        self.assertEqual(503, error.exception.status_code)

    def test_readiness_rejects_missing_gemini_key(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(api.HTTPException) as error:
                api.readiness()
        self.assertEqual(503, error.exception.status_code)

    def test_readiness_accepts_gemini_key(self):
        with patch.dict("os.environ", {"GEMINI_API_KEY": "test-key"}, clear=True):
            self.assertEqual({"status": "ready", "provider": "gemini"}, api.readiness())

    def test_retrieval_contract(self):
        match = api.RetrievalMatch(
            documentId="DOC-DEMO",
            chunkId="REC-1",
            content="Demo content",
            score=0.9,
            filename="Demo source",
            sourceType="SYNTHETIC_DEMO",
            approvalStatus="PENDING_OWNER_REVIEW",
        )
        with patch.object(api.runtime, "search", return_value=[match]) as search:
            response = api.retrieval_search(api.RetrievalRequest(userId="user-1", query="question", limit=4))
        search.assert_called_once_with("user-1", "question", 4)
        self.assertEqual("DOC-DEMO", response.matches[0].documentId)

    def test_retrieval_filters_private_chunks_by_owner(self):
        runtime = api.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("other", "other", owner="user-2"), 0.1),
            (_document("mine", "mine", owner="user-1", filename="mine.md"), 0.2),
            (_document("enterprise", "enterprise", owner="admin-1", source_type="ENTERPRISE_DOCUMENT",
                       filename="policy.md"), 0.3),
            (_document("demo", "demo", source_type="SYNTHETIC_DEMO"), 0.4),
        ])
        matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["mine", "enterprise", "demo"], [match.documentId for match in matches])
        self.assertEqual("mine.md", matches[0].filename)
        self.assertAlmostEqual(0.9, matches[0].score, places=6)

    def test_retrieval_caps_chunks_per_document_without_losing_recall(self):
        runtime = api.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("doc-a", "a1", owner="user-1"), 0.10),
            (_document("doc-a", "a2", owner="user-1"), 0.12),
            (_document("doc-a", "a3", owner="user-1"), 0.14),
            (_document("doc-a", "a4", owner="user-1"), 0.16),
            (_document("doc-b", "b1", owner="user-1"), 0.90),
        ])
        with patch.dict("os.environ", {"RAG_MAX_CHUNKS_PER_DOCUMENT": "2"}):
            matches = runtime.search("user-1", "query", 4)
        self.assertEqual(4, len(matches))
        self.assertIn("doc-b", [match.documentId for match in matches])
        self.assertEqual(3, sum(1 for match in matches if match.documentId == "doc-a"))
        self.assertEqual(sorted((match.score for match in matches), reverse=True),
                         [match.score for match in matches])

    def test_retrieval_drops_duplicate_chunk_content(self):
        runtime = api.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("doc-a", "Chinh sach hoan tien 7 ngay.", owner="user-1"), 0.10),
            (_document("doc-b", "  Chinh sach hoan tien 7 ngay. ", owner="user-1"), 0.20),
        ])
        matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["doc-a"], [match.documentId for match in matches])

    def test_retrieval_applies_minimum_relevance(self):
        runtime = api.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("doc-a", "strong match", owner="user-1"), 0.2),
            (_document("doc-b", "weak match", owner="user-1"), 1.4),
        ])
        with patch.dict("os.environ", {"RAG_MIN_RELEVANCE": "0.5"}):
            matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["doc-a"], [match.documentId for match in matches])

    def test_retrieval_requests_a_wider_candidate_pool_than_the_limit(self):
        runtime = api.RagRuntime()
        store = StubVectorStore([])
        runtime._vectorstore = store
        runtime.search("user-1", "query", 6)
        self.assertGreaterEqual(store.k, 48)

    def test_retrieval_ignores_blank_query(self):
        runtime = api.RagRuntime()
        runtime._vectorstore = StubVectorStore([(_document("doc-a", "content", owner="user-1"), 0.1)])
        self.assertEqual([], runtime.search("user-1", "   \n  ", 5))

    def test_index_metadata_detects_embedding_model_change(self):
        with tempfile.TemporaryDirectory() as directory:
            index_dir = Path(directory)
            with patch.dict("os.environ", {"GEMINI_EMBEDDING_MODEL": "model-a"}):
                api.RagRuntime._write_index_model(index_dir)
                self.assertEqual("model-a", api.RagRuntime._read_index_model(index_dir))
            with patch.dict("os.environ", {"GEMINI_EMBEDDING_MODEL": "model-b"}):
                self.assertNotEqual(api._embedding_model(), api.RagRuntime._read_index_model(index_dir))

    def test_index_metadata_is_absent_for_a_legacy_index(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(api.RagRuntime._read_index_model(Path(directory)))

    def test_extract_text_handles_every_content_shape(self):
        self.assertEqual("plain", api._extract_text("plain"))
        self.assertEqual("", api._extract_text([]))
        self.assertEqual("", api._extract_text(None))
        self.assertEqual("a b", api._extract_text(["a b"]))
        self.assertEqual("cau tra loi", api._extract_text([{"text": "cau tra loi"}]))
        self.assertEqual(
            "Hello! How can I help you today?",
            api._extract_text([{
                "type": "text",
                "text": "Hello! How can I help you today?",
                "extras": {"signature": "El4KXAERTTIPWM4oUT1zee1BHK2xUEDa"},
            }]),
        )
        self.assertEqual("Cau tra loi", api._extract_text([
            {"type": "reasoning", "reasoning": "suy nghi noi bo"},
            {"type": "text", "text": "Cau tra loi"},
        ]))

    def test_completion_flattens_gemini_content_blocks(self):
        class FakeLlm:
            def invoke(self, _messages):
                return AIMessage(content=[
                    {"type": "reasoning", "reasoning": "suy nghi noi bo"},
                    {"type": "text", "text": "Chinh sach hoan tien la 7 ngay. [S1]",
                     "extras": {"signature": "El4KXAERTTIPWM4oUT1zee1BHK2xUEDa"}},
                ])

        request = api.ChatCompletionRequest(messages=[api.ChatMessage(role="user", content="hoan tien?")])
        with patch.object(api, "ChatGoogleGenerativeAI", return_value=FakeLlm()):
            response = api.RagRuntime().complete(request)
        self.assertEqual("Chinh sach hoan tien la 7 ngay. [S1]", response.choices[0].message.content)

    def test_completion_rejects_a_response_without_text_blocks(self):
        class ThinkingOnlyLlm:
            def invoke(self, _messages):
                return AIMessage(content=[{"type": "reasoning", "reasoning": "chi suy nghi"}])

        request = api.ChatCompletionRequest(messages=[api.ChatMessage(role="user", content="hoan tien?")])
        with patch.object(api, "ChatGoogleGenerativeAI", return_value=ThinkingOnlyLlm()):
            with self.assertRaises(RuntimeError):
                api.RagRuntime().complete(request)

    def test_openai_compatible_completion_contract(self):
        expected = api.ChatCompletionResponse(
            id="chatcmpl-test",
            model="test-model",
            choices=[api.CompletionChoice(message=api.ChatMessage(role="assistant", content="answer"))],
        )
        request = api.ChatCompletionRequest(
            model="test-model",
            messages=[api.ChatMessage(role="user", content="question")],
        )
        with patch.object(api.runtime, "complete", return_value=expected):
            response = api.chat_completions(request)
        self.assertEqual("answer", response.choices[0].message.content)


if __name__ == "__main__":
    unittest.main()
