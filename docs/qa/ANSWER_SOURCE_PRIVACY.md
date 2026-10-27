# Ẩn nguồn tài liệu trong câu trả lời khách hàng

Ngày kiểm tra: 28/10/2026. Nhánh: `fix/hide-answer-sources`.

## Hành vi mới

- Hỏi BlueAI không hiển thị số nguồn, tên file, nút mở đoạn trích hoặc các ký hiệu `[S1]` trong câu trả lời. Sao chép và xuất Markdown sử dụng cùng nội dung đã lọc.
- API `POST /api/v1/conversations/{id}/ask` và `GET /api/v1/conversations/{id}/messages` trả danh sách nguồn tài liệu rỗng, cả ở cấp ngoài và trong `assistantMessage.evidence`. Lịch sử cũ có bằng chứng cũng đi qua lớp lọc này.
- Nội dung assistant được lọc ký hiệu nguồn và các tên file/định danh được biết từ bằng chứng của lượt trả lời. Không sửa nội dung người dùng đã gửi.
- Widget, API key bot đã phát hành và phiên bot tích hợp dùng đầu ra công khai đã lọc. Deserialization của lượt widget cũ cũng loại ký hiệu nguồn khi retry.
- Prompt vẫn buộc thông tin sản phẩm, giá, chính sách phải dựa trên tài liệu được phép. Model không nhận tên file hoặc chunk ID từ phần retrieval của prompt, không được liệt kê nguồn và phải nói rõ khi thiếu thông tin.
- Chính sách hiển thị không phụ thuộc giá trị `citationPolicy` cũ đã lưu. Studio thay bộ chọn dẫn nguồn bằng lời giải thích chính sách hiện hành.

## Phạm vi cần phân biệt

Việc tìm tài liệu, kiểm tra READY/quyền truy cập, bằng chứng lưu trong database và context hội thoại vẫn hoạt động. Không có migration hoặc xóa dữ liệu.

Trang quản trị KB và chi tiết chẩn đoán trong preview Studio vẫn dành cho admin theo phân quyền hiện có. API tác vụ nâng cao cấp máy có document grant vẫn giữ hợp đồng evidence nội bộ; không dùng phản hồi đầy đủ của API này để render thẳng ra khách hàng. API key bot thông thường chỉ dùng đầu ra chat công khai.

Lọc trình bày không phải bộ phân loại bí mật tùy ý: nội dung sản phẩm/chính sách được phép vẫn dùng để trả lời. Không đưa bí mật hoặc tài liệu không được phép cho khách vào allowlist của bot. Lịch sử rất cũ không có bằng chứng chỉ có thể lọc các mẫu ký hiệu nguồn đã biết; không thể suy ra mọi tên tài liệu đã từng được viết tự do trong nội dung.

## Kiểm tra tự động

Đã chạy trên mã local, không gửi yêu cầu tới Gemini thật hoặc thay đổi production:

| Nhóm | Kết quả |
| --- | --- |
| Frontend Vitest | 133 test, 14 file, đạt |
| Backend chọn theo phạm vi ảnh hưởng | 117 test, 8 lớp, đạt, không skip |
| Frontend TypeScript và production build | Đạt |

### Kiểm tra bổ sung ngày 28/10/2026

- Đã chạy toàn bộ `mvn verify`: 506 test được phát hiện trong 65 lớp, **461 PASS, 45 SKIPPED, 0 failure/error**. 45 ca bị bỏ qua đều thuộc các lớp MySQL cần database kiểm thử riêng; không tính chúng là PASS. Bản sao source dùng để chạy test đã đối chiếu hash với source làm việc: không sai khác.
- Chạy lại frontend: **133/133 PASS** và build thành công.
- Đã thao tác qua Chrome trên frontend Vite và backend Spring Boot thật, chỉ bind loopback; database H2, retriever/provider giả lập local. Các bước đạt: đăng nhập, cuộc trò chuyện mới, hỏi gói eSIM QA, câu hỏi tiếp nối, reload lịch sử, sao chép, thiếu thông tin và không có lỗi console ở tab nghiệm thu.
- API HTTP thật đã nhận phản hồi provider cố tình chứa tên file, chunk ID và ký hiệu nguồn; câu trả lời/lịch sử được lọc, các danh sách nguồn rỗng và giá 430.000 VND giữ nguyên.
- Xuất Markdown: nút báo thành công, nhưng công cụ chưa xác minh được tệp tải xuống. Truy cập trang Downloads bị chính sách trình duyệt chặn; ca này **PARTIAL**, không phải PASS.
- Phản hồi provider trong thử UI là fixture có sẵn để kiểm tra plumbing/bộ lọc, không phải bằng chứng về khả năng suy luận của Gemini thật. Việc chuyển dữ kiện ngữ cảnh được kiểm tra riêng bởi integration test backend.
- Không phát hiện lỗi trong các ca đã thực thi; còn cần CI JDK 21, các gate MySQL và nghiệm thu với model thật trước production. Chưa push/merge/deploy bản vá.

Báo cáo có ảnh được lưu riêng tại thư mục `blueai-hide-sources-20261028` cạnh checkout để không đưa log, cấu hình QA hoặc tài nguyên tạm vào Git.

Backend chạy với profile `test`, H2 và provider/retrieval giả lập. Các kiểm tra xác nhận dữ liệu KB đi vào prompt đúng, nguồn không ra API/UI, bằng chứng vẫn lưu server, context và phân quyền admin không đổi. Đây không phải kiểm thử chất lượng suy luận của model thật hoặc xác nhận production đã cập nhật. Máy local dùng JDK 25 biên dịch với `release 21`; vẫn cần CI JDK 21 trước khi merge. Docker không chạy nên đợt này không chạy lại MySQL/Redis container.

Các test chính:

- `AnswerPresentationTest`: phản hồi mới/lịch sử, ký hiệu nguồn đơn/nhóm/link, che metadata, không sửa user hoặc bằng chứng gốc, retry đầu ra public cũ.
- `RagChatConfiguredIntegrationTest`: retrieval và provider được gọi, câu trả lời không có nguồn, lịch sử không khôi phục nguồn, database vẫn giữ đúng file/chunk/score.
- `RagChatConversationContextIntegrationTest` và `DialogSalesContextIntegrationTest`: nhớ ngữ cảnh, liên kết phiên, Dialog, xác nhận và routing KB.
- `SharedAdminWorkspaceIntegrationTest`: dùng chung KB giữa admin, chat cá nhân vẫn riêng.
- `WidgetRagDialogIntegrationTest`: chỉ dùng tài liệu READY đã chọn, không lộ nguồn ở đầu ra công khai.
- `PublishedBotApiTest` và `CepIntegrationApiTest`: không làm hỏng key, scope, quota, phiên và hợp đồng tích hợp.
- `answerPresentation.test.tsx`: legacy frontend, render câu trả lời không có tên/đoạn trích/nhãn nguồn; giữ nguyên câu hỏi người dùng.

## Kiểm tra chấp nhận sau khi phát hành

1. Admin xác nhận KB thật READY và bot dùng đúng tài liệu. Không cần upload lại để áp dụng thay đổi hiển thị.
2. Ở Hỏi BlueAI, hỏi một câu có đáp án được xác minh trong KB. Nội dung nghiệp vụ đúng; không có danh sách nguồn, tên file, đoạn trích riêng hoặc `[S1]`.
3. Kiểm tra response của lượt hỏi: `sources` và `assistantMessage.evidence.sources` rỗng. Không đánh đồng việc ẩn nguồn với không chạy retrieval.
4. Tải lại một hội thoại đã từng có dẫn nguồn. API lịch sử và giao diện không trả lại metadata đã ẩn. Nội dung lịch sử gốc trên server không bị ghi đè.
5. Sao chép và xuất Markdown; kiểm tra không có ký hiệu/tên nguồn được biết. Số ngày, dung lượng, giá và nội dung người dùng giữ nguyên.
6. Hỏi tiếp một chi tiết từ lượt trước hoặc liên kết một phiên được quyền sử dụng; kiểm tra vẫn nhớ context.
7. Thử widget và API key bot QA đã phát hành; không có file/chunk/đoạn trích nguồn trong đầu ra công khai, vẫn chỉ dùng allowlist.
8. Hỏi điều KB chưa có và yêu cầu liệt kê tên file nội bộ. Model không bịa thông tin hoặc tuân theo yêu cầu công khai metadata. Dùng model thật ở staging để đánh giá phần hành vi này.

Các tiêu chí cũ yêu cầu khách nhìn thấy citation phải được thay bằng tiêu chí kiểm chứng nội bộ và không lộ metadata ở tài liệu này. Trước phát hành cần triển khai đồng thời backend và frontend; chỉ cập nhật frontend không đủ để ẩn dữ liệu trong API.

Phạm vi báo cáo: kết quả kiểm thử local trước phát hành. Báo cáo này không xác nhận bản vá đã merge hoặc deploy production.
