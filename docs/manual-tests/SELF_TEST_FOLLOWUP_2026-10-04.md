# Hướng dẫn tự kiểm bản bổ sung

## Chuẩn bị

Mở **http://localhost:23000** (QA). Cổng 3000 là stack chính chưa thay image. Chỉ dùng dữ kiện giả; không đưa API key hoặc dữ liệu khách hàng thật vào chat. Nếu cần đăng nhập, tài khoản fixture QA là `acceptance-admin@example.test`, mật khẩu `QaAcceptance2026!Fixture-Only`; không sử dụng credential này trên production.

Không bấm **Áp dụng cho Hỏi BlueAI** trên bot thử nghiệm: thao tác đó đổi bot active toàn cục. Preview không cần activate.

## 1. Knowledge Base có dữ liệu thật để kiểm

Vào **Tài liệu & Tri thức RAG**. Xác nhận `qa-sales-policy.md`, ID `b35c58bf-d447-47cb-90b5-583d82bb489a`, READY và 1 chunk. Một record FAILED cũ được giữ làm bằng chứng, không phải upload mới bị lỗi.

Tạo hội thoại mới trong Hỏi BlueAI và hỏi:

> Theo tài liệu QA Aurora, gói Pro hỗ trợ bao nhiêu sales, giá minh họa bao nhiêu mỗi tháng và triển khai mất mấy ngày làm việc? Nêu nguồn tài liệu.

Kỳ vọng: 60 sales, 2.500.000 VND/tháng, 7 ngày làm việc; có citation nguồn tài liệu. Tất cả là dữ liệu giả. Hỏi tiếp về giờ ngày lễ: tài liệu không có, bot phải nêu thiếu căn cứ, không tự bịa.

## 2. Nhớ trong một phiên

Tạo phiên A và gửi:

> Tôi đang tư vấn khách giả lập QAOrion04. Khách có 37 sales, ngân sách 2.900.000 VND mỗi tháng, mã cơ hội OR-0410. Chưa chốt báo giá. Chỉ ghi nhận dữ kiện, không gọi đây là chính sách sản phẩm.

Hỏi tiếp: “Nhắc lại số sales, ngân sách và mã cơ hội của khách này.” Kỳ vọng đúng ba dữ kiện, không nhầm với giá gói trong KB. Reload rồi kiểm lịch sử vẫn còn.

## 3. Nhớ giữa hai phiên, không liên kết thủ công

Tạo **Cuộc trò chuyện mới** B, mở **Quản lý context và ghi nhớ**, chọn **Tự tìm trong lịch sử**. Không thêm link hoặc ghi nhớ USER cho bước này, để không nhầm cơ chế.

Hỏi:

> Tôi đang chuẩn bị buổi trao đổi tiếp theo cho khách QAOrion04. Dựa vào các phiên trước của tôi, nhắc đúng số sales, ngân sách tháng và mã cơ hội đã ghi nhận. Đừng tự đoán nếu không có nguồn.

Kỳ vọng: 37 / 2.900.000 VND / OR-0410; trong bằng chứng context có nguồn history đúng phiên A. Đừng chỉ đánh giá câu trả lời nghe hợp lý: phải kiểm nguồn và từng con số. Cách tìm hiện tại là heuristic từ khóa, không bảo đảm mọi cách diễn đạt đều khớp.

## 4. Kiểm giới hạn và liên kết chủ động

Tạo phiên C, chọn **Chỉ phiên hiện tại**, hỏi lại dữ kiện QAOrion04. Khi chưa tạo ghi nhớ USER, không được tự lấy từ phiên A; bot cần nói thiếu context.

Sau đó chọn **Các phiên đã liên kết**, thêm phiên A và hỏi lại. Phải thấy nguồn linked snapshot. Khi sửa thêm thông tin ở A, dùng làm mới link để lấy snapshot mới; không mặc định link luôn cập nhật tự động.

Ghi nhớ USER là cơ chế riêng có thể dùng qua nhiều phiên, nên không lấy chế độ “Chỉ phiên hiện tại” làm lời hứa loại bỏ mọi ghi nhớ đã xác nhận trước đó.

## 5. Upload và thông báo lỗi

Thử file TXT/MD UTF-8 nhỏ với nội dung giả, rồi bấm Làm mới chờ READY hoặc đọc failure reason nếu parser không đọc được. Không tải file chứa bí mật.

Chọn file rỗng: phải báo lỗi. Chọn file TXT >20 MiB: phải thấy “Tài liệu vượt giới hạn 20 MiB…”, không thêm record. File đúng 20 MiB là ranh giới được phép; không cần tự lặp upload lớn để tránh nặng QA. Backend đã kiểm 2/20/21 MiB qua ingress thật.

File thử quá mức đã có tại `C:/Users/ductr/AppData/Local/Temp/blueai-qa-upload-qJRf2H/qa-oversized-21MiB.txt`. Có thể sinh một fixture mới bằng `docs/manual-tests/qa-browser-upload-fixture.mjs` nếu thư mục temp đã bị dọn.

Không chỉ đổi đuôi `.exe` thành `.pdf`: server kiểm signature/structure. TXT/CSV/MD yêu cầu UTF-8; DOCX macro/embedded object bị từ chối. PDF/DOCX kiểm cấu trúc không đồng nghĩa bảo đảm mọi parser exploit đều được chặn.

## 6. Preview Dialog và HTML

Vào **BlueAI Custom**, chọn `QA security preview mut11h75`, mở **Luồng hội thoại** và **Thử BlueAI**. Gửi `qa security synthetic unmatched input`.

Kỳ vọng: payload `<img ...><script>...</script>` xuất hiện dưới dạng chữ, không mở popup/chạy script hoặc tạo ảnh payload. Đây là fixture cố ý chứa HTML để kiểm bảo mật, không phải nội dung tư vấn thật. Không activate bot này. Có thể dùng các bot Sales demo khác để kiểm nghiệp vụ báo giá; demo không tạo đơn hàng thật.

## 7. Ghi kết quả, không tự thử trên production

Ghi mỗi bước PASS/FAIL, input, kết quả mong đợi/thực tế và ảnh. Nếu bot trả sai dữ kiện, giữ hội thoại đó, không sửa tay đáp án rồi coi là PASS. Gửi tên phiên và ảnh, không gửi token/cookie/key.

Drill Redis, quota, migration và scanner đã chạy bằng helper QA. Không stop database/Redis stack chính để tự thử; không spam provider thật. Local pass chưa thay team review, GitHub CI cho commit mới, staging HTTPS/VPS, dependency treatment và release approval.
