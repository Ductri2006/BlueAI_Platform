# Hỗ trợ Việt Anh trong BlueAI

Ngày cập nhật 27 tháng 11 năm 2026.

BlueAI chọn ngôn ngữ trả lời cho từng hội thoại và áp dụng trong chat web, Thử BlueAI, widget và API bot đã phát hành. Đợt này hỗ trợ tiếng Việt và tiếng Anh.

## Cách sử dụng

- Khách hỏi tiếng Việt hoặc tiếng Anh bằng một câu có nội dung rõ ràng. Bot dùng ngôn ngữ nhận diện từ câu đó.
- Các câu ngắn như OK, yes, tên thiết bị, mã gói hoặc số điện thoại tiếp tục dùng ngôn ngữ đã xác định.
- Khách có thể yêu cầu “Please answer in English” hoặc “Trả lời bằng tiếng Việt”. Lựa chọn rõ ràng này được giữ cho tới khi khách yêu cầu đổi lại trong cùng hội thoại.
- Khi chưa đủ thông tin nhận diện, hội thoại mới mặc định tiếng Việt.

Ngôn ngữ được lưu trong trạng thái hội thoại hiện có. Tin trả lời cũ và tài liệu KB được dùng làm dữ liệu tham khảo theo quyền truy cập của phiên.

## Cấu hình Dialog

Trong BlueAI Custom, mở Luồng hội thoại, chọn bước cần sửa, rồi mở Câu trả lời tiếng Anh.

1. Điền Nội dung trả lời tiếng Anh.
2. Với bước cần dữ kiện, điền Câu hỏi bổ sung tiếng Anh.
3. Với bước chờ khách trả lời, có thể điền Lời đáp khi chưa rõ bằng tiếng Anh.
4. Giữ nguyên các biến của bản gốc, ví dụ `{{location}}`. Máy chủ kiểm tra các biến khi lưu và phát hành.
5. Bổ sung câu mẫu tiếng Anh cho intent và từ đồng nghĩa tiếng Anh cho entity khi muốn cùng một kịch bản nhận cả hai ngôn ngữ. Giá trị entity chuẩn tiếp tục dùng chung cho cả hai.
6. Lưu nháp, thử cả hai ngôn ngữ rồi phát hành. Kiểm tra phiên mới để nghiệm thu đúng bản phát hành.

Các trường API tương ứng là `englishResponseText`, `englishMissingEntityPrompt` và `englishIrrelevantResponseText` trên mỗi Dialog node. Chuỗi rỗng xóa bản dịch. Caller cũ bỏ qua các trường mới được giữ bản dịch đã lưu cho cùng tên bước. Khi thay đổi tên biến trong bản gốc, cập nhật bản dịch đi kèm.

## Cách dùng AI và token

| Trường hợp | Xử lý |
|---|---|
| Đã có câu Dialog tiếng Anh | Dùng trực tiếp câu đã lưu và chèn dữ kiện |
| Thông báo hệ thống phổ biến | Dùng bản Việt hoặc Anh có sẵn |
| Dialog tùy chỉnh còn thiếu bản ngôn ngữ và Core đang bật | Dịch qua client AI dùng chung, ghi nhận token theo cơ chế hiện có |
| Dialog thuần, Core tắt, thiếu bản tiếng Anh | Trả câu tiếng Anh hướng dẫn liên hệ hỗ trợ để tiếp tục |
| RAG | Truy xuất câu hỏi gốc và sinh câu trả lời theo ngôn ngữ phiên |

Bộ kiểm tra bản dịch tự động đối chiếu mã dạng SKU, APN, URL, số liệu, loại tiền, các cặp số với đơn vị thời gian hoặc dung lượng, và giá trị entity đã chèn vào câu trả lời. Bản dịch sai cấu trúc hoặc thay đổi các mục này trả lỗi `REPLY_TRANSLATION_FAILED`. Tên hoặc địa danh tiếng Việt trong entity không làm câu Dialog tiếng Anh bị nhận diện thành tiếng Việt. Quản trị viên vẫn cần rà ý nghĩa nghiệp vụ của bản dịch theo các tình huống thực tế.

Các lỗi giới hạn AI và chống spam phổ biến có bản tiếng Anh theo ngôn ngữ được xác định cho lượt chat. HTTP status, mã lỗi và `Retry-After` tiếp tục cung cấp thông tin cho ứng dụng tích hợp xử lý việc chờ hoặc gửi lại.

## API tích hợp

Endpoint `POST /api/v1/ai/tasks/chat` dùng cùng runtime với widget. Caller tiếp tục gửi `userId`, `message`, `conversationId`, `messageId` và `channel` theo hợp đồng hiện tại. Cơ chế nhận diện ngôn ngữ sử dụng nội dung tin nhắn và trạng thái hội thoại.

Giữ `messageId` khi phát lại cùng một tin. Dùng cùng bộ định danh khách, hội thoại và kênh để nối các lượt chat. Khi nghiệm thu Telegram, kiểm tra API key đang trỏ đúng bot và bản phát hành. Kết quả kiểm thử endpoint API được ghi riêng với kết quả thử trên ứng dụng Telegram thật.

## Dữ liệu và triển khai

Migration V16 thêm ba cột nullable cho bản tiếng Anh của Dialog node. Cấu hình và snapshot cũ vẫn đọc được. Bản dịch được lưu vào snapshot khi phát hành. Trạng thái ngôn ngữ dùng JSON hội thoại hiện có.

Triển khai backend hỗ trợ V16 cùng frontend có các trường tiếng Anh. Khi khôi phục image cũ, giữ lịch sử migration đã áp dụng và bộ sao lưu của lần triển khai. Phiên widget và API đang mở tiếp tục dùng cấu hình bản phát hành đã gắn với phiên đó.

## Các ca nghiệm thu

| Mã | Thao tác | Kết quả cần có |
|---|---|---|
| LANG 01 | Hỏi “Mình không cài được eSIM” | Trả lời tiếng Việt |
| LANG 02 | Hỏi “Hello, I can't set up my eSIM” | Xử lý câu hỏi hỗ trợ và trả tiếng Anh |
| LANG 03 | Sau câu tiếng Anh, gửi “iPhone 15” | Giữ tiếng Anh và ngữ cảnh |
| LANG 04 | Yêu cầu trả tiếng Anh, sau đó gửi câu tiếng Việt | Giữ lựa chọn tiếng Anh đã yêu cầu |
| LANG 05 | Yêu cầu “Please explain in Vietnamese” | Chuyển sang tiếng Việt |
| LANG 06 | Hỏi Anh trên tài liệu nguồn Việt | Tìm đúng nội dung và giải thích bằng tiếng Anh |
| LANG 07 | Hai khách dùng hai ngôn ngữ đồng thời | Ngôn ngữ và lịch sử riêng từng khách |
| LANG 08 | Gửi lại cùng messageId | Phát lại kết quả đã lưu |
| LANG 09 | Dùng câu Dialog tiếng Anh đã lưu, Core tắt | Trả câu đã cấu hình, không gọi AI |
| LANG 10 | Bản dịch thử làm đổi APN, dung lượng hoặc thời hạn | Bị chặn bởi kiểm tra bản dịch |
| LANG 11 | Lưu bản tiếng Anh, mở lại và phát hành | Nội dung được giữ trong cấu hình và release |
| LANG 12 | Hủy tác vụ trong hội thoại tiếng Anh | Xóa dữ kiện tác vụ và tiếp tục tiếng Anh |
| LANG 13 | Nâng schema cũ lên V16 trên MySQL 8.4 | Giữ dữ liệu và checksum migration cũ |
| LANG 14 | Chat nhanh hai lượt liên tiếp | Lịch sử giữ thứ tự người dùng và trợ lý |
| LANG 15 | Gemini trả giới hạn sử dụng khi khách hỏi tiếng Anh | Thông báo tiếng Anh, giữ HTTP 429 và Retry-After |
| LANG 16 | Dialog tiếng Anh chèn địa danh Hà Nội | Giữ nguyên Hà Nội, dùng câu đã cấu hình và không gọi AI |

Các phép thử Gemini thật trong đợt phát triển dùng tài liệu eSIM kiểm thử và transcript riêng. Truy xuất đa ngôn ngữ được thử thêm trên index trong bộ nhớ với tài liệu kiểm thử tiếng Việt. Nghiệm thu bot Telegram thật thực hiện khi có quyền truy cập bot.

Trong vòng kiểm thử hồi quy, đã sửa thêm cơ chế cập nhật thời gian hội thoại để thời gian không lùi so với dấu thời gian đã cấp cho tin nhắn. Việc này bảo đảm thứ tự tin người dùng và trợ lý khi hai lượt được tạo sát nhau.

## Kết quả kiểm chứng ngày 27 tháng 11 năm 2026

| Phạm vi | Kết quả |
|---|---|
| Backend, toàn bộ lượt verify mặc định | 559 ca chạy đạt, 0 lỗi; 65 ca có điều kiện môi trường được bỏ qua |
| Frontend | 170 ca đạt; TypeScript và bản build production thành công |
| Luồng chat, lưu/phát hành, widget và API trên MySQL 8.4 | 26 ca đạt trong lượt riêng, có kiểm tra API chat với định danh và kênh Telegram giả lập |
| Migration trên MySQL 8.4 | Schema mới, nâng schema cũ V1/V3/V5/V6 lên V16 đạt; dữ liệu kiểm thử cũ được bảo toàn |
| Tương thích Flyway | Tạo schema bằng Flyway 11.7.2 và nâng bằng 11.20.3 lên V16 đạt |
| Gemini thật, phần sinh câu trả lời và dịch Dialog | 3 ca đạt, nhà cung cấp ghi nhận tổng 1.966 token |
| Embedding Gemini thật, truy xuất chéo ngôn ngữ | 4 ca đạt trên 3 tài liệu tiếng Việt kiểm thử trong index bộ nhớ |
| Biểu mẫu tiếng Anh trong trình duyệt | Mở bằng bàn phím, nhập và lưu nháp đạt với dữ liệu giả lập; kiểm tra chiều rộng 390/768/1280 px và giao diện sáng/tối |

Các ca MySQL chạy riêng không thay thế toàn bộ 65 ca có điều kiện bị bỏ qua ở lượt mặc định. Những nhóm MySQL và Redis khác trong bộ hồi quy có điều kiện chưa được chạy lại toàn bộ trong đợt này.

Phép thử trình duyệt dùng giao diện thật với API giả lập cục bộ. Phép thử Gemini dùng mã backend ứng viên với nguồn eSIM kiểm thử được giới hạn. Các kết quả này chưa phải nghiệm thu trên production với toàn bộ kho tài liệu doanh nghiệp hoặc trên ứng dụng Telegram thật.

Bản sửa được chuẩn bị trên nhánh `codex/vietnamese-english-chat`. Tại thời điểm ghi kết quả, chưa push, merge hoặc triển khai bản sửa này lên website chính thức.
