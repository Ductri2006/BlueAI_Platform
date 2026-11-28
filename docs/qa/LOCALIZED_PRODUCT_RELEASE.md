# Localized catalogue release — VI / EN / Simplified Chinese

Supplier SKU and original name remain unchanged in the catalogue. Customer answers use a localized label derived from verified coverage, allowance, total/daily period and validity. Product amounts and currency never pass through the chat model. Only exact integer MB/1024 equivalents are shortened to GB, following the partner's documented 10240 MB / Total 10GB example; other quantities retain their source units.

## Quality and routing

- Explicit DEMO, TEST, sample, draft and equivalent Vietnamese/Chinese test records are excluded from advice.
- Explicit name-versus-field conflicts in duration, amount or total/daily basis are excluded, without rewriting supplier records. Admin product views expose `adviceEligible` and `adviceExclusionReasons` for investigation.
- Advice scans beyond excluded cheapest records, with a maximum of 16 pages of 64 candidates and six returned plans. A bounded scan finding no verified match does not assert that the supplier has no products.
- Set `BLUECOM_PRODUCTS_REQUIRE_VERIFIED_CATALOG=true` for business use. Missing/unapproved catalogue configuration returns a localized unavailable answer for sales questions instead of quoting demo or stale knowledge-base plans. Legacy installations can explicitly retain the old optional-catalogue route with `false`; production Compose defaults to `true`.
- Installation-only requests retain scoped RAG. Mixed recommendation/installation requests use verified catalogue facts and request phone details for the installation follow-up.
- A calendar month is clarified as travel dates or exact day count. Exact SKU lookup does not inherit unrelated destination/duration constraints from earlier questions.
- Answers, copy and export keep source markers and document names hidden. Recoverable errors retain the draft and focus instead of being labeled as missing knowledge.

## Production configuration

The release operator must validate the company-approved production endpoint and key before activation. Never substitute a sandbox key or relabel sandbox records as production.

Private server file: `/opt/blueai/secrets/products.production.env`, permissions `0600`, outside the Git checkout. Never put credentials in `VITE_*`, chat messages or reports.

Required configuration:

```dotenv
BLUECOM_PRODUCTS_BASE_URL=
BLUECOM_PRODUCTS_API_KEY=
BLUECOM_PRODUCTS_SOURCE_ENVIRONMENT=PRODUCTION
BLUECOM_PRODUCTS_PRODUCTION_APPROVED=false
BLUECOM_PRODUCTS_REQUIRE_VERIFIED_CATALOG=true
BLUECOM_PRODUCTS_PUBLIC_ADVICE_ENABLED=false
BLUECOM_PRODUCTS_SELLING_PRICE_CURRENCY_CONFIRMED=true
BLUECOM_PRODUCTS_AUTO_SYNC_ENABLED=false
```

The operator supplies the approved endpoint/key privately, checks the server's outbound IP allowlist, then verifies API pagination and retail currency semantics. Approval/public-advice/automatic-refresh flags are enabled only after production-source acceptance. The environment file must be loaded explicitly by the deployment process; creating it does not automatically configure the running application. Default sync interval is 15 minutes and maximum accepted catalogue age is 30 minutes: responses reflect a synchronized snapshot, not a per-message live lookup.

## Acceptance gates

1. Backend verify, separate MySQL 8.4/Redis gates, frontend tests/build, Python RAG tests/audit and production configuration checks pass on the exact candidate commit.
2. A fresh isolated production database copy upgrades from V17 to V18 and the prior production image can still read it and newly written history.
3. Actual sandbox catalogue sync verifies integration without treating its prices as production quotes. Production-source sync must be accepted separately with approved credentials before deployment.
4. VI/EN/zh-CN questions, explicit language switches, ambiguous month, daily/total/unlimited/unknown data, unknown SKU, mixed installation, multi-destination and safe failure paths are checked. Supplier name never appears as the foreign-language display label; SKU/price/validity/coverage remain equivalent.
5. Published widget output and exact request replay are checked independently of authenticated chat. Telegram gateway code is owned by the integration team and remains outside this change; final text is produced by the shared backend.
6. Chrome rendering at desktop/tablet/mobile sizes, long SKU wrapping, light/dark, draft retention, keyboard and source hiding are checked. Report unverified native IME/zoom/provider cases explicitly.
7. Fresh encrypted backup, original images and scoped rollback commands are retained. Production deployment waits for the above gates and production credentials; no real customer accounts or documents are used as disposable test fixtures.

CI crash-recovery harness keeps a referenced timer until bounded probes finish, preventing an unresolved startup await from silently terminating Node with exit code 13. A failing fixture now emits its own container diagnostics before cleanup. This does not change the backend's recovery implementation.
