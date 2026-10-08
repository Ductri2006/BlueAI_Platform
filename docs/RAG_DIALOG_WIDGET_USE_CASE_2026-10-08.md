# Use case: trợ lý sales/CSKH RAG + Dialog nhúng website

Bản source trên nhánh `feature/AI_debugg`. Chưa deploy.

## Tạo và cấu hình

1. Đăng nhập ADMIN, vào **BlueAI Custom → tạo bot → Demo sales — RAG + Dialog cho website**.
2. Tại **Cấu hình BlueAI chính**, chọn chế độ và tài liệu **READY** được phép công khai cho khách website.
3. **Lưu → thử bot → phát hành**. Copy script trong tab **Tích hợp** sang website đích. Khi hạ tầng khác origin, cấu hình `APP_WIDGET_ALLOWED_ORIGINS` cho domain đích; widget lấy API host từ URL script hoặc `data-api-base`.
4. Muốn áp dụng cho **Hỏi BlueAI** nội bộ, dùng thao tác **Áp dụng làm BlueAI chính** riêng. Widget luôn dùng release của bot trong script.

| Chế độ | `dialogEnabled` | `blueAiCoreEnabled` | Hành vi |
| --- | --- | --- | --- |
| RAG + Dialog | true | true | Dialog xử lý intent/slot; câu hỏi ngoài kịch bản chuyển RAG |
| Chỉ RAG | false | true | Bỏ qua intent, entity, welcome và pending slot của Dialog |
| Chỉ Dialog | true | false | Chỉ chạy kịch bản, không gọi retrieval hoặc LLM |

Không được tắt cả hai. Chế độ và danh sách tài liệu được lưu trong snapshot release; đổi bản nháp không đổi phiên widget đang mở. Sau khi phát hành lại, tải lại trang/bắt đầu phiên mới để dùng release mới. Cấu hình cũ thiếu `dialogEnabled` mặc định bật Dialog; release cũ thiếu danh sách công khai không có quyền dùng KB public.

## Luồng demo kết hợp

Upload [FAQ minh họa](fixtures/widget-public-faq-demo.txt), đợi READY rồi chọn tài liệu này tại danh sách công khai trước khi publish. Các chính sách trong fixture chỉ là dữ liệu demo.

| Tình huống | Câu thử | Kết quả mong đợi |
| --- | --- | --- |
| Chào hỏi | Xin chào | Dialog giới thiệu các kịch bản demo |
| Giờ chi nhánh | Giờ hoạt động → Hà Nội | Dialog hỏi slot và trả lịch demo; không gọi AI trong lúc chờ slot |
| Chính sách ngoài kịch bản | Điều kiện hoàn trả hàng lỗi là gì? | RAG dùng FAQ đã chọn và trả lời theo tài liệu |
| Thu thập yêu cầu | Báo giá → sản phẩm A → 20 → HCM → xác nhận | Dialog hỏi thông tin, tóm tắt và xác nhận; chưa tính giá, chưa tạo đơn/CRM |
| Hỏi tiếp | Giải thích thêm chính sách vừa nêu | RAG có lịch sử riêng của phiên widget, tối đa 12 message; không dùng memory/hội thoại nhân viên |
| Chỉ RAG | Chọn chỉ RAG, lưu/publish, mở phiên mới, hỏi xin chào | Không chạy câu chào của Dialog |
| Chỉ Dialog | Chọn chỉ Dialog, lưu/publish, mở phiên mới, hỏi chính sách ngoài kịch bản | Fallback kịch bản; không gọi AI |
| Không có nguồn | Bỏ chọn tài liệu, publish hoặc xóa tài liệu đã chọn | Thông báo chưa đủ thông tin; không dùng tài liệu nội bộ khác để thay thế |
| Gửi lại do mạng | Cùng `requestId` và nội dung | Trả response đã lưu, không gọi provider thêm |

Widget chỉ retrieval trên `allowedDocumentIds` trong release, sau đó backend kiểm tra lại ownership, trạng thái READY và danh sách này trước khi tạo prompt. Tài liệu admin khác, tài liệu chưa chọn và dữ liệu nhân viên không được dùng trong prompt widget. API public chỉ trả message/fallback; không trả trace, intent, entity hay context nội bộ. Quota, widget token và idempotency vẫn được áp dụng cho cả ba chế độ.

## Một tài khoản, một phiên đăng nhập

Đăng nhập thành công ở thiết bị B thu hồi toàn bộ refresh token cũ và thay `active_session_id` của tài khoản trong transaction có khóa user. Access token chứa `sessionId`; validator đối chiếu database ở mỗi request nên access token của A bị từ chối ngay, không đợi JWT hết hạn. Refresh ở A trả `401 SESSION_REPLACED`; browser refresh cookie cũ được xóa.

Thiết bị A quay về đăng nhập ở request tiếp theo; khi trang đang mở và không thao tác, frontend kiểm tra mỗi 15 giây và khi quay lại tab/focus. Lỗi mạng không tự đăng xuất. Login sai mật khẩu không đẩy thiết bị đang dùng; logout từ phiên cũ không thu hồi phiên mới. Hai login đồng thời được khóa theo tài khoản và chỉ phiên thành công sau cùng còn hợp lệ. Refresh rotation cùng một phiên không thay sessionId. Sau khi nâng cấp V10, token cũ chưa có sessionId phải đăng nhập lại; không được hồi sinh sau logout.

## Migration và kiểm chứng

`V10__bot_modes_and_single_login.sql` thêm `app_users.active_session_id`, `studio_bots.dialog_enabled` và `studio_bots.widget_document_ids`. Không sửa checksum các migration cũ. Chưa chạy migration trên môi trường đang phục vụ.

Regression tests: `SingleLoginIntegrationTest`, `SingleLoginMySqlIntegrationTest`, `WidgetRagDialogIntegrationTest`, `studioModes.test.tsx`; migration tests cập nhật kỳ vọng V10. Bộ MySQL chỉ chạy khi có database test riêng `blueai_auth_test` và được thêm vào CI. Test widget/provider dùng stub; cần xác nhận provider/key và domain thật khi test staging sau này.
