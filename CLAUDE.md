# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Three independently developed services, merged into this repo with `git subtree`. Each keeps its own build tooling, README and `.env.example`:

| Path | Stack | Owns |
|---|---|---|
| `BE/` | Java 21, Spring Boot 3.5.16, MySQL 8.4 | Auth, users, conversations, memories, document ownership, RAG orchestration, the answer API |
| `FE/` | React 19, Vite 7, TypeScript, Tailwind 3 | Vietnamese UI: chat, admin, knowledge dashboards |
| `feature-rag-chatbot/rag-chatbot/` | Python 3.12/3.13, FastAPI, LangChain, FAISS, Gemini | Vector search, embeddings, and an OpenAI-shaped chat endpoint. Nothing else. |

The upstream branches still exist on `origin` (`BE`, `FE`, `feature/rag-chatbot`); `merge_app` is the integration branch. Sync a subtree with `git subtree pull --prefix=BE origin BE` rather than rewriting subtree history.

## Commands

### Full stack (preferred)

```powershell
cd BE
$env:GOOGLE_API_KEY = "<gemini-api-key>"
docker compose up --build
```

`BE/compose.yaml` is the compose file that matters — it builds all four services from this repo. FE `http://localhost:3000`, API `http://localhost:8080`, RAG `http://localhost:8000`, MySQL `localhost:33069`.

### BE (Java)

There is no Maven wrapper and `mvn` is not on PATH here, so run Maven in Docker:

```powershell
cd BE
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-21-alpine mvn test
# single class / single method: append
#   mvn test -Dtest=RagChatConfiguredIntegrationTest
#   mvn test "-Dtest=RagChatConfiguredIntegrationTest#askCallsConfiguredRetrieverAndLlmAndReturnsVerifiedSource"
```

Tests are `@SpringBootTest` + `MockMvc` against H2 in MySQL mode (`src/test/resources/application-test.properties`, `ddl-auto=create-drop`). Upstream HTTP is stubbed by `@MockitoBean`-ing `RagRestClientFactory` and driving a `MockRestServiceServer`; `@DynamicPropertySource` supplies the `app.rag.*` values. The locally installed JDK is 24 — the project targets 21.

### FE

```bash
cd FE
npm install
npm run dev      # port 3000; proxies /api and /actuator to 127.0.0.1:8080
npm run build    # tsc -b && vite build
npm test         # vitest run
npx vitest run src/api/blueAiApi.test.ts -t "asks the authenticated RAG endpoint"
```

### RAG service

```powershell
cd feature-rag-chatbot/rag-chatbot
py -3.12 -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
.\scripts\start-rag-local.ps1              # uvicorn api:app on 127.0.0.1:8000; refuses to start without a Gemini key
python -m unittest discover -s tests -v    # the runner is unittest, not pytest
python -m unittest tests.test_api -v       # single module
python scripts/validate_kb.py              # knowledge-base CSV schema check
python scripts/check_secrets.py --root ..  # secret scan
.\scripts\test-rag-e2e.ps1                 # end-to-end; needs BE on 8080 and RAG on 8000
```

## Architecture

### The answer path

`POST /api/v1/conversations/{id}/ask` → `RagChatService.ask` runs the whole orchestration in one transaction:

1. Verify the caller is `ACTIVE` and owns the conversation.
2. `POST {app.rag.retrieval-base-url}/v1/retrieval/search` with `{userId, query, limit: 6}`.
3. **Re-verify every returned chunk against MySQL** (`toVerifiedSources`) — see the trust boundary below.
4. Build one system prompt from the verified sources labelled `[S1]…`, the caller's `UserMemory` rows, the rolling conversation summary, and the last 20 messages.
5. `POST {app.rag.llm-base-url}/v1/chat/completions` — OpenAI request/response shape, temperature 0.2.
6. Persist the user and assistant `ConversationMessage`, touch the conversation, and possibly extend the summary.

Past the 20-message live window, older messages are folded into a `ConversationSummary` in batches of 10 by a second LLM call. `HISTORY_LIMIT`, `RETRIEVAL_LIMIT`, `SUMMARY_BATCH_SIZE` and the truncation caps are constants at the top of `RagChatService`.

### The trust boundary (do not weaken this)

Python filters private chunks by `owner_id`, but Java never trusts that filter. Every chunk must resolve to a `RagDocument` that is `READY` and either owned by the caller or owned by an ADMIN (the shared enterprise corpus); anything else is dropped silently. The bundled demo CSV (`source_type=SYNTHETIC_DEMO`, `approval_status=PENDING_OWNER_REVIEW`) passes only when `app.rag.allow-shared-demo-knowledge=true` — default false — and its trust metadata is carried through to the FE so the UI can mark it unapproved.

Two related invariants:

- When an upstream is unconfigured or unreachable the API returns a coded `503`/`502` (`RAG_RETRIEVER_NOT_CONFIGURED`, `LLM_NOT_CONFIGURED`, `RAG_RETRIEVAL_FAILED`, `LLM_REQUEST_FAILED`) and never a fabricated answer. The test profile blanks `app.rag.*` specifically to assert this.
- Retrieved text is declared untrusted data in the system prompt. Keep that instruction when editing `buildSystemPrompt`, and keep the same rule in the Python prompt in `chatbot.py`.

### Document ingestion is a pull model

Java stores files and rows; Python polls for work. Nothing pushes.

`POST /api/v1/documents` (ADMIN) writes the file under `app.documents.storage-dir` as `{ownerId}/{documentId}{ext}` and inserts a row as `PENDING_CHUNKING`. The FastAPI lifespan task in `ingestion.py` then loops every ~5s: `POST /api/v1/internal/documents/claim` (flips rows to `PROCESSING`) → `GET /api/v1/internal/documents/{id}/content` (409s unless the row is `PROCESSING`) → extract PDF/DOCX/TXT/MD → chunk with the row's requested `chunkSize`/`chunkOverlap` → add to FAISS with `document_id`/`owner_id`/`source_type` metadata → `PATCH /api/v1/internal/documents/{id}/result` with `READY` plus chunk count, or `FAILED` plus reason.

`/api/v1/internal/**` is `permitAll` in `SecurityConfig` because it authenticates differently: a constant-time compare of the `X-Worker-Api-Key` header inside `WorkerDocumentController`. `APP_WORKER_API_KEY` (Java) and `JAVA_WORKER_API_KEY` (Python) must hold the same value. A new internal endpoint must do that header check itself.

### Auth

HS256 JWT access token (15m) plus an opaque refresh token (30d). The decoder validates issuer, audience and a `type=access` claim, so a refresh token can never be used as a bearer token. Authorities come from the `roles` claim with a `ROLE_` prefix; the principal name is the `email` claim, but **`jwt.getSubject()` is the user id and is what every controller passes as `ownerId`**.

Refresh tokens are persisted as SHA-256 only, rotated on every use, and grouped by `familyId`; presenting an already-revoked token revokes the whole family (`REFRESH_TOKEN_REUSED`). There is no public registration — `RegistrationController` is gated by `app.auth.public-registration-enabled` (default false), and the first admin is bootstrapped from `APP_BOOTSTRAP_ADMIN_*` only when that email is absent. `/api/v1/admin/**`, `/api/v1/documents/**` and `/api/v1/system/**` are ADMIN-only in the filter chain.

### Persistence

Hibernate `ddl-auto=update` owns the MySQL schema. There is no Flyway despite what `BE/README.md` claims, and no migration directory — entity changes *are* the migration, so check them for destructive column changes before running against a populated database.

### Frontend

No router, no Redux. `src/store/useAppStore.ts` is a hand-rolled singleton: a module-level `state` object, a `Set` of listeners, and a `useState` tick to re-render. Local session ids (`local-<uuid>`) are mapped to backend conversation ids through an `apiConversationIds` map, so a conversation is created on the server lazily.

`src/api/blueAiApi.ts` is the single fetch layer: it keeps both tokens in `localStorage`, retries a 401 once through a de-duplicated refresh promise, and clears the session when refresh fails. Add endpoints as methods there instead of calling `fetch` from components. `AuthContext` restores a session by calling `/auth/me`.

Only chat, login, `AdminDashboard`, `KnowledgeDashboard`, `RagMiniChat` and `KnowledgeGraph3DModal` talk to the backend. `SalesCopilotWorkspace`, `HITLReviewDialog`, `ResellerDashboard` and `B2CDashboard` are static demo UI with nothing behind them. Three.js components are lazily chunked; keep them that way.

### Config is the integration contract

`BE/src/main/resources/application.properties` maps every setting to an env var with a local default. The ones that change behaviour rather than just wiring:

- `isRetrieverConfigured()` needs only `app.rag.retrieval-base-url`; `isLlmConfigured()` needs base URL **and** API key **and** model, all non-blank.
- `GET /api/v1/system/metrics` reports `aiRuntimeStatus` by probing `{retrieval-base-url}/ready` with a 750 ms timeout. That endpoint 503s until Gemini is configured, so the admin UI shows the AI runtime as not-ready instead of falsely ready.
- The defaults in `application.properties` and `compose.yaml` are local-dev credentials (JWT secret, DB password, bootstrap admin password, worker key). Override every one of them outside local.

## Gotchas

- `BE/target/` is tracked in git (the jar plus `.class` files) and there is no `.gitignore` at the repo root or in `BE/`. Any Maven build dirties ~100 files. Stage explicit paths; never `git add -A`.
- `feature-rag-chatbot/.github/workflows/*.yml` never runs — GitHub only reads `.github/workflows` at the repo root, and those workflows also assume `rag-chatbot/` is top-level. Treat their steps as the checks to run locally.
- The FAISS index persists in `RAG_INDEX_DIR` (Docker volume `blueai-rag-index`) and is loaded with `allow_dangerous_deserialization=True` — it is a pickle, so only load an index this project built. Changing `GEMINI_EMBEDDING_MODEL` invalidates it; delete the directory or volume to force a rebuild.
- With no index present, the first search builds one from `Papers/blueai_rag_knowledge_base_sample.csv`, which spends embedding calls. Those 33 records are synthetic demo data and must not be presented as Bluecom policy.
- The parent directory `../` is a different, unrelated project (a legacy .NET/PostgreSQL Sprint-1 slice) and holds a GitNexus-generated `CLAUDE.md`/`AGENTS.md` that is auto-loaded into sessions here. Its GitNexus index is rooted at that parent path, not at this repo.
- READMEs, UI copy and some prompts are Vietnamese; Java/TypeScript identifiers and error codes are English. Match whichever the file you are editing already uses.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **backend** (2108 symbols, 6349 relationships, 173 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` — find execution flows related to the issue
2. `gitnexus_context({name: "<suspect function>"})` — see all callers, callees, and process participation
3. `READ gitnexus://repo/backend/process/{processName}` — trace the full execution flow step by step
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — see what your branch changed

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview — graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Tools Quick Reference

| Tool | When to use | Command |
|------|-------------|---------|
| `query` | Find code by concept | `gitnexus_query({query: "auth validation"})` |
| `context` | 360-degree view of one symbol | `gitnexus_context({name: "validateUser"})` |
| `impact` | Blast radius before editing | `gitnexus_impact({target: "X", direction: "upstream"})` |
| `detect_changes` | Pre-commit scope check | `gitnexus_detect_changes({scope: "staged"})` |
| `rename` | Safe multi-file rename | `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` |
| `cypher` | Custom graph queries | `gitnexus_cypher({query: "MATCH ..."})` |

## Impact Risk Levels

| Depth | Meaning | Action |
|-------|---------|--------|
| d=1 | WILL BREAK — direct callers/importers | MUST update these |
| d=2 | LIKELY AFFECTED — indirect deps | Should test |
| d=3 | MAY NEED TESTING — transitive | Test if critical path |

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/backend/context` | Codebase overview, check index freshness |
| `gitnexus://repo/backend/clusters` | All functional areas |
| `gitnexus://repo/backend/processes` | All execution flows |
| `gitnexus://repo/backend/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:
1. `gitnexus_impact` was run for all modified symbols
2. No HIGH/CRITICAL risk warnings were ignored
3. `gitnexus_detect_changes()` confirms changes match expected scope
4. All d=1 (WILL BREAK) dependents were updated

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json` — the `stats.embeddings` field shows the count (0 means no embeddings). **Running analyze without `--embeddings` will delete any previously generated embeddings.**

> Claude Code users: A PostToolUse hook handles this automatically after `git commit` and `git merge`.

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
