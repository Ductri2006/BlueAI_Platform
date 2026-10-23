# Dùng chung dữ liệu admin, giữ conversation riêng — 23/10/2026

## Kết quả

Admin đang hoạt động cùng truy cập tài liệu do admin cung cấp, cấu hình dialog/bot và API key bot đã phát hành. Mỗi bot vẫn có một key, một bộ quota chung; không nhân bản tài liệu, bot hoặc key theo tài khoản. Các conversation cá nhân trong Hỏi BlueAI và lịch sử/bộ nhớ vẫn theo owner tài khoản.

Tài liệu và bot/dialog đã có truy vấn workspace chung trong `DocumentService` và `BotStudioService`. Điểm thiếu được sửa trong lượt này là `PublishedBotApiService.settings/saveSettings`: thay kiểm tra chủ bot bằng kiểm tra admin đang hoạt động và bot thuộc kho quản trị dùng chung. Giữ nguyên `requireOwner/requireLiveKey` của runtime, mã hóa key, rate limiter, ID khách/hội thoại/kênh và grant của integration credential nâng cao.

Không cần migration hay thao tác chuyển dữ liệu: bản ghi của admin1 và admin khác được trả qua cùng truy vấn có sẵn. Không đổi chủ sở hữu gốc, không xoay key; CEP tiếp tục dùng key đã cấu hình. Backend phải chạy bản build mới và trình duyệt cần tải lại để cập nhật dữ liệu đã cache.

## Kiểm tra đã qua

| Suite | Tests | Kết quả |
| --- | ---: | --- |
| PublishedBotApiTest | 9 | Qua |
| SharedAdminWorkspaceIntegrationTest | 9 | Qua |
| BotStudioIntegrationTest | 13 | Qua |
| WidgetRagDialogIntegrationTest | 18 | Qua |
| ConversationContextIntegrationTest | 6 | Qua |
| AuthSecurityIntegrationTest | 5 | Qua |
| AiTaskApiIntegrationTest | 17 | Qua |
| CepIntegrationApiTest | 9 | Qua |
| **Tổng** | **86** | **0 failures/errors/skips** |

- Hai admin đăng nhập bằng JWT thật riêng biệt cùng đọc được key đã tồn tại.
- Admin B lưu quota, admin A đọc thấy cùng bản nháp; B phát hành lại giữ key cũ và áp dụng quota mới.
- Không tạo thêm key; owner bot vẫn giữ nguyên. Hai khách CEP dùng cùng key vẫn có session riêng và dùng chung quota.
- Conversation của A/B không xuất hiện trong danh sách của nhau; không mở, đọc tin nhắn, hỏi tiếp hoặc xóa conversation của admin khác.
- Admin xem/sửa tài liệu và dialog chung; bản nháp cũ bị 409 để tránh ghi đè khi hai admin sửa đồng thời. Áp dụng bot chính không xóa bot khác.
- Người dùng thường, admin bị khóa/xóa và caller chưa đăng nhập không được truy cập key. Bot cũ riêng của USER không bị đưa vào kho admin.
- Scope máy với máy của task, cấp tài liệu, session và quota tiếp tục qua các kiểm tra hồi quy.

Test dùng H2 trong bộ nhớ và fixture provider; không gọi Gemini, không sửa database MySQL/production. Bộ test MySQL `SharedAdminWorkspaceMySqlIntegrationTest` chưa chạy ở lượt này; không tính là đã qua. Không thay đổi frontend hoặc khởi động dịch vụ production.

Khi chạy regression bằng `spring.config.location` chỉ trỏ tới test properties, hai kiểm tra actuator ban đầu trả 404 vì thiếu cấu hình expose metrics/Prometheus. Đã chạy lại hai suite tương ứng với các cờ quản lý như cấu hình main: `management.endpoints.web.exposure.include=health,info,metrics,prometheus`, `management.endpoint.prometheus.access=read-only`, `management.prometheus.metrics.export.enabled=true`; kết quả cuối đều qua. Không sửa cấu hình production để xử lý lỗi test đó.

## Phạm vi GitNexus

Đã dùng query/context trước khi sửa; impact `PublishedBotApiService` LOW với hai nơi nhập trực tiếp là Studio service và controller key. `saveSettings` LOW, gọi trực tiếp từ controller. Tên `settings` trùng với helper test; đã bổ sung context theo đường dẫn service và dùng impact lớp để rà cả endpoint đọc/lưu. Test class/cleanup LOW.

`requireOwner` báo HIGH, ảnh hưởng endpoint đọc/lưu/chat. Đã cảnh báo và **giữ nguyên** hàm này; thêm guard riêng cho quản lý workspace admin để không nới xác thực runtime của CEP.

`detect_changes(scope=all)` đã chạy. Diff tổng vẫn báo CRITICAL vì có nhiều thay đổi tích hợp/config/build artifacts từ các lượt trước; không đánh giá toàn checkout là LOW. Các file mới từ lượt tích hợp trước (trong đó có service/test key) chưa được Git theo dõi, nên được rà riêng cùng compile/tests. Không tạo commit, không gộp hoặc khôi phục các thay đổi khác.

Checklist vận hành: [`../../qa/SHARED_ADMIN_WORKSPACE.md`](../../qa/SHARED_ADMIN_WORKSPACE.md).
