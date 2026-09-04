import unittest
from unittest.mock import patch

import api
from langchain_core.documents import Document


class RagApiTest(unittest.TestCase):
    def test_health(self):
        response = api.health()
        self.assertEqual("ok", response["status"])
        self.assertEqual("gemini", response["provider"])
        self.assertIn("geminiConfigured", response)

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

        class VectorStore:
            def similarity_search_with_relevance_scores(self, _query, k):
                self.k = k
                return [
                    (Document(page_content="other", metadata={"document_id": "other", "owner_id": "user-2", "source_type": "USER_DOCUMENT", "approval_status": "READY"}), 0.95),
                    (Document(page_content="mine", metadata={"document_id": "mine", "owner_id": "user-1", "filename": "mine.md", "source_type": "USER_DOCUMENT", "approval_status": "READY"}), 0.9),
                    (Document(page_content="enterprise", metadata={"document_id": "enterprise", "owner_id": "admin-1", "filename": "policy.md", "source_type": "ENTERPRISE_DOCUMENT", "approval_status": "READY"}), 0.85),
                    (Document(page_content="demo", metadata={"document_id": "demo", "source_type": "SYNTHETIC_DEMO", "approval_status": "PENDING_OWNER_REVIEW"}), 0.8),
                ]

        runtime._vectorstore = VectorStore()
        matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["mine", "enterprise", "demo"], [match.documentId for match in matches])
        self.assertEqual("mine.md", matches[0].filename)

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
