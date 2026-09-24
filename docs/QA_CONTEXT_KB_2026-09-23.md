# Báo cáo kiểm thử và sửa lỗi Context + Knowledge Base

Ngày: 23/09/2026. Branch làm việc: `debugg_AI`. Môi trường: Docker local, web `http://localhost:3000/`.

## 1. Kết luận kiểm thử

Đã trực tiếp sử dụng giao diện như nhân viên sales, không chỉ gọi API hoặc dùng câu trả lời giả lập. Các lượt chat trên giao diện gọi provider thật và truy xuất tài liệu thật đang có trong hệ thống.

Các bộ kiểm thử tự động mới nhất: **Backend 69/69, Frontend 20/20, Python RAG 36/36**, không có test thất bại. Backend chạy bằng Java 21 trong Docker; frontend qua TypeScript và production build. Docker Compose đã build và khởi động lại bản sửa.

Kết quả này chứng minh các kịch bản được kiểm tra, không phải cam kết hệ thống không bao giờ có lỗi. Đây chưa phải kiểm thử tải production, kiểm toán toàn bộ bảo mật hay chứng nhận AI luôn trả lời đúng mọi cách diễn đạt.

## 2. Các kịch bản thực tế trên giao diện

Dữ liệu khách hàng và mã cơ hội dưới đây là dữ liệu kiểm thử, không phải giao dịch thật.

| Kịch bản | Bằng chứng quan sát | Kết quả |
|---|---|---|
| Nhớ thông tin trong cùng phiên | Hikari, BLUE-SALES-1842, 75 eSIM Hàn Quốc, trước 09:30 thứ Hai; hỏi lại không cung cấp lại đáp án | Đạt |
| Sửa dữ kiện cũ | Đổi 75 → 80, sau đó 80 → 90; AI trả số lượng mới và giữ hạn báo giá | Đạt |
| Phiên mới chưa liên kết | Hỏi về Hikari ở phiên độc lập; AI nói chưa ghi nhận, không tự lấy mã/số lượng từ phiên khác | Đạt; kiểm tra lại trên bản sửa |
| Liên kết thủ công | Chọn phiên nguồn trong Context & ghi nhớ; AI ở phiên đích nhớ đúng 5 mục, gồm 80 eSIM | Đạt |
| Snapshot không tự cập nhật | Phiên nguồn đổi sang 90; phiên đích chưa làm mới vẫn trả 80 theo snapshot | Đạt |
| Làm mới snapshot | Bấm Làm mới snapshot; hỏi lại, AI trả BLUE-SALES-1842 và 90 eSIM | Đạt |
| Ghi nhớ cá nhân | Lưu nhóm SEN-XANH-6291 và định dạng Nhu cầu / Việc tiếp theo; phiên mới nhớ đúng, không phải nhắc lại nội dung | Đạt |
| Tự tìm lịch sử | Ở phiên chưa liên kết, bật Tự tìm trong lịch sử; AI tìm đúng BLUE-SALES-1842 và 90 eSIM, kèm tên các nguồn context | Đạt trên bản snapshot mới |
| Trả lời theo Knowledge Base | Hỏi eSIM không có sóng: Data Roaming, Cellular Data, máy bay 10 giây, APN, không xóa eSIM | Đạt |
| Mở nguồn thật | Bấm [S1], mở đúng `Bluecom_Knowledge_Base_RAG.csv`, dòng KB-ESIM-001, chunk `35038f29-4bec-41cc-aad9-099b03cbffeb:1` | Đạt |
| Câu hỏi vừa context vừa KB | Hỏi số lượng Hikari và hướng dẫn mất sóng trong cùng câu; AI tách 80 eSIM từ context và hướng dẫn có [S1] từ KB | Ban đầu lỗi; đã sửa và test lại đạt |
| Tải lại trang | Reload sau câu trả lời mới; nguồn [S1], đoạn trích và danh sách context còn nguyên, mở lại được | Đạt |
| Cửa sổ hẹp | Tạo và chuyển phiên bằng nút/dropdown mới khi sidebar desktop bị ẩn | Đạt |
| Không có căn cứ trong KB | Hỏi chính sách bồi thường eSIM trên Sao Hỏa; AI nói chưa đủ thông tin, không bịa chính sách hoặc hành động hỗ trợ | Đạt |

Không dùng tài liệu giả lập để kết luận kịch bản KB đạt. Các bài test đơn vị/integration có fixture riêng để kiểm tra code; chúng được phân biệt với lượt chat provider thật ở bảng trên.

## 3. Những lỗi được sửa trong lượt tiếp tục

### 3.1. Câu hỏi hỗn hợp bị bỏ qua tài liệu

Nguyên nhân: chỉ cần có từ như “nhắc lại” hoặc “context”, hệ thống cũ chọn chế độ CONTEXT_ONLY và không gọi retrieval. Câu hỏi “nhắc số lượng khách, rồi hướng dẫn xử lý mất sóng theo KB” vì vậy thất bại dù KB có tài liệu.

Sửa ở `BE/src/main/java/com/blueai/rag/context/ContextRelevanceRanker.java`:

- Phân biệt ý định hồi nhớ dữ kiện với ý định hỏi tài liệu/chính sách/quy trình.
- Câu hỏi có cả hai ý định vẫn truy xuất KB.
- So khớp theo cụm từ, tránh bắt nhầm một phần từ.
- Chuẩn hóa cả ký tự `đ` sang `d` khi so khớp tiếng Việt không dấu.

`ConversationContextService.java` cũng không truy xuất KB không liên quan chỉ vì một phiên chưa có dữ kiện cá nhân cần nhớ. Nó đưa provenance của lịch sử hiện tại vào kết quả khi thực sự cung cấp lịch sử đó cho model.

### 3.2. Context không phải chính sách doanh nghiệp

Sửa `BE/src/main/java/com/blueai/rag/chat/service/RagChatService.java`:

- Dữ kiện sales dùng thông tin USER mới nhất; không xem lời từ chối cũ của AI là bằng chứng rằng dữ kiện mới không tồn tại.
- Chính sách, giá và quy trình phải dựa trên tài liệu, không được ghi đè bằng lời người dùng.
- Với câu hỏi hỗn hợp, trả từng phần bằng đúng loại nguồn; không gắn [S1] lên số lượng do sales cung cấp.
- Không tuyên bố đã chuyển hỗ trợ, gửi email hay tạo ticket khi không có kết quả công cụ thực hiện hành động đó. Đây là ràng buộc prompt, không phải chứng minh chống mọi prompt injection.

### 3.3. Nguồn dẫn biến mất sau reload

Trước đây database chỉ lưu nội dung tin nhắn; metadata nguồn nằm trong bộ nhớ trình duyệt nên mất khi tải lại.

Các file bổ sung/chỉnh sửa:

- `BE/src/main/java/com/blueai/rag/conversation/model/MessageEvidence.java`: snapshot bất biến của tài liệu và context được cung cấp khi tạo câu trả lời.
- `MessageEvidenceConverter.java`: chuyển snapshot sang JSON để lưu database, không đưa nội dung riêng tư vào thông báo lỗi.
- `ConversationMessage.java`: thêm cột nullable `evidence_json`; constructor cũ vẫn hoạt động. Tin nhắn USER không mang metadata tin cậy.
- `BE/src/main/java/com/blueai/rag/conversation/dto/ConversationMessageResponse.java`: trả metadata đã lưu cùng tin nhắn thuộc tài khoản hiện tại.
- `RagChatService.java`: lưu câu trả lời và evidence trong cùng giao dịch.
- `FE/src/api/blueAiApi.ts`: một hàm chuyển đổi dùng chung cho lịch sử có evidence; giữ nguyên tin nhắn USER.
- `FE/src/store/useAppStore.ts`: khôi phục nguồn khi tải lịch sử.

**Giới hạn tương thích:** tin nhắn được tạo trước bản vá không có evidence để khôi phục. Không suy diễn hoặc tạo giả nguồn dẫn cho chúng. Snapshot tài liệu là bằng chứng tại thời điểm truy xuất, không phải xác nhận rằng tài liệu vẫn là phiên bản hiện hành về sau.

Local đang dùng Hibernate `ddl-auto=update`, nên cột nullable được thêm khi khởi động. Với môi trường production quản lý schema bằng migration, cần đưa thay đổi cột này vào quy trình migration và sao lưu trước triển khai.

### 3.4. Snapshot dài có thể mất cập nhật mới

Sửa `BE/src/main/java/com/blueai/rag/conversation/service/ConversationContextSnapshotFactory.java`:

- Đặt các trao đổi mới nhất ở đầu snapshot, ghi rõ thứ tự “mới nhất trước”.
- Đặt tóm tắt cũ phía sau; không gọi tóm tắt do AI tạo là “đã xác nhận”.
- Khi giới hạn độ dài cắt phần cuối, ưu tiên giữ cập nhật mới thay vì dữ kiện cũ.
- ID tin nhắn cuối vẫn trỏ đúng tin nhắn mới nhất.

Test riêng tạo lịch sử dài vượt ngân sách và kiểm tra dữ kiện 90 eSIM còn, dữ kiện 75 eSIM cũ bị cắt. Đây là kiểm thử code, không phải tuyên bố đã stress test hội thoại dài bằng provider thật.

### 3.5. Hiển thị và điều hướng

- `FE/src/components/chat/GroundedStatusBadge.tsx`: dùng nhãn “Có … tài liệu tham chiếu” / “Có … nguồn context tham khảo”, không tự tuyên bố toàn bộ câu trả lời đã được xác minh.
- `FE/src/components/chat/ChatWorkspace.tsx`: thêm tạo/chuyển hội thoại ở cửa sổ nhỏ; giữ giao diện desktop hiện tại.
- `FE/src/components/chat/MessageItem.tsx`: giữ hiển thị context riêng với citation; dọn khoảng trắng thừa.

### 3.6. Model và lỗi provider tạm thời

Đã xác nhận container thực sự chạy:

- Model chính: `gemini-3.5-flash-lite`.
- Model dự phòng: `gemini-3.6-flash`.

Giá trị local cũ ghi đè mặc định của Compose là một vấn đề cấu hình. Lỗi 503 quá tải từ provider là vấn đề riêng; không thể kết luận cứ đổi model là loại bỏ vĩnh viễn lỗi provider.

`feature-rag-chatbot/rag-chatbot/api.py` giới hạn tối đa 2 model khác nhau trong một lượt; mỗi model tối đa một attempt SDK, timeout 12 giây. Tổng phần gọi model dự kiến nằm trong read timeout 30 giây của Java. Retry toàn request có giới hạn do `RagCompletionClient` quản lý. `FE/nginx.conf` cho phép chờ 150 giây để proxy không tự cắt giữa quá trình retrieval/retry của backend.

Fallback được test tự động với lỗi 503 giả lập; lỗi xác thực không kích hoạt fallback tại lớp Python. Không cố ý gây quá tải provider thật để thử. Không in API key hoặc đưa key vào báo cáo.

## 4. Giải thích cơ chế context để báo cáo

1. Người dùng đăng nhập; backend xác định chủ sở hữu, không nhận một owner tùy ý từ giao diện.
2. Mỗi câu hỏi lấy lịch sử hiện tại, tóm tắt cũ và memory đã xác nhận phù hợp.
3. Chế độ chỉ phiên hiện tại không tự đọc các phiên khác. Memory phạm vi USER vẫn có thể áp dụng ở mọi phiên của chính người đó.
4. Chế độ liên kết dùng snapshot do người dùng chọn. Nguồn thay đổi không tự ghi đè snapshot; phải làm mới.
5. Chế độ tự tìm lịch sử dùng mức liên quan từ khóa trên các phiên gần đây thuộc cùng người dùng. Đây chưa phải tìm kiếm ngữ nghĩa toàn bộ lịch sử vô hạn.
6. Câu hỏi về sản phẩm/chính sách/quy trình gọi retrieval; backend kiểm tra tài liệu truy xuất có được phép dùng và ở trạng thái hợp lệ.
7. Model nhận cả context và tài liệu, với quy tắc phân biệt loại bằng chứng.
8. Lưu câu trả lời và evidence; giao diện đọc lại được nguồn sau reload.

Ngân sách mặc định hiện tại: tối đa 20 tin nhắn gần đây, 6.500 ký tự lịch sử; 3.000 ký tự tóm tắt; 2.000 ký tự memory; 5.000 ký tự tổng context liên kết; 3.000 ký tự lịch sử liên quan. Tự tìm xét tối đa 50 phiên gần đây, chọn tối đa 2 nguồn liên quan. Context quá dài vẫn phải cắt hoặc tóm tắt; không nên mô tả tính năng là “AI nhớ mọi thứ mãi mãi”.

## 5. Kiểm thử code và phạm vi ảnh hưởng

Các test mới tập trung ở:

- `BE/src/test/java/com/blueai/rag/context/ContextRelevanceRankerTest.java`.
- `BE/src/test/java/com/blueai/rag/chat/controller/RagChatConversationContextIntegrationTest.java`.
- `BE/src/test/java/com/blueai/rag/conversation/model/MessageEvidenceConverterTest.java`.
- `BE/src/test/java/com/blueai/rag/conversation/service/ConversationContextSnapshotFactoryTest.java`.
- `FE/src/api/blueAiApi.test.ts`.
- `feature-rag-chatbot/rag-chatbot/tests/test_api.py`.

Đã chạy toàn bộ bộ test backend, gồm quyền sở hữu, link context, memory, budget, summary bất đồng bộ, auth, Studio và retry. Kiểm thử tự động không dùng provider thật để cho kết quả lặp lại được. Các lượt chat UI ở mục 2 bổ sung kiểm tra chất lượng trả lời thật.

Phân tích GitNexus cảnh báo ảnh hưởng cao ở cấu trúc tin nhắn, prompt dùng chung và snapshot. Bản vá giữ tương thích dữ liệu cũ bằng trường nullable, kiểm tra lại các luồng đọc/ghi hội thoại và chạy toàn bộ test thay vì chỉ test riêng một hàm. Công cụ phân tích không phải bằng chứng rằng không còn lỗi.

Chưa commit hoặc push trong đợt kiểm tra này. Không thay đổi branch, không ghi đè các thay đổi sẵn có ngoài phạm vi sửa lỗi.

## 6. Bàn giao và dữ liệu kiểm thử

- Đã xóa đúng 5 hội thoại giả lập, ghi nhớ `sales-response-style`, refresh token và tài khoản thử nghiệm do agent tạo. Đã kiểm tra số hội thoại và tài khoản thử nghiệm còn lại bằng 0. Các dữ liệu thử này không có cơ chế khôi phục trong giao diện; kịch bản/kết quả được ghi lại trong báo cáo.
- Đã kiểm tra lại `Bluecom_Knowledge_Base_RAG.csv` vẫn ở trạng thái READY. Không xóa tài khoản của người dùng hoặc KB thật này.
- Trong phần kiểm tra trước của cùng đợt, hai tài liệu thử nghiệm cũ `blueai-rag-e2e-sao-bang-47.md` và `blueai-csv-knowledge-e2e.csv` đã được loại khỏi metadata/index để không lẫn vào KB chính thức. File upload gốc chưa xóa; FAISS có bản sao lưu ở `/app/.rag-index/e2e-cleanup-backup-20260922/` trong volume RAG. Có thể nạp lại từ file gốc; không có bản sao SQL riêng của hai metadata row đã xóa.
- Trang web vẫn chạy tại `http://localhost:3000/`, để ở màn hình đăng nhập cho người dùng kiểm tra bằng tài khoản riêng.
- Kiểm tra cuối: health backend HTTP 200, RAG ready, MySQL/Redis/RAG healthy, cấu hình Nginx hợp lệ, `git diff --check` sạch.
- GitNexus detect-changes báo 34 file tracked, 239 symbol và 123 luồng có thể bị ảnh hưởng trên toàn bộ thay đổi đang tích lũy của branch; mức cảnh báo critical. Đây bao gồm các thay đổi có trước lượt tiếp tục, không chỉ bản vá hôm nay. Các file mới chưa track cũng đã được đưa vào build/test, nhưng số thống kê trên không thay thế review diff đầy đủ trước merge.

Khuyến nghị bàn giao: đủ cơ sở để tiếp tục UAT nội bộ cho các luồng context/KB đã liệt kê. Chưa coi đây là chứng nhận production-ready tuyệt đối. Provider có thể tiếp tục gặp quota/503; quy tắc phân loại theo từ khóa vẫn có giới hạn với cách diễn đạt mới; tóm tắt AI và câu trả lời vẫn cần đánh giá trên bộ câu hỏi nghiệp vụ thực tế lớn hơn.
