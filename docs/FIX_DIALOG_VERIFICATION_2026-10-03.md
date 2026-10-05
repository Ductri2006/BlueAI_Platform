# Báo cáo hoàn tất lượt kiểm tra Dialog ngày 03/10/2026

Nhánh: `feature/conversation-context`.
Commit nền: `54ec3e786dd1453ca88b5ee536eec1dca8b5adf1`.
Trạng thái: các thay đổi vẫn ở máy local, **chưa commit/push/merge hoặc deploy production**.

## 1. Kết luận

Đã xử lý và kiểm tra lại các điểm còn dở trong báo cáo 02/10: đồng bộ API QA, bảo vệ bản nháp trên giao diện, phân biệt nguồn Dialog/context/KB và kiểm thử sau tải lại trang. Những ca đã chạy trong lượt cuối đều đạt; không khẳng định mọi tình huống của hệ thống đều không có lỗi.

Nghiệp vụ chính được chứng minh: phiên mới có thể gợi ý dữ kiện từ ghi nhớ đã xác nhận; sales phải xác nhận trước khi dữ kiện được áp dụng; dữ kiện mới của khách thắng context cũ. Context không được coi là bằng chứng KB.

Báo cáo triển khai toàn bộ ba phạm vi và các file của ngày 02/10 nằm tại [báo cáo nghiệp vụ/context](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/FIX_DIALOG_BUSINESS_CONTEXT_2026-10-02.md>). Tài liệu này bổ sung kết quả ngày 03/10, không thay các số liệu lịch sử thành số liệu mới.

## 2. Các lỗi và cải thiện tiếp tục xử lý

| Before | After | Why |
| --- | --- | --- |
| Câu trả lời đúng nhánh Dialog bị hiển thị “Ngoài phạm vi kho tri thức nội bộ” | Backend lưu nguồn xử lý; UI ghi “Theo kịch bản Dialog” và giải thích không phải trích dẫn KB | Tránh sales hiểu nhầm câu trả lời kịch bản là lỗi retrieval |
| Gợi ý chưa xác nhận có thể được trình bày như context đã dùng | Nhãn “Context cần xác nhận”, phần “Context đề xuất (chưa áp dụng)” | Phân biệt đề xuất với dữ kiện đã áp dụng |
| Nhãn nguồn có nguy cơ chỉ đúng trong lượt chat hiện tại | Lưu `origin` trong evidence JSON; dùng cùng mapper khi tải lịch sử | Nhãn còn đúng sau tải lại trang |
| Popup `window.confirm` cho việc rời bản nháp gây khó quan sát/kiểm thử | Bảng xác nhận trong trang với hai lựa chọn rõ ràng | Giữ thao tác dễ hiểu; lựa chọn mặc định là giữ bản nháp |
| Tab ở nút cuối của bảng có thể đưa focus ra ngoài | Vòng Tab/Shift+Tab giữa hai nút; Escape giữ nháp; trả focus khi đóng | Hoạt động bàn phím ổn định và an toàn hơn |
| Điều hướng được quyết định ngay bởi popup đồng bộ | Đợi quyết định bất đồng bộ, kiểm tra lại trạng thái đang lưu, không điều hướng khi đã unmount/target bị gỡ | Tránh mất nháp hoặc điều hướng lỗi thời |

Computer Use được dùng để thao tác UI thật. Emil Design Engineering ảnh hưởng tới cách thể hiện trạng thái, mặc định giữ nháp, focus/bàn phím và việc không thêm animation gây chậm thao tác. Không thay đổi phong cách tổng thể ứng dụng.

### Nguồn trả lời do máy chủ xác định

- `CORE`: đường xử lý Core/RAG hiện có.
- `DIALOG`: nội dung từ kịch bản cấu hình.
- `DIALOG_CONTEXT_CONFIRMATION`: đề xuất cần sales xác nhận, chưa áp dụng.

Đây không phải cờ tin cậy do client gửi. DTO hỏi và tạo tin nhắn không cho client gán evidence/origin. Evidence cũ không có `origin` vẫn đọc được; không suy đoán và gán lại nguồn cho tin nhắn lịch sử chỉ dựa trên nội dung.

Không cần thêm migration cho origin: trường này được thêm vào evidence JSON đã có. Migration V9 cho chính sách context của từng node là thay đổi từ ngày 02/10, vẫn giữ nguyên.

### Bản nháp và điều hướng

Cancel/“Ở lại chỉnh sửa” hoặc Escape chỉ đóng bảng, giữ dữ liệu. “Bỏ thay đổi và tiếp tục” mới cho phép đổi bot/rời khu vực. Khi đang lưu thì chặn điều hướng; nếu tác vụ bắt đầu trong lúc đợi quyết định, kiểm tra lại trước khi tiếp tục.

Đóng tab/reload vẫn dùng cảnh báo `beforeunload` của trình duyệt theo giới hạn nền tảng. Không thay mọi popup xóa intent/entity/node bằng bảng mới trong lượt này.

## 3. Bản đồ file bổ sung ngày 03/10

| File | Vai trò |
| --- | --- |
| [AnswerOrigin.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/conversation/model/AnswerOrigin.java>) — mới | Enum nguồn trả lời do backend tạo |
| [MessageEvidence.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/conversation/model/MessageEvidence.java>) | Thêm origin, giữ constructor cũ và evidence bất biến |
| [RagChatService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/chat/service/RagChatService.java>) | Gán nguồn xử lý đúng khi lưu phản hồi Core/Dialog/xác nhận |
| [blueAiApi.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/api/blueAiApi.ts>) | Đọc origin và ánh xạ nhãn khi trả lời trực tiếp/tải lịch sử; Dialog không giả định đã gọi LLM |
| [types/index.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/types/index.ts>) | Bổ sung trạng thái hiển thị Dialog và chờ xác nhận |
| [GroundedStatusBadge.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/chat/GroundedStatusBadge.tsx>) | Hai nhãn nguồn mới |
| [MessageItem.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/chat/MessageItem.tsx>) | Giải thích kịch bản không phải KB; phân biệt context đề xuất/đã dùng |
| [StudioDiscardDialog.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/studio/StudioDiscardDialog.tsx>) — mới | Bảng xác nhận và hook quyết định; mặc định giữ nháp, Escape, focus và cleanup |
| [studioDraftProtection.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/studio/studioDraftProtection.ts>) | Guard bất đồng bộ và chỉ tiếp tục điều hướng sau chấp nhận hợp lệ |
| [ChatbotStudio.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/studio/ChatbotStudio.tsx>) | Tích hợp bảng mới với đổi bot/tạo bot/rời khu vực; chỉnh lại indentation |
| [DialogSalesContextIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/chat/controller/DialogSalesContextIntegrationTest.java>) | Thêm ba ca origin/lịch sử/KB; 53 ca tích hợp đạt |
| [MessageEvidenceConverterTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/conversation/model/MessageEvidenceConverterTest.java>) | Thêm ca đọc JSON cũ và round-trip ba origin; tổng 5 ca đạt |
| [dialogAnswerOrigin.test.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/chat/dialogAnswerOrigin.test.tsx>) — mới | 10 ca nhãn, mapper lịch sử, KB và thông báo render |
| [studioDraftProtection.test.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/studio/studioDraftProtection.test.ts>) | Guard khi quyết định chậm, Cancel, replay một lần, target gỡ/unmount/busy, click sửa đổi, clean draft |
| [StudioDiscardDialog.test.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/studio/StudioDiscardDialog.test.tsx>) — mới | Nhãn accessibility, mặc định focus giữ nháp và escape nội dung HTML |

Các test render tĩnh không thay thế kiểm thử focus/bàn phím trong trình duyệt; các hành vi đó đã được thao tác thật ở mục 5.

## 4. Kết quả kiểm thử cuối

| Nhóm | Kết quả | Phạm vi/giới hạn |
| --- | --- | --- |
| Backend `clean verify` | 336 được phát hiện; 307 chạy đạt, 29 bỏ qua; 0 failure/error; build thành công | Không coi skip là pass |
| Sales/context integration | 53 đạt | Tập con trong lượt backend; provider HTTP giả lập |
| Dialog validator / runtime | 43 / 10 đạt | Tập con trong lượt backend |
| Evidence converter | 5 đạt | JSON cũ và mới |
| MySQL 8.4 riêng | 45 đạt; 0 failure/error/skip | Auth mutation 24, session concurrency 6, task authorization 8, task recovery 7; không cộng máy móc với lượt full vì có thể chạy lại ca đã đạt |
| Frontend | 96 đạt trong 8 file | Sau bản sửa vòng focus cuối |
| TypeScript / production FE build | Đạt | Build cuối từ đúng thư mục FE, CSS được tạo đầy đủ |
| Docker API image / health | Build thành công; `/actuator/health` UP | API QA đã chạy image `blueai-dialog-qa-api:20261003` |
| Production Compose | Đạt | Cổng nội bộ, cấu hình production, restart/health và thứ tự khởi động |
| Nginx ingress | Đạt | Ranh giới riêng tư, upload, spoof protection, session quota/JSON/preflight và đổi địa chỉ backend |
| Git diff whitespace | Đạt | Thông báo LF/CRLF không phải failure |

Lượt backend đầu ngày có một failure do test mới dùng tài liệu fixture của USER nhưng kỳ vọng `ENTERPRISE_DOCUMENT`. Đã kiểm tra logic phân loại theo role chủ sở hữu, sửa kỳ vọng thành `USER_DOCUMENT`, không nới quyền truy cập hay đổi production logic để ép test đạt. Lượt `clean verify` sau đó thành công.

Kiểm thử migration V5→V9 và Flyway 11.7.2→11.20.3 trên MySQL 8.4 đã đạt ngày 02/10, không báo là vừa chạy lại ngày 03/10. API QA ngày 03/10 đã khởi động trên schema V9, đọc/lưu lịch sử chat và bản nháp thành công.

## 5. Kiểm thử trực tiếp như sales/admin

1. Người dùng bấm Cancel: bot Sales vẫn được chọn, ô role còn nội dung QA chưa lưu, trạng thái dirty còn nguyên và focus quay về ô nhập.
2. Escape giữ nháp; chuyển sang Hỏi BlueAI rồi Cancel không rời Studio.
3. Chấp nhận bỏ nháp chỉ bỏ thay đổi QA chưa lưu; rời sang chat thành công. Mở lại cấu hình vẫn có nội dung đã lưu trước đó.
4. Lưu role QA ở nháp r4, reload rồi mở Studio: nội dung được giữ. Khôi phục role `Trợ lý BlueAI`, lưu r5. Không phát hành thay đổi thử.
5. Đổi bot khi dirty: bảng có focus mặc định ở nút giữ nháp. Sau sửa, Tab ở nút cuối quay về nút đầu; Shift+Tab ở nút đầu quay về nút cuối. Chấp nhận thì bot đối chứng tải thành công; trở lại Sales khi clean không hỏi bỏ nháp.
6. Tạo phiên chat mới, hỏi “cho tôi hỏi giờ hoạt động”: gợi ý HCM từ ghi nhớ nhưng chưa áp dụng; UI ghi “Context cần xác nhận”.
7. Gửi “xác nhận”: giờ HCM 08:00–22:00, nhãn “Theo kịch bản Dialog”.
8. Gửi “Khách đổi sang Hà Nội, cho tôi hỏi giờ hoạt động”: giờ Hà Nội 08:00–21:00, không giữ HCM.
9. Reload: lịch sử và hai nhãn nguồn vẫn đúng; không cần đoán nguồn từ nội dung câu trả lời.
10. Hỏi hướng dẫn Data Roaming/APN ngoài Dialog: Core trả kết quả có `[S1]`; mở nguồn hiển thị `knowledge-sales-demo.txt`, trích đoạn fixture và trạng thái xác minh READY. Sau reload vẫn có nguồn.
11. Preview chọn `Co hoi sales QA - HCM`: giải thích route `CONTEXT_CONFIRMATION`, dữ kiện chưa áp dụng và nguồn `dialog.location`; xác nhận rồi trả HCM. Đối chiếu DB QA trước/sau: vẫn 1 tin nhắn, timestamp không đổi, Dialog state vẫn NULL. Preview không ghi vào phiên nền.

### Môi trường và tác động

UI QA: `http://localhost:19003/`; API chỉ loopback `19081`; MySQL 8.4 và provider QA nằm trong network riêng. Không thay stack `blueai-auth-*` hoặc frontend thật ở `localhost:3000`.

API QA cũ được dừng và giữ lại với tên backup để có thể khôi phục; không xóa DB/volume/cache Maven. Nháp QA có revision r5 sau lưu thử và khôi phục role ban đầu; bot đã publish vẫn giữ release cũ. Các tin nhắn mới là dữ liệu thử, không phải dữ liệu khách hàng.

Provider và tài liệu dùng trong QA là **fixture giả lập có ghi nhãn rõ**, không dùng key/model thật. Bằng chứng trên xác nhận luồng context, Dialog, quyền, persistence và hiển thị evidence; **không chứng minh chất lượng retrieval/embedding hoặc câu trả lời của model thật**.

## 6. Rà bảo mật và phạm vi thay đổi

- Không đọc hoặc in key thật, không sửa `.env` thật, không truyền dữ liệu khách sang provider.
- Quét có che giá trị trong `BE/src`: còn 2 finding `generic-api-key` ở JWT fixture đã tồn tại: `ProductionNoMailConfigurationIntegrationTest.java:18` và `application-test.properties:19`. Không phát hiện thêm finding trong lượt này.
- Quét `FE/src`: không có finding. Không đồng nghĩa đã quét toàn repo, lịch sử Git hay thay thế audit 20 rule bảo mật.
- `BE/target` có 0 file tracked; output build không đưa vào Git.
- Rà DTO: client không được gán origin/evidence cho câu trả lời. Metadata mới chỉ là enum nguồn; không mở rộng response public widget để lộ debug trace hay context riêng tư.
- GitNexus detect-changes lượt cuối ghi nhận 19 file tracked, 106 symbol, 454 execution flow ảnh hưởng, mức critical vì đi qua chat dùng chung. Đã kiểm tra impact và các caller thực tế, chạy full regression. Các symbol/file mới chưa có trong index có mức UNKNOWN; đã tìm các import/call site bằng mã nguồn, không coi UNKNOWN là LOW. Không mặc định mọi flow do graph liệt kê đều là dependency chắc chắn.
- File chưa tracked được rà thêm qua trạng thái Git và đọc source; không nằm hết trong con số 19 file tracked.

## 7. Bằng chứng và log

Thư mục ảnh ngoài repo: `C:/Users/ductr/.codex/visualizations/2026/09/16/01a0a7e3-b6ed-7711-8731-5a01169a5089/`.

- `dialog-discard-user-cancel-20261003.jpg`: bảng xác nhận mở cho người dùng.
- `dialog-cancel-kept-draft-20261003.jpg`: Cancel giữ bản nháp.
- `dialog-origin-context-proposal-20261003.jpg`: đề xuất chưa áp dụng trong phiên mới.
- `dialog-origin-confirmed-20261003.jpg`: nhánh HCM sau xác nhận.
- `dialog-origin-after-reload-20261003.jpg`: lịch sử và nhánh Hà Nội sau reload.
- `dialog-kb-evidence-20261003.jpg`: nguồn KB/trích đoạn fixture.
- `dialog-preview-trace-20261003.jpg`: giải thích route/context trong preview.
- `dialog-final-configuration-20261003.jpg`: editor bước Ask location sau lượt kiểm tra, nháp đã đồng bộ.

Log ngoài repo:

- `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-complete-final-20261003.log` — full backend.
- `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-mysql-regression-20261003.log` — MySQL gates.
- `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-image-20261003.log` — build API image.
- `C:/Users/ductr/AppData/Local/Temp/blueai-dialog-be-secrets-20261003.log` và `blueai-dialog-fe-secrets-20261003.log` — quét mã nguồn có che giá trị.

## 8. Trước khi production

1. Team review diff và chạy CI trên commit/PR chính thức; lượt này chưa push nên chưa có kết quả GitHub Actions mới.
2. Kiểm thử model/retrieval thật trên tài liệu test không nhạy cảm trong staging với cấu hình provider được phê duyệt.
3. Chạy smoke test deployment thực tế: HTTPS/domain, tài khoản sales, quyền tài liệu, monitoring/backup và giới hạn quota theo môi trường.

Những mục này là bước kiểm chứng phát hành còn lại, không phải failure đang tồn tại trong các test QA đã chạy.
