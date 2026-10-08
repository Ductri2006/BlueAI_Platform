import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from app import main as api
from app.controllers import health as health_controller, documents as document_controller
from app.controllers import retrieval as retrieval_controller, completion as completion_controller
from app.core import config
from app.models import schemas
from app.repositories import faiss_repository
from app.services import runtime as runtime_module, completion as completion_service, retrieval as retrieval_service
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage


class CompletionUsageTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.dict("os.environ", {
            "GEMINI_CHAT_MODEL": "test-primary",
            "GEMINI_CHAT_FALLBACK_MODELS": "test-backup",
            "JAVA_WORKER_API_KEY": "test-only-private-worker-key",
        }))
        self.llm = Mock()
        self.llm.invoke.return_value = SimpleNamespace(
            content=" Answer ",
            usage_metadata={"input_tokens": 120, "output_tokens": 24, "total_tokens": 160},
        )
        self.factory = self.enterContext(patch.object(completion_service, "ChatGoogleGenerativeAI", return_value=self.llm))
        self.enterContext(patch.object(api, "start_worker", return_value=None))
        self.client = self.enterContext(TestClient(api.app))

    def ask(self, **fields):
        return self.client.post("/v1/chat/completions", headers={
            "X-Worker-Api-Key": "test-only-private-worker-key",
        }, json={
            "messages": [{"role": "user", "content": "Kiểm tra token"}], **fields,
        })

    def test_http_contract_includes_usage_with_java_field_names(self):
        response = self.ask(model="openai-compatible-placeholder")
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("test-primary", body["model"])
        self.assertEqual("Answer", body["choices"][0]["message"]["content"])
        self.assertEqual({"prompt_tokens": 120, "completion_tokens": 24, "total_tokens": 160}, body["usage"])
        self.factory.assert_called_once()

    def test_openapi_exposes_usage_so_old_running_services_can_be_detected(self):
        schemas = self.client.get("/openapi.json").json()["components"]["schemas"]
        self.assertIn("usage", schemas["ChatCompletionResponse"]["properties"])
        self.assertEqual({"prompt_tokens", "completion_tokens", "total_tokens"},
                         set(schemas["CompletionUsage"]["properties"]))

    def test_absent_metadata_preserves_answer_without_fabricated_usage(self):
        self.llm.invoke.return_value.usage_metadata = None
        response = self.ask()
        self.assertEqual(200, response.status_code)
        self.assertEqual("Answer", response.json()["choices"][0]["message"]["content"])
        self.assertIsNone(response.json()["usage"])

    def test_partial_metadata_does_not_turn_missing_counts_into_zero(self):
        cases = [
            {}, {"input_tokens": 10},
            {"input_tokens": 10, "output_tokens": 2},
            {"input_tokens": 10, "total_tokens": 12},
            {"output_tokens": 2, "total_tokens": 12},
            {"input_tokens": None, "output_tokens": 2, "total_tokens": 12},
        ]
        for metadata in cases:
            with self.subTest(metadata=metadata):
                self.llm.invoke.return_value.usage_metadata = metadata
                response = self.ask()
                self.assertEqual(200, response.status_code)
                self.assertIsNone(response.json()["usage"])

    def test_zero_counts_are_included_instead_of_treated_as_missing(self):
        self.llm.invoke.return_value.usage_metadata = {
            "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
        }
        self.assertEqual({"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}, self.ask().json()["usage"])

    def test_provider_total_is_preserved_when_it_includes_thinking(self):
        body = self.ask().json()
        self.assertEqual(160, body["usage"]["total_tokens"])
        self.assertGreater(body["usage"]["total_tokens"],
                           body["usage"]["prompt_tokens"] + body["usage"]["completion_tokens"])

    def test_large_usage_counts_survive_json_serialization(self):
        self.llm.invoke.return_value.usage_metadata = {
            "input_tokens": 5_000_000_000, "output_tokens": 2, "total_tokens": 5_000_000_002,
        }
        self.assertEqual(5_000_000_002, self.ask().json()["usage"]["total_tokens"])

    def test_fallback_returns_successful_model_and_its_usage(self):
        unavailable = Mock()
        unavailable.invoke.side_effect = RuntimeError("503 UNAVAILABLE")
        self.factory.side_effect = [unavailable, self.llm]
        response = self.ask()
        self.assertEqual(200, response.status_code)
        self.assertEqual("test-backup", response.json()["model"])
        self.assertEqual(160, response.json()["usage"]["total_tokens"])
        self.assertEqual(["test-primary", "test-backup"],
                         [call.kwargs["model"] for call in self.factory.call_args_list])

    def test_history_and_system_prompt_are_sent_to_the_provider(self):
        response = self.ask(messages=[
            {"role": "system", "content": "System instructions"},
            {"role": "user", "content": "Hà Nội mở cửa lúc mấy giờ?"},
            {"role": "assistant", "content": "8 giờ"},
            {"role": "user", "content": "Trà Vinh mở cửa lúc mấy giờ?"},
        ])
        self.assertEqual(200, response.status_code)
        messages = self.llm.invoke.call_args.args[0]
        self.assertEqual([SystemMessage, HumanMessage, AIMessage, HumanMessage], [type(message) for message in messages])
        self.assertEqual("Trà Vinh mở cửa lúc mấy giờ?", messages[-1].content)

    def test_invalid_request_does_not_call_gemini(self):
        for fields in ({"messages": []}, {"temperature": 3},
                       {"messages": [{"role": "unknown", "content": "test"}]}):
            with self.subTest(fields=fields):
                self.assertEqual(422, self.ask(**fields).status_code)
        self.factory.assert_not_called()

    def test_provider_failure_returns_502_without_success_usage(self):
        self.llm.invoke.side_effect = RuntimeError("401 UNAUTHENTICATED")
        response = self.ask()
        self.assertEqual(502, response.status_code)
        self.assertNotIn("usage", response.json())
        self.factory.assert_called_once()

    def test_negative_provider_counts_are_rejected(self):
        self.llm.invoke.return_value.usage_metadata = {
            "input_tokens": -1, "output_tokens": 2, "total_tokens": 1,
        }
        self.assertEqual(502, self.ask().status_code)

    def test_each_prompt_serializes_its_own_usage_not_the_previous_one(self):
        first = self.ask().json()
        self.llm.invoke.return_value = SimpleNamespace(
            content="Second answer",
            usage_metadata={"input_tokens": 884, "output_tokens": 9, "total_tokens": 893},
        )
        second = self.ask().json()
        self.assertEqual(160, first["usage"]["total_tokens"])
        self.assertEqual(893, second["usage"]["total_tokens"])
        self.assertNotEqual(first["id"], second["id"])


if __name__ == "__main__":
    unittest.main()
