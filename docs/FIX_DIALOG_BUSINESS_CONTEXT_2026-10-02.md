# Báo cáo và điểm tiếp tục: Dialog trong BlueAI Custom

Ngày: 02/10/2026. Nhánh làm việc: `feature/conversation-context`.
Commit nền: `54ec3e786dd1453ca88b5ee536eec1dca8b5adf1`.
Các thay đổi trong báo cáo **chưa commit, chưa push, chưa merge main**.

> Cập nhật 03/10/2026: các điểm UI/API còn dở ở mục 7 đã được tiếp tục xử lý và kiểm chứng. Xem [báo cáo kiểm tra bổ sung](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/FIX_DIALOG_VERIFICATION_2026-10-03.md>) cho kết quả mới; số liệu và giới hạn bên dưới là trạng thái tại thời điểm 02/10.

## 1. Kết luận hiện tại

Đã triển khai ba phạm vi: nghiệp vụ Dialog/context, giao diện cấu hình an toàn hơn, và kiểm thử hồi quy/tích hợp/giao diện. Những luồng đã kiểm chứng hoạt động đúng trong môi trường QA riêng. Không khẳng định toàn hệ thống không còn lỗi hoặc đã sẵn sàng production: còn các bước kiểm tra cuối ở mục 7.

Theo yêu cầu của chủ dự án, dừng công việc khi hạn mức khả dụng còn khoảng 3%, lưu tiến độ và chỉ tiếp tục khi được gọi lại. Hạn mức tài khoản không phải bộ đếm token riêng của task; số phần trăm được kiểm tra qua trạng thái tài khoản.

## 2. Nghiệp vụ được sửa

### Context không được tự coi là dữ kiện của khách hiện tại

Mỗi node có chính sách `NONE` (mặc định) hoặc `CONFIRMED_ONLY`. Khi thiếu trường cần hỏi, Dialog có thể gợi ý từ ghi nhớ đã xác nhận hoặc phiên được liên kết rõ ràng. Gợi ý chưa được điền vào biến nghiệp vụ cho đến khi sales xác nhận.

Ví dụ đã test: hỏi giờ hoạt động trong phiên mới → gợi ý `location = HCM` từ ghi nhớ → sales trả lời “xác nhận” → trả lời giờ HCM. Sau đó sales nói “Khách đổi sang Hà Nội...” → dùng Hà Nội, không giữ HCM.

- Dữ kiện mới do người dùng nhập được ưu tiên hơn dữ kiện cũ.
- Ghi nhớ chưa xác nhận, hết hạn hoặc mâu thuẫn không được tự dùng làm gợi ý.
- Không tự lấy phiên tương tự bất kỳ làm dữ kiện cho khách hiện tại.
- Khi từ chối, đổi chủ đề hoặc hủy luồng, xử lý trạng thái chờ tương ứng; không mang biến của luồng đã kết thúc sang yêu cầu mới.
- Máy chủ kiểm tra lại gợi ý và nguồn; không tin nguồn do trình duyệt gửi lên.
- Quy ước khóa ghi nhớ cho trường Dialog: ví dụ `dialog.location`; tên trường cần khớp cấu hình entity.

### Dialog và knowledge base là hai nguồn khác nhau

Nếu Dialog xử lý được câu hỏi, dùng nội dung đã cấu hình trong Dialog. Nếu không khớp, chuyển về luồng Core/RAG, dùng quyền truy cập tài liệu và dẫn nguồn được kiểm tra. Context giúp hiểu yêu cầu, không thay thế bằng chứng từ KB.

Preview có thể chọn phiên thuộc quyền truy cập của người dùng, dùng lịch sử preview riêng, không ghi tin nhắn hay trạng thái preview vào phiên thật. Đường xử lý Core được chia sẻ với chat thật để tránh preview là một hệ thống khác.

### Chặn cấu hình nguy hiểm và nhầm nhánh

Kiểm tra tham chiếu, giá trị entity chuẩn, cây cha/con, vòng lặp Jump/Skip/Folder và giới hạn 20 bước tự động. WAIT là điểm dừng chờ người dùng, không bị coi như vòng lặp tự động.

Chuẩn hóa tham chiếu theo tên thật trong cấu hình. Đồng thời sửa lỗi phân biệt tên node: `Bao gia` và `Báo giá` phải là hai tên khác nhau. Test hồi quy đã chứng minh code cũ đi nhầm nhánh; bản sửa giữ dấu khi so sánh định danh, trong khi chuẩn hóa ngôn ngữ cho câu hỏi vẫn có thể bỏ dấu.

## 3. Thay đổi giao diện

Skill Emil Design Engineering được áp dụng để làm rõ trạng thái, giữ thao tác ổn định và tránh mất chỉnh sửa; không thay đổi phong cách tổng thể của ứng dụng.

| Trước | Sau | Lý do |
| --- | --- | --- |
| Chính sách dùng context không rõ trên từng node | Có lựa chọn và hướng dẫn tiếng Việt | Sales/admin hiểu gợi ý khác với xác nhận |
| Thay đổi node dễ làm đảo priority ngầm | Giữ priority ổn định; sắp xếp lại cần xác nhận | Không đổi nghiệp vụ ngoài ý định |
| Cấu hình lỗi khó nhận biết | Lỗi chặn lưu và cảnh báo riêng, hiển thị gần cấu hình | Phát hiện lỗi trước publish |
| Chuyển bot/rời trang có thể mất bản nháp | Kiểm tra thay đổi chưa lưu | Bảo vệ công sức chỉnh sửa |
| Phản hồi cũ có thể cập nhật preview sau reset/đóng | Bỏ qua phản hồi lỗi thời; khóa thao tác khi đang gửi/lưu | Tránh hiển thị nhầm bot hoặc trạng thái |
| Preview thiếu giải thích đường đi | Hiển thị route, node, gợi ý chưa xác nhận và nguồn KB | Dễ kiểm chứng nghiệp vụ và nguồn trả lời |

## 4. Bản đồ file

### Backend chính

- `BE/src/main/java/com/blueai/studio/model/DialogContextReusePolicy.java`: enum chính sách dùng lại context.
- `BE/src/main/java/com/blueai/studio/model/BotDialogNode.java`: lưu chính sách trên node, mặc định an toàn.
- `BE/src/main/resources/db/migration/V9__dialog_context_reuse_policy.sql`: thêm cột, mặc định `NONE`; không sửa migration cũ.
- `BE/src/main/java/com/blueai/studio/dto/BotStudioDtos.java`: dữ liệu preview/context/trace/sources, giới hạn và kiểm tra đầu vào; giữ constructor tương thích.
- `BE/src/main/java/com/blueai/studio/service/DialogConfigurationValidator.java`: kiểm tra cấu hình và vòng lặp, tách khỏi runtime.
- `BE/src/main/java/com/blueai/studio/service/DialogContextBridge.java`: chuyển context hợp lệ thành gợi ý có nguồn, loại bỏ xung đột.
- `BE/src/main/java/com/blueai/studio/service/DialogRuntimeService.java`: xác nhận/từ chối/gán dữ kiện mới, reset, giới hạn bước, trace và phân biệt tên node có dấu.
- `BE/src/main/java/com/blueai/studio/service/BotStudioService.java`: validation/persist chính sách, chuẩn hóa tham chiếu, preview dùng chung luồng chat.
- `BE/src/main/java/com/blueai/rag/chat/service/RagChatService.java`: nối Dialog với context và RAG; preview chỉ đọc, kiểm tra quyền và bằng chứng; giới hạn lịch sử preview.

Public widget vẫn chỉ trả nội dung cần thiết, không lộ trace hoặc nguồn context riêng tư. Lịch sử preview chỉ nhận role user/assistant, không nhận role system do client gửi.

### Frontend chính

- `FE/src/api/blueAiApi.ts`: kiểu dữ liệu mới và tùy chọn context/lịch sử cho preview.
- `FE/src/components/studio/ChatbotStudio.tsx`: editor, hướng dẫn, lỗi/cảnh báo, priority, bản nháp và khóa thao tác đang lưu.
- `FE/src/components/studio/TryItOutPanel.tsx`: chọn phiên, welcome theo bot, gợi ý/trace/KB, quản lý lịch sử và phản hồi lỗi thời.
- `FE/src/components/studio/studioDrafts.ts`: tích hợp các helper validation.
- Các file mới cùng thư mục: `DialogContextPolicy.tsx`, `DialogNodeNameField.tsx`, `dialogValidation.ts`, `dialogNodeOperations.ts`, `studioDraftProtection.ts`.

### Kiểm thử

- Mới: `DialogConfigurationValidatorTest.java`, `DialogContextRuntimeTest.java` trong `BE/src/test/java/com/blueai/studio/service/`.
- Mới: `BE/src/test/java/com/blueai/rag/chat/controller/DialogSalesContextIntegrationTest.java`.
- Cập nhật: `BotStudioIntegrationTest.java`, `FlywayMigrationIntegrationTest.java`, `MySqlMigrationIntegrationTest.java`, `MySqlFlywayUpgradeIntegrationTest.java`.
- FE: `studioPreviewApi.test.ts`, `dialogSales.test.tsx`, `studioDraftProtection.test.ts`, cập nhật `studioDrafts.test.ts`.

## 5. Kết quả kiểm thử

| Nhóm | Kết quả | Giới hạn/ghi chú |
| --- | --- | --- |
| Backend cuối: clean verify | 331 được phát hiện; 302 chạy PASS, 29 skip; 0 failure/error; build PASS | 29 test có điều kiện môi trường; không coi skip là pass |
| Validator Dialog | 43 PASS | Bao gồm vòng lặp, WAIT, tham chiếu, cảnh báo |
| Runtime context | 10 PASS | Có hồi quy tên node có/không dấu |
| Sales/context tích hợp | 50 PASS | Dịch vụ thật, HTTP provider giả lập |
| Frontend | 72 PASS; TypeScript và build PASS | Bao gồm API, helper, render và bảo vệ trạng thái |
| MySQL 8.4 migration | 1 PASS | V5 lên V9; dữ liệu cũ giữ nguyên, node cũ nhận `NONE` |
| Flyway cũ/mới + MySQL/auth/task | Seed cũ PASS; lượt hồi quy 46 PASS | Flyway 11.7.2/V6 lên 11.20.3/V9; dùng DB QA riêng |
| API end-to-end | 8 kiểm tra PASS | Gợi ý, xác nhận, đổi dữ kiện, từ chối, preview không ghi phiên, nguồn KB, publish/activate, phiên mới |
| Production Compose/Nginx regression | PASS | Kiểm tra cấu hình, ranh giới truy cập, quota/preflight và đổi backend |
| Docker image mới | Build PASS | Chưa thay container QA đang chạy bằng image cuối |
| Git diff whitespace | PASS | Cảnh báo LF/CRLF là thông báo chuyển dòng, không phải lỗi test |

Không cộng các hàng thành số test duy nhất: một số kiểm tra là tập con hoặc chạy lại cùng test trên môi trường khác nhau.

Gitleaks giới hạn trong `BE/src` và `FE/src`, có che giá trị nhạy cảm: FE không có finding; BE có hai finding generic-api-key ở JWT giả lập đã tồn tại trong test (`ProductionNoMailConfigurationIntegrationTest.java` và `application-test.properties`). Không kết luận đã quét sạch toàn repo/lịch sử Git. `BE/target` không có file tracked.

GitNexus báo phạm vi ảnh hưởng critical vì thay đổi đi qua luồng chat chung. Đã kiểm tra tác động trước sửa và chạy hồi quy tương ứng; lần detect cuối ghi nhận 14 file tracked, 76 symbol, 469 luồng ảnh hưởng. Các file mới chưa tracked được rà thêm qua trạng thái Git, không nằm hết trong số này.

## 6. Bằng chứng UI và môi trường

QA riêng: `http://localhost:19003/`, API loopback `19081`, MySQL 8.4 riêng. Không đọc key thật, không dùng tài khoản hay DB thật. Frontend thật ở `localhost:3000` và stack `blueai-auth-*` không bị thay thế.

Đã thao tác trực tiếp trên giao diện:

1. Mở BlueAI Custom và cấu hình chính sách cho node.
2. Priority không hợp lệ bị chặn; sửa lại rồi lưu được.
3. Preview chọn phiên HCM, hỏi thiếu location → gợi ý chưa xác nhận.
4. Xác nhận → câu trả lời nhánh HCM.
5. Câu hỏi ngoài Dialog → Core trả lời kèm nguồn `[S1]` và trích đoạn tài liệu fixture.
6. Tạo hội thoại sales mới thật trên UI → context HCM được gợi ý, xác nhận thành công.
7. Đổi sang Hà Nội → giờ Hà Nội 08:00–21:00, không dùng HCM 08:00–22:00.

Provider và tài liệu trong môi trường này là **fixture giả lập có gắn nhãn rõ**. Đây là bằng chứng luồng phần mềm/context/quyền/hiển thị nguồn hoạt động; chưa phải đánh giá chất lượng model thật hoặc retrieval/embedding thật.

Ảnh nằm ngoài repo trong:
`C:/Users/ductr/.codex/visualizations/2026/09/16/01a0a7e3-b6ed-7711-8731-5a01169a5089/`

- `dialog-validation-20261002.jpg`
- `dialog-context-proposal-20261002.jpg`
- `dialog-context-confirmed-20261002.jpg`
- `dialog-preview-kb-20261002.jpg`
- `dialog-sales-new-session-20261002.jpg`
- `dialog-sales-override-20261002.jpg`

Log backend cuối: `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-complete-final-20261002.log`.
Log image cuối: `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-image-complete-20261002.log`.

## 7. Điểm tiếp tục, chưa được coi là hoàn tất

- Đồng bộ container API QA sang image cuối, kiểm tra health và một lượt smoke test sau khởi động. UI vừa test dùng image trước bản sửa cạnh tên node có dấu; mã nguồn/image cuối đã qua toàn bộ backend test.
- Kiểm thử thủ công Cancel/Leave cho cảnh báo bản nháp chưa lưu. IAB bị kẹt native `window.confirm` ở tab QA cũ; tab phục hồi dùng bàn phím vẫn test được. Unit tests bảo vệ bản nháp đã đạt, nhưng không báo bước Cancel UI là đã đạt.
- Rà cách trình bày nguồn trong chat chính: câu trả lời Dialog Hà Nội bị gắn “Ngoài phạm vi kho tri thức nội bộ”. Nội dung nhánh đúng và đúng là không có tài liệu KB, nhưng nên phân biệt rõ “Theo kịch bản Dialog” để sales không hiểu nhầm chatbot lỗi. Chưa sửa mục này trong lượt hiện tại.
- Chỉ khi có cấu hình/tài liệu thích hợp và quyền dùng provider thật: kiểm thử retrieval và model thật trên dữ liệu test không nhạy cảm, phân biệt với fixture ở trên.
- Rà diff/security cuối, cập nhật báo cáo, chạy CI và team review trước khi quyết định commit/push hoặc merge. Không tự push theo yêu cầu cũ của các task trước.

Môi trường QA tạm đang giữ để tiếp tục: network `blueai-dialog-qa-20261002`, các container `blueai-dialog-mysql-qa-20261002`, `blueai-dialog-provider-qa-20261002`, `blueai-dialog-api-qa-20261002`, FE dev server cổng 19003. Không xóa volume DB hoặc cache Maven. Nếu Docker được tắt qua đêm, kiểm tra trạng thái các container trước khi tiếp tục.
