import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from app.services.embedding_usage import MeteredEmbeddings, query_usage
from app.models.schemas import ChatCompletionRequest, ChatMessage
from app.services.completion import CompletionService


class EmbeddingUsageTest(unittest.TestCase):
    def adapter(self, usage=None, count=6):
        models = Mock()
        models.count_tokens.return_value = SimpleNamespace(total_tokens=count)
        models.embed_content.return_value = SimpleNamespace(
            embeddings=[SimpleNamespace(values=[0.1, 0.2])], usage_metadata=usage)
        return MeteredEmbeddings.model_construct(model="gemini-embedding-001", client=SimpleNamespace(models=models)), models

    def test_preserves_query_vector_and_uses_provider_count_when_usage_is_absent(self):
        adapter, models = self.adapter()
        with query_usage() as usage:
            self.assertEqual(adapter.embed_query("BlueAI"), [0.1, 0.2])
        self.assertEqual(usage[0].tokens, 6)
        self.assertEqual(usage[0].source, "COUNT_TOKENS")
        self.assertEqual(models.embed_content.call_args.kwargs["config"].task_type, "RETRIEVAL_QUERY")
        self.assertEqual(models.embed_content.call_args.kwargs["config"].http_options.retry_options.attempts, 1)

    def test_prefers_actual_usage_and_isolates_each_query_context(self):
        adapter, _ = self.adapter(SimpleNamespace(prompt_token_count=5))
        with query_usage() as outer:
            adapter.embed_query("one")
            with query_usage() as inner:
                adapter.embed_query("two")
            adapter.embed_query("three")
        self.assertEqual([value.tokens for value in outer], [5, 5])
        self.assertEqual(len(inner), 1)
        self.assertEqual(outer[0].source, "USAGE_METADATA")

    def test_overlong_input_does_not_silently_truncate_or_call_embedding(self):
        adapter, models = self.adapter(count=2041)
        with self.assertRaises(ValueError):
            adapter.embed_query("too long")
        models.embed_content.assert_not_called()

    @patch("app.services.embedding_usage._worker_quota")
    def test_indexing_is_system_usage_and_is_not_added_to_the_query_counter(self, callback):
        callback.return_value = {"id":"operation"}
        adapter, models = self.adapter()
        with query_usage() as usage:
            self.assertEqual(adapter.embed_documents(["Document text"]), [[0.1, 0.2]])
        self.assertEqual(usage, [])
        self.assertEqual(callback.call_args.args[0], "confirm")
        self.assertEqual(callback.call_args.args[1]["measurement"], "COUNT_TOKENS")
        self.assertEqual(models.embed_content.call_args.kwargs["config"].task_type, "RETRIEVAL_DOCUMENT")

    @patch("app.services.completion.ChatGoogleGenerativeAI")
    @patch("app.services.completion._chat_models", return_value=["primary", "backup"])
    def test_accounted_completion_caps_output_and_does_not_replay_an_ambiguous_timeout(self, _models, llm):
        llm.return_value.invoke.side_effect = TimeoutError("provider timeout")
        with self.assertRaises(TimeoutError):
            CompletionService().complete(ChatCompletionRequest(messages=[ChatMessage(role="user", content="hello")],
                operation_id="qa-operation", max_tokens=512))
        self.assertEqual(llm.call_count, 1)
        self.assertEqual(llm.call_args.kwargs["max_tokens"], 512)
        self.assertEqual(llm.call_args.kwargs["max_retries"], 0)


if __name__ == "__main__":
    unittest.main()
