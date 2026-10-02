# Báo cáo sửa Flyway/MySQL 8.4 và review chéo bản vá production

Ngày chốt kiểm tra: **02/10/2026**, theo giờ Việt Nam.

Repository: BlueAI_Platform; branch: `feature/conversation-context`; nền commit: `8f09d00`.

## 1. Kết luận

Đã xử lý nguyên nhân cảnh báo tương thích Flyway/MySQL 8.4, review bằng ba sub-agent chuyên trách, sửa các vấn đề xác nhận được và chạy lại test trên bản code cuối.

- Backend: **246/246 test pass, 0 failure, 0 error, 0 skipped**; `clean verify` và đóng gói JAR thành công trên Java 21/Linux.
- Flyway mới: **không còn thông báo `Flyway upgrade recommended`** trong lượt kiểm tra cuối với MySQL **8.4.11**.
- Kiểm tra nâng engine: dùng Flyway **11.7.2 thật** tạo schema/history V6, sau đó dùng **11.20.3 thật** nâng đến V8, kiểm tra checksum và dữ liệu được giữ nguyên.
- Test chủ động kill/restart backend bằng **JAR cuối**: pass; recovery giải phóng bốn claim, replay không gọi provider thêm, task mới chạy được.
- RAG: **60 test pass**; frontend: **43 test pass** và build thành công.
- Nginx thật trên Linux, restore SQL/tài liệu/index trong Docker riêng, cùng các regression vận hành: pass trong phạm vi mô tả bên dưới.

**Chưa commit, push, merge main hoặc deploy bản vá vào stack đang phục vụ của bạn.** Các thay đổi có sẵn trước lượt này và hai báo cáo ngày 01/10 được giữ lại. Báo cáo này bổ sung kết quả mới, không thay thế lịch sử kiểm tra cũ.

Đây là bản vá đã qua các cổng kiểm tra local được liệt kê; **không phải cam kết hệ thống không thể có lỗi hoặc đã được chứng nhận production**. Cần CI trên GitHub, review/acceptance của team và staging trước khi promote.

## 2. Vì sao có cảnh báo Flyway và đã sửa thế nào?

Spring Boot parent đang dùng kéo mặc định Flyway 11.7.2. Trong mã MySQL support của version này, ngưỡng cảnh báo là MySQL 8.1; vì vậy MySQL 8.4 phát cảnh báo dù một số migration vẫn chạy được. Ngưỡng trong 11.20.3 đã bao phủ 8.4. Đối chiếu mã nguồn chính thức: [Flyway 11.7.2](https://raw.githubusercontent.com/flyway/flyway/flyway-11.7.2/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java), [Flyway 11.20.3](https://raw.githubusercontent.com/flyway/flyway/flyway-11.20.3/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java).

Thay đổi trong [pom.xml](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/pom.xml>): pin `flyway.version=11.20.3` để **cả `flyway-core` và `flyway-mysql` cùng version**. Đã kiểm tra dependency tree thực tế, không chỉ sửa dòng cấu hình. Giữ Java 21, Spring Boot hiện tại và nhánh Flyway 11.x; không hạ MySQL, không tắt log để giấu cảnh báo.

Test mới [MySqlFlywayUpgradeIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/migration/MySqlFlywayUpgradeIntegrationTest.java>) chạy hai tiến trình Maven riêng:

1. Engine cũ tạo V1–V6 trong database test rỗng, seed user, task hoàn tất và task bỏ dở; lưu bản đối chiếu sáu bản ghi history.
2. Engine mới chạy V7/V8; xác nhận toàn bộ history cũ, checksum, dữ liệu user, key và kết quả task hoàn tất vẫn giữ nguyên.
3. Kiểm tra backfill expiration, không còn migration pending, `validate` pass và migrate lần nữa không làm thêm thay đổi.
4. Xác nhận JAR engine đúng version ở cả hai giai đoạn; giai đoạn mới không có cảnh báo tương thích.

**Không sửa migration V1–V6, không chạy `repair`, không baseline mù.** V7 của bản vá trước được giữ; V8 là migration bổ sung forward-only cho exact key/index.

## 3. Chia team review và cách chốt kết quả

| Reviewer | Phạm vi | Kết quả và cách kiểm chứng |
| --- | --- | --- |
| Wegener | Auth, refresh/password race, audit, retention, admin mutation | Bốn phát hiện ban đầu được xử lý; đọc chéo auth/audit/AI integration; không tìm thêm lỗi xác nhận được trong snapshot đã đọc. Main chạy các regression MySQL/H2. |
| Newton | AI task lease, quyền tài liệu, replay, idempotency, recovery, query plan | Sáu phát hiện ban đầu được xử lý; review chéo tìm thêm một phản ví dụ fairness và hai điểm yếu của test. Đã sửa code/test, sau đó main chạy lại toàn bộ. |
| Noether | Backup/restore, deploy/rollback, private worker, Nginx, cấu hình production | Tám phát hiện ban đầu có bản xử lý; bổ sung test lỗi từng giai đoạn và runbook. Main kiểm tra Docker restore thật, Nginx thật và test vận hành. |

Tổng cộng **18 phát hiện ban đầu**, cộng **3 điểm bổ sung từ review chéo**: một lỗi fairness còn sót và hai thiếu sót trong chất lượng regression. Không cộng số test focused với số test toàn bộ để làm tăng giả số lượng kiểm tra.

Main tích hợp thay đổi, đọc lại các nhánh quan trọng và thực hiện test cuối. Kết quả sub-agent đọc code không được xem là bằng chứng rằng một test đã chạy.

Theo quy ước repository, sử dụng GitNexus để phân tích ảnh hưởng trước các sửa đổi và kiểm tra phạm vi sau sửa. Các luồng auth/recovery dùng chung schema có mức ảnh hưởng **HIGH/CRITICAL**; đã báo trước và dùng regression rộng thay vì chỉ kiểm tra một method. Graph có giới hạn suy luận receiver/shared-schema; không xem cạnh thiếu là code không được gọi. Danh sách file Git thực tế và test các file mới được đối chiếu riêng, vì kết quả detect-changes không bao quát đầy đủ mọi file untracked.

## 4. Nhóm auth: sửa race condition, audit và lạm dụng logout

| Vấn đề | Xử lý | Bằng chứng chính |
| --- | --- | --- |
| Admin đổi trạng thái/xóa user có thể ghi lại password/auth version/tombstone cũ | Dùng chung khóa hàng user và refresh entity từ DB; giữ khóa qua revoke và commit. Nâng lock của entity đã tải trước không thay thế việc refresh. | 24 tình huống MySQL về snapshot cũ, thứ tự chờ, password/status/delete, monotonic auth version và tombstone. |
| Audit `REQUIRES_NEW` cần connection thứ hai; có thể làm rollback revoke khi pool cạn hoặc báo lỗi sau mutation đã commit | Thêm `recordInTransaction` với propagation MANDATORY; auth/admin/retention ghi mutation và audit cùng connection/commit. Failure audit được commit ở các guard đã kiểm tra trước mutation. | Ba test runtime pool một connection; các test MySQL về audit trong transaction và rollback không để success audit giả. |
| Retention khóa reset token trước refresh token, ngược password mutation | Thống nhất thứ tự refresh → reset → audit. | Regression kiểm tra thứ tự gọi, cutoff, số bản ghi và audit. |
| Logout với token rác ghi audit vô hạn; refresh/logout chưa có edge throttling | Không ghi audit cho unknown/repeated logout không revoke thêm; thêm giới hạn request/connection và JSON 429/Retry-After tại Nginx. | 100 logout rác không tăng audit; burst qua Nginx thật, OPTIONS không tiêu quota. |

Các file chính:

- [AuthService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/service/AuthService.java>) và [RefreshTokenRepository.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/repository/RefreshTokenRepository.java>): giữ thứ tự owner/user trước refresh-token của bản vá trước; đổi audit và xử lý logout.
- [AdminUserService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/service/AdminUserService.java>): helper khóa/refresh user cho password/status/delete; audit atomic.
- [AuthAuditLogService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/service/AuthAuditLogService.java>) và [AuthRecordRetentionService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/service/AuthRecordRetentionService.java>): transaction/audit và lock order.
- [AuthAdminMutationMySqlIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthAdminMutationMySqlIntegrationTest.java>), [AuthAuditCapacityIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthAuditCapacityIntegrationTest.java>), [AuthRecordRetentionOrderingTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthRecordRetentionOrderingTest.java>).
- [AuthMySqlConcurrencyIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthMySqlConcurrencyIntegrationTest.java>) và [AuthPasswordConcurrencyIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthPasswordConcurrencyIntegrationTest.java>): giữ kiểm tra refresh/reset ở hai thứ tự, cập nhật cấu hình migration/test liên quan.

## 5. Nhóm AI task: giữ deadline, quyền hiện tại và khả năng recovery

| Vấn đề | Xử lý | Bằng chứng chính |
| --- | --- | --- |
| Transaction Repeatable Read có thể dùng snapshot quyền cũ khi finalize/poll/replay | Đọc hiện tại có khóa cho client/owner/document/grant; thứ tự khóa nhất quán; lưu kết quả/evidence atomic và từ chối trả evidence đã revoke. | Tám test MySQL authorization với barrier/snapshot, owner/doc/grant/replay và tenant boundary. |
| Claim đã hết hạn trước khi dispatch nhưng vẫn gọi provider hoặc được cấp lại budget | Deadline được mang từ claim; kiểm tra durable status, lease và quyền trước mỗi retrieval/completion; không reset deadline sau khi chờ. | Tám test dispatch lease: hết hạn có/không sweep, mất quyền giữa hai bước, zero-provider call, quota/replay. |
| MySQL collation không phân biệt hoa/thường làm hai key khác nhau bị xem là một | V8 đổi key sang `utf8mb4_0900_bin` NO PAD qua placeholder; H2 dùng VARCHAR tương ứng trong test. | Hai key chỉ khác case tạo hai task/hai lượt quota; replay đúng key không gọi thêm và không tăng quota. |
| Client lỗi/đang bị khóa làm các client sau bị đói lượt recovery | Advance cursor dù một page thất bại; NOWAIT bỏ qua lock bận; **giữ cutoff cố định cho từng vòng**, chỉ reset sau khi tới cuối. | Test SQL/cursor/wrap thật được assert; MySQL khóa client, thả khóa, liên tục thêm task mới hết hạn và xác nhận client cũ được xử lý. |
| Quét candidate không có index/bound hợp lý khi backlog tăng | Keyset `(expires_at,id)`, tối đa 100 candidate/task rows mỗi lượt, V8 covering/per-client indexes. | Backlog 1.000 task; đúng 100 ID cuối sau cursor ở hàng 900; EXPLAIN ANALYZE đọc node index thật với actual rows × loops = 100, không sort. |
| Lỗi transaction ghi usage làm mất câu trả lời provider đã thực hiện | Best-effort usage bắt cả DataAccessException và TransactionException; không retry provider vì telemetry lỗi. | Provider adapter fake HTTP kiểm tra lỗi tạo/commit transaction vẫn giữ completion; không gọi AI thật. |

Giải thích ngắn: cursor giống dấu trang khi đọc hàng đợi. Nếu vừa đọc vừa kéo dài mốc cuối, một client từng bị bỏ qua có thể không được quay lại. Giữ mốc cuối của một vòng giúp vòng đó kết thúc; vòng mới thử lại client cũ và nhận các task vừa hết hạn.

Các file chính:

- [AiTaskService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskService.java>): claim/dispatch/finalize/poll/replay và kiểm tra quyền hiện tại. Provider chạy ngoài transaction DB.
- [AiTaskRecoveryService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskRecoveryService.java>) và [AiTaskRecoveryScheduler.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskRecoveryScheduler.java>): expiration durable, fencing, paging, fixed cutoff, skip busy locks và scheduler.
- [ConfiguredAiProviders.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/ConfiguredAiProviders.java>) và [ConfiguredAiProvidersTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/ConfiguredAiProvidersTest.java>): best-effort usage transaction errors.
- [V8__ai_task_exact_keys_and_recovery_indexes.sql](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/resources/db/migration/V8__ai_task_exact_keys_and_recovery_indexes.sql>), [application.properties](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/resources/application.properties>) và [application-test.properties](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/resources/application-test.properties>): binary key placeholder và schema mới.
- [AiTaskDispatchLeaseIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskDispatchLeaseIntegrationTest.java>), [AiTaskMySqlAuthorizationIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskMySqlAuthorizationIntegrationTest.java>), [AiTaskMySqlRecoveryIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskMySqlRecoveryIntegrationTest.java>), [AiTaskRecoverySchedulerTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskRecoverySchedulerTest.java>), [AiTaskApiIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskApiIntegrationTest.java>).
- [test-ai-task-recovery.mjs](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-ai-task-recovery.mjs>): kill/restart HTTP thật với provider giả.
- [AI_TASK_API.md](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/AI_TASK_API.md>): cập nhật recovery/deadline/key contract, retention/idempotency window và bỏ mô tả cũ mâu thuẫn với automatic expiration.

Không tự retry task đã expire. Replay cùng key giữ failure đã lưu; key mới là một invocation mới có thể tốn phí. Nếu xóa ledger row theo retention, bảo đảm idempotency của key đó cũng hết; cần execution ID duy nhất/tombstone nếu business đòi bảo vệ lâu hơn. Đây không phải bảo đảm exactly-once billing từ provider.

## 6. Nhóm vận hành, Nginx và cấu hình bảo mật

| Phát hiện | Xử lý và giới hạn |
| --- | --- |
| Redirection native làm hỏng archive trên PowerShell cũ | Backup/restore yêu cầu PowerShell ≥7.4; drain toàn bộ tar/gzip để phát hiện stream/trailer hỏng. Test byte 0–255 và corrupt gzip pass. [Hành vi binary redirection chính thức](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_redirection?view=powershell-7.4). |
| Restore dùng option mount không hợp lệ với Compose run | Dùng read-only `--volume`, path tuyệt đối truyền một argument; kiểm tra path có dấu cách và restore Docker thật. |
| Restore dở vẫn có thể mở lại dịch vụ | Mark quiesced trước stop; chỉ start sau import/archive/validation thành công; lỗi không tự start; hỗ trợ `-KeepQuiesced`, cảnh báo recovery thủ công, dọn SQL scratch. |
| Worker probe tạo file tạm chứa secret | Truyền environment theo tên từ process memory; không literal key trong command line, không secret file, restore environment trong finally. Docker-admin vẫn là trust boundary. |
| Deploy false-green vì chỉ actuator UP | Thêm sáu post-start gates: API, RAG operational readiness, frontend HTML, API proxy, private actuator và private worker denial. Deploy/rollback fail khi bất kỳ gate sai. Không tự promote traffic. |
| Nginx giữ IP backend cũ sau recreate | Dùng Docker DNS resolver và upstream `resolve`/shared zone; fixture giữ IP cũ bằng container khác rồi thay backend. Nginx phục hồi mà không restart. Cần Nginx ≥1.27.3; đã test 1.27.5. [Tài liệu upstream resolve](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server). |
| Test spoof forwarding chỉ bắt giá trị giả cụ thể, không đảm bảo client IP thật | Assert đúng single-IP baseline ở generic API và hai route widget; X-Forwarded-For được thay, không append dữ liệu browser. |
| DB password có fallback hardcoded | Bỏ fallback khỏi application.properties; thiếu password production bị validator từ chối. Test resolve từ file cấu hình thật, không chỉ dùng MockEnvironment có sẵn password. |

Files vận hành:

- [backup.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/backup.ps1>), [restore.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/restore.ps1>), [deploy.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/deploy.ps1>), [deploy-helpers.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/deploy-helpers.ps1>), [worker-private-e2e.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/worker-private-e2e.ps1>).
- [test-backup-restore.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-backup-restore.ps1>), [test-deploy-rollback.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-deploy-rollback.ps1>), [test-worker-private-e2e.ps1](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-worker-private-e2e.ps1>).
- [nginx.conf](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/nginx.conf>) và [test-nginx-ingress.mjs](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-nginx-ingress.mjs>): session limits, private routes, uploads, spoof/DNS replacement regressions; fixture subnet chạy được trên Docker 27/Linux và Docker Desktop.
- [ProductionSecurityConfigurationValidatorTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/config/ProductionSecurityConfigurationValidatorTest.java>): từ chối default password rỗng trong production.
- [api.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/api.py>), [ingestion.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/ingestion.py>), [tests/test_api.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/tests/test_api.py>): thêm `/readiness` sau startup, yêu cầu worker còn chạy và có successful poll gần đây; không gọi provider để health-check. Giữ `/ready` cho startup để tránh vòng chờ RAG ↔ backend.
- [PRODUCTION_DEPLOYMENT.md](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/PRODUCTION_DEPLOYMENT.md>): runbook PowerShell/readiness/restore, latest V8, writer drain, UTC sessions và partial MySQL migration recovery.

## 7. Kết quả kiểm thử thực tế

| Cổng kiểm tra | Kết quả | Phạm vi bằng chứng |
| --- | --- | --- |
| Backend cuối `mvn -B clean verify` | **246 pass, 0 failure/error/skip**, build JAR pass | Bật cả database auth, migration và old/new-engine gate; Java 21/Linux, MySQL 8.4.11 và H2. |
| Seed engine cũ | **1 pass** | Tiến trình Maven riêng với 11.7.2; tái hiện đúng cảnh báo cũ. Verification engine mới đã nằm trong 246 test. |
| Nhóm regression backend đáng chú ý | 6 auth MySQL + 24 admin MySQL + 3 pool-capacity + 1 retention + 8 dispatch + 8 authorization + 7 MySQL recovery + 5 scheduler | Các số này là **subset của 246**, không phải test cộng thêm; recovery gồm một test assertion parser. |
| Kill/restart final JAR | **PASS** | Bốn claim durable bị bỏ dở; scheduler expire trước polling; replay không tăng provider calls; invocation mới hoàn tất. Provider HTTP giả, không quota thật. |
| RAG | **60 pass**, pip check pass, pip-audit không có known advisory trong môi trường test | Không tự nạp .env, mock embeddings/provider. Log warning âm tính là tình huống test chủ động. |
| Frontend | **43 pass**, build pass | Source UI không đổi trong lượt cuối; production npm audit không có known advisory. |
| Live Nginx | **PASS trên Windows/Docker Desktop và Linux Docker 27.5.1** | Private ingress, 2/20 MB uploads, reject quá giới hạn, exact spoof baseline, session rate/JSON response, CORS preflight budget, backend IP replacement. Connection-limit configuration chưa có stress regression độc lập. |
| Restore thật, opt-in | **PASS** | Database/table/row giả, archive document/index giả được restore qua script vào Docker volume riêng; stale/hidden files thay đúng, SQL scratch dọn sạch. Đây không phải khôi phục dataset production hoặc kiểm tra semantic search từ index thật. |
| Restore failure safety | **8 scenario pass** | Mock: partial stop, SQL, documents, index, validation, empty DB đều không start; success chỉ start sau validation; KeepQuiesced không start. |
| Deploy/rollback acceptance | **18 scenario pass** | Mock 9 mode cho mỗi action: ready, RAG503, liveness-only, bad JSON, FE502, SPA fallback, private worker/actuator lộ, native failure. Không rollout production thật. |
| Worker credential safety | **3 scenario pass** | Fake-key success/network failure/probe failure; key không nằm trong argv/file, environment được trả lại. Không gọi worker của stack thật. |
| Production Compose/Promtail | **PASS** | Interpolation/ports/health/restart/config contracts bằng secret giả; không đọc .env thật. |
| Workflow và source hygiene | **PASS** | YAML 6 jobs, whitespace diff, pattern scan key trong source tracked/untracked; BE/target không bị track. Pattern scan không phải full-history/enterprise secret scanner. |

### CI được bổ sung

[verify.yml](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/.github/workflows/verify.yml>) có job old-engine seed → new-engine verify riêng, các MySQL concurrency/authorization/recovery gates, process crash test, Nginx ingress thật, `-RunDockerRestore` và hai ops regressions mới. JDBC URLs test ép `connectionTimeZone=UTC` cùng `forceConnectionTimeZoneToSession=true`.

Đã parse/assert workflow local. **GitHub Actions chưa chạy bản patch này vì chưa push**; local PASS không được ghi thành remote CI PASS.

### Các lỗi fixture/tool gặp khi chạy và cách xử lý

- Không standalone validate trước các migration pending trong upgrade test; migrate kiểm tra applied checksums rồi validate latest. Đã chạy lại old→new thành công.
- Flyway cần hai connection lúc startup; pool-capacity test boot với hai rồi hạ runtime về một để đo đúng lỗi auth.
- Linux Docker 27 cần user-configured subnet cho explicit test IP; fixture chỉ recreate network test rỗng với subnet đã được cấp, không thay network ứng dụng.
- Windows có thể tìm thấy hai executable Docker; fixture lấy một native executable. File fixture text được viết byte-deterministic để không nhầm CRLF với lỗi restore.
- `clean` không xóa được mount-root target dưới source read-only; final build dùng workspace volume writable riêng, chỉ copy pom/src và không xóa BE/target của bạn.
- Docker đã khởi động lại giữa các lượt; chỉ database test riêng được dựng lại. Lượt `clean verify` cuối và crash/restart cuối được chạy lại sau đó.

Không lấy một lần retry để che lỗi không hiểu nguyên nhân. Các kết quả PASS ở trên là sau khi sửa đúng fixture/code và chạy lại; warning/deprecation của framework test không bị đồng nhất với cảnh báo Flyway production đã xử lý.

## 8. Bằng chứng/log local

- [Backend 246 tests và final JAR](<C:/Users/ductr/AppData/Local/Temp/blueai-review-clean-final-cycle-20261002.log>).
- [Seed Flyway 11.7.2 thật](<C:/Users/ductr/AppData/Local/Temp/blueai-review-final-seed-cycle-20261002.log>), [resolved Flyway dependencies](<C:/Users/ductr/AppData/Local/Temp/blueai-review-deps-20261002.log>).
- [Final JAR crash/restart](<C:/Users/ductr/AppData/Local/Temp/blueai-review-crash-final-cycle-20261002.log>).
- [Live restore và negative safety](<C:/Users/ductr/AppData/Local/Temp/blueai-review-restore-live-pass-20261002.log>).
- [Deploy/rollback 18 scenarios](<C:/Users/ductr/AppData/Local/Temp/blueai-review-deploy-final-20261002.log>), [worker secret regressions](<C:/Users/ductr/AppData/Local/Temp/blueai-review-worker-secret-final-20261002.log>).
- [Linux Nginx](<C:/Users/ductr/AppData/Local/Temp/blueai-review-linux-ingress-final-20261002.log>), [RAG 60 tests](<C:/Users/ductr/AppData/Local/Temp/blueai-review-rag-final-20261002.log>), [frontend 43 tests](<C:/Users/ductr/AppData/Local/Temp/blueai-review-fe-tests-20261002.log>).
- [GitNexus change scope](<C:/Users/ductr/AppData/Local/Temp/blueai-review-change-scope-20261002.log>).

Log trong Temp là bằng chứng local, có thể bị hệ điều hành dọn; nếu cần lưu lâu, team nên lưu artifacts từ lần CI/staging đã duyệt. Không đưa database dump, key hoặc file .env lên Git để lưu bằng chứng.

Sau xác minh đã xóa **chỉ** container/database test `blueai-review-mysql-20261002`, network `blueai-review-net-20261002` và hai build/artifact volume `blueai-review-build-20261002`, `blueai-review-target-20261002`. Dữ liệu fixture/JAR trong các tài nguyên tạm này không được giữ, nhưng có thể tạo lại bằng source và test scripts; log bằng chứng vẫn còn trong Temp. Tài liệu, database, volume và các dịch vụ của stack `blueai-auth` không bị xóa hoặc nâng cấp bởi lượt này.

## 9. Những điều cần làm trước production

1. Team xem diff/PR; chạy GitHub CI trên commit được duyệt và staging bằng cùng artifact/config.
2. Trước V7/V8: đóng admission, drain và dừng **tất cả writer cũ**, kể cả replica ngoài Compose. Writer cũ không có expires_at không tương thích cột NOT NULL mới.
3. Verify backup mã hóa `age` thật và tập dữ liệu đại diện; diễn tập restore và failure giữa các statement MySQL DDL. DDL không rollback toàn bộ migration nhiều statement; không `repair`/retry mù. Chỉ rollback về image đã được chứng minh tương thích schema mới; rollback image không undo schema.
4. Ép UTC trên **JDBC session thật** của từng pool/migration runner và kiểm tra `@@session.time_zone`. Global timezone/JVM timezone/serverTimezone một mình không phải bằng chứng session UTC. [Connector/J datetime properties](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html).
5. Staging với domain/TLS/trusted proxy thật, CORS, cookies, giới hạn IP/NAT và traffic promotion có kiểm soát. Load/soak theo số user và dữ liệu dự kiến; fixtures không phải benchmark production.
6. Nếu DB password fallback cũ từng dùng thật, rotate credential đó qua môi trường được duyệt. Lượt này bỏ fallback trong code, **không** tự rotate provider/DB secret hoặc rewrite lịch sử Git.
7. Maven-wide SCA, container/CVE scan, encrypted-backup disaster rehearsal và test UI sales/knowledge-base với provider thật chưa được thực hiện trong lượt này. Audit npm/Python và pattern scan không thay thế các kiểm tra đó.

Giới hạn review cụ thể: pool-capacity dùng H2 và chỉ covers login/refresh/logout; retention là unit ordering test, chưa phải stress deadlock MySQL; AI authorization tests dùng SQL mutation/repository mock thay vì real concurrent AdminUserService/JPA; dispatch lease dùng H2, không tự chứng minh mọi MySQL lock-wait case. Các gates MySQL khác bổ sung bằng chứng, nhưng không làm các giới hạn này biến mất.

Nếu bị hard-kill trong backup/restore, plaintext scratch có thể còn và cần cleanup thủ công theo runbook. Image fixture dạng tag có thể thay đổi; release production nên ghi digest artifact đã duyệt.

**Bước tiếp theo phù hợp:** đưa patch lên branch/PR sau khi bạn duyệt báo cáo, để team chạy CI + staging. Chưa có cơ sở bỏ qua các bước này và đẩy thẳng main/production chỉ vì local tests pass.
