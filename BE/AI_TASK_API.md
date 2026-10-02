# Workflow AI task API (MVP)

BlueAI exposes stateless `CLASSIFY`, `EXTRACT`, and `RAG_ANSWER` operations for workflow nodes. It does not own workflow branching, channel identity, delivery, or cross-run memory. A workflow runner sends bounded caller-supplied history on each invocation; history is limited to eight user/assistant messages and 4,000 total characters.

## Provisioning and credentials

An active administrator provisions a client with `POST /api/v1/admin/integration-clients` using the normal administrator JWT:

```json
{
  "name": "support-workflow",
  "capabilities": ["CLASSIFY", "EXTRACT", "RAG_ANSWER"],
  "documentIds": ["c517ead6-e721-4cbb-8f81-b45de7c596f3"]
}
```

The returned `credential` is random, at least 256 bits, and shown once. BlueAI stores only its SHA-256 digest. Keep it in the workflow runner's secret store and send it as `Authorization: Bearer <credential>`. `GET /api/v1/admin/integration-clients?page=0&size=50` lists only clients owned by the administrator and never includes credentials or digests. `DELETE /api/v1/admin/integration-clients/{id}` revokes only that administrator's client. A client is rejected when its creator is no longer an active administrator. This MVP uses the creator's account as the data boundary; it does not model SaaS workspaces or organizations.

Example provisioning request:

```sh
curl -X POST https://blueai.example/api/v1/admin/integration-clients \
  -H 'Authorization: Bearer <admin-jwt>' -H 'Content-Type: application/json' \
  -d '{"name":"support-workflow","capabilities":["CLASSIFY","EXTRACT","RAG_ANSWER"],"documentIds":["c517ead6-e721-4cbb-8f81-b45de7c596f3"]}'
```

Clients can call only provisioned capabilities and document grants. Document grants must belong to the creating administrator and be READY at provisioning. RAG rechecks ownership, grant, READY status, deletion status, and owner/client activity before retrieval and before returning saved evidence. Retrieval sends a document allowlist to Python before its result is selected. Saved RAG task evidence IDs are retained as tombstones so hard deletion cannot make old evidence appear authorized. A replay or poll after evidence access changes returns `410 AI_TASK_EVIDENCE_REVOKED`.

## Create, retry, and poll

`POST /api/v1/ai/tasks` requires the machine credential and an `Idempotency-Key` of 1 to 128 characters. Keys are case-sensitive and byte-exact (migration V8); `execution:A` and `execution:a` are different logical invocations. A key should identify one logical node invocation and remain stable across retries, for example `executionId:nodeId:invocationId`. Do not include an attempt number. The canonical request digest includes capability, input, configuration, history, and metadata. Reusing a key and request returns the stored task/result; reusing a key with different content returns `409 IDEMPOTENCY_CONFLICT`. Concurrent duplicates return `202 PROCESSING` with a `Location` poll URL. `GET /api/v1/ai/tasks/{taskId}` is scoped to its creating client.

The claim is durable before any provider call. Provider calls happen outside database transactions, have no automatic retry, and use one configured deadline (`app.ai.tasks.deadline`, default 25 seconds; allowed 10–45 seconds) across retrieval and completion, plus a 32 KiB upstream response cap. HTTP status failures, invalid output, and timeouts are saved as `FAILED` in an HTTP 200 task envelope for replay. Missing, ungranted, non-READY, or deleted selected documents are rejected with an access error before retrieval; `NO_MATCH` means a valid granted selection produced no usable retrieval evidence or the model abstained.

Migration V7 adds a durable `expires_at` calculated from the database clock: task deadline plus a fixed 15-second result-finalization grace period (40 seconds by default). A background sweep starts five seconds after backend startup and runs every ten seconds; submission, polling and finalization also recover expired claims. Expiration saves a terminal `FAILED` envelope with `error.code: AI_TASK_EXPIRED` and frees the concurrency slot. A late worker cannot overwrite this result or add evidence, because completion requires both `PROCESSING` and an unexpired lease under the same client row lock as recovery. Before each retrieval/completion dispatch the service rechecks durable status, access and the original claim deadline; waiting before dispatch never resets its budget. The sweep reads up to 100 candidate task rows per pass using an expiry/id keyset cursor and V8 indexes, deduplicates clients in memory, skips busy client locks without waiting, and advances even on failure. Each scan cycle fixes its eligibility cutoff until reaching the end, then resets the cursor and cutoff. This lets skipped clients get another turn even while newer tasks keep expiring; new expirations enter the next cycle. Isolated tests disable the sweep with `app.ai.tasks.recovery-enabled=false`; production should leave it enabled.

Recovery and replay never re-execute the provider: billing/outcome after a process crash may be uncertain. Replay the **same key and body** to inspect the saved failure. Only submit a **new key** if the caller explicitly wants a new logical invocation and accepts possible additional provider cost. This provides durable at-most-one claim per key, not exactly-once upstream billing. Deploy V7 in a maintenance window after stopping old backend replicas; legacy rows are backfilled with `expires_at=created_at`, making abandoned `PROCESSING` tasks immediately eligible for recovery. Completed results remain intact. Back up the database first; do not modify released migration files or roll back the application while workers are active.

Per client, new requests are limited to 30 per minute and four concurrent claims. Completed idempotent replays do not consume a new quota slot. `429` responses include `Retry-After: 60`. The provider adapter also has 32 process-wide upstream slots. Task results and request digests are stored durably without automatic retention cleanup in this MVP. Operators should apply an approved retention process with a supported replay window of at most seven days, and must align workflow retries with that window. Removing a ledger row also removes its idempotency guarantee: an old key submitted after cleanup can become a new paid invocation. Never purge a key still eligible for retries; use permanently unique execution IDs, and retain minimal key tombstones if longer replay protection is required. Do not send information that should not be retained in BlueAI's task ledger.

## Capability examples

### CLASSIFY

```json
{
  "capability": "CLASSIFY",
  "input": {"text": "I need to change my delivery address"},
  "configuration": {"allowedLabels": ["sales", "support", "delivery"]},
  "metadata": {"executionId": "run-42", "nodeId": "intent-3", "channel": "telegram"}
}
```

Result shape: `result.label`. The provider must return one declared label or an explicit abstention. Abstention becomes `NO_MATCH`; labels outside the declared list or malformed JSON become `FAILED`.

### EXTRACT

```json
{
  "capability": "EXTRACT",
  "input": {"text": "Please call me at 555-0100"},
  "configuration": {"fields": [
    {"name": "phone", "type": "STRING", "required": true},
    {"name": "quantity", "type": "NUMBER", "required": false}
  ]}
}
```

Fields are flat and limited to `STRING`, `NUMBER`, or `BOOLEAN`; arbitrary JSON Schema references and nested object definitions are not accepted. Output has `result.values` and `result.missingFields`. Missing required values produce `NEEDS_REVIEW`; the provider is instructed not to infer missing values, dates, or personal information.

### RAG_ANSWER

```json
{
  "capability": "RAG_ANSWER",
  "input": {"text": "What is the return window?"},
  "configuration": {"documentIds": ["c517ead6-e721-4cbb-8f81-b45de7c596f3"]},
  "history": [{"role": "user", "content": "I bought a jacket"}]
}
```

With a valid granted READY document selection, no retrieval matches produces `NO_MATCH` with an empty answer and does not call completion. An explicit model abstention also produces `NO_MATCH`. Missing, ungranted, non-READY, or deleted selected documents instead receive an access error before retrieval. Completion citations must be unique chunk IDs returned from the authorized retrieval result; an unknown citation makes the task `FAILED`. A successful result contains `result.answer`, `result.evidenceStatus: VERIFIED_SOURCES`, and validated `citations`. This means the cited source IDs came from the authorized retrieval response; it is not a mathematical guarantee that the answer is true.

## Response and errors

Final task responses include `taskId`, `status`, `capability`, `result`, `citations`, `confidenceStatus`, `usage`, and `traceId`; `error` is present when applicable, and nullable fields such as `confidence` or `error` may be omitted by JSON serialization. Confidence is not fabricated (`confidenceStatus` is `UNAVAILABLE`). Task response `usage` remains `UNKNOWN`; provider-reported Gemini token counts, when present, are recorded separately in the administrator token dashboard. A `202 PROCESSING` response contains `taskId` and `status`; polling a processing task also returns `capability` and `createdAt`. `COMPLETED`, `NEEDS_REVIEW`, `NO_MATCH`, `FAILED`, and `PROCESSING` are workflow-routing statuses. Workflow runners should route `NEEDS_REVIEW` to a human/manual branch, handle `NO_MATCH` with their own fallback, and avoid treating `FAILED` as a successful AI result.

Errors use `{ "error": { "code", "message", "traceId" } }`. Common HTTP errors include `401 INVALID_INTEGRATION_CREDENTIAL`, `403 CAPABILITY_NOT_ALLOWED` / document access errors, `404 AI_TASK_NOT_FOUND`, `409 IDEMPOTENCY_CONFLICT`, `410 AI_TASK_EVIDENCE_REVOKED`, `413 REQUEST_TOO_LARGE`, and `429 AI_RATE_LIMITED` / `AI_CONCURRENCY_LIMIT`. Request JSON is limited to 64 KiB; text and configuration fields have tighter capability-specific limits. Request metadata is opaque workflow context and never establishes identity or tenant scope. Unknown or identity-like request fields are rejected.

## Operational limitations

The integration principal is isolated to `/api/v1/ai/tasks/**`; it cannot call admin endpoints, and a human JWT cannot call the machine API. Existing interactive RAG behavior remains separate. There is no persistent memory between task calls unless the workflow explicitly resends bounded history. No provider URL, callback, arbitrary tool call, or client-controlled prompt role is accepted. For integration allowlist retrieval, Python scans at most 100,000 indexed vectors before it can select top results; a larger index fails closed with 503 until an index partitioning strategy is available. Request/response content is retained in the task ledger until the operator retention process removes it.

If the HTTP response is lost after task claim and no `taskId` was received, submit the exact same request with the same idempotency key to discover its saved result or receive `202 PROCESSING`; never create a new key automatically while the earlier provider outcome is uncertain. With the database available and recovery enabled, a crashed process's abandoned claim is automatically terminalized after its lease expires. Operator reconciliation is still needed for uncertain upstream billing or business outcomes: local expiration cannot prove that the provider did not execute or charge. The service never starts a second provider call for that key; there is intentionally no automatic stale-task re-execution or public recovery endpoint.

Only if normal recovery cannot proceed should an operator consider reviewed manual terminalization. It must atomically set `status='FAILED'`, `error_code='AI_TASK_OUTCOME_UNKNOWN'`, `completed_at`, and a complete, schema-valid `response_json` failure envelope (including the original task id/capability, `result:{}`, `citations:[]`, `confidenceStatus:'UNAVAILABLE'`, `usage:{status:'UNKNOWN'}`, stable error code/message/traceId). Constrain the reviewed environment-specific SQL to the exact task id, client id, and `status='PROCESSING'`; never bulk-clear active claims. Keep an audit record of that action.
