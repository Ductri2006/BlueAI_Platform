# Kiểm chứng API key — 21/10/2026

> Cập nhật 23/10/2026: quyền xem/copy và chỉnh quota bot key đã mở cho admin đang hoạt động trong kho quản trị dùng chung. Các mô tả chỉ chủ sở hữu được đọc bên dưới là hành vi tại ngày kiểm thử cũ; xem `../../qa/SHARED_ADMIN_WORKSPACE.md` cho hợp đồng hiện tại. Conversation vẫn riêng theo tài khoản.

- Backend compile: thành công với Java 25, target Java 21 của dự án.
- Backend: 83 tests qua, 0 lỗi, 0 skip trong 9 suite: PublishedBotApiTest, CepIntegrationApiTest, AiTaskApiIntegrationTest, AiTaskDispatchLeaseIntegrationTest, AiTaskRecoverySchedulerTest, AuthSecurityIntegrationTest, FlywayMigrationIntegrationTest, BotStudioIntegrationTest, WidgetRagDialogIntegrationTest.
- Frontend: TypeScript + Vite production build thành công; 13 test files, 130 tests qua.
- Kiểm tra trình duyệt trên database H2 tạm: tạo bot → nhập quota 15 → Phát hành → API key tự xuất hiện → nút copy hoạt động, định dạng key đúng, UI hiển thị quota đã phát hành là 15. Ảnh `custom-api-key.png` che key bằng password input.
- Test backend kiểm tra quota theo key giữa nhiều khách, key khác độc lập, reset cửa sổ, 8 request đồng thời với quota 3 chỉ cho 3 request qua; đồng thời xác nhận quota widget không ghi đè quota key.
- Test backend xác nhận key không gọi được admin/task nâng cao; chủ sở hữu khác không đọc được key; user bị disable thì key bị chặn; ID BIGINT không bị chuyển số; session khách tách biệt và replay/conflict theo messageId.
- Kiểm tra whitespace trên source thay đổi: qua. File `application.properties` có thay đổi staged/unstaged từ trước, không chỉnh trong công việc này; kiểm tra toàn checkout vẫn có cảnh báo whitespace/CRLF ở file đó và build outputs được Git theo dõi.

GitNexus đã chạy impact trước các lớp/hàm được sửa, bổ sung context khi tên hàm trùng. `assertTaskAccess` có cảnh báo HIGH nên giữ nguyên. `detect_changes(scope=all)` báo CRITICAL cho diff tổng hợp: 73 file gồm source, tài liệu, file cấu hình đã sửa từ trước và build artifacts; không coi đó là đánh giá LOW cho toàn patch. Các file mới được rà qua source và các suite nêu trên; chưa commit.

Khi khởi động ứng dụng để thử UI, cấu hình dự án ban đầu trỏ vào MySQL local `blueai_dev`; Flyway đã áp dụng thành công V11–V13 trên MySQL 9.4. Tiến trình đó đã dừng; tài khoản admin thử nghiệm phát sinh đã được xóa bằng điều kiện email/tên/role/thời điểm tạo cụ thể, 1 bản ghi. Không rollback các migration bổ sung. Phần kiểm thử UI tiếp theo dùng H2 memory riêng với cấu hình chỉ định rõ. Các suite MySQL upgrade theo biến môi trường chưa được chạy trong lượt này.

Chưa sửa code CEP, chưa gọi Zalo/WhatsApp thật và chưa triển khai production. Checklist phần CEP/nghiệm thu nằm tại `../../CEP_INTEGRATION_CHECKLIST.md`.
