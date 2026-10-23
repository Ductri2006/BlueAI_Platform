# BlueAI API key — hợp đồng cho CEP / website thứ ba

## Luồng chính

1. Trong BlueAI Custom, cấu hình bot và giới hạn **request / phút / API key**.
2. Bấm **Lưu bản nháp**, sau đó **Phát hành**. BlueAI tự sinh key nếu đây là lần phát hành đầu; phát hành lại giữ nguyên key và áp dụng giới hạn mới.
3. Chủ sở hữu bot bấm **Sao chép API key** ở Custom hoặc Tích hợp. CEP lưu key tại backend của mình.
4. Backend CEP gọi `POST /api/v1/ai/tasks/chat`. BlueAI xác định bot bằng key, chạy bản bot đã phát hành và trả `answer` cùng định danh để CEP gửi về khách.

Mặc định 30 request/phút/key. Có thể đặt mặc định khi khởi động bằng `APP_API_KEY_REQUESTS_PER_MINUTE` (1–1000). Người dùng chọn giới hạn 1–1000 trong Custom; bản nháp không đổi giới hạn đang chạy cho đến khi phát hành.

## Request tối thiểu

```http
POST /api/v1/ai/tasks/chat
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

```json
{"userId":"cep-contact-123","message":"Xin chào"}
```

`userId` là ID khách đang chat, không phải ID nhân viên đăng nhập. ID phải là chuỗi để không mất độ chính xác với BIGINT. Nội dung text tối đa 2.000 ký tự.

CEP nên gửi thêm các trường sau để tách đúng hội thoại, kênh và chống gửi trùng:

```json
{
  "userId":"9007199254740993",
  "message":"Chính sách hoàn tiền là gì?",
  "conversationId":"company-10:channel-5:conversation-245",
  "messageId":"message-9001",
  "channel":"zalo"
}
```

`conversationId` mặc định bằng `userId`; `channel` mặc định `api`; `messageId` được sinh mới nếu không gửi. Để retry an toàn, CEP phải tự cấp messageId ổn định và gửi lại cùng nội dung. Không truyền key qua URL hoặc JSON body. Key này chỉ gọi endpoint chat đã nêu, không có quyền quản trị, API task nâng cao hay tài liệu tùy ý.

## Response

```json
{
  "answer":"Nội dung trả lời của bot",
  "fallback":false,
  "userId":"9007199254740993",
  "conversationId":"company-10:channel-5:conversation-245",
  "messageId":"message-9001",
  "botId":"<bot-id>",
  "sessionId":"<runtime-session-id>",
  "releaseId":"<published-release-id>"
}
```

CEP dùng `answer` để tạo tin gửi qua pipeline hiện tại. BlueAI không gửi trực tiếp Zalo/WhatsApp, không tạo đơn hàng, thanh toán hoặc thay đổi ticket. `fallback` chỉ phản ánh kết quả runtime hiện tại; CEP cần quy tắc chuyển nhân viên và xử lý lỗi riêng.

Các lượt cùng key + userId + conversationId + channel dùng chung trạng thái dialog và lịch sử phiên. Khác một trong các giá trị đó sẽ được tách phiên. Session hết hạn (mặc định 30 phút) hoặc có bản phát hành mới thì lượt tiếp theo mở phiên mới. Runtime vẫn có giới hạn tổng lượt/session đang cấu hình. Chống gửi trùng chỉ có hiệu lực trong phiên/release còn được giữ; CEP phải chống giao tin trùng độc lập ở lớp kênh.

## Lỗi và rate limit

- `401`: key sai, không còn tồn tại hoặc chủ sở hữu không còn là admin đang hoạt động.
- `403 API_KEY_SCOPE_DENIED`: dùng key ở endpoint ngoài `/api/v1/ai/tasks/chat`.
- `400`: body/ID/nội dung/giới hạn không hợp lệ.
- `409`: cùng messageId nhưng khác nội dung, hoặc hội thoại đang xử lý lượt khác. Retry lượt đang xử lý với cùng IDs và nội dung.
- `429 API_KEY_RATE_LIMITED`: vượt quota của key; đọc `Retry-After` (giây).
- Lỗi runtime/model có thể trả `4xx/5xx`; không biến lỗi thành tin AI thành công.

Quota là số request HTTP vào endpoint chat trong cửa sổ 60 giây bắt đầu từ request đầu tiên, chia sẻ giữa mọi khách và backend dùng chung key. Request đã qua xác thực được tính cả khi body lỗi hoặc là replay. Key khác có bộ đếm riêng. Bộ đếm và khóa nằm trong database; không phụ thuộc bộ nhớ một replica. Public widget vẫn dùng cơ chế bảo vệ widget riêng.

## Quản lý trong BlueAI

- `GET /api/v1/studio/bots/{botId}/api-key`: JWT của admin đang hoạt động, trả cấu hình bản nháp, quota đã phát hành và key để copy của bot trong kho quản trị dùng chung; response `Cache-Control: no-store`. Các admin thấy cùng key đã có, không tạo bản sao theo tài khoản.
- `PUT /api/v1/studio/bots/{botId}/api-key-settings`: cùng quyền, body `{"requestsPerMinute":30}`; lưu bản nháp.
- `POST /api/v1/studio/bots/{botId}/publish`: phát hành bot, tự tạo/cập nhật cấu hình key trong cùng giao dịch.

Admin cùng dùng tài liệu, dialog/bot và key đã phát hành. Conversation, lịch sử chat và bộ nhớ cá nhân vẫn giới hạn theo tài khoản; không chia sẻ theo quyền admin. Quota của một bot key dùng chung cho mọi nơi sử dụng key đó. Chỉnh quota ở bản nháp chỉ áp dụng sau khi phát hành; phát hành lại không đổi key. Việc cấp/thu hồi integration credential nâng cao vẫn tuân theo grant và chủ sở hữu riêng của API task.

Key sinh ngẫu nhiên 256 bit. Xác thực dùng SHA-256 digest; bản có thể copy được mã hóa AES-GCM tại database. UI che key và chỉ copy khi người dùng bấm. Không lưu key ở localStorage. Khóa mã hóa được suy ra từ JWT secret đang cấu hình: đổi secret sẽ làm các key/phiên đã mã hóa trước đó không giải mã được; cần kế hoạch cấp lại trước khi đổi secret. Phiên bản hiện tại chưa có giao diện xoay/thu hồi từng key; vô hiệu hóa chủ sở hữu hoặc xóa bot sẽ vô hiệu hóa key tương ứng.

## Use case CEP

| Use case | Cách nối |
|---|---|
| Chat CSKH Zalo/WhatsApp | Gọi API key chat; CEP gửi `answer` về đúng kênh |
| Intent, entity, dialog nhiều lượt | Bot Studio đã phát hành tự thực thi và giữ ngữ cảnh từng phiên |
| Hỏi đáp tài liệu / BlueAI Core | Chạy theo cấu hình và tài liệu đã chọn trong bot |
| Chuyển nhân viên, gắn nhãn, giao/đóng ticket | CEP thực thi theo rule và quyền của CEP; không coi nội dung tự do của model là lệnh |
| Thanh toán, đơn hàng, thông tin chuyến bay, cập nhật khách | CEP gọi API nghiệp vụ hiện có; BlueAI hỗ trợ hội thoại |
| Sinh ảnh / adapter provider hiện có của CEP | Giữ provider đó; API chat BlueAI hiện chỉ nhận/trả text |
| Node phân loại/trích xuất/RAG có JSON cấu trúc độc lập | API integration client nâng cao trong `AI_TASK_API.md`, credential/capability riêng; key bot đơn giản không tự được cấp các quyền này |

API integration client và bot-session nâng cao vẫn được giữ cho workflow cần grant chi tiết. Luồng copy key trên giao diện dùng endpoint đơn giản ở trên.
