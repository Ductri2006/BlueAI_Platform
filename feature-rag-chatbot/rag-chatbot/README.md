# BlueAI RAG Chatbot

[![RAG Chatbot CI](https://github.com/Ductri2006/BlueAI_Platform/actions/workflows/rag-ci.yml/badge.svg)](https://github.com/Ductri2006/BlueAI_Platform/actions/workflows/rag-ci.yml)

Chatbot RAG cho hệ sinh thái eSIM Bluecom. Module đọc knowledge base
dạng CSV, tạo embedding bằng Gemini, lưu vector trong FAISS và dùng Gemini để
trả lời dựa trên các tài liệu được truy xuất.

## Dữ liệu

File `Papers/blueai_rag_knowledge_base_sample.csv` gồm 33 record tổng hợp phục vụ
demo. Tất cả record hiện có `source_type=SYNTHETIC_DEMO` và
`approval_status=PENDING_OWNER_REVIEW`; không được xem chúng là chính sách chính
thức của Bluecom.

## Chạy trên Windows

```powershell
cd backend/feature-rag-chatbot/rag-chatbot
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

Mở `.env`, đặt Gemini API key và `JAVA_WORKER_API_KEY` thành secret riêng, ngẫu
nhiên tối thiểu 32 byte; dùng cùng giá trị `APP_WORKER_API_KEY` trong
`backend/BE/.env`. Có thể dùng `GEMINI_API_KEY` hoặc `GOOGLE_API_KEY`; nếu đặt cả
hai thì Google client ưu tiên `GOOGLE_API_KEY`. Sau đó chạy service cùng document worker:

```powershell
.\scripts\start-rag-local.ps1
```

Service cung cấp `GET /health`, `GET /ready`, `POST /v1/retrieval/search` và endpoint tương thích
OpenAI `POST /v1/chat/completions`. Java BE gọi hai API nội bộ với header
`X-Worker-Api-Key`; thiếu hoặc sai khóa sẽ bị từ chối. `chatbot.py` vẫn có thể
chạy riêng như CLI để thử nhanh một câu hỏi.

`/health` là liveness và trả trạng thái cấu hình không chứa secret. `/ready` chỉ
trả `200` khi đã có Gemini key; Docker dùng endpoint này để không báo hệ thống AI
sẵn sàng giả khi thiếu credential.

Khi có `JAVA_API_BASE_URL` và `JAVA_WORKER_API_KEY`, service cũng tự claim tài
liệu đã upload, đọc PDF/DOCX/TXT/Markdown, chunk, thêm embedding vào FAISS và báo
trạng thái `READY` về Java. Index được lưu ở `RAG_INDEX_DIR`; Docker Compose gắn
volume để không mất index khi container khởi động lại. Mỗi chunk tài liệu riêng
được gắn `ownerId` và bị lọc theo người dùng ở endpoint retrieval.

Không commit `.env`, virtual environment, log, FAISS index hoặc dữ liệu thật của
khách hàng. Free Tier của Gemini chỉ nên được dùng với dữ liệu demo không chứa
PII hoặc bí mật doanh nghiệp.

## Kiểm thử end-to-end

Sau khi Spring Boot ở cổng 8080 và RAG service ở cổng 8000 đều chạy, script sau
đăng nhập bằng admin, upload tài liệu mẫu thành nguồn doanh nghiệp, chờ worker
đánh dấu `READY`, hỏi một câu khách hàng và kiểm tra các dữ kiện cùng citation:

```powershell
.\scripts\test-rag-e2e.ps1
```

Fixture nằm tại `samples/blueai-rag-e2e-sao-bang-47.md`. Script không in access
token hoặc Gemini key ra terminal.

## CI/CD

GitHub Actions tự động kiểm tra syntax, dependency, knowledge base, unit test và
secret pattern trên mỗi push hoặc pull request liên quan đến `rag-chatbot/`.
Các bước này không gọi Gemini API và không cần API key.

Continuous Delivery build Docker image và push lên GitHub Container Registry khi
tạo tag dạng `rag-v*`:

```powershell
git tag rag-v0.1.0
git push origin rag-v0.1.0
```

Cũng có thể chạy workflow `RAG Chatbot Delivery` thủ công trong GitHub Actions.
Workflow chỉ xuất bản image, chưa tự deploy lên cloud. Khi chạy container, truyền
secret bằng file `.env` cục bộ thay vì đưa API key vào image:

```powershell
docker run --rm --env-file .env -p 8000:8000 `
  ghcr.io/ductri2006/blueai_platform/rag-chatbot:rag-v0.1.0
```
