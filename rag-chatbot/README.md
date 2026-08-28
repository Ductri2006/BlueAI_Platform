# BlueAI RAG Chatbot

[![RAG Chatbot CI](https://github.com/Ductri2006/BlueAI_Platform/actions/workflows/rag-ci.yml/badge.svg)](https://github.com/Ductri2006/BlueAI_Platform/actions/workflows/rag-ci.yml)

Chatbot RAG thử nghiệm cho hệ sinh thái eSIM Bluecom. Module đọc knowledge base
dạng CSV, tạo embedding bằng Gemini, lưu vector trong FAISS và dùng Gemini để
trả lời dựa trên các tài liệu được truy xuất.

## Dữ liệu

File `Papers/blueai_rag_knowledge_base_sample.csv` gồm 33 record tổng hợp phục vụ
demo. Tất cả record hiện có `source_type=SYNTHETIC_DEMO` và
`approval_status=PENDING_OWNER_REVIEW`; không được xem chúng là chính sách chính
thức của Bluecom.

## Chạy trên Windows

```powershell
cd rag-chatbot
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

Mở `.env`, thay placeholder bằng Gemini API key của riêng bạn rồi chạy:

```powershell
python chatbot.py
```

Không commit `.env`, virtual environment, log, FAISS index hoặc dữ liệu thật của
khách hàng. Free Tier của Gemini chỉ nên được dùng với dữ liệu demo không chứa
PII hoặc bí mật doanh nghiệp.

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
docker run --interactive --tty --rm --env-file .env `
  ghcr.io/ductri2006/blueai_platform/rag-chatbot:rag-v0.1.0
```
