# RAG retrieval contract

Java already owns authentication, document ownership, conversations, memories, and the final answer API. The Python/LangChain service owns vector search.

## Java calls Python search

Set `APP_RAG_RETRIEVAL_BASE_URL` to the Python service origin. Java calls:

```http
POST {APP_RAG_RETRIEVAL_BASE_URL}/v1/retrieval/search
Content-Type: application/json
X-Worker-Api-Key: <APP_WORKER_API_KEY>
```

Request body:

```json
{
  "userId": "a-user-id",
  "query": "User question",
  "limit": 6
}
```

Response body:

```json
{
  "matches": [
    {
      "documentId": "rag-document-id",
      "chunkId": "vector-store-chunk-id",
      "content": "Relevant chunk text",
      "score": 0.92,
      "filename": "Refund policy",
      "sourceType": "USER_DOCUMENT",
      "approvalStatus": "READY"
    }
  ]
}
```

Python must filter uploaded private documents by `userId`. Java performs a second ownership and `READY` status check before sending a private result to the LLM, so chunks from another user are discarded.

The bundled CSV contains only `SYNTHETIC_DEMO` / `PENDING_OWNER_REVIEW` records.
Java accepts these shared demo records only when `APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE=true`;
the flag defaults to false and responses preserve trust metadata for the frontend.

## Java calls the LLM

The LLM must expose the OpenAI-compatible `chat/completions` request and response shape. Both
retrieval and completion calls also require `X-Worker-Api-Key`, using the same random
`APP_WORKER_API_KEY` configured for the document worker. Configure:

```powershell
$env:APP_RAG_RETRIEVAL_BASE_URL = "http://localhost:8000"
$env:APP_LLM_BASE_URL = "http://localhost:8000"
$env:APP_LLM_API_KEY = "internal-rag-service"
$env:APP_LLM_MODEL = "gemini-3.5-flash-lite"
$env:APP_WORKER_API_KEY = "<same random secret configured as JAVA_WORKER_API_KEY>"
```

Optional path overrides:

```powershell
$env:APP_RAG_RETRIEVAL_SEARCH_PATH = "/v1/retrieval/search"
$env:APP_LLM_CHAT_PATH = "/v1/chat/completions"
```

If either service is missing, `POST /api/v1/conversations/{conversationId}/ask` returns a clear `503` instead of pretending that an answer exists.
