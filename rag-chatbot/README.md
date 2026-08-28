# BlueAI RAG Chatbot

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
