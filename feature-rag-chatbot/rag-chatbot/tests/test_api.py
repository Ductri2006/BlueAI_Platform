import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app import main as api
from app.controllers import health as health_controller, documents as document_controller
from app.controllers import retrieval as retrieval_controller, completion as completion_controller
from app.core import config
from app.models import schemas
from app.repositories import faiss_repository
from app.services import runtime as runtime_module, completion as completion_service, retrieval as retrieval_service
from fastapi.testclient import TestClient
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


class PurgeableVectorStore:
    def __init__(self):
        self.docstore = SimpleNamespace(_dict={
            "vector-1": _document("doc-1", "secret one", owner="user-1"),
            "vector-2": _document("doc-1", "secret two", owner="user-1"),
            "vector-3": _document("doc-2", "keep me", owner="user-1"),
        })
        self.deleted = []
        self.saved = 0
        self.fail_next_save = False

    def delete(self, ids):
        self.deleted.extend(ids)
        for vector_id in ids:
            self.docstore._dict.pop(vector_id, None)

    def save_local(self, _directory):
        if self.fail_next_save:
            self.fail_next_save = False
            raise OSError("injected durable save failure")
        self.saved += 1
        directory = Path(_directory)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "index.faiss").write_bytes(b"fake-faiss")
        (directory / "index.pkl").write_text(",".join(sorted(self.docstore._dict)), encoding="utf-8")

    @classmethod
    def from_disk(cls, directory):
        store = cls()
        ids = [item for item in Path(directory, "index.pkl").read_text(encoding="utf-8").split(",") if item]
        store.docstore._dict = {
            vector_id: _document("doc-2" if vector_id == "vector-3" else "doc-1", vector_id)
            for vector_id in ids
        }
        return store


class IngestionVectorStore(PurgeableVectorStore):
    def __init__(self):
        super().__init__()
        self.docstore._dict = {}
        self.next_id = 0

    def add_embeddings(self, text_embeddings, metadatas):
        for (text, _vector), metadata in zip(text_embeddings, metadatas):
            vector_id = f"ingest-{self.next_id}"
            self.next_id += 1
            self.docstore._dict[vector_id] = Document(page_content=text, metadata=dict(metadata))


class RagApiTest(unittest.TestCase):
    def test_http_contract_after_fastapi_starlette_upgrade(self):
        match = schemas.RetrievalMatch(
            documentId="doc-1", chunkId="chunk-1", content="Grounded text",
            score=0.9, filename="guide.pdf", sourceType="ENTERPRISE_DOCUMENT",
            approvalStatus="READY",
        )
        answer = schemas.ChatCompletionResponse(
            id="chat-test", model="mock-model",
            choices=[schemas.CompletionChoice(message=schemas.ChatMessage(role="assistant", content="Answer"))],
        )
        worker_headers = {"X-Worker-Api-Key": "worker-key"}
        with patch.dict("os.environ", {"JAVA_WORKER_API_KEY": "worker-key"}), \
                patch.object(api, "start_worker", return_value=None), TestClient(api.app) as client:
            self.assertEqual(200, client.get("/health").status_code)
            with patch.object(runtime_module.runtime, "search", return_value=[match]) as search:
                response = client.post("/v1/retrieval/search", headers=worker_headers, json={
                    "userId": "user-1", "query": "policy", "limit": 4,
                })
            self.assertEqual(200, response.status_code)
            self.assertEqual("doc-1", response.json()["matches"][0]["documentId"])
            search.assert_called_once_with("user-1", "policy", 4)
            self.assertEqual(422, client.post(
                "/v1/retrieval/search", headers=worker_headers, json={"query": "policy"}
            ).status_code)

            with patch.object(runtime_module.runtime, "complete", return_value=answer):
                response = client.post("/v1/chat/completions", headers=worker_headers, json={
                    "messages": [{"role": "user", "content": "Hello"}],
                })
            self.assertEqual(200, response.status_code)
            self.assertEqual("Answer", response.json()["choices"][0]["message"]["content"])

            with patch.dict("os.environ", {
                "JAVA_API_BASE_URL": "http://auth-api:8080", "JAVA_WORKER_API_KEY": "worker-key",
            }), patch.object(document_controller, "request_run") as request_run:
                self.assertEqual(401, client.post("/v1/internal/ingest/run").status_code)
                response = client.post("/v1/internal/ingest/run", headers={"X-Worker-API-Key": "worker-key"})
                self.assertEqual(202, response.status_code)
                request_run.assert_called_once_with()

    def test_health(self):
        response = health_controller.health()
        self.assertEqual("ok", response["status"])
        self.assertEqual("gemini", response["provider"])
        self.assertIn("geminiConfigured", response)
        self.assertIn("retrievalTuning", response)

    def test_health_reports_worker_target_so_a_misconfigured_worker_is_visible(self):
        with patch.dict("os.environ", {
            "JAVA_API_BASE_URL": "http://auth-api:8080",
            "JAVA_WORKER_API_KEY": "k",
        }):
            response = health_controller.health()
        self.assertEqual("http://auth-api:8080", response["documentWorker"]["javaApiBaseUrl"])
        self.assertIn("lastError", response["documentWorker"])

    def test_ingest_trigger_requires_the_shared_worker_key(self):
        with patch.dict("os.environ", {
            "JAVA_API_BASE_URL": "http://127.0.0.1:8080",
            "JAVA_WORKER_API_KEY": "worker-key",
        }):
            with patch.object(document_controller, "request_run") as request_run:
                with self.assertRaises(health_controller.HTTPException) as wrong_key:
                    document_controller.trigger_ingest(x_worker_api_key="nope")
                self.assertEqual(401, wrong_key.exception.status_code)
                with self.assertRaises(health_controller.HTTPException) as no_key:
                    document_controller.trigger_ingest(x_worker_api_key=None)
                self.assertEqual(401, no_key.exception.status_code)
                request_run.assert_not_called()

                self.assertEqual({"status": "queued"}, document_controller.trigger_ingest(x_worker_api_key="worker-key"))
                request_run.assert_called_once_with()

    def test_ingest_trigger_reports_an_unconfigured_worker(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(health_controller.HTTPException) as error:
                document_controller.trigger_ingest(x_worker_api_key="anything")
        self.assertEqual(503, error.exception.status_code)

    def test_readiness_rejects_missing_gemini_key(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(health_controller.HTTPException) as error:
                health_controller.readiness()
        self.assertEqual(503, error.exception.status_code)

    def test_readiness_accepts_gemini_key(self):
        with patch.dict("os.environ", {"GEMINI_API_KEY": "test-key"}, clear=True):
            self.assertEqual({"status": "ready", "provider": "gemini"}, health_controller.readiness())

    def test_operational_readiness_rejects_disabled_stalled_or_failing_worker(self):
        healthy = {
            "configured": True, "running": True, "consecutiveFailures": 0,
            "lastSuccessAt": datetime.now(timezone.utc).isoformat(), "intervalSeconds": 5,
        }
        invalid_states = [
            {**healthy, "configured": False}, {**healthy, "running": False},
            {**healthy, "consecutiveFailures": 100}, {**healthy, "lastSuccessAt": None},
            {**healthy, "lastSuccessAt": "invalid"},
            {**healthy, "lastSuccessAt": (datetime.now(timezone.utc) - timedelta(minutes=10)).isoformat()},
        ]
        with patch.dict("os.environ", {"GEMINI_API_KEY": "test-key"}, clear=True), \
                patch.object(api, "start_worker", return_value=None), TestClient(api.app) as client:
            self.assertEqual(200, client.get("/ready").status_code)
            for state in invalid_states:
                with self.subTest(state=state), patch.object(health_controller, "worker_status", return_value=state):
                    self.assertEqual(503, client.get("/readiness").status_code)
                    self.assertEqual(200, client.get("/health").status_code)
            with patch.object(health_controller, "worker_status", return_value=healthy), \
                    patch.object(runtime_module.runtime, "complete") as complete, patch.object(runtime_module.runtime, "search") as search:
                self.assertEqual(200, client.get("/readiness").status_code)
                complete.assert_not_called()
                search.assert_not_called()

    def test_retrieval_contract(self):
        match = schemas.RetrievalMatch(
            documentId="DOC-DEMO",
            chunkId="REC-1",
            content="Demo content",
            score=0.9,
            filename="Demo source",
            sourceType="SYNTHETIC_DEMO",
            approvalStatus="PENDING_OWNER_REVIEW",
        )
        with patch.dict("os.environ", {"JAVA_WORKER_API_KEY": "worker-key"}), \
                patch.object(runtime_module.runtime, "search", return_value=[match]) as search:
            response = retrieval_controller.retrieval_search(
                schemas.RetrievalRequest(userId="user-1", query="question", limit=4),
                x_worker_api_key="worker-key",
            )
        search.assert_called_once_with("user-1", "question", 4)
        self.assertEqual("DOC-DEMO", response.matches[0].documentId)

    def test_retrieval_filters_private_chunks_by_owner(self):
        runtime = runtime_module.RagRuntime()
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
        runtime = runtime_module.RagRuntime()
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
        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("doc-a", "Chinh sach hoan tien 7 ngay.", owner="user-1"), 0.10),
            (_document("doc-b", "  Chinh sach hoan tien 7 ngay. ", owner="user-1"), 0.20),
        ])
        matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["doc-a"], [match.documentId for match in matches])

    def test_retrieval_applies_minimum_relevance(self):
        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = StubVectorStore([
            (_document("doc-a", "strong match", owner="user-1"), 0.2),
            (_document("doc-b", "weak match", owner="user-1"), 1.4),
        ])
        with patch.dict("os.environ", {"RAG_MIN_RELEVANCE": "0.5"}):
            matches = runtime.search("user-1", "query", 5)
        self.assertEqual(["doc-a"], [match.documentId for match in matches])

    def test_retrieval_requests_a_wider_candidate_pool_than_the_limit(self):
        runtime = runtime_module.RagRuntime()
        store = StubVectorStore([])
        runtime._vectorstore = store
        runtime.search("user-1", "query", 6)
        self.assertGreaterEqual(store.k, 48)

    def test_integration_allowlist_expands_search_before_top_k_filtering(self):
        unauthorized = [(_document(f"private-{i}", f"private {i}", owner="other"), 0.01) for i in range(250)]
        authorized = [(_document("granted", "granted evidence", owner="user-1"), 0.3)]

        class IndexedStore(StubVectorStore):
            def __init__(self, results):
                super().__init__(results)
                self.index = SimpleNamespace(ntotal=len(results))

            def similarity_search_with_score(self, query, k):
                self.k = k
                return self.results[:k]

        store = IndexedStore(unauthorized + authorized)
        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = store
        matches = runtime.search("user-1", "query", 1, ["granted"])

        self.assertEqual(store.index.ntotal, store.k)
        self.assertEqual(["granted"], [match.documentId for match in matches])

    def test_empty_integration_allowlist_skips_vector_search(self):
        store = StubVectorStore([])
        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = store
        self.assertEqual([], runtime.search("user-1", "query", 1, []))
        self.assertIsNone(store.k)

    def test_integration_allowlist_fails_closed_when_index_exceeds_scan_limit(self):
        class LargeIndexedStore(StubVectorStore):
            index = SimpleNamespace(ntotal=100_001)

        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = LargeIndexedStore([])
        with self.assertRaisesRegex(RuntimeError, "100,000-vector"):
            runtime.search("user-1", "query", 1, ["granted"])

    def test_retrieval_ignores_blank_query(self):
        runtime = runtime_module.RagRuntime()
        runtime._vectorstore = StubVectorStore([(_document("doc-a", "content", owner="user-1"), 0.1)])
        self.assertEqual([], runtime.search("user-1", "   \n  ", 5))

    def test_document_purge_compacts_faiss_docstore_and_is_idempotent(self):
        runtime = runtime_module.RagRuntime()
        store = PurgeableVectorStore()
        runtime._vectorstore = store
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"RAG_INDEX_DIR": directory}):
            self.assertEqual(2, runtime.purge_document("doc-1"))
            self.assertEqual(["vector-1", "vector-2"], store.deleted)
            self.assertEqual(1, store.saved)
            self.assertEqual(0, runtime.purge_document("doc-1"))
            self.assertEqual(1, store.saved)
            self.assertIn("vector-3", store.docstore._dict)

    def test_document_purge_save_failure_restores_durable_view_and_retry_cleans_disk(self):
        runtime = runtime_module.RagRuntime()
        store = PurgeableVectorStore()
        durable_store = PurgeableVectorStore()
        # Disk recovery must be testable without a provider key or network call.
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict("os.environ", {"RAG_INDEX_DIR": directory}, clear=True), \
                patch.object(runtime_module.RagRuntime, "_embeddings", return_value=unittest.mock.Mock()):
            store.save_local(directory)
            store.fail_next_save = True
            with patch.object(faiss_repository.FAISS, "load_local", return_value=durable_store):
                with self.assertRaises(OSError):
                    runtime._vectorstore = store
                    runtime.purge_document("doc-1")

            self.assertIs(runtime._vectorstore, durable_store)
            self.assertIn("vector-1", durable_store.docstore._dict)
            self.assertEqual(2, runtime.purge_document("doc-1"))
            self.assertNotIn("doc-1", Path(directory, "index.pkl").read_text(encoding="utf-8"))

            restarted = runtime_module.RagRuntime()
            with patch.object(faiss_repository.FAISS, "load_local", return_value=PurgeableVectorStore.from_disk(directory)):
                self.assertEqual(0, restarted.purge_document("doc-1"))
            self.assertNotIn("doc-1", restarted._vectorstore.docstore._dict)

    def test_add_user_document_replaces_existing_chunks_on_retry(self):
        runtime = runtime_module.RagRuntime()
        store = IngestionVectorStore()
        fake_embeddings = unittest.mock.Mock()
        fake_embeddings.embed_documents.return_value = [[0.1]]
        runtime._vectorstore = store
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict("os.environ", {"RAG_INDEX_DIR": directory}), \
                patch.object(runtime, "_embeddings", return_value=fake_embeddings):
            first = runtime.add_user_document("doc-retry", "user-1", "USER_DOCUMENT", "retry.txt", "retry content", 1200, 0)
            second = runtime.add_user_document("doc-retry", "user-1", "USER_DOCUMENT", "retry.txt", "retry content", 1200, 0)

        self.assertEqual(first, second)
        self.assertEqual(first, sum(
            1 for document in store.docstore._dict.values()
            if document.metadata.get("document_id") == "doc-retry"
        ))

    def test_document_purge_endpoint_requires_worker_key(self):
        with patch.dict("os.environ", {"JAVA_WORKER_API_KEY": "worker-key"}, clear=True), \
                patch.object(runtime_module.runtime, "purge_document", return_value=3) as purge:
            with self.assertRaises(health_controller.HTTPException) as error:
                document_controller.purge_document("doc-1", x_worker_api_key="wrong")
            self.assertEqual(401, error.exception.status_code)
            self.assertEqual(
                {"status": "purged", "documentId": "doc-1", "removedChunks": 3},
                document_controller.purge_document("doc-1", x_worker_api_key="worker-key"),
            )
            purge.assert_called_once_with("doc-1")

    def test_index_staging_needs_only_the_index_directory_not_its_parent(self):
        store = PurgeableVectorStore()
        with tempfile.TemporaryDirectory() as directory:
            index_dir = Path(directory) / "mounted-index"
            original_mkdtemp = tempfile.mkdtemp
            with patch.object(faiss_repository.tempfile, "mkdtemp", wraps=original_mkdtemp) as staging:
                runtime_module.RagRuntime._save_vectorstore_atomically(store, index_dir)
            self.assertEqual(index_dir, staging.call_args.kwargs["dir"])
            self.assertEqual({"index.faiss", "index.pkl"}, {p.name for p in index_dir.iterdir()})

    def test_index_file_swap_failure_restores_both_durable_files(self):
        store = PurgeableVectorStore()
        with tempfile.TemporaryDirectory() as directory:
            index_dir = Path(directory)
            store.save_local(directory)
            old_files = {p.name: p.read_bytes() for p in index_dir.iterdir()}
            store.delete(["vector-1"])
            original_replace = faiss_repository.os.replace
            def fail_second_install(source, target):
                source, target = Path(source), Path(target)
                if source.parent.name.startswith(".staging-") and target.name == "index.pkl":
                    raise OSError("injected second-file install failure")
                return original_replace(source, target)
            with patch.object(faiss_repository.os, "replace", side_effect=fail_second_install):
                with self.assertRaisesRegex(OSError, "second-file"):
                    runtime_module.RagRuntime._save_vectorstore_atomically(store, index_dir)
            self.assertEqual(old_files, {p.name: p.read_bytes() for p in index_dir.iterdir()})

    def test_ingestion_save_failure_restores_memory_and_retry_does_not_duplicate(self):
        runtime = runtime_module.RagRuntime()
        store, durable_store = IngestionVectorStore(), IngestionVectorStore()
        embeddings = unittest.mock.Mock()
        embeddings.embed_documents.return_value = [[0.1]]
        runtime._vectorstore = store
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict("os.environ", {"RAG_INDEX_DIR": directory}), \
                patch.object(runtime, "_embeddings", return_value=embeddings):
            store.save_local(directory)
            before = Path(directory, "index.pkl").read_bytes()
            store.fail_next_save = True
            with patch.object(faiss_repository.FAISS, "load_local", return_value=durable_store):
                with self.assertRaises(OSError):
                    runtime.add_user_document("new-doc", "user-1", "USER_DOCUMENT", "new.txt", "new content", 1200, 0)
            self.assertIs(runtime._vectorstore, durable_store)
            self.assertEqual({}, durable_store.docstore._dict)
            self.assertEqual(before, Path(directory, "index.pkl").read_bytes())
            for _ in range(2):
                self.assertEqual(1, runtime.add_user_document("new-doc", "user-1", "USER_DOCUMENT", "new.txt", "new content", 1200, 0))
            self.assertEqual(1, len(durable_store.docstore._dict))

    def test_index_metadata_detects_embedding_model_change(self):
        with tempfile.TemporaryDirectory() as directory:
            index_dir = Path(directory)
            with patch.dict("os.environ", {"GEMINI_EMBEDDING_MODEL": "model-a"}):
                runtime_module.RagRuntime._write_index_model(index_dir)
                self.assertEqual("model-a", runtime_module.RagRuntime._read_index_model(index_dir))
            with patch.dict("os.environ", {"GEMINI_EMBEDDING_MODEL": "model-b"}):
                self.assertNotEqual(config._embedding_model(), runtime_module.RagRuntime._read_index_model(index_dir))

    def test_index_metadata_is_absent_for_a_legacy_index(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(runtime_module.RagRuntime._read_index_model(Path(directory)))

    def test_extract_text_handles_every_content_shape(self):
        self.assertEqual("plain", completion_service._extract_text("plain"))
        self.assertEqual("", completion_service._extract_text([]))
        self.assertEqual("", completion_service._extract_text(None))
        self.assertEqual("a b", completion_service._extract_text(["a b"]))
        self.assertEqual("cau tra loi", completion_service._extract_text([{"text": "cau tra loi"}]))
        self.assertEqual(
            "Hello! How can I help you today?",
            completion_service._extract_text([{
                "type": "text",
                "text": "Hello! How can I help you today?",
                "extras": {"signature": "El4KXAERTTIPWM4oUT1zee1BHK2xUEDa"},
            }]),
        )
        self.assertEqual("Cau tra loi", completion_service._extract_text([
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
                ], usage_metadata={"input_tokens": 120, "output_tokens": 24, "total_tokens": 160})

        request = schemas.ChatCompletionRequest(messages=[schemas.ChatMessage(role="user", content="hoan tien?")])
        with patch.object(completion_service, "ChatGoogleGenerativeAI", return_value=FakeLlm()):
            response = runtime_module.RagRuntime().complete(request)
        self.assertEqual("Chinh sach hoan tien la 7 ngay. [S1]", response.choices[0].message.content)
        self.assertEqual(120, response.usage.prompt_tokens)
        self.assertEqual(24, response.usage.completion_tokens)
        self.assertEqual(160, response.usage.total_tokens)

    def test_completion_rejects_a_response_without_text_blocks(self):
        class ThinkingOnlyLlm:
            def invoke(self, _messages):
                return AIMessage(content=[{"type": "reasoning", "reasoning": "chi suy nghi"}])

        request = schemas.ChatCompletionRequest(messages=[schemas.ChatMessage(role="user", content="hoan tien?")])
        with patch.object(completion_service, "ChatGoogleGenerativeAI", return_value=ThinkingOnlyLlm()):
            with self.assertRaises(RuntimeError):
                runtime_module.RagRuntime().complete(request)

    def test_completion_falls_back_after_a_transient_model_failure(self):
        attempted_models = []

        class FakeLlm:
            def __init__(self, model):
                self.model = model

            def invoke(self, _messages):
                if self.model == "primary-model":
                    raise RuntimeError("503 UNAVAILABLE: high demand")
                return AIMessage(content="fallback ok")

        def fake_factory(*, model, **_kwargs):
            attempted_models.append(model)
            return FakeLlm(model)

        request = schemas.ChatCompletionRequest(messages=[schemas.ChatMessage(role="user", content="hello")])
        with patch.dict("os.environ", {
            "GEMINI_CHAT_MODEL": "primary-model",
            "GEMINI_CHAT_FALLBACK_MODELS": "fallback-model",
        }):
            with patch.object(completion_service, "ChatGoogleGenerativeAI", side_effect=fake_factory):
                response = runtime_module.RagRuntime().complete(request)

        self.assertEqual(["primary-model", "fallback-model"], attempted_models)
        self.assertEqual("fallback-model", response.model)
        self.assertEqual("fallback ok", response.choices[0].message.content)

    def test_openai_compatible_completion_contract(self):
        expected = schemas.ChatCompletionResponse(
            id="chatcmpl-test",
            model="test-model",
            choices=[schemas.CompletionChoice(message=schemas.ChatMessage(role="assistant", content="answer"))],
        )
        request = schemas.ChatCompletionRequest(
            model="test-model",
            messages=[schemas.ChatMessage(role="user", content="question")],
        )
        with patch.dict("os.environ", {"JAVA_WORKER_API_KEY": "worker-key"}), \
                patch.object(runtime_module.runtime, "complete", return_value=expected):
            response = completion_controller.chat_completions(request, x_worker_api_key="worker-key")
        self.assertEqual("answer", response.choices[0].message.content)

    def test_completion_budget_has_no_nested_sdk_retries(self):
        fake = unittest.mock.Mock()
        fake.invoke.return_value = AIMessage(content="ok")
        with patch.object(completion_service, "ChatGoogleGenerativeAI", return_value=fake) as factory:
            runtime_module.RagRuntime().complete(schemas.ChatCompletionRequest(messages=[schemas.ChatMessage(role="user", content="test")]))
        self.assertEqual(1, factory.call_args.kwargs["max_retries"])
        self.assertEqual(12, factory.call_args.kwargs["timeout"])

    def test_model_list_deduplicates_and_bounds_provider_attempts(self):
        with patch.dict("os.environ", {"GEMINI_CHAT_MODEL": "primary", "GEMINI_CHAT_FALLBACK_MODELS": "primary, backup, third"}):
            self.assertEqual(["primary", "backup"], config._chat_models())

    def test_authentication_failure_does_not_try_fallback(self):
        fake = unittest.mock.Mock()
        fake.invoke.side_effect = RuntimeError("401 UNAUTHENTICATED")
        with patch.object(completion_service, "ChatGoogleGenerativeAI", return_value=fake) as factory:
            with self.assertRaises(RuntimeError):
                runtime_module.RagRuntime().complete(schemas.ChatCompletionRequest(messages=[schemas.ChatMessage(role="user", content="test")]))
        self.assertEqual(1, factory.call_count)


if __name__ == "__main__":
    unittest.main()
