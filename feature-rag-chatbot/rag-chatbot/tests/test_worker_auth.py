"""Regression gates for the Java/RAG private authentication boundary."""

import unittest
from unittest.mock import patch

import api
from fastapi.testclient import TestClient


class WorkerAuthenticationTest(unittest.TestCase):
    ENDPOINTS = (
        ("/v1/retrieval/search", {"userId": "user-1", "query": "policy", "limit": 4}),
        ("/v1/chat/completions", {"messages": [{"role": "user", "content": "hello"}]}),
        ("/v1/internal/ingest/run", None),
        ("/v1/internal/documents/doc-1/purge", None),
    )

    def test_unconfigured_or_development_key_fails_closed_before_work(self):
        for configured_key in ("", "local-worker-api-key-change-before-production"):
            with self.subTest(configured_key=configured_key), \
                    patch.dict("os.environ", {
                        "JAVA_API_BASE_URL": "http://auth-api:8080",
                        "JAVA_WORKER_API_KEY": configured_key,
                    }, clear=True), \
                    patch.object(api, "start_worker", return_value=None), \
                    patch.object(api.runtime, "search") as search, \
                    patch.object(api.runtime, "complete") as complete, \
                    patch.object(api.runtime, "purge_document") as purge, \
                    patch.object(api, "request_run") as ingest, TestClient(api.app) as client:
                for endpoint, body in self.ENDPOINTS:
                    response = client.post(endpoint, json=body, headers={"X-Worker-Api-Key": configured_key})
                    self.assertEqual(503, response.status_code, endpoint)
                for work in (search, complete, purge, ingest):
                    work.assert_not_called()

    def test_missing_or_wrong_header_cannot_reach_private_work(self):
        for headers in ({}, {"X-Worker-Api-Key": "wrong-key"}):
            with self.subTest(headers=headers), \
                    patch.dict("os.environ", {
                        "JAVA_API_BASE_URL": "http://auth-api:8080",
                        "JAVA_WORKER_API_KEY": "test-only-private-worker-key",
                    }, clear=True), \
                    patch.object(api, "start_worker", return_value=None), \
                    patch.object(api.runtime, "search") as search, \
                    patch.object(api.runtime, "complete") as complete, \
                    patch.object(api.runtime, "purge_document") as purge, \
                    patch.object(api, "request_run") as ingest, TestClient(api.app) as client:
                for endpoint, body in self.ENDPOINTS:
                    response = client.post(endpoint, json=body, headers=headers)
                    self.assertEqual(401, response.status_code, endpoint)
                for work in (search, complete, purge, ingest):
                    work.assert_not_called()

    def test_valid_auth_preserves_integration_document_allowlist(self):
        with patch.dict("os.environ", {"JAVA_WORKER_API_KEY": "test-only-private-worker-key"}, clear=True), \
                patch.object(api, "start_worker", return_value=None), \
                patch.object(api.runtime, "search", return_value=[]) as search, TestClient(api.app) as client:
            response = client.post("/v1/retrieval/search", headers={
                "X-Worker-Api-Key": "test-only-private-worker-key",
            }, json={"userId": "user-1", "query": "policy", "limit": 4, "allowedDocumentIds": ["doc-1"]})
            self.assertEqual(200, response.status_code)
            search.assert_called_once_with("user-1", "policy", 4, ["doc-1"])


if __name__ == "__main__":
    unittest.main()
