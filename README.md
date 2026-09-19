# Project Tổng Hợp — BlueAI Platform

Hướng dẫn chạy toàn bộ hệ thống trên máy local bằng Docker Compose: FE (React), BE (Spring Boot), RAG service (FastAPI + Gemini) và MySQL.

## 1. Chuẩn bị

| Cần có | Ghi chú |
|---|---|
| Docker Desktop | Phải đang chạy trước khi gõ lệnh. Trên Windows nên dùng WSL2 backend. |
| ~4 GB RAM trống | Stack gồm bốn container: FE, BE, RAG, MySQL. |
| Gemini API key | Lấy tại https://aistudio.google.com/apikey |

Không cần cài Java, Maven, Node hay Python trên máy — tất cả được build bên trong image.

Bốn cổng phải còn trống: `3000`, `8080`, `8000`, `33069`.

## 2. Đặt Gemini API key

`BE/compose.yaml` đọc key từ biến `GOOGLE_API_KEY`. Key này **chỉ** đi vào service RAG (Python); BE Java không cần key Gemini (`APP_LLM_API_KEY` chỉ là token nội bộ giữa hai container).

### Cách 1 — file `.env` (khuyến nghị, chỉ làm một lần)

Tạo file `BE/.env` với nội dung:

```dotenv
GOOGLE_API_KEY=AIza...key-của-bạn
```

Bằng PowerShell:

```powershell
cd "C:\Users\QUANGMINH\Documents\ChatGPT\New project\backend\BE"
'GOOGLE_API_KEY=AIza...key-của-bạn' | Set-Content -Encoding ascii .env
```

Docker Compose tự đọc `.env` khi lệnh được chạy từ trong thư mục `BE`. File này đã được `.gitignore` loại trừ nên key không bị commit. Không dán key vào `application.properties` hay `compose.yaml`.

### Cách 2 — biến môi trường của terminal

Không tạo file, nhưng phải set lại mỗi khi mở terminal mới:

```powershell
$env:GOOGLE_API_KEY = "AIza...key-của-bạn"
```

## 3. Khởi động

```powershell
cd "C:\Users\QUANGMINH\Documents\ChatGPT\New project\backend\BE"
docker compose up --build
```

Lần đầu mất khoảng 5–10 phút vì phải build image Maven, npm và pip. Những lần sau bỏ `--build` cho nhanh.

Compose điều phối thứ tự: `mysql` và `rag-ai` phải healthy trước, rồi `auth-api` mới chạy, cuối cùng là `frontend`.

| Service | URL | Vai trò |
|---|---|---|
| frontend | http://localhost:3000 | UI React; nginx proxy `/api` sang BE |
| auth-api | http://localhost:8080 | Spring Boot: auth, hội thoại, tài liệu, orchestration RAG |
| rag-ai | http://localhost:8000 | FastAPI: FAISS + Gemini (retrieval và chat) |
| mysql | localhost:33069 | MySQL 8.4, database `blueai_auth`, user `blueai` |

## 4. Kiểm tra AI đã sống

```powershell
curl.exe http://localhost:8000/health
curl.exe http://localhost:8000/ready
curl.exe http://localhost:8080/actuator/health
```

`/health` phải trả `"geminiConfigured": true`. Nếu là `false` thì key chưa tới được container — xem mục Sự cố.

`/ready` trả `200` khi có key và `503` khi thiếu. Healthcheck của Compose dùng đúng endpoint này, nên thiếu key là cả stack không lên được.

## 5. Đăng nhập

Mở http://localhost:3000. Đăng ký công khai bị tắt mặc định, dùng tài khoản admin bootstrap:

```
Email:    admin@blueai.local
Mật khẩu: BlueAiAdmin123!
```

Menu bên trái: **Hỏi BlueAI** (chat RAG) cho mọi tài khoản; **Tài liệu & Tri thức RAG**, **Chatbot Studio** và **Quản trị Hệ thống** chỉ hiện với ADMIN.

## 6. Tạo và nhúng chatbot

Đăng nhập bằng tài khoản ADMIN rồi mở **Chatbot Studio**. Luồng demo hoàn chỉnh:

1. Tạo bot từ template tiếng Việt.
2. Sửa intents và các câu mẫu trong tab **Intents**.
3. Khai báo entity cùng từ đồng nghĩa trong tab **Entities**.
4. Ghép intent/entity, slot bắt buộc và phản hồi trong tab **Dialog**.
5. Bấm **Lưu nháp**, sau đó **Thử bot** để xem intent, entity, node và context được nhận diện qua nhiều lượt.
6. Bấm **Phát hành**. Tab **Tích hợp** cung cấp script nhúng có `publicKey` của bot.

Widget được phục vụ tại `/widget.js`. Nó tạo phiên 30 phút khi khách mở hộp chat, giữ token trong bộ nhớ trang và gửi `requestId` cho mỗi lượt. Backend ghim phiên vào release đang hoạt động; phát hành bản mới không làm đổi release của phiên cũ. API công khai chỉ trả nội dung cần render và không trả intent, entity, node hay context nội bộ.

API widget/runtime là public nhưng không phải không giới hạn: mỗi IP chỉ được tạo tối đa 10 phiên/phút, gửi tối đa 30 tin nhắn/phút; mỗi phiên tối đa 12 tin nhắn/phút và 120 lượt tổng cộng. Gửi lại cùng `requestId` với cùng nội dung sẽ nhận lại response cũ, không tiêu thêm quota hoặc gọi AI lần nữa. Cùng một nội dung với `requestId` mới chỉ được replay hai lần trong 2 phút; lần lặp bất thường tiếp theo nhận `429` kèm `Retry-After`. Session và idempotency record hết hạn được dọn tự động. Các giới hạn này là lớp bảo vệ application dùng Redis shared state; production vẫn nên đặt thêm rate limit ở reverse proxy/WAF nếu chạy nhiều instance.

CORS của API đăng nhập dùng `APP_CORS_ALLOWED_ORIGINS`. CORS của widget dùng `APP_WIDGET_ALLOWED_ORIGINS` (mặc định `*` vì widget public, không dùng browser credentials); khi nhúng trên production nên đặt thành danh sách domain cụ thể, phân tách bằng dấu phẩy.

Các API chính:

| API | Quyền | Mục đích |
|---|---|---|
| `GET/POST /api/v1/studio/bots` | ADMIN | Danh sách/tạo bot thuộc chính chủ sở hữu |
| `GET /api/v1/studio/bots/{id}` | ADMIN | Tải cấu hình bản nháp |
| `PUT /api/v1/studio/bots/{id}/configuration` | ADMIN | Lưu nguyên tử với `expectedRevision` |
| `POST /api/v1/studio/bots/{id}/preview` | ADMIN | Chạy thử bản nháp và trả trace |
| `POST /api/v1/studio/bots/{id}/publish` | ADMIN | Tạo snapshot release bất biến |
| `POST /api/v1/widget/{publicKey}/sessions` | Công khai | Tạo phiên cho bot đã phát hành |
| `POST /api/v1/runtime/sessions/{id}/messages` | Widget token | Gửi lượt chat idempotent |

## 7. Để câu trả lời có citation

Câu hỏi đầu tiên mất 30–60 giây: RAG service chưa có FAISS index nên phải embed knowledge base mẫu trước. Đó không phải treo.

Mặc định chat vẫn trả lời nhưng **không có nguồn nào** và UI tô độ tin cậy màu đỏ. Đây là thiết kế: `RagChatService.toVerifiedSources` chỉ nhận chunk nào khớp một tài liệu `READY` trong MySQL thuộc chính bạn hoặc thuộc ADMIN, còn 33 record demo trong `Papers/*.csv` không có row nên bị loại.

Hai cách để có nguồn:

1. **Upload tài liệu (đúng chuẩn)** — đăng nhập ADMIN, vào **Tài liệu & Tri thức RAG**, tải lên PDF/DOCX/CSV/TXT/MD. Worker Python tự claim sau khoảng 5 giây, chunk, embed rồi báo `READY` về BE. Mỗi dòng CSV được chuyển thành một record có nhãn cột trước khi chunk. Hỏi lại sẽ thấy nhãn `[S1]`.
2. **Bật dữ liệu demo** — thêm vào `BE/.env` rồi tạo lại container BE:

   ```dotenv
   APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE=true
   ```

   ```powershell
   docker compose up -d --force-recreate auth-api
   ```

   Dữ liệu này là tổng hợp để demo, UI đánh dấu "Chưa được phê duyệt" — không được dùng như chính sách thật của Bluecom.

## 8. Biến môi trường

Chỉ `GOOGLE_API_KEY` là bắt buộc, còn lại đều có giá trị mặc định cho local trong `compose.yaml`.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `GOOGLE_API_KEY` | *(trống)* | Gemini key cho RAG service. Thiếu là stack không lên. |
| `GEMINI_CHAT_MODEL` | `gemini-3.5-flash-lite` | Model chat của RAG service |
| `APP_LLM_MODEL` | `gemini-3.5-flash-lite` | Model BE gửi kèm request, ghi đè `GEMINI_CHAT_MODEL` |
| `GEMINI_EMBEDDING_MODEL` | `models/gemini-embedding-001` | Đổi giá trị này sẽ làm index cũ vô hiệu |
| `APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE` | `false` | Cho phép CSV demo làm nguồn trả lời |
| `RAG_MIN_RELEVANCE` | `0` | Ngưỡng loại chunk yếu (xấp xỉ cosine similarity, 0 = tắt; thử 0.5–0.6) |
| `RAG_MAX_CHUNKS_PER_DOCUMENT` | `3` | Số chunk tối đa một tài liệu được chiếm trong 6 nguồn |
| `RAG_CANDIDATE_MULTIPLIER` | `8` | Số ứng viên lấy từ FAISS = limit × giá trị này, tối đa 200 |
| `DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` | `blueai-local` / `blueai-root-local` | Mật khẩu MySQL |
| `APP_JWT_SECRET` | secret dev | Khoá HS256 ký access token |
| `APP_WORKER_API_KEY` | `local-worker-api-key-...` | Key BE ↔ worker Python, hai bên phải giống nhau |
| `APP_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | `admin@blueai.local` / `BlueAiAdmin123!` | Admin đầu tiên, chỉ tạo khi email chưa tồn tại |

Toàn bộ mặc định trên là credential local-dev. Phải đổi hết trước khi chạy ở bất cứ đâu ngoài máy cá nhân.

## 9. Lệnh hay dùng

```powershell
docker compose ps                      # trạng thái và healthcheck
docker compose logs -f rag-ai          # log RAG; đổi tên service để xem cái khác
docker compose up -d --build auth-api  # build lại một service
docker compose restart auth-api
docker compose down                    # dừng, giữ dữ liệu
docker compose down -v                 # dừng và XOÁ MySQL cùng FAISS index
```

`down -v` xoá volume `blueai-auth_blueai-auth-mysql` (toàn bộ user, hội thoại, tài liệu) và `blueai-auth_blueai-rag-index`. Chỉ dùng khi muốn về trạng thái sạch.

## 10. Sự cố thường gặp

**`rag-ai` mãi không healthy, `auth-api` không khởi động**
Thiếu Gemini key. `/ready` trả 503 nên Compose không đánh dấu healthy, và `auth-api` chờ vô hạn vì `depends_on: service_healthy`. Kiểm tra `curl.exe http://localhost:8000/health` xem `geminiConfigured`. Nhớ chạy `docker compose` từ trong thư mục `BE` để `.env` được đọc.

**Chat trả `RAG_RETRIEVER_NOT_CONFIGURED` hoặc `LLM_NOT_CONFIGURED`**
BE thiếu `APP_RAG_RETRIEVAL_BASE_URL`, hoặc thiếu một trong ba `APP_LLM_BASE_URL` + `APP_LLM_API_KEY` + `APP_LLM_MODEL`. Trong Compose chúng đã có mặc định, lỗi này thường xuất hiện khi chạy BE ngoài Docker.

**Chat trả `RAG_RETRIEVAL_FAILED` hoặc `LLM_REQUEST_FAILED`**
BE gọi được nhưng RAG/Gemini lỗi: hết quota free tier, key sai, hoặc tên model không còn tồn tại. Đọc `docker compose logs rag-ai`.

**Quản trị Hệ thống báo AI runtime `NOT_READY`**
`GET /api/v1/system/metrics` probe `http://rag-ai:8000/ready` với timeout 500 ms connect và 750 ms request. Khi RAG đang bận build index thì probe có thể trượt, thử lại sau.

**Đổi `GEMINI_EMBEDDING_MODEL` rồi search báo lỗi vector**
Index cũ không dùng lại được. Chạy `docker compose down -v`, hoặc xoá riêng volume `blueai-auth_blueai-rag-index`.

**Cổng đã bị chiếm**
`netstat -ano | findstr ":3000 :8080 :8000 :33069"` rồi tắt tiến trình đang giữ cổng, hoặc sửa mục `ports` trong `compose.yaml`.

## 11. Lưu ý bảo mật

- Không commit `.env`. Root `.gitignore` đã chặn `.env` và `.env.*` nhưng vẫn giữ `.env.example`.
- FAISS index được nạp với `allow_dangerous_deserialization=True`, tức là pickle — chỉ nạp index do chính project này sinh ra.
- Free tier Gemini chỉ nên dùng với dữ liệu demo, không chứa PII hay bí mật doanh nghiệp.
- Chi tiết kiến trúc và hợp đồng Java ↔ Python: `BE/README.md`, `BE/RAG_WORKER_CONTRACT.md`, `feature-rag-chatbot/rag-chatbot/README.md`.



