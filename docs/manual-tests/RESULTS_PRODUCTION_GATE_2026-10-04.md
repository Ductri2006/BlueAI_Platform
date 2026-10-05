# Báo cáo sửa lỗi và kiểm thử lại BlueAI — 04/10/2026

## 1. Kết luận

Các lỗi chức năng chính đã được sửa và kiểm lại trên QA riêng. Chat trên giao diện đã nhớ đúng dữ kiện giữa hai phiên độc lập; Knowledge Base có tài liệu READY, citation thật và không còn được dùng ở lượt mới sau khi tài liệu bị xóa. **Chưa đủ điều kiện duyệt production** vì còn dependency finding, phần kiểm thử chưa đủ biến thể và các gate cần hạ tầng thật.

Theo lựa chọn của bạn: **giữ Tailwind 3, chưa nâng Tailwind 4**. Không đổi thiết kế bằng một cuộc nâng cấp framework trong đợt này.

| Bộ 90 trường hợp ban đầu | PASS | PARTIAL | FAIL | BLOCKED | NOT RUN |
| --- | ---: | ---: | ---: | ---: | ---: |
| Kết quả 03/10 | 36 | 36 | 5 | 3 | 10 |
| Tổng hợp sau đợt sửa 04/10 | 65 | 16 | 1 | 0 | 8 |

Đây là **ledger tích lũy**, không phải 65 bài UI vừa chạy trong một lượt. Một case có thể kết hợp bằng chứng UI, API và integration; những case không đổi giữ kết quả/bằng chứng ngày trước. Không đổi tiêu chí ban đầu để lấy PASS. `REL-01` được hạ về PARTIAL vì bản vá mới vẫn là working tree chưa commit, chưa có release bundle được duyệt.

Chi tiết từng ID và provenance: [ledger 90 trường hợp](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-manual-ledger-results-2026-10-04.json>). Image IDs, hashes nguồn, số test và audit: [verification summary](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-verification-summary-2026-10-04.json>).

## 2. Môi trường và phạm vi an toàn

- Branch: `feature/conversation-context`; base HEAD `fbc8179f4aefec489f971fe304d01e97077b7bb8`. Các thay đổi của đợt này **chưa commit/push**; không merge main.
- QA project: `blueai-preprod-20261003`; frontend `http://localhost:23000`, API loopback `23081`, RAG loopback `23800`, database/volumes riêng. Cả 5 service healthy sau thay image.
- Profile `qa` dùng HTTP local; không thay cho profile `prod` và HTTPS staging.
- Không rebuild/restart hoặc xóa dữ liệu stack chính `blueai-auth`. Các thao tác tạo/disable/soft-delete/backup chỉ dùng ID của fixture tổng hợp trong QA.
- Provider/JWT secret được tái sử dụng trong process memory từ đúng container QA đã kiểm label; không in hoặc ghi giá trị vào artifact. Không thực hiện revoke/rotate provider key trong đợt này.
- Chat UI dùng provider thật với dữ kiện giả, số lượt hữu hạn. Spam/concurrency/recovery và context budget dùng Dialog/provider giả hoặc HTTP client mock, không load-test bằng quota Gemini thật.
- File backup plaintext chỉ phục vụ recovery test; đã xóa các thư mục tạm có prefix và ownership được kiểm. Không tạo backup plaintext production.

## 3. Những lỗi đã sửa, lý do và file liên quan

### 3.1. Tài liệu upload bị FAILED vì không ghi được FAISS

Runtime chạy bằng user `10001`, chỉ volume index writable; trước đây staging được tạo ở thư mục cha `/app`, nên worker không đủ quyền. Staging mới nằm **bên trong volume index**, cùng filesystem để đổi tên file; không đổi tên mount directory. Khi thêm vector nhưng lưu durable thất bại, RAM được nạp lại từ index đã lưu, tránh dùng chunk chưa lưu thành công. Retry cùng document không nhân đôi vector.

File: [api.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/api.py>), [RAG regression tests](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/tests/test_api.py>), [real FAISS probe](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-real-faiss.py>).

Đã kiểm save/persist/restart, owner isolation, retry, lỗi ghi giữa chừng, rollback RAM/durable và purge bằng FAISS thật, user không đặc quyền, không có network/provider. Đây là rollback cho lỗi ghi đã kiểm, **không phải cam kết transaction nhiều file chịu được mọi thời điểm mất điện/process crash**.

### 3.2. MySQL session không thực sự ở UTC

`serverTimezone=UTC` trước đây không ép timezone của MySQL session khi server global là `+07:00`. URL mới dùng `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`. Probe dùng chính URL trong Compose: global `+07:00`, connection session `+00:00`, Connector/J `9.7.0`.

File: [compose.yaml](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/compose.yaml>), [application.properties](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/resources/application.properties>), [JDBC contract probe](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-jdbc-contract.ps1>), [QaJdbcContractProbe.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/QaJdbcContractProbe.java>). Override QA cũng dùng URL mới.

Flyway giữ `11.20.3`; các bài fresh/upgrade/MySQL chạy riêng đã đạt. Không repair/baseline hoặc thay migration history trên dữ liệu thật.

### 3.3. Tự tìm context bỏ sót khách hàng trong câu hỏi dài

UI đã phát hiện lỗi thật: phiên QATarvos ghi **37 sales / 2.900.000 VND / TV-4712**, nhưng câu hỏi dài bị scoring theo độ phủ từ khóa làm history không vượt ngưỡng. Provider thiếu dữ kiện rồi trả sai **15 / 50.000 USD / OP-88219**. Bằng chứng sai được giữ lại, không chuyển thành PASS.

Ranker mới cộng điểm khi câu hỏi chỉ rõ tên khách/client/công ty và candidate chứa **đúng cả token tên**, không cộng cho từ chung như “khách cần”, không dùng substring để khớp khách khác. Không hạ threshold toàn bộ hoặc lấy tất cả history. Owner boundary, budget và chế độ chỉ phiên hiện tại vẫn giữ nguyên.

File: [ContextRelevanceRanker.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/context/ContextRelevanceRanker.java>), [ranker tests](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/context/ContextRelevanceRankerTest.java>), [RagContextProductionGateIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/chat/controller/RagContextProductionGateIntegrationTest.java>).

Test mới bao phủ câu hỏi dài Việt/Anh, tên gần giống, generic customer reference, history liên quan/không liên quan/owner khác, 100 messages, summary oversized, memory lifecycle và source đã xóa. Nội dung request provider được kiểm bằng `MockRestServiceServer` với controller/H2 thật; không nhận là 100 lượt chat trên provider thật.

Sau khi build/thay API QA, UI phiên mới **không liên kết thủ công** trả đúng 37 / 2,9 triệu / TV-4712, có `RELATED_HISTORY` đúng conversation nguồn, không có ghi chú làm vườn, reload vẫn giữ kết quả. Ranker vẫn là heuristic từ khóa có kiểm soát, không phải semantic search hoàn hảo cho mọi cách gọi tên hoặc mọi ngôn ngữ.

### 3.4. Bảo vệ session trình duyệt và xử lý terminal 401

Frontend không còn lưu access/refresh token vào localStorage. Access token ở RAM; refresh credential nằm trong cookie `HttpOnly`, `SameSite=Strict`, host-only, path giới hạn. Profile prod luôn bật `Secure`. Ba route browser login/refresh/logout bắt buộc Origin allowlist **và** `X-BlueAI-Browser: 1` trước khi gọi AuthService, trả `no-store`. API nghiệp vụ vẫn chỉ xác thực Bearer; không mở rộng thành cookie-auth cho mọi endpoint.

Giữ endpoint token cũ cho client/API tương thích, nhưng web dùng `/api/v1/auth/browser/...`. Có rotation, single-flight refresh, chống response refresh cũ làm sống lại session đã clear và logout chờ rotation để revoke credential mới nhất. Khi 401 cuối cùng, AuthProvider xóa identity và reload để không giữ conversations/documents/drafts của account trước trong module cache.

File backend: [BrowserAuthController.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/controller/BrowserAuthController.java>), [BrowserAuthResponse.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/dto/BrowserAuthResponse.java>), [SecurityConfig.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/config/SecurityConfig.java>), [BrowserAuthIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/controller/BrowserAuthIntegrationTest.java>), [BrowserAuthCookieTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/controller/BrowserAuthCookieTest.java>).

File frontend: [blueAiApi.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/api/blueAiApi.ts>), [blueAiApi.test.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/api/blueAiApi.test.ts>), [AuthContext.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/auth/AuthContext.tsx>).

UI kiểm login/reload/logout/back/forward, USER chỉ chat, ADMIN mở đủ workspace và tài khoản bị disable giữa phiên → API/refresh 401 → login, không còn history trong UI. Fixture được bật lại sau test. JWT expiry có control chữ ký hợp lệ 200, cùng loại JWT expired 401. Một probe cũ ký sai đã được đánh dấu `SUPERSEDED`, không dùng làm bằng chứng expiry.

Giới hạn: chưa kiểm flow HTTPS thật hoặc cạnh tranh refresh nhiều tab/trình duyệt. Cookie không tự loại rủi ro XSS; access token Bearer bị sao chép cần được xử lý theo TTL/authVersion, không khẳng định logout lập tức vô hiệu mọi bản access token bị đánh cắp.

### 3.5. Security headers và dependency runtime

Thêm CSP same-origin, `nosniff`, frame denial, no-referrer cho HTML và lỗi ở Nginx. Location có header riêng (ví dụ 429) include lại snippet để không mất header do inheritance. Login/refresh/logout browser mới chia sẻ quota với đường cũ. HSTS dành cho gateway HTTPS, không gắn vào upstream HTTP QA.

File: [security-headers.conf](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/security-headers.conf>), [nginx.conf](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/nginx.conf>), [FE Dockerfile](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/Dockerfile>), [test-nginx-ingress.mjs](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-nginx-ingress.mjs>).

Backend đồng bộ Tomcat `10.1.60`, Jackson BOM `2.21.7`, Httpcore5 `5.4.4` trong [pom.xml](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/pom.xml>). Không nhảy major Spring Boot/Flyway. FE runtime đổi sang Nginx `1.30` pinned digest và cập nhật Alpine packages.

RAG runtime dùng Python `3.13` base pinned; dependencies cài lúc build, sau đó bỏ pip/ensurepip không cần cho chạy app. Các dependency vendored trong pip trước đó có finding riêng; đã loại khỏi runtime và scan lại, **không gọi chúng là false positive**. File: [RAG Dockerfile](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/Dockerfile>). Uvicorn, parser và FAISS trên image mới vẫn chạy/test được.

### 3.6. Logging và restore readiness

Compose production dùng Alloy thay Promtail. Discovery/keep filter chỉ ship log đúng project; có ECS nested và literal dotted fields. Redaction xử lý Bearer và các trường credential; JSON quoted password được che **toàn bộ cả khoảng trắng và escaped quotes**. Bài live Alloy → Loki kiểm không ship outsider, không lộ fixture credential, nhãn đúng và có log mới từ cả hai format sau restart, không chỉ đọc lại log cũ.

File: [compose.production.yaml](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/compose.production.yaml>), [config.alloy](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/alloy/config.alloy>), [test-alloy-config.mjs](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-alloy-config.mjs>), [test-alloy-ingestion.mjs](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-alloy-ingestion.mjs>), [CI workflow](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/.github/workflows/verify.yml>).

Socket Docker mount `:ro` **không biến Docker API thành quyền read-only**; cần đánh giá socket proxy/host permissions trước production. Loki retention 168 giờ đã cấu hình, chưa có bằng chứng quan sát đủ 7 ngày. Không coi log redaction là bảo đảm không bao giờ có secret trong bất kỳ dạng message nào.

Real restore test tìm thêm race: readiness có thể nhìn thấy temporary MySQL initializer qua socket rồi import SQL khi server đó tắt. [restore.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/restore.ps1>) nay chờ `SELECT 1` bằng TCP `127.0.0.1`, không nhận initializer `--skip-networking` là ready. Backup/restore SQL + documents + FAISS thật đã đạt và failure regression tiếp tục fail-closed.

Runbook/README đã cập nhật cookie protocol, cùng-origin/CSP, collector Alloy, schema latest V9 và giới hạn staging: [PRODUCTION_DEPLOYMENT.md](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/PRODUCTION_DEPLOYMENT.md>), [BE README](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/README.md>), [README dự án](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/README.md>).

## 4. Kết quả kiểm chứng

| Nhóm | Kết quả thực tế | Giới hạn của kết luận |
| --- | --- | --- |
| Backend full verify | 55 suites, 367 test entries, 338 executed PASS, 29 skipped, 0 failure/error | Skip không là PASS; MySQL conditional chạy riêng |
| MySQL 8.4 / Flyway / auth-AI concurrency | 1 migration + 45 auth/AI + 1 old-engine seed + 1 new-engine verify, đều đạt | Là 48 executions có overlap với conditional suites; không cộng thành unique tests |
| Real backend kill/recovery | Durable task replay/expiry/slot recovery và fresh task đạt | Provider giả, không xác nhận production billing/quota |
| Frontend | 106/106 PASS, TypeScript và Vite production build đạt | Giữ Tailwind 3; không chứng minh mọi browser cũ |
| RAG | 63/63 PASS trên image mới, real FAISS non-root probe đạt | Không có paid provider calls trong bộ tự động |
| Latest API regressions | 39/39 + 8/8 negative đạt | Không tự chuyển thành “đã test UI” |
| API bổ sung | 9 hợp lệ từ remediation harness; 3 session-edge, 7 validator và 1 pagination probe đạt | Expiry probe ban đầu bị supersede bởi positive-control mới |
| Nginx / Compose / Alloy | Compose contracts, Nginx live quotas/headers/private routes/DNS, Alloy native validation/live ingestion đạt | Local fixtures, không phải ingress/CDN/VPS thật |
| Backup/restore | Quiesced SQL 27 tables, tài liệu upload và FAISS 34 vectors được phục hồi kiểm tra offline; 0 provider calls | Archive là dữ liệu giả; chưa encrypted off-host/RPO/RTO |

UI đã thực hiện cả trước và sau lỗi context, KB populated/missing-policy/deleted, citation panel, current-only/link/automatic, reload, role boundary, session invalidation, admin workspaces và Custom Cancel/Discard. Không thực hiện task CRM hay đơn hàng thật trong Dialog demo.

Ảnh bằng chứng context trước/sau:

![Sau sửa: phiên mới tự nhớ đúng QATarvos, có nguồn history](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/evidence-2026-10-04/08-automatic-context-after-fix-PASS.jpg>)

Kết quả raw: [nhật ký thao tác UI](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-ui-results-2026-10-04.json>), [context trước/sau](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-context-retest-results-2026-10-04.json>), [KB trước/sau xóa](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-kb-delete-results-2026-10-04.json>), [backup/restore](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-real-backup-results-2026-10-04.json>), [session expiry/capability](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-session-edge-results-2026-10-04.json>).

QA được để lại với một tài liệu mẫu mới `b35c58bf-d447-47cb-90b5-583d82bb489a`, READY/1 chunk, để bạn tự thử. Record FAILED `fecc5848-651f-46e4-9272-d4daa10ba8bc` là bằng chứng lỗi cũ, không phải upload mới vẫn thất bại. Record cũ không được tự chuyển sang READY. Nguồn READY ban đầu `61b78d0d-ae15-4743-a0e9-a91d6aed2d7b` đã soft-delete đúng mục đích test; lịch sử câu trả lời cũ không bị viết lại.

## 5. Vì sao chưa được gọi là “pass hết”

### 5.1. FAIL còn lại: SEC-05

| Scan mới | HIGH | CRITICAL |
| --- | ---: | ---: |
| API runtime image | 0 | 0 |
| Frontend runtime image | 0 | 0 |
| RAG runtime image | 45 (OS packages) | 0 |
| RAG Python application dependencies | 0 | 0 |
| Npm production dependencies | 0 | 0 |
| Npm all, gồm build dependencies | 5 | 0 |

Npm finding tập trung ở build dependency chain Tailwind 3 → braces. Advisory hiện chưa có phiên bản braces được vá; giữ Tailwind 3 theo yêu cầu của bạn đồng nghĩa giữ finding mở, không phải nó đã biến mất. Không chạy force-upgrade hoặc ignore advisory để đổi màu báo cáo. [GitHub reviewed advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm).

Trong 45 OS HIGH của RAG, 44 chưa có `FixedVersion` trong dữ liệu scanner; 1 là `libpcre2-8-0`, installed `10.46-1~deb13u2`, fixed `10.46-1~deb13u3`. Lần thử fresh apt metadata bị từ chối vì thời gian signed Release còn ở tương lai so với Docker UTC. Không tắt kiểm tra chữ ký/ngày hoặc chỉnh clock để lách gate. Image hiện tại dùng apt layer đã build thành công trước đó; cache không chứng minh có bản cập nhật OS mới nhất. Cần thử lại fresh build với signed metadata hợp lệ, rà applicability/package tối thiểu và owner/risk treatment cho các CVE chưa có fix.

Không khẳng định Java/OS/CSS dependency sẽ không có advisory mới sau ngày scan. Các monitoring image ngoài 3 application image cũng cần được đưa vào quy trình audit định kỳ.

### 5.2. 16 PARTIAL — không phải 16 lỗi code đã xác nhận

| ID | Phần còn thiếu để đóng toàn bộ tiêu chí |
| --- | --- |
| REL-01, REL-02 | Commit/release attestation và scan credential trên đủ artifact; đang là working tree, không approved bundle |
| REL-06 | Startup profile prod/HTTPS trên staging thật với negative config, không chỉ tests/mock/local QA |
| KB-07, KB-08 | Đủ spoofed format/parser payload và size boundary qua toàn Nginx → Java → worker; ingress fake-backend hiện đã test 2/20/21 MB |
| KB-10, KB-11 | Thêm full live stale-worker attempt và parser/provider transient failure/retry matrix; unit/private boundary không thay toàn bộ acceptance |
| API-06 | Đủ session/IP/lifetime/quota variants; hai IP thật qua proxy nằm ở OPS-10 |
| SEC-01 | UI payload ở message/node, ngoài title đã render literal; không mặc định CSP đã làm mọi XSS test đạt |
| SEC-02, SEC-03 | Ma trận input tampering/oversize/SQL-shaped input trên đủ endpoint, không chỉ representative searches/titles |
| SEC-04 | Phân loại finding fixture/real, xác nhận rotate key nếu đã lộ và scan artifact; không suy “repo private” thành đã an toàn |
| OPS-01 | Đủ verified legacy V1/V3 fixtures trên MySQL; fresh/V5/V6 và Flyway engine upgrade đã đạt |
| OPS-06, OPS-07 | Promotion/rollback trên staging và scrape/receiver thực của team, ngoài offline validators/stub đã đạt |
| OPS-08 | Quan sát retention, lifecycle monitoring và hardening Docker socket; live ingestion/redaction/restart đã đạt |

### 5.3. 8 NOT RUN cần hạ tầng và quyết định của team

`REL-07`, `REL-08`, `OPS-09` đến `OPS-14`: registry/release approval/schema fingerprint; tài nguyên VPS; domain/TLS/firewall; hai IP thật/trusted proxy; load/soak; encrypted off-host backup và RPO/RTO/on-call; provider budget/billing/error policy; promotion/writer drain/rollback tương thích V9.

Không tự thực hiện production mutations hoặc đưa dữ liệu khách hàng thật vào load test để đóng những gate này. Cần team cung cấp môi trường staging/target, domain, proxy topology, ngân sách và người duyệt release. Local xanh **không thay** các bằng chứng này.

## 6. Cách chạy lại và công việc tiếp theo

Các helper QA nằm trong `docs/manual-tests`; chúng khóa vào project/port/ID riêng, không dùng làm script deploy production:

- `qa-remediation-start.ps1`: thay image QA, kiểm healthy, không ghi secret vào file.
- `qa-api-acceptance.mjs`, `qa-negative-api.mjs`: regression API; dùng `QA_RESULT_SUFFIX=-2026-10-04` để không đè kết quả gốc. Chạy lại sẽ tạo fixture mới, cần giữ các ID/evidence cũ riêng.
- `qa-session-edge.mjs`: expiry có positive-control, widget expired đúng contract, grant document owner boundary.
- `qa-dialog-validation.mjs`, `qa-conversation-pagination.mjs`: invalid save atomic/paging owner isolation, không gọi provider.
- `qa-context-retest.mjs`: prepare/verify cho regression UI đã ghi; không prepare lại khi muốn giữ bằng chứng trước sửa.
- `qa-kb-delete-retest.mjs`: chỉ ID nguồn ban đầu đã soft-delete; **không chạy lại phase delete mặc định trên tài liệu đã xóa**. Muốn lặp một lượt mới cần fixture ID/evidence mới và review scope trước.
- `qa-real-faiss.py`, `qa-real-backup.ps1`, `qa-verify-restored-faiss.py`: FAISS thật, backup isolated. Với backup mới cần truyền `-DocumentId b35c58bf-d447-47cb-90b5-583d82bb489a`, không dùng ID đã tombstone.
- `qa-finalize-evidence.mjs`: kiểm artifact, chụp metadata không credential và tính lại đủ 90 case; không tự thực hiện UI test.

Trình tự tiếp theo: hoàn thiện ma trận local còn PARTIAL → team review và CI cho commit mới → staging HTTPS/VPS có release bundle → load/backup/incident/rollback drill → duyệt production. Tailwind 4 chỉ triển khai khi bạn quyết định lại, với visual regression và browser target như [upgrade guide chính thức](https://tailwindcss.com/docs/upgrade-guide).

Đã dùng GitNexus để kiểm phạm vi tác động trước sửa, đặc biệt session clear (CRITICAL) và relevance scoring (HIGH), rồi kiểm hồi quy tương ứng. Kết quả không có graph edge ở config/framework không được dùng làm bằng chứng “không ai sử dụng”; đã đối chiếu CI/Compose/callers bằng tìm kiếm nguồn. Không tự tạo thêm thread/subagent hay gửi thông tin sang task khác trong đợt này.

Rà cuối `detect-changes --scope all` ghi 21 tracked files, 70 symbols và 137 flows bị ảnh hưởng, mức **critical về phạm vi tác động**, không phải phát hiện 137 lỗi hay một CVE critical mới. Lý do chính là auth client dùng chung ở nhiều workspace. Các class/config/test mới chưa track cũng đã được đọc và kiểm riêng, không suy kết quả tracked-diff bao phủ tự động mọi file mới. `git diff --check` đạt; không có nội dung diff của package-lock và không nâng Tailwind. Compiler vẫn có một số cảnh báo deprecated/unchecked sẵn có; RAG có cảnh báo chuyển hướng khỏi langchain-community. Không mô tả bản vá là “không còn bất kỳ warning hoặc rủi ro nào”.
