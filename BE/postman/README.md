# BlueAI Postman API tests

Collection dành cho backend Java/Spring Boot tại `BE`, được đối chiếu với controller và DTO hiện tại.

## Import và chạy nhanh

1. Trong Postman chọn **Import**, chọn hai file:
   - `BlueAI.postman_collection.json`
   - `BlueAI.local.postman_environment.json`
2. Chọn environment **BlueAI - Local** ở góc trên phải.
3. Đặt `baseUrl` (mặc định `http://localhost:8080`, không có `/` cuối), `email` và `password` theo tài khoản đã có. Mật khẩu để trống trong file export; lấy từ cấu hình bootstrap của bạn. Không có đăng ký công khai mặc định.
4. Khởi động backend theo `../README.md`. Chọn collection → **Run** để chạy theo thứ tự, hoặc Send từng request từ nhóm **01**.

Mặc định chỉ chạy **00 Health → 01 Auth → 02 Conversations/context → 03 Memories → 98 Negative tests**. Các nhóm còn lại được bỏ qua cho đến khi bạn bật biến tương ứng. Nhóm cơ bản tạo hai hội thoại và tin nhắn test; memory test được xóa ngay trong nhóm 03. Các hội thoại vẫn còn để bạn xem bằng frontend hoặc chạy cleanup.

Login/refresh tự lưu và thay cả `accessToken` lẫn `refreshToken`. Collection kế thừa Bearer token; các API dùng credential riêng đã được override. ID hội thoại, tài liệu, bot, integration client và session cũng tự lưu vào environment. Chạy lại Login để tạo `runId` mới trước một lượt test mới. Một tài khoản chỉ có một phiên đăng nhập; login bằng Postman có thể làm phiên frontend hiện tại hết hiệu lực.

## Bật thêm nhóm test

Sửa giá trị biến environment thành chuỗi `true`, rồi chạy nhóm và những request chuẩn bị cần thiết.

| Biến | Nhóm | Điều kiện / tác dụng |
| --- | --- | --- |
| `runAdmin` | 04, 10 | Login bằng ADMIN. Tạo user test, đổi trạng thái/mật khẩu của user đó; đọc audit, metrics và Gemini usage. |
| `runDocuments` | 05 | ADMIN. Ở request Upload, Body → form-data → `file`, chọn `sample-knowledge.txt` cạnh README này. |
| `runAi` | 06 | Có `conversationId`; RAG/model provider phải hoạt động. |
| `runStudio` | 07 | ADMIN. Tạo bot riêng, lưu cấu hình, preview, publish, test API key và widget. |
| `runIntegration` | 08 | ADMIN tạo client CLASSIFY/EXTRACT. Task dùng credential `bai_` tự lưu; cần model provider. |
| `runIntegrationRag` | 09 | Chạy upload rồi chờ `READY`, tạo/publish bot trước. Cần `documentId`, `botId`, RAG/model provider. |
| `runBrowser` | 90 | Bật cookie jar; `browserOrigin` phải có trong CORS allowlist. Chạy sau các test dùng JWT. |
| `runBrowserPassword` | 90b | Chạy sau toàn bộ nhóm 04 với `runAdmin=true`. Đổi mật khẩu của USER kiểm thử bằng luồng browser và kiểm tra xóa cookie refresh. |
| `runWorker` | 91 | Điền `workerApiKey` thật của môi trường test. Claim thay đổi queue; không chạy song song worker tự động khi kiểm thử thủ công. |
| `runRegister` | 92 | Chỉ khi server bật `APP_PUBLIC_REGISTRATION_ENABLED=true`. Mặc định endpoint không tồn tại. |
| `runCleanup` | 99 | Xóa hội thoại/tài liệu/user test đã lưu ID, revoke integration clients và logout. |
| `runMaintenance` | 99b | Thao tác toàn hệ thống: activate bot, purge auth records và purge document tombstones hết retention. Chỉ bật khi chủ động muốn thực hiện. |

Các cờ áp dụng cho cả **Send** và **Runner** qua pre-request script dùng [`pm.execution.skipRequest()`](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution). Dùng phiên bản Postman hỗ trợ API này. Nếu request bị skip, xem Postman Console để biết cờ hoặc ID còn thiếu. Request bị skip **không phải** test đã PASS.

## Các luồng thường dùng

- **Auth:** 01 Login → Me → Refresh. Để logout, bật `runCleanup`, Send riêng request Logout ở nhóm 99 nếu không muốn dọn dữ liệu.
- Nhóm 01 có GET quota cá nhân. Nhóm 10 có GET tổng quan giới hạn cho ADMIN. Hai API đều đọc trạng thái hiện tại và trả `Cache-Control: no-store`.
- Nhóm 04 tạo USER kiểm thử với các mật khẩu riêng cho từng lượt chạy, rồi cấp mật khẩu tạm bằng `currentPassword` của ADMIN. API trả HTTP 200 cùng `temporaryPassword` và `expiresAt`. Các bước tiếp theo kiểm tra phiên bị giới hạn, buộc đặt mật khẩu cá nhân, thu hồi phiên cũ, từ chối dùng lại mật khẩu tạm và tự đổi mật khẩu bằng mật khẩu hiện tại. Token USER dùng biến riêng để giữ phiên ADMIN xuyên suốt nhóm.
- Nhóm 90b đăng nhập bằng USER đã tạo ở nhóm 04, đổi mật khẩu qua `/api/v1/auth/browser/password/change`, kiểm tra cookie refresh hết hạn và đăng nhập lại bằng mật khẩu mới. Chỉ bật trên tài khoản kiểm thử được tạo trong lượt chạy đó. Nhóm 99 xóa mềm USER này khi bật `runCleanup=true`.
- **Chat RAG:** chạy 01, 02; ADMIN upload ở 05, Send lại Get document cho đến khi `status=READY`; bật `runAi`, chạy 06. `/messages` chỉ lưu tin nhắn; `/ask` tự lưu cả câu hỏi lẫn câu trả lời, không cần gửi cùng câu hỏi vào hai endpoint.
- **Bot API key/widget:** bật `runStudio`, chạy 07. Collection tạo bot starter, tắt Core và để danh sách nguồn widget rỗng để test dialog. Get/Create bot chuẩn bị body Save với revision mới nhất, giữ intents/entities/nodes từ server. Bot chỉ được publish, không tự activate toàn hệ thống. `Read globally active bot` có thể trả 404 nếu hệ thống chưa có bot active; đây là kết quả cần kiểm tra, không bị đổi thành PASS.
- **Task idempotency:** chạy CLASSIFY rồi Replay CLASSIFY; Replay giữ nguyên body/key. Send CLASSIFY lại tạo key mới. Nếu server trả `202 PROCESSING`, dùng Get task result; collection không tự polling. `200` với trạng thái `FAILED` vẫn làm test completion thất bại. EXTRACT có thể trả `NEEDS_REVIEW` khi thiếu dữ kiện bắt buộc.
- **RAG integration:** nhóm 09 tạo client có document/bot grants và binding `postman/demo-company`. Mọi document trong published snapshot phải được cấp quyền và ở trạng thái READY. Giữ nguyên identity của `externalContext` khi gửi tin nhắn cùng session; chỉ thay `messageId` giữa các lượt.
- **Browser auth:** cookie refresh do Postman cookie jar quản lý, không đặt vào JSON. Nhóm 90 dùng biến access token riêng. Login cùng account có thể vô hiệu token nhóm 01; hãy login lại nhóm 01 nếu tiếp tục test Bearer sau đó.

## Đọc kết quả

Mỗi request có assertion HTTP status và JSON (trừ download file/204); các response quan trọng có kiểm tra token, ID hoặc envelope. Nhóm 98 cố ý mong đợi `400/401/403` và chỉ PASS khi nhận đúng lỗi. RAG/model `502/503`, task `FAILED`, hết quota hoặc thiếu grant đều cần xử lý cấu hình/dịch vụ tương ứng; không được coi là test thành công.

Collection bao phủ các route controller Java, có thêm health actuator và biến thể tìm kiếm/idempotency. Không bao gồm API Python RAG trực tiếp, toàn bộ actuator hay socket/SSE. File upload chưa gắn đường dẫn máy cá nhân: chọn file thủ công trước khi chạy nhóm 05. Gemini range dùng `page=1`, các API phân trang khác dùng `page=0`.

`runCleanup` chỉ dọn các ID đang lưu trong environment; chạy lại setup có thể thay ID cũ. Backend không có DELETE bot, nên bot test vẫn tồn tại. Purge/activation được tách sang nhóm 99b. Không export lại environment có token/key/password thật để chia sẻ.

## Trạng thái kiểm tra của bộ collection

Bộ collection có 95 request trong 18 nhóm. Chạy `node ops/test-postman-collection.mjs` từ `BE` để kiểm tra cú pháp script, biến tham chiếu, credential trống khi export và hợp đồng phục hồi mật khẩu. CI chạy kiểm tra này trong job backend. Kết quả chạy API thực tế được ghi trong `validation-summary.json`, kèm các nhóm đã bật và giới hạn phạm vi kiểm thử.
