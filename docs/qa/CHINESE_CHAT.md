# BlueAI — tiếng Trung giản thể

## Phạm vi và trạng thái

Đợt này mở rộng runtime dùng chung từ Việt–Anh sang Việt–Anh–Trung giản thể (`zh-CN`). Phần giao diện ứng dụng quản trị vẫn dùng tiếng Việt; admin có thêm bản dịch Trung cho Dialog. Không sửa code/token/webhook/menu Telegram gateway.

Đây là tài liệu nghiệm thu bản ứng viên. Chưa xác nhận tiếng Trung trên production hoặc Telegram thật khi chưa hoàn thành các gate bên dưới. Kết quả thử sinh câu trả lời không thay thế kết quả thử truy xuất embedding.

## Chọn ngôn ngữ

1. Yêu cầu rõ như `请用中文回答。`, `Please answer in Chinese.` hoặc `Trả lời bằng tiếng Trung` được nhớ trong hội thoại.
2. Yêu cầu ngôn ngữ đã chỉ định được giữ cho đến khi khách yêu cầu đổi rõ ràng. `请用英文回答。` chuyển sang Anh; `请用越南语回答。` chuyển sang Việt.
3. Với phiên tự động, câu hỏi rõ nghĩa được nhận diện lại. Câu ngắn, tên máy, mã gói, URL, số điện thoại và lời xác nhận giữ ngôn ngữ trước đó.
4. Phiên mới không đủ căn cứ dùng mặc định Việt. Không suy quốc gia/địa chỉ của khách từ ngôn ngữ.
5. Chỉ đọc lịch sử tin nhắn của khách trong phiên đó; câu trả lời cũ của bot và nội dung KB không thiết lập lựa chọn ngôn ngữ.

Bộ nhận diện cục bộ dùng quy tắc tương thích VI/EN và Lingua 1.2.2. Giới hạn mẫu đầu vào nhận diện 1.000 ký tự; câu hỏi gốc vẫn được giữ nguyên. Chỉ nạp nhóm Việt/Anh/Trung/Nhật/Hàn; Nhật/Hàn giúp tránh ép chữ viết Đông Á sang Trung, không có nghĩa đã bật trả lời hai ngôn ngữ này. Kết quả nhận diện câu ngắn hoặc chỉ có chữ Hán chung giữa Nhật/Trung không bảo đảm chính xác tuyệt đối.

`APP_CHAT_CHINESE_ENABLED=false` tắt nhận diện/lựa chọn Trung và chuẩn hóa trạng thái `zh-CN` thành mặc định Việt trong bản ứng viên. Tham số JVM `blueai.chat.chinese-enabled` dùng cho test. Không thay đổi tài liệu hay lịch sử tin nhắn. Đợt này dùng các trường JSON và dictionary V17 hiện có, không thêm migration/cột tiếng Trung.

## RAG và chi phí

- Truy xuất câu hỏi gốc bằng embedding đã cấu hình; không đổi model hoặc tái tạo index chỉ để thêm ngôn ngữ.
- Chỉ dùng tài liệu READY đúng quyền/allowlist của bot.
- Prompt chỉ định Trung giản thể, dịch thao tác/menu từ nguồn Việt, giữ SKU/APN/URL/số liệu.
- Phản hồi RAG bị nhận diện lệch ngôn ngữ khi yêu cầu Trung trả lỗi 502 `LLM_RESPONSE_LANGUAGE_MISMATCH` với thông báo Trung; không tự gọi sinh lại. Kiểm tra này không chứng minh mọi mệnh đề hoặc mọi thuật ngữ trong câu trả lời đã đúng; vẫn cần QA nghiệp vụ và người biết tiếng Trung.
- Nhận diện offline không dùng token Gemini. Embedding, completion và dịch Dialog dùng cơ chế ghi nhận token hiện có; không quy đổi ký tự Trung thành token bằng công thức cố định.
- Thiếu bằng chứng không được tạo giá, chính sách hay gói cước. Chất lượng còn phụ thuộc tài liệu và bản phát hành bot.

## Chuẩn bị Dialog

1. Tạo/chọn bản nháp riêng, không ghi đè bot kinh doanh chỉ để thử.
2. Thêm câu mẫu Trung vào intent và từ đồng nghĩa Trung vào entity. Giữ giá trị chuẩn dùng chung (ví dụ synonym `日本` → canonical `Japan`).
3. Mở Luồng hội thoại → Phản hồi → Bản dịch câu trả lời → Tiếng Trung giản thể.
4. Điền lời đáp, câu hỏi dữ kiện thiếu và lời đáp khi chưa rõ. Giữ nguyên biến `{{destination}}` hoặc biến tương ứng của bước.
5. Lưu nháp, tải lại đọc lại, thử cả ba ngôn ngữ. Khi sẵn sàng mới phát hành và nghiệm thu phiên mới.

Có bản dịch: dùng trực tiếp. Thiếu bản dịch với AI bật: dịch qua client chung và kiểm tra giá trị quan trọng. Chỉ Dialog/AI tắt: thiếu bản dịch dùng thông báo hỗ trợ bằng Trung. Không tự bổ sung câu mẫu nghiệp vụ cho các bot đã phát hành; admin cần rà cấu hình của từng bot. Các lệnh `取消`/`重新开始` hủy luồng, xác nhận/từ chối Trung chỉ khớp nguyên lời đáp trong ngữ cảnh xác nhận.

## Nhập liệu

Composer, mini-chat, Thử BlueAI và widget phải giữ chữ đang ghép bằng IME; Enter xác nhận chữ không gửi câu chưa hoàn chỉnh. Enter tiếp theo gửi; Shift+Enter giữ quy tắc xuống dòng ở textarea. Test tự động composition events không thay thế thử bàn phím/IME thật trên thiết bị khách.

## Bộ kiểm thử và gate phát hành

- `MultilingualLanguagePolicyTest`: corpus 100 câu trong `language-acceptance.csv`, 50 Trung/25 Anh/25 Việt; giữ phiên, đổi ngôn ngữ, phủ định/trích dẫn, Nhật/Hàn, mã gói và tắt cờ.
- `ChineseChatIntegrationTest`: lưu/phát hành, allowlist nguồn, entity Trung không có dấu cách, thoát slot cũ sang câu RAG mới, tách khách, idempotency, DTO, API public và chặn output lệch ngôn ngữ.
- Chạy bộ hồi quy Việt–Anh và backend/FE đầy đủ; ghi rõ ca bỏ qua do điều kiện môi trường.
- Chạy integration trên MySQL 8.4 riêng, không dùng database production. Test Trung cho phép cấu hình `BLUEAI_CHINESE_QA_DB_URL` và `BLUEAI_CHINESE_QA_DB_DRIVER`.
- Thử Gemini thật bằng dữ liệu giả lập có đánh dấu, rồi thử KB thật qua bản phát hành QA. Nếu embedding/LLM trả 429, dừng lượt gọi và ghi chưa nghiệm thu, không đổi key/model để vượt hạn mức.
- Kiểm tra frontend desktop/tablet/mobile, sáng/tối, bàn phím, chữ dài và IME; kiểm tra trực tiếp web/Telegram khi bản ứng viên đã được phát hành an toàn.
- CI đạt, backup/rollback sẵn sàng và các gate live hoàn tất mới merge/deploy. Giữ nguyên worker, index, quyền nguồn và Telegram gateway ngoài phạm vi.

## Kịch bản manual tối thiểu

| Tin nhắn / thao tác | Kỳ vọng |
|---|---|
| `如何安装旅游eSIM？` | Câu trả lời Trung giản thể, chỉ dùng nguồn được phép |
| `iPhone 15` | Giữ Trung và chủ đề đang hỏi |
| `请用英文回答。` | Chuyển và giữ Anh |
| `请用越南语回答。` | Chuyển và giữ Việt |
| `Tôi muốn mua eSIM Trung Quốc` ở phiên mới | Việt, không đổi theo quốc gia |
| `套餐咨询日本` với intent/synonym đã cấu hình | Nhận đúng intent và canonical Japan |
| Câu hỏi mới về mất mạng khi đang chờ quốc gia | Bỏ dữ kiện tác vụ cũ, chuyển RAG nếu AI bật |
| Hỏi giá không có trong tài liệu | Báo thiếu thông tin bằng ngôn ngữ phiên |
| Hai khách dùng Anh/Trung | Lịch sử và ngôn ngữ riêng |
| Phát lại cùng requestId | Không sinh/ghi lượt lần thứ hai |
| Dịch mẫu `TEST-JP-7D`, `vmobile.jp`, `10 GB`, `7 ngày` | Giữ mã/APN/dung lượng, thời hạn tương đương `7天` |

Giản thể là phạm vi nghiệm thu hiện tại. Phồn thể, Pinyin, giọng nói, toàn bộ menu đa ngôn ngữ và chất lượng thương mại của mọi kịch bản cần kiểm tra riêng trước khi công bố hỗ trợ.
