# Báo cáo demo 6 kịch bản sales Dialog

Ngày kiểm tra: 03/10/2026. Nhánh làm việc: `feature/conversation-context`.

## 1. Kết quả và phạm vi

Đã xây dựng một mẫu khởi tạo mới trong BlueAI Custom, lấy ý tưởng cấu hình Dialog theo intent, entity, node cha/con và context của Watson. Đây là bản triển khai trên hệ thống BlueAI hiện có, **không phải một assistant được tạo trên IBM Watson** và không khẳng định tương đương mọi tính năng Watson.

Mẫu có 3 intent, 4 entity và 13 node. Tạo mẫu mới chỉ tạo bản nháp của người dùng hiện tại; không phát hành, không kích hoạt và không thay bot đang dùng. Mẫu tắt Core/LLM để thử các nhánh xác định, không gọi provider hay knowledge base.

Đã tạo và thao tác trực tiếp trên giao diện QA với bot **Sales DEMO - 6 kịch bản Watson**, ID `c50cbed3-5088-4f5c-ab3f-6e8ee64d4bc1`. Bot vẫn là DRAFT, chưa kích hoạt. Tại thời điểm hoàn tất kiểm thử local, thay đổi nguồn chưa commit/push; trạng thái Git/CI sau đó cần đối chiếu trên branch.

## 2. Sáu kịch bản đã thực hiện

| Kịch bản | Cấu hình và nghiệp vụ | Kiểm tra trên giao diện | Kết quả |
| --- | --- | --- | --- |
| 1. Chào hỏi / hướng dẫn | WELCOME giới thiệu phạm vi; intent `chao_hoi` chỉ dẫn cách dùng | Mở preview; gửi `xin chào` | Đạt: giới thiệu đúng khả năng và giới hạn demo |
| 2. Giờ chi nhánh | Intent `hoi_gio`; hỏi entity `chi_nhanh` còn thiếu; chọn một trong 3 node con | `hỏi giờ Hà Nội`, `hỏi giờ Sài Gòn`, `hỏi giờ Đà Nẵng` | Đạt: trả đúng lịch minh họa theo từng chi nhánh; Sài Gòn nhận là HCM |
| 3. Thu thập yêu cầu báo giá | Intent `bao_gia`; lần lượt hỏi sản phẩm, số lượng, khu vực; SKIP_USER_INPUT bỏ qua các câu hỏi đã đủ dữ kiện | `báo giá` → `sản phẩm A` → `20` → `Hà Nội`; thử thêm một câu chứa đủ 3 dữ kiện | Đạt: hỏi đúng thông tin thiếu, sau đó đọc lại yêu cầu; không tính giá thật |
| 4. Xác nhận / từ chối | Entity `xac_nhan` điều hướng tới hai node con khác nhau; phải dừng ở tóm tắt trước khi xử lý tiếp | `xác nhận`; lượt khác gửi `không đồng ý` | Đạt: hai kết quả khác nhau, không nhận nhầm lời từ chối thành đồng ý |
| 5. Sửa dữ kiện trước xác nhận | Giữ node đang chờ xác nhận và các entity đã thu thập; cập nhật entity mới được nhận diện | Khi tóm tắt 20 sản phẩm A giao Hà Nội, gửi `đổi thành 30` | Đạt: đổi số lượng thành 30, giữ sản phẩm A và Hà Nội, đọc lại để xác nhận |
| 6. Không hiểu yêu cầu | Node FALLBACK giải thích phạm vi hỗ trợ và hướng dẫn bắt đầu lại | `thời tiết sao hỏa` | Đạt: trả thông báo dự phòng đã cấu hình, không gọi AI/KB |

Giờ mẫu: Hà Nội 08:00–21:00; HCM 08:00–22:00; Đà Nẵng 08:30–21:00. Đây là dữ liệu minh họa, không phải lịch hoạt động của doanh nghiệp đã xác nhận.

Ngoài 6 nhánh chính, đã thử `hủy`, bắt đầu yêu cầu báo giá mới sau một lần xác nhận/từ chối và không mang khu vực của câu hỏi giờ sang yêu cầu báo giá mới. Lần kiểm tra chính gồm lời chào mở đầu và 15 lượt gửi chat; sau đó kiểm tra lại một lượt sửa số lượng để lưu ảnh bằng chứng.

## 3. Cách tự thử lại

### Với môi trường QA đang mở

1. Truy cập `http://localhost:19003/`, chọn **BlueAI Custom** bằng tài khoản quản trị QA hiện có.
2. Chọn **Sales DEMO - 6 kịch bản Watson**.
3. Mở **Thử BlueAI**. Giữ **Context nền cho lượt thử → Không dùng hội thoại có sẵn**.
4. Nhấn **Xoá ngữ cảnh thử nghiệm** nếu muốn bắt đầu lượt mới. Thao tác này chỉ làm sạch context preview, không xóa hội thoại thật.
5. Gửi lần lượt:

```text
báo giá
sản phẩm A
20
Hà Nội
đổi thành 30
xác nhận
```

Ở câu `đổi thành 30`, kiểm tra hai vị trí: câu trả lời phải là 30 sản phẩm A giao Hà Nội; phần **Context cho câu tiếp theo** phải giữ `san_pham=Sản phẩm A`, `chi_nhanh=Hà Nội`, `so_luong=30` và đang đợi `xac_nhan`.

6. Thử lời từ chối bằng lượt mới:

```text
báo giá 20 sản phẩm A giao Hà Nội
không đồng ý
```

7. Thử `hủy`, `hỏi giờ Sài Gòn`, hoặc `thời tiết sao hỏa` để kiểm tra nhánh khác.

### Tạo lại mẫu sau khi lấy code

Backend phải được build/chạy từ nguồn mới; frontend phải dùng phiên bản mới cùng API phù hợp. Trong BlueAI Custom, chọn tạo cấu hình mới, chọn **Mẫu khởi tạo → Demo sales — 6 kịch bản Dialog**, đặt tên rồi tạo. Cấu hình mẫu được đóng gói trong backend nên không cần nhập tay 13 node hoặc sao chép dữ liệu QA.

Bot có sẵn trên máy QA không tự xuất hiện ở database của đồng đội. Đồng đội cần tạo bản nháp riêng bằng lựa chọn mẫu trên giao diện. Tài khoản vẫn phải có quyền Studio theo chính sách của hệ thống.

## 4. Các file đã thêm hoặc sửa

### Backend

| File | Vai trò |
| --- | --- |
| `BE/src/main/java/com/blueai/studio/model/BotCreationTemplate.java` — mới | Enum lựa chọn STARTER hoặc SALES_DEMO; không nhận đường dẫn tài nguyên tùy ý |
| `BE/src/main/java/com/blueai/studio/dto/BotStudioDtos.java` — sửa | Thêm lựa chọn template cho request; giữ constructor cũ và khả năng xử lý payload cũ |
| `BE/src/main/java/com/blueai/studio/service/SalesDialogDemoTemplate.java` — mới | Nạp cấu hình từ tài nguyên cố định, đóng stream đúng cách và trả các danh sách bất biến |
| `BE/src/main/resources/studio/sales-dialog-demo.json` — mới | Nguồn cấu hình duy nhất của 6 kịch bản: intent, entity, quan hệ cha/con, câu hỏi thiếu dữ kiện và bước tiếp theo |
| `BE/src/main/java/com/blueai/studio/service/BotStudioService.java` — sửa | Chọn mẫu, validate rồi lưu cấu hình trong luồng tạo bản nháp hiện có; tắt Core riêng cho mẫu demo |
| `BE/src/main/java/com/blueai/studio/service/DialogRuntimeService.java` — sửa | Render biến context trong câu hỏi thiếu dữ kiện, tránh hiển thị nguyên văn `{{san_pham}}` hoặc `{{so_luong}}` |
| `BE/src/test/java/com/blueai/studio/service/SalesDialogDemoTemplateTest.java` — mới | 16 ca chạy cho cấu hình và hành vi runtime, dùng đúng JSON đóng gói |
| `BE/src/test/java/com/blueai/studio/controller/BotStudioIntegrationTest.java` — sửa | Thêm 3 ca tích hợp cho lưu bản nháp, quyền truy cập/ownership, template không hợp lệ và payload cũ |

### Frontend

| File | Vai trò |
| --- | --- |
| `FE/src/api/blueAiApi.ts` — sửa | Bổ sung trường template tùy chọn trong request tạo bot |
| `FE/src/components/studio/StudioTemplateSelector.tsx` — mới | Bộ chọn mẫu tái sử dụng ở màn hình tạo đầu tiên và modal tạo mới; ghi rõ giới hạn và tác động |
| `FE/src/components/studio/ChatbotStudio.tsx` — sửa | Gửi mẫu được chọn; mở tab Dialog sau khi tạo demo; thông báo rõ đây là bản nháp, không thay bot đang dùng |
| `FE/src/components/studio/StudioTemplateSelector.test.tsx` — mới | 2 ca kiểm tra lựa chọn/giải thích và request API cũ/mới |
| `FE/src/components/studio/StudioPreviewFallbackBadge.tsx` — mới | Hiển thị “Dự phòng” cho fallback, không gắn nhãn đó khi Dialog vẫn đang hỏi entity còn thiếu |
| `FE/src/components/studio/StudioPreviewFallbackBadge.test.tsx` — mới | 3 ca kiểm tra nhãn cho câu hỏi lại, fallback thật và trường hợp không cần nhãn |
| `FE/src/components/studio/TryItOutPanel.tsx` — sửa | Dùng badge mới; không đổi logic lưu/carry context của backend |

Thêm chính báo cáo này trong `docs/SALES_DIALOG_SIX_SCENARIOS_DEMO_2026-10-03.md`.

### Hai lỗi/nhầm lẫn phát hiện khi thử

1. Câu hỏi thiếu dữ kiện trước đây chưa render các biến đã biết. Đã sửa một dòng tại `respondFromNode`, có kiểm thử để câu hỏi hiển thị sản phẩm/số lượng thực tế.
2. Khi người dùng chỉ nói `đổi thành 30`, runtime có thể không nhận một intent mới, nhưng vẫn đang xử lý node Dialog chờ xác nhận. Giao diện trước đây lấy cờ fallback cũ để gắn nhãn gây hiểu nhầm. Đã sửa phần nhãn hiển thị; trace vẫn là DIALOG, không thay đổi hợp đồng an toàn carry/reset context của runtime.

### Điều chỉnh trải nghiệm theo hướng dẫn Emil design engineering

| Before | After | Why |
| --- | --- | --- |
| Chỉ có mẫu khởi tạo chung | Bộ chọn mẫu riêng với mô tả và giới hạn ngay tại nơi tạo | Người dùng biết mình đang tạo gì và bot đang dùng có bị thay hay không |
| Một câu hỏi lại đang chờ dữ kiện cũng có thể hiện “fallback” | Chỉ hiển thị nhãn “Dự phòng” khi không đang chờ entity | Tránh hiểu nhầm một nhánh Dialog hoạt động đúng là lỗi hoặc mất context |

## 5. Bằng chứng kiểm thử

| Kiểm tra | Kết quả |
| --- | --- |
| Backend tập trung: Studio integration + Dialog runtime + template | 135 ca đạt; 0 lỗi, 0 thất bại, 0 bỏ qua |
| Backend verify tổng thể | 355 ca được phát hiện: 326 đạt, 29 bỏ qua có điều kiện môi trường; 0 lỗi, 0 thất bại; BUILD SUCCESS |
| Frontend tests | 101 ca đạt trong 10 file |
| TypeScript | Đạt |
| Frontend production build | Đạt |
| Build Docker image backend QA | Đạt: `blueai-six-scenarios-api-qa:20261003` |
| Backend QA health | UP sau khi thay image |
| Thử trực tiếp trên giao diện | Cả 6 kịch bản đạt, có kiểm tra sửa dữ kiện, từ chối, hủy và yêu cầu mới |
| Quét secret trên patch và 8 file nguồn/test mới bằng Gitleaks | Không phát hiện secret trong phần được quét; không đọc/in key hoặc file `.env` |
| Kiểm tra phạm vi thay đổi GitNexus | 7 file đã track, 12 symbol và 12 luồng chịu ảnh hưởng; mức HIGH do API/UI dùng chung; các file mới được rà riêng |
| `git diff --check` | Đạt |

Phân tích ảnh hưởng đã thực hiện trước các sửa đổi dùng chung, bao gồm create API và `respondFromNode`; đã dùng kiểm thử hồi quy tổng thể để kiểm tra các bên gọi. GitNexus dùng index trước sửa đổi và không liệt kê đầy đủ file untracked; vì vậy không coi số 7 là tổng số file của bản vá.

Các ca bỏ qua có điều kiện môi trường **không được tính là đã đạt**. Tại thời điểm hoàn tất kiểm thử local, chưa chạy một pipeline CI GitHub cho bản vá này; cũng chưa chạy lại toàn bộ các gate production MySQL/ingress ở lượt triển khai demo này. Kết quả CI sau khi push phải được xem riêng, không suy ra từ kết quả local. Không dùng kết quả này để tuyên bố đủ điều kiện production hoặc không thể còn lỗi.

Nhật ký local nằm ở thư mục Temp:

- `blueai-six-scenarios-be-20261003.log`
- `blueai-six-scenarios-full-20261003.log`
- `blueai-six-scenarios-fe-build-final-20261003.log`
- `blueai-six-scenarios-image-20261003.log`
- `blueai-six-scenarios-secrets-20261003.json`

Ảnh bằng chứng được lưu trong thư mục visualizations của phiên Codex, tên `sales-six-scenarios-context-20261003.jpg`; câu `đổi thành 30` và context sản phẩm A/Hà Nội/30 cùng xuất hiện trên giao diện.

## 6. An toàn môi trường và giới hạn

- Không chỉnh key, `.env`, tài khoản provider hoặc database của stack chính. Không purge lịch sử Git ở nhiệm vụ này.
- Chỉ thay backend QA ở cổng loopback `19081`; frontend QA dùng `19003`. Stack chính `localhost:3000` và `18080` không bị thay.
- Container QA cũ được giữ ở trạng thái dừng với tên `blueai-dialog-api-qa-before-sales-20261003` để có thể quay lại; không xóa volume hay dữ liệu.
- Không đổi kiểm tra admin/owner; có ca kiểm tra non-admin bị 403, người khác không đọc được bot của owner và template không hợp lệ bị 400. Không cần migration database mới.
- Mẫu vẫn là bản nháp, không tự phát hành hoặc kích hoạt. Bot active trước đó vẫn giữ nguyên.
- `so_luong` là entity từ điển, chỉ nhận 10, 20, 30, 50, 100 cùng từ đồng nghĩa đã khai báo. **Chưa có bộ nhận diện số tự do/sys-number**.
- “Báo giá” trong mẫu là **thu thập yêu cầu**, không tính giá, không kiểm tồn kho, không lưu CRM, không tạo đơn và không gửi thông tin cho bên khác.
- Mọi node dùng `contextReusePolicy=NONE`. Điều này không ngăn nhớ dữ kiện trong luồng đang chờ; nó ngăn mẫu tự lấy entity từ context hội thoại khác. Bản demo này **không phải bằng chứng kiểm thử nhớ xuyên phiên**.
- Preview là lượt thử riêng; đóng bảng làm mất lịch sử thử. Cấu hình bot được lưu qua API/database nhưng context preview không phải lịch sử sales được lưu lâu dài.
- Báo cáo ghi nhận kết quả kiểm thử trước commit/push, không phải chứng nhận triển khai production. Không merge lên main trong nhiệm vụ này. Team có thể review cấu hình, thay dữ liệu minh họa bằng dữ liệu nghiệp vụ được xác nhận rồi mới đánh giá các bước triển khai thực tế.
