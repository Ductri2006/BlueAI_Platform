# Báo cáo lấy code mới và rà lỗi — 01/10/2026

## Kết luận

Đã cập nhật code local từ `6cd5a5c` lên `8f09d0077118555ec9370fe5ec8aeee67ec44d69`, lấy các thay đổi mới nhất đã thấy trên `origin/vibe_coding`. Branch này bao gồm cập nhật của `FE_Debugg` và tính năng conversation context. Việc cập nhật không có xung đột và không cần tạo merge commit.

Code có thể tiếp tục dùng để phát triển và kiểm thử, nhưng chưa đủ bằng chứng để mở production: CI của chính commit này còn đỏ, có hai lỗi bộ kiểm thử đã tái hiện và một trường hợp thu hồi phiên cần điều tra tính ổn định. API AI mới còn thiếu cơ chế phục hồi tác vụ đang chạy sau khi tiến trình bị dừng.

Lượt này lấy code và rà lỗi; chưa sửa logic ứng dụng, chưa commit/push hoặc đưa code lên `main`.

## Trạng thái Git

- Repository: `D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo`.
- Branch làm việc: `feature/conversation-context`.
- Remote chứa bản mới: `origin/vibe_coding`, commit `8f09d00`.
- Cách cập nhật: `git fetch origin --prune`, kiểm tra quan hệ lịch sử, sau đó `git merge --ff-only origin/vibe_coding`.
- Mốc dự phòng trước cập nhật: `backup/pre-team-pull-2026-10-01`, trỏ tới `6cd5a5c`.
- Branch local hiện đi trước `origin/feature/conversation-context` 10 commit; đây là commit đã có của team được lấy về, không phải 10 commit mới do lượt rà này tạo.
- `main` không được thay đổi. File `.env` local không được chỉnh sửa.

## Kết quả kiểm tra

| Kiểm tra | Kết quả | Phạm vi và giới hạn |
| --- | --- | --- |
| Frontend Vitest | **43/43 pass** | Bộ test hiện có của frontend. |
| Frontend TypeScript/Vite | **Build pass** | Tạo được bản build production. |
| Backend Maven verify, Java 21 | **174 pass, 1 skip, 0 failure/error** | Tổng 175 test. Chạy từ thư mục build mới trong container Maven; test MySQL có chủ ý skip vì không khai báo DB test riêng. |
| Test refresh/reset chạy lặp riêng | **5/5 lần pass local** | Cùng test từng fail trên CI; kết quả này chưa loại trừ race hoặc sự khác biệt về lịch chạy transaction. |
| MySQL migration trên GitHub | **Pass** | Job riêng của đúng commit `8f09d00`, kiểm tra schema mới và upgrade V5 → V6 trên MySQL 8.4. Không áp migration lên database local của người dùng. |
| Python unittest, Python 3.13 | **58/59 pass** | Tái hiện một failure của test phục hồi chỉ mục FAISS trong môi trường không có API key. |
| Python pip check | **Pass** | Môi trường Python tách biệt, cài theo requirements hiện tại. |
| Python pip-audit | **Không phát hiện advisory đã biết** | Sau khi cập nhật pip của môi trường kiểm tra lên 26.2.1. Không bao phủ OS packages hoặc Java dependencies. |
| npm audit | **0 advisory** | Quét cả dependency chạy ứng dụng và dependency phát triển theo lockfile. |
| Production Compose | **Pass** | Nội suy với credential giả; kiểm tra public ports, health, restart và dependency startup. |
| Nginx ingress, local Docker | **Pass** | Private API boundary, upload 2/20 MB, từ chối request quá lớn, forwarded-IP spoof, login quota, JSON 429, Retry-After và OPTIONS. |
| Fixture Nginx trên Linux | **Tái hiện 500 → 200** | Thư mục mode 700 gây Permission denied/HTTP 500; mode 755 cho HTTP 200. Xác định lỗi của fixture CI. |
| Test metric/load denominator và Promtail config | **Pass** | Kiểm tra logic/config; không phải load test hoặc alert delivery thật. |
| Kiểm tra mẫu API key trong file Git hiện tại | **Không có kết quả khớp** | Chỉ các mẫu Google/OpenAI thông dụng; không phải secret scan toàn lịch sử hoặc chứng nhận không có mọi loại secret. |
| Build artifact bị track | **Không có file trong `BE/target`** | Kiểm tra bằng Git ở commit hiện tại. |

GitHub workflow được đối chiếu: [Verify của commit 8f09d00](https://github.com/Ductri2006/BlueAI_Platform/actions/runs/36672431458). Frontend và MySQL migration pass; backend, Python và deployment-config fail.

## Các vấn đề cần xử lý

### 1. [P1] Thu hồi refresh token khi đổi mật khẩu chưa ổn định ở tình huống chạy đồng thời

**Bằng chứng:** CI fail tại `AuthPasswordConcurrencyIntegrationTest.resetOverlappingAnUncommittedRefreshInvalidatesTheRotatedSession`, dòng 166: sau khi admin đổi mật khẩu và hai transaction kết thúc, `authService.refresh(rotated.refreshToken())` vẫn không ném lỗi như test yêu cầu. Full backend local trên build mới và 5 lần chạy riêng test này đều pass, vì vậy cần phân biệt vấn đề cạnh tranh transaction với việc test chưa khống chế chính xác thứ tự thực thi; chưa khẳng định mọi lần đổi mật khẩu đều gặp lỗi và chưa tái hiện được failure này tại local trong lượt rà.

**Nguồn cần rà:**

- `BE/src/main/java/com/blueai/auth/service/AuthService.java:119`: refresh khóa bản ghi token, kiểm tra tài khoản rồi tạo token tiếp theo.
- `BE/src/main/java/com/blueai/auth/service/AdminUserService.java:109`: setPassword khóa bản ghi user, tăng auth version và revoke refresh token bằng bulk update.
- `BE/src/main/java/com/blueai/auth/repository/RefreshTokenRepository.java`: khóa token và bulk revoke.
- `BE/src/test/java/com/blueai/auth/service/AuthPasswordConcurrencyIntegrationTest.java:140`: latch hiện đợi tới bước encode mật khẩu, chưa chứng minh bulk revoke đã bắt đầu chờ transaction refresh.

**Tác động:** Có bằng chứng CI rằng bảo đảm thu hồi refresh token trong tình huống này chưa ổn định. Nếu là race của ứng dụng, một refresh token tồn tại trước reset có thể tiếp tục được dùng sau reset. Test decode access token cũ đi trước dòng 166 không phải assertion đã fail.

**Hướng xử lý:** Đồng bộ login/refresh/reset bằng cùng cơ chế khóa user với thứ tự khóa nhất quán để tránh deadlock; hoặc ràng buộc refresh token với auth version của phiên lúc phát hành và kiểm tra khi refresh. Bổ sung kiểm thử concurrency có thứ tự xác định trên database thật. Không sửa bằng cách bỏ assertion hoặc tăng số lần tự động retry test.

### 2. [P1] Tác vụ AI có thể giữ trạng thái PROCESSING sau khi backend bị dừng

**Bằng chứng từ code:** `AiTaskService.submit` commit ledger ở trạng thái `PROCESSING` trước khi gọi provider. Nếu tiến trình dừng trong khoảng giữa claim và finalize, bản ghi này còn tồn tại sau restart. Kiểm tra concurrency tại dòng 54–55 đếm mọi `PROCESSING` mà không xét tuổi bản ghi; `get` tại dòng 101 và replay cùng idempotency key cũng tiếp tục trả trạng thái đang xử lý. Chưa thấy lease, expires-at, recovery scheduler hoặc bước chuyển trạng thái các tác vụ cũ trong code ứng dụng hiện tại.

**Nguồn:** `BE/src/main/java/com/blueai/ai/task/AiTaskService.java:54`, `:57`, `:101`; migration V5 cho `ai_task_ledger`.

**Tác động:** Bốn tác vụ bị bỏ dở của cùng integration client sẽ chiếm đủ bốn slot và khiến yêu cầu mới nhận `AI_CONCURRENCY_LIMIT` kể cả sau khi server đã khởi động lại. Đây là finding từ đọc code, chưa diễn tập kill/restart ứng dụng trong lượt này.

**Hướng xử lý:** Thêm lease/deadline lưu trong DB và cơ chế thu hồi tác vụ quá hạn sau restart hoặc khi claim tác vụ mới. Finalize phải có fencing/attempt ID để completion cũ không ghi đè kết quả sau khi lease đã hết. Với request có thể đã gọi provider, không tự retry mù: trạng thái cuối cần thể hiện lỗi/interrupted, giữ idempotency và tránh phát sinh chi phí AI lần hai.

### 3. [P2] Bộ test phục hồi FAISS phụ thuộc API key thật

**Bằng chứng:** Python chạy 59 test, một failure tại `tests/test_api.py:308`. Test mock `FAISS.load_local` nhưng không mock `runtime._embeddings`. Khi đường phục hồi gọi `_reload_durable_index_after_failure`, biểu thức `self._embeddings()` được thực thi trước `load_local`; constructor embedding yêu cầu key, làm `_vectorstore` còn `None`.

**Nguồn:** `feature-rag-chatbot/rag-chatbot/tests/test_api.py:296`; `feature-rag-chatbot/rag-chatbot/api.py:428` và `:436`.

**Phân loại:** Đây là lỗi isolation của bộ test đã tái hiện trên cả CI và local không có key. Chưa phải bằng chứng đường phục hồi production có key hợp lệ sẽ thất bại.

**Hướng xử lý:** Mock embedding trong toàn bộ đoạn phục hồi và mô phỏng restart của test. Giữ nguyên các assertion kiểm tra dữ liệu durable và việc purge retry; test phải pass khi hoàn toàn không có key/provider/network.

### 4. [P2] Fixture của test Nginx không đọc được trên Linux

**Bằng chứng:** `mkdtempSync` tạo thư mục fixture với mode 700; thư mục được bind-mount vào webroot nhưng Nginx worker chạy bằng user khác. CI trả HTTP 500 ở request `/index.html`. Lượt rà tái hiện bằng volume Linux: mode 700 có log `Permission denied` và internal redirection cycle; đổi thư mục fixture sang 755 thì cùng config trả 200.

**Nguồn:** `BE/ops/test-nginx-ingress.mjs:15`.

**Phân loại:** Lỗi quyền fixture CI; test ingress local Windows pass. Không kết luận Nginx production mặc định trả 500: frontend Dockerfile copy bản dist đã build, không sử dụng thư mục mkdtemp này.

**Hướng xử lý:** Cho Nginx quyền đọc/traverse đúng thư mục fixture, ví dụ chmod 755 cho thư mục và 644 cho file sau khi tạo. Chỉ áp dụng cho dữ liệu test tạm, không mở quyền secret hoặc đổi Nginx sang chạy root. Giữ gate thực chạy trên Linux trong CI.

## Vấn đề môi trường local đã phân biệt với lỗi code

Lần chạy Maven đầu dùng `BE/target` cũ gặp hai class main application trùng bean name: package cũ `com.blueai.auth.BlueAiAuthApplication` vẫn còn compiled class sau khi source đã chuyển thành `com.blueai.BlueAiAuthApplication`. Đây là build artifact cũ; không phải 103 lỗi chức năng mới.

Lần kiểm chứng chính dùng target mới trong volume kiểm tra tách biệt, Maven verify pass. Khi team pull bản này rồi chạy Maven trực tiếp với thư mục target cũ, cần dùng build sạch (`mvn clean verify`). Không xóa database, document storage hoặc volume ứng dụng để xử lý vấn đề này.

Docker Desktop đã được khởi động để chạy kiểm tra container. Các container/volume tạm của thí nghiệm quyền Nginx đã được xóa sau kiểm tra; chúng chỉ chứa fixture do lượt rà tạo, không phải dữ liệu ứng dụng.

GitNexus được làm mới theo hướng dẫn repository ở chế độ `--index-only`, không ghi lại AGENTS/CLAUDE hoặc code. Graph được dùng để đối chiếu luồng refresh và API AI; không xem kết quả graph là bằng chứng đầy đủ vì công cụ cảnh báo một số call site/process không được liên kết.

## Giới hạn của lượt rà

- Không gọi Gemini thật và không dùng quota/key provider để chạy unittest.
- Chưa kiểm tra giao diện đầu-cuối trên bản image mới, deploy HTTPS staging, chạy tải cao/soak, diễn tập backup/restore hoặc reboot cả stack.
- Các container ứng dụng local tự chạy lại khi Docker khởi động có thể dùng image cũ; việc chúng đang Up không được dùng làm bằng chứng bản `8f09d00` đã deploy thành công.
- Test migration MySQL được xác nhận từ job GitHub riêng của chính commit; lượt này không thay đổi schema database đang dùng của người dùng.
- Quét npm/Python không thay thế audit dependency Java hoặc OS/container image.
- Báo cáo này ghi kết quả review, không xác nhận hệ thống không còn bất kỳ lỗi nào.

## Thứ tự đề xuất tiếp theo

1. Điều tra và làm ổn định bảo đảm thu hồi refresh token; thêm phục hồi tác vụ AI bị dừng.
2. Sửa hai fixture Python/Nginx, chạy lại toàn bộ CI của bản sửa.
3. Kiểm tra stack image mới trên môi trường thử riêng với tài liệu knowledge base và hội thoại giữa các phiên.
4. Chốt release/deploy sau khi CI và các kiểm tra staging bắt buộc đã đạt.
