# Cập nhật Gemini key và kiểm tra rate limit — 22/10/2026

## Cấu hình đã cập nhật

Đã thay `GEMINI_API_KEY` trong `backend/feature-rag-chatbot/rag-chatbot/.env`. File này được Git ignore và không được Git theo dõi; key không được ghi vào mã nguồn, báo cáo, URL hoặc log kiểm thử. Không đổi worker secret giữa Java/Python, thuật toán quota, quota của bot đang triển khai, database MySQL hoặc cấu hình model hiện tại.

`APP_LLM_API_KEY` trong Java là credential của transport nội bộ tới worker localhost, khác provider key và được giữ nguyên. Worker đọc Gemini key mới từ `.env` khi khởi động.

## Gemini trực tiếp

- Model hiện tại: `gemini-3.5-flash-lite`.
- 6 request liên tiếp tới API Gemini chính thức, mỗi request chỉ yêu cầu trả lời `OK`, tối đa 32 output token.
- Kết quả: **6 × HTTP 200**, 0 lỗi, tổng 42 token trong `usageMetadata`.
- Chưa xuất hiện 429 trong sáu request; bài thử này **không xác định trần RPM/TPM/RPD của Google**.
- Kịch bản dừng khi gặp lỗi đầu tiên và giới hạn tối đa 20 request, không vét hết quota nhà cung cấp.

Chi tiết: [provider-probe.json](provider-probe.json).

Theo [tài liệu Gemini](https://ai.google.dev/gemini-api/docs/rate-limits), quota áp dụng theo project và model, có các chiều RPM/TPM/RPD; đổi key trong cùng project không tạo quota riêng. Giới hạn thực tế cần xem trong [Google AI Studio](https://aistudio.google.com/usage?timeRange=last-28-days&tab=rate-limit).

## BlueAI → Python worker → Gemini thật

Môi trường thử: BlueAI Spring Boot trên `127.0.0.1:8088`, cấu hình chỉ định rõ từ `application-test.properties`, database `jdbc:h2:mem:gemini-rate-limit-20261022`. Worker thật trên `127.0.0.1:8008` dùng key mới, index FAISS thử riêng. Chỉ upload văn bản tổng hợp về giờ mở cửa một cửa hàng giả lập; không gửi corpus CSV sẵn có hoặc dữ liệu khách thật tới Gemini. Database, tài khoản admin và các bot thử chỉ tồn tại trong H2 của lần chạy này.

Bot bật BlueAI Core, tắt Dialog, chọn đúng tài liệu tổng hợp để đảm bảo câu trả lời phải đi qua retrieval + Gemini thật. Quota không được thay bằng mock trong bài thử HTTP này.

| Lượt | Hành động | Kết quả |
| --- | --- | --- |
| 1–2 | Phát hành quota 2 request/phút/key; hai khách gọi | 200, trả lời có nguồn `[S1]`, Gemini usage = 2 requests |
| 3 | Request tiếp theo trong cùng phút | 429 `API_KEY_RATE_LIMITED`, `Retry-After: 55`; Gemini requests/token không tăng |
| 4 | Lưu draft quota 3 nhưng chưa phát hành; gọi tiếp | Vẫn 429, published quota vẫn là 2 |
| 5 | Phát hành lại quota 3; gọi tiếp | 200; key BlueAI giữ nguyên |
| 6 | Gọi tiếp khi đã dùng 3 lượt | 429, `Retry-After: 53` |
| 7 | Chờ hết cửa sổ 60 giây và gọi lại | 200; bộ đếm phục hồi |

Cả bốn lần thành công đều trả đúng giờ `08:00`–`22:00` từ tài liệu thử. Tổng Gemini completion usage ghi nhận ở BlueAI: **4 requests, 3.104 prompt tokens, 108 completion tokens, 3.212 total tokens**. Các request 429 không phát sinh completion/usage mới. Số này không gồm sáu request gọi trực tiếp ở phần trên hoặc embedding của tài liệu/query.

Chi tiết: [blueai-live-quota.json](blueai-live-quota.json).

## Kiểm tra tự động

- `PublishedBotApiTest`: **7/7** qua, gồm quota chung nhiều khách, key riêng, reset cửa sổ, cập nhật quota khi phát hành và 8 request đồng thời với quota 3 chỉ cho 3 request qua.
- `RagCompletionUsageTest`: **25/25** qua, gồm usage, lỗi nhà cung cấp, retry 429/503 và giới hạn số lần thử.
- Python `test_completion_usage.py`: **13/13** qua, gồm SDK usage, fallback và contract Java/Python.
- Tổng **45 test**, 0 failures/errors/skips trong các suite đã chọn.

## Kịch bản kiểm thử

- `feature-rag-chatbot/rag-chatbot/scripts/test-gemini-rate-limit.py`: đọc key từ `.env`, probe nhỏ với Gemini thật, xuất JSON không chứa key. Ví dụ trong thư mục `rag-chatbot`: `.venv/Scripts/python.exe scripts/test-gemini-rate-limit.py --requests 6 --output report.json`.
- `feature-rag-chatbot/rag-chatbot/scripts/test-published-rate-limit-live.py`: cần khởi động fixture H2/worker tách biệt đã mô tả, chạy với `--isolated --output report.json`. Token và bot/provider key chỉ nằm trong bộ nhớ. Không chạy vào production.
- Worker bootstrap thử nằm trong `BE/target/gemini-rate-limit-worker.py`, tạo index FAISS trống 3.072 chiều cho model embedding hiện tại để tránh index toàn bộ corpus demo. Không sửa runtime production.

## Phạm vi và rà soát

Không chỉnh class/hàm production của lượt này; chỉ cập nhật `.env` local, thêm hai kịch bản kiểm thử và báo cáo. GitNexus query/context đã xác nhận luồng cấu hình Gemini và published bot; file script mới chưa có trong index nên impact theo tên file trả `not found`, được rà như entry point CLI không có caller production.

`detect_changes(scope=all)` đã chạy: diff toàn checkout báo **CRITICAL** (75 file tracked, gồm source tích hợp và cấu hình đã sửa từ trước cùng build artifacts được repo theo dõi). Đây không phải đánh giá riêng cho việc đổi key; không commit hoặc tự gộp các thay đổi đó. Các script/báo cáo mới được kiểm tra riêng, không chứa provider key.

Chưa đo trần quota thật của Google, chưa thử Zalo/WhatsApp, chưa triển khai production. Giới hạn 2 rồi 3 request/phút chỉ áp dụng cho bot H2 thử nghiệm.
