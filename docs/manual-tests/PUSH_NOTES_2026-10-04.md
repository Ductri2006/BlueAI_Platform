# Ghi chú 5 lần push — 04/10/2026

Branch đích: `feature/conversation-context`. Mỗi lần một commit theo nhóm, push thường (fast-forward), không force-push và không merge main. **Bản tổng hợp chỉ đủ sau lần 5.** Các báo cáo QA ghi “uncommitted” được giữ như snapshot lúc test, không viết lại lịch sử kiểm chứng sau commit.

Tác giả/committer: `Ductri2006`, đã đối chiếu attribution bằng GitHub API. Từ lần 2 dùng email noreply của tài khoản, chỉ cấu hình trong repository này; lần 1 vẫn được GitHub gắn đúng tài khoản và không sửa lại lịch sử đã push. Không thêm co-author hoặc thay tác giả các commit cũ.

| Lần | Commit | Nội dung |
| --- | --- | --- |
| 1 | `ccd094f` | Phiên đăng nhập, quota, input |
| 2 | `349b0c8` | Upload, worker, durable index |
| 3 | `dd4b2ca` | Context sales giữa phiên |
| 4 | `9776381` | Runtime, migration, ingress, log và CI |

Commit tài liệu cuối nằm ngay sau bốn mốc này và được xác định bằng HEAD của branch sau khi team fetch/pull; không tự ghi hash của chính nó vào nội dung commit.

## Lần 1 — Bảo mật phiên đăng nhập, quota và input

- Browser login/refresh/logout mới: refresh cookie HttpOnly/SameSite/production Secure, Origin/header guard, response không lộ refresh token; FE token chỉ ở RAM.
- Clear session/cache khi terminal 401; rotation/logout tránh làm sống lại phiên cũ.
- Widget rate limiter fail-closed 503 khi Redis bật nhưng unavailable; local counter chỉ dùng development, có hard bound.
- Client upload prechecks, XSS regression và SQL/owner/field-tampering tests.
- Phạm vi chính: `BE/src/main/java/com/blueai/auth`, `WidgetRateLimiter`, các test bảo mật; `FE/src/api`, `AuthContext`, `contentSecurity.test.tsx`.
- Kiểm chứng trước chia commit: full verify của bản tổng hợp 372 executed PASS/30 skipped; FE 123 PASS; drill Redis và UI XSS có bằng chứng. Không nhận full-working-tree results là CI độc lập của riêng commit này.
- Giới hạn: access token vẫn theo TTL/authVersion; HTTPS/multi-tab/staging review còn cần. Các thay đổi ingress/production dependency được bổ sung ở lần 4.

## Lần 2 — Upload, worker và lưu index

- Kiểm PDF signature, UTF-8 text, DOCX package và expanded bounds; tên traversal bị reject trước normalize.
- Ghi streaming có giới hạn actual bytes; reject/cleanup trước DB/job.
- Worker parser chạy ngoài event loop, error không trả exception/provider body; skip stale 409 để batch tiếp tục.
- Retry/claim isolation tests; FAISS staging nằm trong writable volume, rollback RAM về durable state khi save fail, retry không nhân đôi vector.
- Phạm vi: DocumentService/DocumentContentValidator, upload/worker integration tests; RAG api/ingestion và Python tests.
- Kiểm chứng tổng hợp: upload 28 executions, worker 8, RAG 70 PASS; ingress nhận 2/20 MiB và reject 21 MiB; non-root FAISS evidence giữ từ vòng trước.
- Giới hạn: format validator không phải antivirus; thread không phải process sandbox hoặc hard CPU/RAM timeout; không paid-provider chaos test.

## Lần 3 — Context sales giữa phiên

- Relevance scoring ưu tiên khách hàng được nhắc rõ trong câu hỏi dài, dùng token tên chính xác thay vì cộng điểm cho từ chung.
- Không nới owner boundary/budget hay đưa mọi history vào prompt; giữ current-only, linked snapshot và relevant history.
- Regression: tên gần giống/generic reference, owner khác, 100 messages, summary/memory/source lifecycle.
- Phạm vi: ContextRelevanceRanker, ranker tests, RagContextProductionGateIntegrationTest.
- Kiểm chứng: UI vòng trước nhớ đúng 37 sales / 2.900.000 VND / TV-4712, có RELATED_HISTORY đúng nguồn, không link thủ công; full backend verify mới có regression này.
- Giới hạn: heuristic từ khóa, không khẳng định semantic memory hoàn hảo hoặc mọi câu hỏi luôn khớp.

## Lần 4 — Vận hành production, runtime và CI

- JDBC force UTC session, giữ Flyway 11.20.3/MySQL 8.4, thêm verified legacy V1/V3 → V9 fixtures và CI.
- Runtime dependency hardening; Nginx security headers và quota browser auth; RAG runtime bỏ build-only pip tooling.
- Alloy thay Promtail, project allowlist/ECS labels/log redaction và live ingestion regression.
- Restore chờ final TCP MySQL server thay vì nhận socket initializer là ready.
- Phạm vi: Compose/properties/pom; FE/RAG Dockerfile, Nginx/snippet; Alloy/ops tests; `.github/workflows/verify.yml`, legacy migration test.
- Kiểm chứng: 2 legacy migration executions riêng; Compose/Nginx/Alloy/restore tests và exact runtime scans có report.
- Giới hạn: RAG OS 45 HIGH, npm build-chain Tailwind 3 5 HIGH còn mở; không bypass scanner/apt signatures. Local ops tests không thay HTTPS/VPS/load/backup staging.

## Lần 5 — Bằng chứng QA và hướng dẫn team

- README/runbook cập nhật, manual plan 90 tiêu chí, ledger tích lũy, manifest/hash/image IDs, screenshots và kết quả có provenance.
- Giữ cả lỗi trước sửa/lượt Redis harness chưa đạt và retest đạt để audit; không đổi skip thành PASS.
- Hướng dẫn tự test và team handoff; QA helper có ownership/port guards.
- Ignore raw bulky scanner reports nhưng giữ local; không xóa file, không đẩy `.env`, build output hoặc database/index runtime.
- Một descriptor cũ `.rag-index/index_meta.json` vẫn được track từ trước, chỉ ghi tên embedding model; không phải vector index hay tài liệu người dùng. Không có thay đổi với file đó trong 5 lần push này.
- Kiểm tra ngay trước push: Gitleaks có 7 candidate đã review là fixture test/CI hoặc regex, không phải kết quả raw zero-findings; bản phân loại không chứa giá trị secret nằm trong `qa-prepush-secret-review-2026-10-04.json`. Chưa chứng minh secret trong lịch sử đã revoke.
- Rerun thêm trong lượt bàn giao: Nginx ingress thật, production Compose contracts và Alloy → Loki ingestion PASS; syntax 17 JavaScript / 8 PowerShell QA helpers PASS. Không chạy các helper gây mutation lên production.
- Ledger: **73 PASS / 8 PARTIAL / 1 FAIL / 8 NOT RUN**. Chưa duyệt production, chưa merge main, không publish local QA images thành release registry.

## Team cần làm sau khi pull bản cuối

Đọc `TEAM_REVIEW_HANDOFF_2026-10-04.md`, chạy CI cho SHA cuối, review bảo mật/input/context/ops, tự build với secret riêng và populate KB. Local QA fixture/account/volume của tác giả không đi theo Git. Chỉ deploy khi các finding và gate staging được xử lý hoặc có risk acceptance đúng thẩm quyền, kèm backup/migration/rollback plan.
