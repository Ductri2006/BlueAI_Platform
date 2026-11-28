# Product catalogue and deterministic advice

This module synchronizes partner product records into MySQL. It is not native model function calling, and chat does not query the partner on every message. One immutable snapshot supplies customer-safe product facts, evidence and a deterministic VI/EN/zh-CN reply. Installation, activation and troubleshooting continue through the support/RAG path.

## Integration contract

    ProductAdvisor.Grounding grounding = publicBot
        ? advisor.groundingForPublic(question, history)
        : advisor.grounding(question, history);
    Optional<String> reply = grounding.deterministicReply(languageState);
    // Reuse grounding.sources(), grounding.context() and grounding.facts().
    // Do not call context(question, history) again to resolve another snapshot.

Grounding exposes context(), sources(), status(), facts(), details(), factNames() and deterministicReply(ReplyLanguagePolicy.State). Its lists and sets are defensively copied. factNames() contains source SKU/name pairs for the grounded language checker. The legacy two-argument Grounding(String, List<RagSourceResponse>) constructor and context()/grounding() APIs remain compatible; the legacy constructor does not automatically certify arbitrary text as authoritative facts.

Statuses:

- NONE: Optional.empty(); continue the normal answer path, including when the optional integration is not configured or public advice is disabled.
- NEEDS_DETAILS: ask for missing destinations, exact travel days, 30/31-day month clarification or an explicit budget currency.
- MATCHES: render at most six matching product bundles without an LLM.
- NO_MATCHES: no verified match in a valid current snapshot; never imply the company has no products.
- UNAVAILABLE: a configured integration has no usable snapshot, has missing/mismatched provenance or a failed snapshot read.
- STALE: stored information exceeded the freshness limit and cannot support current advice.

ProductFacts contains the exact SKU/name, ISO coverage set, nullable validity days, nullable DataAllowance, nullable selling price/currency, and Provenance(sourceBaseUrl, environment, snapshotId, syncedAt). It contains no supplier payload or wholesale price. Renderer wording is localized; exact product names, SKU, amount and explicit ISO currency are preserved. No exchange rate is assumed.

The reviewed [official partner documentation](https://api.esim.blueconnect.vn/esim-bluesim-partner-api-2487322m0) defines package volume as a numeric string in MB or the literal unlimited; dataType 1 is TOTAL and 2 is DAILY, and duration is in days. These documented mappings are applied directly without MB-to-GB conversion. Other enum values or malformed quantities remain unknown; unlimited is represented explicitly with a null amount, not zero or a large invented number. Supported 5G/4G network technology is not a throughput guarantee. supportTopUpType 1 means top-up is not supported. Free-form FUP/refund policy text is withheld until a safe typed contract exists.

## Configuration and authorization

Server-only partner configuration:

    BLUECOM_PRODUCTS_BASE_URL=https://api.sandbox.bluesim.com.vn/api/open/v1
    BLUECOM_PRODUCTS_API_KEY=<server-only partner credential>
    BLUECOM_PRODUCTS_TIMEOUT=30s
    BLUECOM_PRODUCTS_AUTO_SYNC_ENABLED=false
    BLUECOM_PRODUCTS_REFRESH_INTERVAL=15m

New Spring properties can be passed through process environment or an explicitly mounted local configuration file:

    APP_PRODUCTS_MAX_AGE=30m
    APP_PRODUCTS_SOURCE_ENVIRONMENT=AUTO
    APP_PRODUCTS_PRODUCTION_APPROVED=false
    APP_PRODUCTS_PUBLIC_ADVICE_ENABLED=false
    APP_PRODUCTS_SELLING_PRICE_CURRENCY_CONFIRMED=false

Compose must explicitly pass these new settings into its backend container when enabling a nondefault value. Do not use VITE_* or put credentials in browser requests, URLs, Git, screenshots or logs.

The default partner host is SANDBOX. A non-sandbox host requires an explicit PRODUCTION environment and production-approved=true before facts support advice. A working key does not itself approve production prices. Sandbox replies disclose SANDBOX and never represent the snapshot as a verified production quote.

The reviewed currencyCode description explicitly references input price, while sellingPrice is described separately as suggested retail. Until the partner confirms that currencyCode also applies to sellingPrice, its amount is preserved with currency unverified and budget filtering is unavailable. Set selling-price-currency-confirmed=true only after confirming that contract for this source/account. Synthetic tests enable this mapping for their explicit fixture; this does not approve a live source.

Public bots must call groundingForPublic(), which denies product advice by default. The public-advice-enabled flag grants this application-wide catalogue to published bots; it is not a per-bot or per-reseller permission system. Enable it only if the entire catalogue from this credential is approved for that audience. Normal authenticated product browsing remains the company's shared catalogue. Do not present document selection as authorization for this independent product source.

Deleting the configured key disables current advice even when cached rows remain. max-age defaults to 30 minutes and is separate from the 15-minute synchronization interval. Expired rows yield STALE; missing source rows yield UNAVAILABLE.

## Provenance and schema

Integrate through V18; do not rename or edit the already-applied V17 language migration. Product entity additions are nullable:

- source_base_url VARCHAR(512)
- source_environment VARCHAR(20)
- snapshot_id VARCHAR(36)

The primary migration also indexes snapshot_id/available. Rows without provenance are not authoritative for advice until resynchronized. Source URL/environment are stored when fetched and must match current configuration. Changing sandbox to production cannot relabel cached sandbox rows. Each completed sync uses a UUID; removed SKUs are withdrawn by snapshot ID instead of timestamp equality.

The customer projection constructs a new JSON tree. It does not copy extraData, operator metadata or policy objects wholesale. Unreviewed free-form descriptions and policies, including encoded JSON strings, are omitted. Only ADMIN endpoints expose the original partner payload. Neither product prompt/evidence nor deterministic replies receive wholesale fields.

## Endpoints

All /api/v1/products endpoints require an active BlueAI session.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | /api/v1/products/sandbox?page=1&size=200 | ADMIN only; direct partner page, one-based pages, up to 200 items; does not sync the chat catalogue. Despite its route name, it calls the configured partner origin. |
| GET | /api/v1/products?country=JP&days=7&budget=200000&page=0&size=20 | Cached search; zero-based pages; numeric budget on this legacy endpoint is explicitly VND. |
| GET | /api/v1/products/{sku} | Current product detail; customer-safe projection for ordinary users. |
| GET | /api/v1/products/summary | Snapshot counts, stored source and last synchronization time; no credentials. |
| POST | /api/v1/products/sync | ADMIN only; validate every partner page, then publish atomically. |

Partner requests use GET /esim/packages?page=N&size=200 with the apiKey header. Only the configured HTTPS origin is used; automatic redirects are disabled to avoid forwarding that credential. Upstream 401/403 map to safe PRODUCT_UPSTREAM_UNAUTHORIZED / PRODUCT_UPSTREAM_IP_FORBIDDEN errors. Bodies and headers are not exposed. Retry is explicit; no automatic product retry occurs on 429 or timeouts.

The partner may require the backend's public egress IP in its reseller allowlist. Registration of a laptop/browser IP does not authorize the deployed backend.

## Synchronization guarantees and limits

All pages are checked before the database transaction: integral bounded pagination metadata, consistent counts/pages, expected page lengths, unique SKU including case-insensitive collisions, textual identifiers/names and exact nonnegative DECIMAL(19,4)-compatible prices. JSON decimals are parsed exactly and duplicate JSON keys are rejected. Missing price/currency stays unknown, not zero/VND. Failed/invalid sync leaves the last good catalogue unchanged.

A complete-empty response is rejected for review instead of automatically withdrawing every existing SKU. A successful nonempty sync deactivates omitted SKUs. Historical rows can remain for administration but cannot be reused under the wrong provenance.

The synchronization lock is process-local. Use only one synchronization writer across instances. There is no distributed lock or partner ETag/revision guarantee that all remote pages were captured at one upstream instant. The application remains a single shared catalogue; credentials belonging to different reseller accounts require separate account scoping before sharing one table.

## Verification

Product tests use synthetic HTTP responses and disposable test databases, not partner credentials:

- ProductAdvisorTest: product-only routing, support exclusions, VI/EN/zh-CN, missing country/month, exact SKU, all destinations, explicit currencies, immutable rendering and public access defaults.
- ProductPublicProjectionTest: nested wholesale fields/policies and encoded descriptions cannot enter customer data; unknown coverage/currency remains unknown.
- BluecomProductClientTest / BluecomProductClientContractTest: headers, pagination, incomplete/case-duplicate/overflowed/malformed records, exact decimals, no inferred VND, redacted allowlist/429 errors.
- ProductSnapshotIntegrationTest: environment switch, stale/unconfigured/unapproved sources, all-country and duration filters, currency isolation, units, invalid/empty sync preservation, withdrawn/legacy rows and public projections.
- ProductCatalogIntegrationTest: HTTP authentication/admin boundaries and chat/widget replies without partner/retriever/model calls for deterministic catalogue turns.

Only report tests actually executed by the coordinating runner. A successful two-item partner probe establishes connectivity and page shape, not a complete 2,913-item sync, freshness, semantic unit mappings or production business approval. Use a sanitized fixture for contract coverage; never commit the private partner payload or wholesale values from a real account.
