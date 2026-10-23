# Checklist BlueAI ↔ CEP

Ngày cập nhật: 21/10/2026. Luồng mặc định: phát hành bot → copy API key → CEP gọi chat → CEP gửi trả khách.

## BlueAI — đã triển khai

- [x] Tự sinh API key riêng cho bot khi phát hành lần đầu; giữ nguyên khi phát hành lại.
- [x] Ô key được che và nút **Sao chép API key** tại Custom/Tích hợp; tải lại trang vẫn copy được.
- [x] Quota mặc định 30 request/phút/key, cấu hình mặc định bằng `APP_API_KEY_REQUESTS_PER_MINUTE`.
- [x] Sửa quota 1–1000 trong Custom, lưu bản nháp và áp dụng khi phát hành.
- [x] Bộ đếm database theo key; dùng chung giữa nhiều khách/replica; vượt giới hạn trả 429 + Retry-After.
- [x] API đơn giản nhận userId và message, hỗ trợ thêm conversationId/messageId/channel.
- [x] Xác định bot bằng key, chạy cấu hình dialog/Core đã phát hành và trả answer cùng IDs.
- [x] Tách trạng thái theo key/khách/hội thoại/kênh, replay messageId trong cùng phiên.
- [x] Key mã hóa tại database, xác thực bằng digest; admin đang hoạt động cùng xem/copy key của bot trong kho quản trị dùng chung, response không cache. Conversation vẫn riêng từng tài khoản.
- [x] Cô lập key bot khỏi JWT quản trị và API task nâng cao.
- [x] Giữ API integration nâng cao cho CLASSIFY / EXTRACT / RAG_ANSWER và BOT_CHAT có grant chi tiết.
- [x] Thêm migration V11–V13 và kiểm thử quyền, context, replay, rate limit đồng thời.

## CEP — tích hợp theo nút nhân viên đã triển khai

- [x] Backend hai folder CEP đã có connector, cấu hình key mã hóa theo công ty/kênh và JWT/ACL hiện tại.
- [x] Frontend Conversation có **Nhờ BlueAI**, chọn tin khách, xem bản nháp và **Dùng câu trả lời** vào ô soạn tin hiện có.
- [x] Backend xác minh khách/kênh/hội thoại và nhân viên đang phụ trách; tự lấy nội dung từ database.
- [x] Gọi HTTP thành công với key bot BlueAI phát hành trên H2 tách biệt: dialog nhiều lượt, replay và quota 429.
- [x] Thử component React thật trong trình duyệt với dữ liệu/auth CEP giả lập; không gửi qua kênh thật.

Hướng dẫn triển khai và nghiệm thu chi tiết: [`../../messaging-marketplace-backend/docs/BLUEAI_CEP_INTEGRATION.md`](../../messaging-marketplace-backend/docs/BLUEAI_CEP_INTEGRATION.md).

## CEP backend — phần tự động hóa webhook còn cần làm

- [x] Thêm cấu hình URL tại server và API key mã hóa theo company/channel, không đưa vào URL/frontend bundle.
- [x] Chuẩn hóa `userId = String(contactId)`; conversationId gồm company/channel/conversation.
- [x] Chỉ chấp nhận tin văn bản khách đã lưu; ảnh/audio/file cần adapter riêng nếu muốn hỗ trợ.
- [ ] Tạo queue/job gọi `POST /api/v1/ai/tasks/chat`; giữ messageId của tin nhận ổn định.
- [ ] Lưu mapping incoming message ↔ job ↔ response/session/release ↔ outgoing message và delivery status.
- [ ] Serialize theo hội thoại. Retry lỗi mạng/409 đang xử lý bằng cùng IDs/body; 429 đọc Retry-After và backoff.
- [x] Kiểm tra response đúng userId/conversationId/messageId trước khi trả frontend.
- [x] Bản nháp vào ô soạn tin, nhân viên gửi qua pipeline CEP hiện tại. Nhánh tự động gửi còn cần triển khai.
- [ ] Chống tạo/gửi tin trùng ở CEP, kể cả khi job bị chạy lại sau lúc đã gửi thành công.
- [ ] Khi nhân viên tiếp quản, ngừng AI và chặn câu trả lời đến muộn.
- [ ] Rule ticket/tag/payment/order/customer/flight tiếp tục do CEP thực thi; chỉ dùng nhãn/schema được whitelist để rẽ nhánh.
- [ ] Nếu cần node JSON phân loại/trích xuất/RAG độc lập, dùng API task nâng cao có credential/capability đúng; không dùng bot key để gọi API admin.
- [ ] Xác nhận điểm hook trong `PEACOM_CORE` / dependency `@peacom/core`; source hai folder hiện chưa chứa toàn bộ core.

## CEP frontend — trạng thái

- [x] Form key che/masked, trạng thái kênh và lỗi khi gọi AI; URL do server cấu hình.
- [ ] Thao tác “Chuyển BlueAI” / “Chuyển nhân viên”, kèm trạng thái đang xử lý/lỗi.
- [ ] Hiển thị tin AI qua message list hiện có, có nguồn AI trong audit nếu cần.
- [x] Chặn cấu hình/xử lý khi thiếu quyền; backend kiểm tra công ty và nhân viên phụ trách.

## Nghiệm thu liên thông

- [ ] Hai company, hai khách, hai kênh đồng thời không dùng chung lịch sử hoặc trả lời nhầm.
- [ ] Key sai/khác bot bị chặn; key đúng chỉ chạy đúng bot đã phát hành.
- [ ] Quota đếm theo key, khách dùng chung key dùng chung quota, key khác độc lập, cửa sổ mới phục hồi.
- [ ] Dialog nhiều lượt, RAG theo tài liệu và chuyển nhân viên hoạt động trên Zalo và WhatsApp thật.
- [ ] Webhook trùng, mất response, retry job và lỗi gửi ra kênh không tạo hai tin trả lời.
- [ ] Thay đổi quota/bot rồi phát hành có hiệu lực, giữ key và không gửi kết quả cũ sai ngữ cảnh.
- [ ] Chạy kiểm thử trên MySQL/Redis của môi trường triển khai và cấu hình TLS/network phù hợp.

Chi tiết HTTP, giới hạn và ví dụ: [`../BE/CEP_INTEGRATION.md`](../BE/CEP_INTEGRATION.md). Các ô nghiệm thu thực tế và tự động hóa chưa đánh dấu vì chưa chạy với database/auth CEP và Zalo/WhatsApp thật.
