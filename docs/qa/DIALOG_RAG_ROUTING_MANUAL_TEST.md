# Manual test — Dialog không chặn câu hỏi Knowledge Base

## Phạm vi và điều kiện

- Kiểm thử cả Chỉ RAG, RAG + Dialog và Chỉ Dialog.
- Dùng tài khoản kiểm thử. Không gửi thông tin khách hàng thật hoặc API key.
- Chỉ RAG đang dùng ở chat chính không cần bật Dialog để kiểm chứng bản vá.
- Dùng bảng Thử BlueAI cho cấu hình hybrid; không tự phát hành bot hoặc mở tài liệu nội bộ cho widget public.
- Chỉ tạo widget/session trên bot QA với tài liệu đã được phép public.
- Tài liệu READY và số chunks là tín hiệu ingestion hoàn tất, không phải xác nhận dữ liệu nghiệp vụ đã duyệt.
- Mỗi lần thử bắt đầu với context sạch trừ ca kiểm tra nhiều lượt.

| ID | Chế độ / thao tác | Kết quả cần thấy |
|---|---|---|
| DR-01 | Hybrid: “chào bạn” | Dialog chào hỏi, không gọi LLM. |
| DR-02 | Hybrid: “chào bạn, nếu tôi du lịch châu Âu thì có gói eSIM nào và tốc độ ra sao?” | Chạy RAG; không trả hướng dẫn demo A/B. Dẫn nguồn khi có. |
| DR-03 | Hybrid: câu DR-02 viết không dấu / viết hoa | Không bắt nhầm thành lời chào. |
| DR-04 | Hybrid: hỏi gói Nhật Bản, dung lượng và tốc độ | Chạy RAG; không bịa catalog/giá hoặc tốc độ thiếu trong KB. |
| DR-05 | Hybrid: “eSIM có dùng được cho máy lock không?” | Tra cứu hướng dẫn tương thích, không lặp lời chào. |
| DR-06 | Hybrid: chào trước, sau đó hỏi như DR-05 | Câu thứ hai chạy RAG, không bị node chào giữ lại. |
| DR-07 | Hybrid: “giờ hoạt động”, sau đó “Hà Nội” | Dialog hỏi chi nhánh rồi nhận dữ kiện đúng, không gọi RAG cho lượt trả dữ kiện. |
| DR-08 | Hybrid: “giờ hoạt động”, sau đó “mình chưa rõ” | Giữ bước đang hỏi; không tự điền dữ kiện hay gọi nhầm tác vụ. |
| DR-09 | Hybrid: đang hỏi chi nhánh, chuyển sang câu DR-05 | Chạy RAG cho câu mới, bỏ trạng thái tác vụ cũ. Lịch sử chat vẫn được lưu bình thường. |
| DR-10 | Hybrid: “chào bạn, tôi cần báo giá” | Nhận mục đích báo giá đã cấu hình, không dừng ở lời chào. |
| DR-11 | Hybrid: đang chờ xác nhận báo giá, hỏi eSIM, rồi gõ “xác nhận” | Không xác nhận tác vụ cũ; không tạo đơn hàng hoặc suy diễn đồng ý. |
| DR-12 | Chỉ RAG: hỏi DR-02 và câu hỏi nối tiếp | Tiếp tục RAG và dùng context; không chạy demo Dialog. |
| DR-13 | Chỉ Dialog: câu hỏi ngoài kịch bản | Không gọi provider; giữ giới hạn của chế độ chỉ Dialog. |
| DR-14 | Widget hybrid: câu DR-02 / DR-05 | RAG chỉ dùng tài liệu allowlist của bản phát hành; không lộ tài liệu private, context hoặc trace quản trị. |
| DR-15 | Widget: retry cùng requestId, hai visitor khác nhau | Không sinh thêm lượt LLM khi retry; không chia sẻ lịch sử giữa visitor. |
| DR-16 | Chat / preview: tài liệu không tồn tại hoặc thiếu thông tin | Nói rõ thiếu nguồn; không biến demo thành chính sách thật. |

## Giới hạn cần ghi trong báo cáo

Bộ định tuyến vẫn dùng quy tắc từ vựng, không phải bộ phân loại ngữ nghĩa hoàn hảo. Tên gói, coverage, giá và tốc độ phải có catalog đã được doanh nghiệp xác nhận. Kiểm thử provider giả lập chứng minh routing và quyền truy cập, không chứng minh chất lượng trả lời của model thật.
