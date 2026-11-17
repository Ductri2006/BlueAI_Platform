# Hạn mức token AI của BlueAI

Chính sách demo được chuẩn bị cho 10 tài khoản. Mỗi Admin/User có **100.000 token trong chu kỳ 5 giờ** và **1.000.000 token trong chu kỳ 7 ngày**. Đây là phần phân bổ tài nguyên của BlueAI, không phải cam kết mỗi người có thể dùng hết phần đó khi project Gemini đã hết hạn mức chung.

## Người dùng nhìn thấy gì?

- Hai thanh tiến độ hiển thị mức đã dùng và thời điểm hồi phục theo giờ Việt Nam.
- “Chi tiết” cho biết số đã dùng, giới hạn, còn khả dụng, phần đang chờ xác nhận và phần tạm tính.
- Từ 80% sẽ có cảnh báo. Phần giữ trước được vẽ bằng màu xanh nhạt.
- Dòng chống spam vẫn hiển thị số lượt còn lại trong 60 giây. Đây là giới hạn riêng.
- Các tab và Thử BlueAI dùng chung tài khoản. Đăng xuất, đăng nhập hay đổi mật khẩu không hồi token.
- Hết token vẫn có thể dùng Dialog thuần kịch bản. Hạn mức chống spam tiếp tục áp dụng.
- Một yêu cầu có ngữ cảnh lớn có thể bị từ chối khi số còn lại không đủ để giữ trước phần đầu vào và đầu ra tối đa.

## Cách tính

Một lượt RAG có thể dùng embedding câu hỏi, sinh câu trả lời và tác vụ tóm tắt hội thoại. Mỗi tác vụ có mã riêng trong `ai_token_operations`. Hai cửa sổ tài khoản cùng nhận một mức sử dụng này, không cộng đôi khi tính tổng thực tế.

| Tác vụ | Cách ghi nhận | Tài khoản chịu hạn mức |
|---|---|---|
| Sinh câu trả lời | `usage.total_tokens` do Gemini báo, gồm cả thinking khi provider tính trong tổng | Người đang chat/preview |
| Tóm tắt hội thoại | Cùng số liệu usage, loại tác vụ `SUMMARY` | Chủ hội thoại |
| Embedding câu hỏi | Ưu tiên usage; khi model không cung cấp thì dùng `countTokens` của đúng model | Người đang chat/preview |
| Nhúng tài liệu KB | Đếm theo từng batch, ghi riêng ở hệ thống | Hệ thống, không trừ tài khoản đang hỏi |
| Dialog thuần | 0 token AI | Không trừ token |

`COUNT_TOKENS` là số token đầu vào do API đếm, không được trình bày thành chứng từ billing hay usage do embedContent trả về. Kiểm tra trực tiếp model gemini-embedding-001 ngày 17/11/2026 cho thấy embedContent không trả usage, trong khi countTokens trả số hợp lệ. Không đổi model embedding để giữ tương thích index đang có.

Đầu vào được giữ trước theo số byte UTF-8 cộng phần bao gói dự phòng, đầu ra theo giới hạn cấu hình. Đây là **mức giữ trước**, khác số token đã dùng. Khi có số liệu, phần dư được trả lại. Chỉ lưu số đếm, mã tác vụ, thời gian, model và chủ tài khoản; không lưu prompt hoặc API key trong sổ token.

## Chu kỳ hồi phục

- Chỉ bắt đầu khi tác vụ AI được chấp nhận. Xem quota không khởi động đồng hồ.
- Chu kỳ 5 giờ và 7 ngày độc lập, tính từ lần dùng đầu tiên trong mỗi chu kỳ.
- Sau khi hết hạn, phần đó được hồi toàn bộ. Lần dùng tiếp theo mở chu kỳ mới.
- Lưu UTC, hiển thị Asia/Ho_Chi_Minh. Không phải tuần lịch thứ Hai đến Chủ nhật.
- Kết quả trả muộn được ghi vào chu kỳ gốc; không trừ nhầm sang chu kỳ mới.

## Giới hạn dùng chung

Cấu hình demo tham chiếu AI Studio được kiểm tra ngày 17/11/2026. Cần đối chiếu lại đúng project khi thay key hoặc nâng gói.

| Model | RPM | Input TPM | RPD |
|---|---:|---:|---:|
| gemini-3.5-flash-lite | 15 | 250.000 | 500 |
| gemini-embedding-001 | 100 | 30.000 | 1.000 |

Counter ngày của project theo nửa đêm Pacific, có tính chuyển giờ mùa hè. Các counter tách theo model. Chỉ theo dõi tác vụ đi qua BlueAI; ứng dụng khác dùng chung Google project có thể tiêu thụ quota ngoài các counter này. Khi Google từ chối, ứng dụng báo giới hạn nhà cung cấp và không coi đó là lỗi KB.

Gemini Free hiện có chi phí API bằng 0 cho các model trên trong phạm vi Free. Không thể suy ra số lượt chat cố định từ số tài khoản hoặc token. Mỗi lượt còn phụ thuộc lịch sử, tài liệu truy xuất, độ dài trả lời và tóm tắt. Báo cáo chi phí toàn project trước đây không được gán ngược cho từng tài khoản vì thiếu dữ liệu chủ sở hữu.

## Đồng thời, sự cố và tính bền vững

- Giữ trước hai hạn mức bằng transaction ngắn, khóa hàng tài khoản trên database, dùng chung giữa các instance.
- Counter project cũng được khóa khi nhận tác vụ. Không giữ transaction quota qua thời gian gọi Gemini.
- Settlement theo mã tác vụ chỉ ghi một lần, cho phép thay phần ước tính bằng số liệu xác nhận sau đó.
- Từ chối rõ ràng trước sinh nội dung trả lại phần giữ trước. Khi timeout không biết provider đã dùng bao nhiêu, giữ tối đa 5 phút, sau đó chuyển sang phần tạm tính có đánh dấu.
- Không tự phát lại completion đã nhận admission khi gặp timeout không rõ trạng thái. Tắt retry/fallback ẩn ở worker cho luồng này để tránh nhân đôi chi phí.
- Phần tạm tính không tự nhận là usage thực. Có thể đối soát bằng mã tác vụ khi có bằng chứng chính thức.
- Restart không xóa sổ token. Scheduler xử lý phần giữ trước quá hạn. Backup database phải gồm ba bảng mới.

## Triển khai và điều chỉnh

Migration V15 chỉ thêm bảng. Chính sách mặc định tắt để rollout có kiểm soát. Cần triển khai cả BE và worker trước khi bật, sau đó FE có thể đọc API mới. API giới hạn lượt gửi cũ được giữ để tương thích rollback.

| Biến môi trường | Giá trị demo |
|---|---|
| `APP_AI_QUOTA_ENABLED` | `true` khi nghiệm thu và bật chính sách |
| `APP_AI_QUOTA_FIVE_HOUR_TOKENS` | `100000` |
| `APP_AI_QUOTA_WEEKLY_TOKENS` | `1000000` |
| `APP_AI_QUOTA_MAX_OUTPUT_TOKENS` | `2048` |
| `APP_AI_QUOTA_CHAT_RPM`, `...CHAT_TPM`, `...CHAT_RPD` | `15`, `250000`, `500` |
| `APP_AI_QUOTA_EMBEDDING_RPM`, `...EMBEDDING_TPM`, `...EMBEDDING_RPD` | `100`, `30000`, `1000` |
| Worker `AI_TOKEN_QUOTA_ENABLED` | cùng giá trị với `APP_AI_QUOTA_ENABLED` |

Backend và worker phải cùng cấu hình model. Sau khi đổi key hoặc model, kiểm tra lại RPM/TPM/RPD. Để rollout/rollback khẩn cấp có thể tắt chính sách mà giữ nguyên dữ liệu, không cần xóa hay sửa lịch sử Flyway.

## Checklist nghiệm thu

1. Hai tài khoản khác nhau có quota tách biệt; nhiều tab cùng tài khoản có chung số.
2. RAG cập nhật số embedding và generation. Dialog thuần không tạo tác vụ AI.
3. Preview bot dùng quota người thao tác; admin khác không bị trừ nhầm.
4. Gửi đồng thời không vượt số đã cho giữ trước. Ghi kết quả hai lần không trừ hai lần.
5. Hết hạn mức 5 giờ, hết hạn mức 7 ngày, hết project RPM/TPM/RPD có thông báo phù hợp.
6. Thất bại rõ ràng hoàn phần giữ trước; timeout có phần chờ/tạm tính và không phát lại âm thầm.
7. Qua mốc hồi phục, kết quả muộn không trừ sang cửa sổ mới. Đổi mật khẩu không thay user ID.
8. Admin/User xem được quota của chính mình; chưa đăng nhập bị từ chối. Endpoint worker bị chặn từ Internet.
9. Tải tài liệu vẫn giữ task type, kích thước vector, index và quyền KB hiện có. Tài liệu quá dài không bị cắt âm thầm ở embedding-001.
10. Desktop/mobile, sáng/tối, focus bàn phím, lỗi mạng, tải lại và giữ nội dung đang soạn đều được kiểm tra.

Nguồn tham khảo: [Google rate limits](https://ai.google.dev/gemini-api/docs/rate-limits), [Embedding API](https://ai.google.dev/api/embeddings), [Google giải thích countTokens cho embedding-001](https://discuss.ai.google.dev/t/gemini-embedding-001-counttokens-support-token-correspondence-and-truncation-behaviour/190649).
