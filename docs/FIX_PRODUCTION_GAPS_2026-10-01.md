# Báo cáo xử lý bốn vấn đề sau khi pull code mới

Ngày kiểm tra: 01/10/2026. Repo: BlueAI_Platform. Branch làm việc: `feature/conversation-context`.

Bản vá được thực hiện trên nền commit `8f09d00`. Chưa commit, push, merge main hoặc deploy vào môi trường đang phục vụ người dùng. Báo cáo rà lỗi ban đầu `REVIEW_LATEST_PULL_2026-10-01.md` được giữ nguyên để đối chiếu.

## 1. Đổi mật khẩu đồng thời với refresh phiên

### Nguyên nhân

Trước đây refresh khóa bản ghi token trước, còn admin đổi mật khẩu khóa user trước. Refresh có thể tạo một token mới chưa commit tại thời điểm reset bắt đầu bulk revoke. Hai luồng không thống nhất điểm đồng bộ; test cũ cũng chờ reset encode mật khẩu khi refresh chưa commit, tức phụ thuộc vào chính thứ tự khóa không an toàn này.

### Bản sửa

- Tra cứu **owner ID dạng scalar** từ hash token; không tải/caching entity token tại bước này.
- Khóa hàng user bằng `PESSIMISTIC_WRITE`, refresh lại entity để bỏ snapshot cũ.
- Sau đó mới khóa và đọc lại refresh token; kiểm tra revoked/expired/active như trước.
- Giữ khóa user đến khi transaction cấp phiên commit. Reset đang dùng cùng hàng user nên không thể revoke trước khi token mới được ghi xong.
- Logout dùng cùng thứ tự **user trước, token sau**, tránh tạo đường khóa ngược với refresh/reset.
- Giữ nguyên rotating token, phát hiện reuse và revoke cả family; không bỏ kiểm tra bảo mật để làm test pass.

Hai thứ tự đã được kiểm thử:

1. Refresh chưa commit: reset phải chờ; sau khi refresh commit, reset revoke cả token vừa cấp.
2. Reset chưa commit: refresh phải chờ; sau khi reset commit, refresh đọc token đã revoke và bị từ chối.

Các test vẫn kiểm tra JWT cũ bị từ chối, mật khẩu cũ không đăng nhập được, mật khẩu mới dùng được; reset sai input hoặc rollback không được phá phiên hợp lệ/audit thành công giả.

File chính:

- [AuthService.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/service/AuthService.java)
- [RefreshTokenRepository.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/auth/repository/RefreshTokenRepository.java)
- [AuthPasswordConcurrencyIntegrationTest.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthPasswordConcurrencyIntegrationTest.java)
- [AuthMySqlConcurrencyIntegrationTest.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/auth/service/AuthMySqlConcurrencyIntegrationTest.java): chạy lại cùng các tình huống trên MySQL thật, không chỉ H2.

## 2. Tác vụ AI bị kẹt PROCESSING sau khi backend dừng

### Nguyên nhân

Claim được commit trước khi gọi provider. Nếu backend chết trước khi lưu kết quả, claim tồn tại nhưng không còn worker. Bốn claim như vậy chiếm đủ quota concurrency; polling/replay cũng tiếp tục báo PROCESSING.

### Bản sửa

- Thêm migration **V7**, không sửa checksum của V1–V6.
- Mỗi task có `expires_at` lưu trong database: giờ DB + deadline + 15 giây dành cho finalize. Deadline mặc định 25 giây nên lease mặc định 40 giây.
- Thêm index `(status, expires_at, client_id)` phục vụ quét tác vụ quá hạn.
- Task legacy được backfill `expires_at=created_at`; PROCESSING bỏ dở được thu hồi sau nâng cấp. Không xóa task hoặc kết quả đã hoàn tất.
- Recovery lưu một response terminal `FAILED`, mã `AI_TASK_EXPIRED`, hoàn tất timestamp và giải phóng slot.
- Recovery chạy khi submit, poll, finalize và ở scheduler: bắt đầu sau 5 giây, lặp mỗi 10 giây, tối đa 100 client mỗi lượt.
- Claim, finalize, recovery cùng khóa hàng integration client. Finalize chỉ ghi khi task còn PROCESSING **và lease chưa hết**; evidence chỉ được ghi sau khi update có fencing thành công, cùng transaction.
- Worker trả kết quả muộn không được khôi phục task về COMPLETED hay ghi evidence sau expiration.
- Mỗi client recovery là một transaction; lỗi client này không ngăn xử lý client khác trong cùng batch. Lỗi lưu giữa batch phải rollback và có thể thử recovery lại.
- **Không tự gọi lại provider.** Replay cùng key/body trả failure đã lưu. Muốn gọi AI lại phải chủ động dùng key mới, chấp nhận khả năng phát sinh thêm chi phí.

Do task đã expire không được claim lại bằng cùng ID/key, fencing bằng trạng thái terminal và điều kiện lease là đủ cho luồng này; bản vá không bổ sung retry worker dùng lại task ID. Đây không phải bảo đảm exactly-once billing của provider.

Phần code claim/finalize được tách thành các hàm nhỏ để dễ đọc, giảm code dồn một dòng và bỏ dependency cấu hình không dùng. Không thay schema input/output của ba capability hoặc bỏ ownership/document-grant validation.

File chính:

- [V7__ai_task_expiration.sql](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/resources/db/migration/V7__ai_task_expiration.sql)
- [AiTaskService.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskService.java)
- [AiTaskRecoveryService.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskRecoveryService.java)
- [AiTaskRecoveryScheduler.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/ai/task/AiTaskRecoveryScheduler.java)
- [AiTaskApiIntegrationTest.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskApiIntegrationTest.java)
- [AiTaskMySqlRecoveryIntegrationTest.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskMySqlRecoveryIntegrationTest.java)
- [AiTaskRecoverySchedulerTest.java](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/ai/task/AiTaskRecoverySchedulerTest.java)
- [test-ai-task-recovery.mjs](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-ai-task-recovery.mjs): test kill/restart tiến trình thật.
- [AI_TASK_API.md](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/AI_TASK_API.md): cập nhật contract recovery và hướng dẫn nâng cấp.

## 3. Test phục hồi FAISS phụ thuộc API key

Sửa fixture test để mock `_embeddings` cho toàn bộ bước phục hồi và mô phỏng restart. Environment trong test được làm sạch, chỉ giữ thư mục index test. Các assertion về durable state, purge retry và dữ liệu trên disk vẫn giữ nguyên; không sửa logic production để né lỗi của fixture.

CI tắt tự nạp `.env` bằng `PYTHON_DOTENV_DISABLED=1`. Bộ 59 test pass trong môi trường không có GOOGLE_API_KEY/GEMINI_API_KEY, không dùng quota provider.

File: [tests/test_api.py](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/tests/test_api.py).

## 4. Nginx fixture không đọc được trên Linux

`mkdtemp` tạo thư mục mode 0700. Nginx worker không phải chủ sở hữu nên không thể đi qua thư mục để đọc index.html, dẫn đến lỗi 500 dù cấu hình proxy hợp lệ.

Sửa **đúng thư mục fixture tạm** thành 0755 và index.html thành 0644; không tăng quyền thư mục upload hay dữ liệu thật. Thêm lựa chọn chạy test bằng sibling container để kiểm tra filesystem Linux thật dưới Docker Desktop, giữ localhost làm mặc định cho host/CI. Tài nguyên test được thu dọn trong `finally`.

File: [test-nginx-ingress.mjs](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/ops/test-nginx-ingress.mjs).

## Kết quả xác minh

| Kiểm tra | Kết quả thực tế |
| --- | --- |
| Backend Java 21 / Maven verify trên Linux | **183 pass, 0 failure, 0 error**; 10 test MySQL chủ động skip ở lượt không có DB riêng. Tổng discover 193. Build JAR thành công. |
| Test MySQL 8.4 riêng | **10/10 pass, không skip**: 1 migration, 6 auth concurrency, 3 task recovery. Vì vậy 10 test skip bên trên đã được chạy riêng, không bị bỏ qua. |
| Migration | MySQL fresh V1–V7 ở DB auth test; DB migration kiểm tra V1–V5, V5→V6 và V6→V7. User/auth_version được giữ; legacy task có deadline đúng, key/created_at không đổi; validate và migrate lại không tạo thay đổi. H2 validate schema cũng pass. |
| MySQL concurrency/recovery chạy lặp | **5/5 vòng bổ sung pass**, mỗi vòng 9 test: 45 lượt chạy, không failure/error/skip. Không dùng retry để che một lượt test fail. |
| Kill/restart backend thật qua HTTP | **PASS**: kill với 4 task đang gọi provider; 4 claim còn trong MySQL; restart và scheduler tự expire cả 4 trước khi poll; replay chính xác; provider vẫn chỉ nhận 4 lần gọi cũ; task mới hoàn tất với lần gọi thứ 5. |
| Python 3.13 unittest không có key | **59/59 pass**. |
| Frontend Vitest | **43/43 pass**. |
| Frontend TypeScript/Vite | **Build production pass**. |
| Production Compose contract | **PASS**: private ports, health/restart, startup dependencies; chỉ dùng credential giả. |
| Nginx ingress trên Windows/Docker và Linux/Docker | **PASS cả hai**: static page/health, private boundaries, upload size, forwarded-IP spoof, login quota, JSON 429/Retry-After và preflight. |
| Load metric/Promtail config | **PASS**; đây là kiểm tra logic/config, không phải load test hoặc gửi alert thật. |
| npm audit | **0 advisory** theo lockfile hiện tại. |
| pip check / pip-audit | **PASS / không phát hiện advisory đã biết** trong môi trường RAG riêng. |
| Mẫu key Google/OpenAI ở file Git và file mới không bị ignore | **Không có kết quả khớp**; không in key/.env thật. Không phải full secret scan toàn lịch sử Git. |
| File build BE/target bị track | **0 file**. |
| Diff whitespace, cú pháp Node, YAML workflow | **PASS**. |

Test concurrency dùng latch/transaction thật; test recovery kiểm tra active lease không bị thu hồi, quota 4 vẫn được giữ, polling bị giới hạn theo client, terminal result không đổi, worker muộn không resurrect task, hai replica cùng recovery chỉ thu hồi mỗi claim một lần và lỗi giữa transaction phải rollback.

MySQL test hiện còn log **cảnh báo tương thích của Flyway với MySQL 8.4**. Fresh schema, upgrade, validate và các luồng transaction đều pass, nhưng kết quả này không loại bỏ cảnh báo của thư viện. Cần rà/nâng phiên bản Flyway theo dependency baseline của team trước khi chốt production; lượt này không tự nâng framework/dependency ngoài bốn finding.

Test kill/restart lần đầu vướng việc Docker cấp lại cổng host tạm sau restart. Đã xác nhận bằng container probe, sửa fixture lấy lại cổng và chạy lại thành công. Đây là lỗi kịch bản test, không được tính là một lượt recovery thành công trước khi chạy lại.

## CI và rà ảnh hưởng

[verify.yml](D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/.github/workflows/verify.yml) thêm:

- Gate kill/restart backend thật từ JAR đã verify.
- MySQL service `blueai_auth_test` riêng với credential test giả; URL được kiểm tra tên DB trước khi các test có cleanup/DDL chạy.
- Gate auth concurrency và task recovery trên MySQL, tách khỏi test migration cần database trống.
- Tắt tự đọc `.env` trong job RAG.

Đã dùng GitNexus theo hướng dẫn repo để đọc luồng, kiểm tra upstream impact trước khi sửa và detect-changes sau refactor. Authentication là đường critical, vì vậy không dùng kết quả static analysis thay cho kiểm thử đồng thời thật. Index cũng được cập nhật cho các file mới. Các finding từ công cụ phụ thuộc khả năng phân giải caller, không chứng nhận toàn bộ đường chạy đều được bao phủ.

CI mới chỉ được chỉnh và kiểm tra local; chưa push nên **chưa có lượt GitHub Actions mới xác nhận bản vá này**. Không dùng trạng thái workflow của commit cũ để chứng nhận bản vá.

Log xác minh local được giữ để đối chiếu:

- [Backend verify cuối](C:/Users/ductr/AppData/Local/Temp/blueai-fix-final-verify-20261001.log)
- [MySQL migration/concurrency/recovery](C:/Users/ductr/AppData/Local/Temp/blueai-fix-mysql-20261001.log)
- [MySQL vòng lặp thứ 5](C:/Users/ductr/AppData/Local/Temp/blueai-fix-mysql-repeat-5-20261001.log)
- [Diễn tập kill/restart backend](C:/Users/ductr/AppData/Local/Temp/blueai-fix-crash-20261001.log)

## Triển khai an toàn tiếp theo

1. Team review bản vá và chạy CI trên branch trước, chưa merge main ngay.
2. Backup database/upload/index và kiểm tra khả năng restore trước khi nâng cấp.
3. Dùng staging/maintenance window: dừng backend cũ trước khi migration V7 chạy. Không chạy đồng thời backend cũ chưa biết lease và backend mới.
4. Khởi động bản backend mới; Flyway áp V7 tự động, scheduler recovery để bật mặc định.
5. Kiểm tra health, login/refresh/reset, RAG/context, replay và tác vụ mới sau restart; theo dõi `AI_TASK_EXPIRED`/log recovery và quota.
6. Nếu cần rollback, dừng worker trước và có kế hoạch khôi phục dữ liệu/version đồng bộ; không xóa migration đã áp hoặc rollback mù khi task còn chạy.

Chưa rebuild/migrate stack ứng dụng hiện tại của người dùng; toàn bộ kiểm thử database/process crash dùng môi trường riêng. Không dùng API key thật, không tiêu quota model thật, không thay `.env`, không revoke key hoặc sửa lịch sử Git trong lượt này.

Đã dọn container/database test, network test và các volume build/fixture do lượt này tạo. Chỉ dữ liệu giả và artifact có thể build lại bị xóa; log kiểm tra vẫn được giữ, dữ liệu/tài liệu/container ứng dụng hiện tại không bị xóa hoặc thay đổi.

**Kết luận:** bốn vấn đề đã có bản sửa và bằng chứng kiểm thử tương ứng. Đây là cải thiện readiness cho production, **không phải cam kết toàn dự án không còn lỗi**. HTTPS/domain thật, capacity/load/soak test, backup/restore drill, retention task ledger, cảnh báo vận hành và audit Java/OS dependencies vẫn cần theo kế hoạch production hiện có.
