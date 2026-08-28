# RAG retrieval contract

Java already owns authentication, document ownership, conversations, memories, and the final answer API. The Python/LangChain service owns vector search.

## Java calls Python search

Set `APP_RAG_RETRIEVAL_BASE_URL` to the Python service origin. Java calls:

```http
POST {APP_RAG_RETRIEVAL_BASE_URL}/v1/retrieval/search
Content-Type: application/json
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
      "score": 0.92
    }
  ]
}
```

Python must filter its vector search by `userId`. Java performs a second ownership and `READY` status check before sending a result to the LLM, so chunks from another user are discarded.

## Java calls the LLM

The LLM must expose the OpenAI-compatible `chat/completions` request and response shape. Configure:

```powershell
$env:APP_RAG_RETRIEVAL_BASE_URL = "http://localhost:8000"
$env:APP_LLM_BASE_URL = "https://your-llm-host"
$env:APP_LLM_API_KEY = "your-api-key"
$env:APP_LLM_MODEL = "your-model-name"
```

Optional path overrides:

```powershell
$env:APP_RAG_RETRIEVAL_SEARCH_PATH = "/v1/retrieval/search"
$env:APP_LLM_CHAT_PATH = "/v1/chat/completions"
```

If either service is missing, `POST /api/v1/conversations/{conversationId}/ask` returns a clear `503` instead of pretending that an answer exists.
