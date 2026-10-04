# Manual test và cổng quyết định deploy BlueAI

Phiên bản chuẩn: commit `fbc8179f4aefec489f971fe304d01e97077b7bb8`, branch `feature/conversation-context`.

Tài liệu này mô tả cách thử, không mặc định mọi case đã chạy/đạt. Xem báo cáo kết quả cùng ngày để biết bằng chứng thực tế. Không thể chứng minh phần mềm có rủi ro bằng không; mục tiêu là tìm lỗi và ngăn release khi còn gate chưa đạt.

## Chuẩn bị và quy tắc

- Dùng QA/staging riêng, không chạy delete/restore/load test trên dữ liệu thật. Có ADMIN, USER A, USER B và admin thứ hai để thử owner boundary.
- Stack QA local dùng `compose.acceptance.yaml`, database/volume riêng, chỉ bind loopback 23000/23081/23800. Đây là profile `qa` để trình duyệt HTTP local dùng được; **không thay thế staging HTTPS/profile prod**.
- Provider key chỉ cấp từ process memory/secret store; không lưu vào case, Git, ảnh hoặc log. Không dùng dữ liệu khách hàng thật. Spam/load dùng Dialog hoặc provider giả.
- Tài liệu mẫu: `fixtures/qa-sales-policy.md`. Chỉ là chính sách giả, có marker riêng; phải READY trước khi thử citation.
- Ghi cho từng case: ID, người thực hiện, commit/image digest, môi trường, thời điểm, bước, expected/actual, PASS/FAIL/PARTIAL/BLOCKED/NOT RUN và ảnh/log đã che dữ liệu nhạy cảm.
- PARTIAL nghĩa là đã thử một phần nhưng chưa bao phủ hết bước/biến thể của case; không được cộng vào PASS. FAIL có bằng chứng sai expected; BLOCKED có điều kiện tiên quyết chưa đạt; NOT RUN chưa được thực thi trong đợt này.
- PASS UI nghĩa là đã thao tác giao diện; PASS API không tự được tính là đã test UI. CI là nguồn bằng chứng riêng. Một case được skip/blocked không phải PASS.
- Chỉ dọn các tài nguyên test đã ghi rõ ID. Không dùng `down -v`, purge, xóa file/key hoặc repair migration trên stack thật.

## A. Khởi động, release và cấu hình

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| REL-01 | Checkout đúng commit; kiểm tra working tree và manifest 3 image | Không thiếu file; image chỉ đúng commit đã test |
| REL-02 | Build BE Java 21, FE Node 22, RAG Python 3.13 bằng Dockerfile | Cả 3 image build được; không copy `.env`/key vào artifact |
| REL-03 | Khởi động database mới và stack QA | Flyway tới V9, Hibernate validate; MySQL/Redis/RAG/API/FE ready |
| REL-04 | Gọi RAG `/health`, `/ready`, `/readiness` riêng | Phân biệt liveness, key startup gate, worker operational readiness |
| REL-05 | Chạy Compose production validation bằng secret giả | Chỉ ingress publish port, dependency healthy và restart policy đúng |
| REL-06 | Profile prod với credential dev/origin wildcard hoặc HTTP | Startup từ chối, chỉ nêu tên cấu hình; không in secret |
| REL-07 | Kiểm tra image/DB production thật với release được duyệt | Có digest bất biến, schema fingerprint và approval; không dùng tag mutable làm rollback bundle |
| REL-08 | Kiểm tra tài nguyên máy đích và volume trống | Có headroom, dung lượng/index/log/backup tính đủ; không suy từ laptop sang VPS |

## B. Đăng nhập và quản trị tài khoản

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| AUTH-01 | Mở web khi chưa đăng nhập, gọi API protected | UI yêu cầu login; API 401 JSON, không lộ dữ liệu |
| AUTH-02 | Submit trống; email sai định dạng; mật khẩu sai một lần | Validation rõ; sai credential không nhận token hoặc chi tiết gây lộ account |
| AUTH-03 | Đăng nhập ADMIN hợp lệ | Đúng danh tính/role; các workspace admin mở được |
| AUTH-04 | Đăng nhập USER A | Chỉ workspace được phép; admin/Studio/usage API trả 403 |
| AUTH-05 | Dùng JWT rác, JWT hết hạn và token đã revoke | 401, không thực thi thao tác; frontend xử lý phiên hết hạn |
| AUTH-06 | Refresh một token; dùng lại refresh cũ | Rotation đúng; token cũ bị từ chối theo chính sách, không replay cấp token mới |
| AUTH-07 | Logout, thử lại refresh; reload/back trình duyệt | Phiên đã revoke không phục hồi; không hiển thị dữ liệu nhạy cảm sau logout |
| AUTH-08 | ADMIN tạo user test, tìm bằng email và paging | Validate input; USER không tự cấp role ADMIN; response không có hash/password |
| AUTH-09 | ADMIN đặt lại mật khẩu user test qua API | Mật khẩu/JWT/refresh cũ vô hiệu; mật khẩu mới dùng được; response không chứa credential |
| AUTH-10 | Disable/soft-delete user test và đăng nhập lại | Từ chối phiên; filter mặc định ẩn tombstone, includeDeleted chỉ admin thấy |
| AUTH-11 | Burst login/refresh/logout ở ingress fixture | 429 JSON + Retry-After; OPTIONS không ăn quota; request bị chặn không tới backend |
| AUTH-12 | Thử public registration và self-service reset/change đã retired | Bị từ chối theo policy hiện tại, không tạo account hoặc đổi credential |

## C. Hội thoại, context và bộ nhớ

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| CTX-01 | USER A tạo hội thoại, tìm theo marker và mở lại | Lưu được, paging đúng, reload vẫn có lịch sử |
| CTX-02 | USER B list/get/delete/context/memory của A | Không thấy dữ liệu; direct ID 404/403; không thể sửa/xóa |
| CTX-03 | Ghi dữ kiện sales giả, hỏi tiếp bằng đại từ trong cùng hội thoại | Bot dùng đúng dữ kiện, không lẫn người/khách khác |
| CTX-04 | Tạo phiên B, chọn chế độ chỉ phiên này; hỏi dữ kiện A | Không tự lấy context phiên A |
| CTX-05 | Liên kết A vào B, hỏi dữ kiện sales A | Hiển thị link và trả đúng dữ kiện từ snapshot được phép |
| CTX-06 | A có tin nhắn mới; hỏi B trước và sau refresh link | Snapshot không tự trôi; refresh cập nhật đúng, trace nhận diện nguồn |
| CTX-07 | Unlink A hoặc xóa hội thoại nguồn test | Link/snapshot nguồn không còn được truy xuất cho lượt mới. Dữ kiện đã chép vào lịch sử của B không tự bị xóa; nếu cần quên hoàn toàn phải có quy trình xóa dữ liệu riêng |
| CTX-08 | Chế độ automatic với lịch sử liên quan và lịch sử không liên quan | Chỉ history liên quan trong budget; không gom tất cả hội thoại |
| CTX-09 | Tạo memory thủ công, sửa trạng thái và đọc lại | Scope/status đúng; user khác không thấy; sensitive memory không tự ghi |
| CTX-10 | Xóa memory test, tạo phiên mới và hỏi lại | Memory đã xóa không còn được đưa vào prompt |
| CTX-11 | Thử self-link, link trùng, link cross-user và context mode sai | Reject đúng, không làm hỏng context hợp lệ |
| CTX-12 | Lịch sử dài vượt các giới hạn ký tự/messages | Bounded context/summary ổn định; không vượt budget hoặc leak toàn history |

## D. Tài liệu, ingestion, RAG và citation

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| KB-01 | ADMIN upload fixture MD qua UI | UI phản hồi rõ; worker claim và chuyển PENDING/PROCESSING → READY |
| KB-02 | Search theo filename/status; reload dashboard | Status, chunk count và paging đúng; READY không chỉ là toast |
| KB-03 | Hỏi giá Standard QA Aurora từ fixture READY | Trả 1.250.000 VND/tháng, 25 sales; có nguồn khớp document/chunk |
| KB-04 | Mở evidence/citation trên UI | Đúng tên tài liệu, đoạn và ID đã verify; không chỉ render `[S1]` giả |
| KB-05 | Hỏi chính sách ngày lễ không có trong fixture | Nêu thiếu căn cứ; không bịa một chính sách chắc chắn |
| KB-06 | USER A/B tải tài liệu và GET tài liệu trái quyền | Tuân thủ role/owner boundary; không lộ file/path/raw chunk của người khác |
| KB-07 | Upload file rỗng, `.exe`, tên path traversal, giả extension | Reject trước ghi/ingest nguy hiểm; filename được làm sạch |
| KB-08 | Upload 2 MB, sát 20 MB và quá 21 MB qua ingress | Các giới hạn frontend/Nginx/Java đồng bộ; quá mức 413, UI hiểu được |
| KB-09 | Chunk size/overlap âm, overlap >= size và quá lớn | 400; không tạo job hoặc index lỗi |
| KB-10 | Wrong worker key và processing attempt cũ trên private network | 401/409; claim limit=0 với đúng key 400, không claim tài liệu thật |
| KB-11 | Worker/parser/provider lỗi và retry | FAILED/error an toàn; retry hữu hạn/attempt isolation; không gắn READY giả |
| KB-12 | Soft-delete fixture rồi search/hỏi lại | Không còn cite tài liệu đã xóa; tombstone authoritative dù purge vector chưa xong |

## E. BlueAI Custom / Dialog

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| DLG-01 | Tạo Starter và Sales DEMO; reload cấu hình | Demo có 3 intent/4 entity/13 node, Core off; bot active không tự đổi |
| DLG-02 | Welcome và `xin chào` | Hướng dẫn đúng phạm vi; ghi rõ dữ liệu demo |
| DLG-03 | `hỏi giờ` rồi HN/HCM/Đà Nẵng; thử Sài Gòn | Hỏi slot thiếu, chọn đúng branch/synonym |
| DLG-04 | `báo giá` → sản phẩm A → 20 → Hà Nội | Hỏi đúng slot thiếu; prompt render biến; tóm tắt đúng và dừng chờ xác nhận |
| DLG-05 | Cung cấp đủ dữ kiện trong một câu | Bỏ qua 3 câu hỏi đã đủ; vẫn đọc lại trước xác nhận |
| DLG-06 | `đổi thành 30` khi đang chờ | Giữ sản phẩm/khu vực, đổi số lượng, đọc lại; trace DIALOG |
| DLG-07 | `xác nhận` hoặc `không đồng ý` | Hai nhánh khác nhau; không tạo CRM/đơn thật trong demo |
| DLG-08 | Gửi `đồng ý` ngay trong câu yêu cầu ban đầu | Không bỏ qua bước đọc tóm tắt/xác nhận lần sau |
| DLG-09 | Thử số lượng 35, sản phẩm lạ và `hủy` | Giải thích giới hạn từ điển; hủy/reset không giữ slot cũ |
| DLG-10 | Yêu cầu không thuộc demo | Fallback cấu hình, không gọi AI/KB; nhãn không nhầm câu hỏi slot với fallback |
| DLG-11 | Sửa draft rồi đổi bot/workspace, chọn Cancel | Giữ draft; không đổi sang màn khác hoặc tự lưu |
| DLG-12 | Lặp lại và chọn Discard | Bỏ đúng draft đã chỉ định; không xóa cấu hình đã lưu |
| DLG-13 | Hai bản draft cùng revision; lưu một rồi lưu bản cũ | 409/concurrency guard; không ghi đè âm thầm |
| DLG-14 | Node cha/con lỗi, cycle, slot/entity/intent không tồn tại | Validator chặn; lưu cấu hình nguyên tử |
| DLG-15 | Admin thứ hai GET/save/publish bot của admin thứ nhất | 404; không chỉ ẩn ở frontend |
| DLG-16 | Phát hành bot test và tạo release mới | Snapshot bất biến; session cũ vẫn dùng release cũ; chỉ bot test bị tác động |

## F. Widget, integration và chống lạm dụng

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| API-01 | Tạo widget session bot test đã publish | Session/token/release đúng; chưa publish hoặc publicKey sai bị từ chối |
| API-02 | Token session sai/thiếu, expired và token phiên khác | Không dùng được session của người khác |
| API-03 | Gửi requestId trùng cùng body | Replay đúng response, không gọi provider/tăng quota lần nữa |
| API-04 | requestId trùng nhưng body khác | Conflict, không đổi kết quả đã lưu |
| API-05 | Lặp nội dung bằng requestId mới vượt ngưỡng | 429 + Retry-After; không spam provider bằng load test |
| API-06 | Vượt quota session/IP, giả X-Forwarded-For | Chặn đúng; chỉ trusted proxy được quyết định IP |
| API-07 | Preflight origin cho phép và origin không cho phép | Allowlist đúng, không credential wildcard |
| API-08 | Response widget và lỗi public | Không lộ intent/entity/context/trace/key/server stack |
| API-09 | Admin cấp client capability hạn chế; client gọi capability sai | 403; documentIds/configuration không mở rộng quyền trái phép |
| API-10 | Task idempotency/concurrency/retry và kill backend fixture | Durable ledger giữ kết quả/expiry, recovery không gọi provider trùng |

## G. XSS, validation và dependency

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| SEC-01 | Tên hội thoại/node/message chứa `<img src=x onerror=...>` và script | Hiển thị như text; không thực thi HTML/JavaScript |
| SEC-02 | JSON malformed, oversized, trường role/owner/status thêm trái phép | 400/403 theo contract; không thay quyền/record ownership |
| SEC-03 | Chuỗi SQL injection trong search/title | Không bypass auth hoặc lộ bản ghi; dùng truy vấn parameterized |
| SEC-04 | Quét source, artifact và lịch sử Git bằng scanner redacted | Không ship credential thật; finding cần phân biệt fixture/dev/real; revoke được xác nhận riêng |
| SEC-05 | Npm audit all + runtime, pip audit/check, Java và OS image audit | Bao phủ build/runtime; CVE có phân loại/owner/phương án, không chỉ chạy tests |
| SEC-06 | Response header và browser session storage | CSP/X-Content-Type-Options/frame policy đúng; đánh giá rủi ro token storage, không gọi là secure cookie nếu ứng dụng không dùng cookie |

## H. Vận hành và những gate phải kiểm trên hạ tầng thật

| ID | Bước thực hiện / dữ liệu | Kết quả mong đợi |
| --- | --- | --- |
| OPS-01 | Fresh MySQL, schema cũ verified V1/V3/V5/V6, Flyway engine upgrade | Migrate/validate V9, giữ dữ liệu/checksum; không baseline/repair mù |
| OPS-02 | Kiểm tra từng JDBC session và MySQL non-UTC fixture | Session UTC được ép bằng connectionTimeZone + forceConnectionTimeZoneToSession, expiry đúng |
| OPS-03 | Restart API/RAG/FE QA sau khi lưu dữ liệu | Hội thoại/tài liệu/index còn nguyên; operational readiness trở lại |
| OPS-04 | Kill backend khi task đang PROCESSING trong fixture | Scheduler giải phóng lease/slot, expire đúng, replay không gọi provider lại |
| OPS-05 | Backup/restore fixture SQL + documents + FAISS; inject partial failure | Nội dung đúng; checksum/archive hợp lệ; khi lỗi không mở lại traffic |
| OPS-06 | Deploy/rollback validator và post-start acceptance | Không coi API UP là đủ; RAG/frontend/private route phải đạt; reject bundle mutable |
| OPS-07 | Prometheus scrape private, alert receiver stub | Metrics đúng boundary; alert thực sự đến receiver, không chỉ validate YAML |
| OPS-08 | Log pipeline, redaction, retention và monitoring image lifecycle | Không log secret; ship đúng project; dùng collector còn được hỗ trợ |
| OPS-09 | TLS/domain/firewall/CORS trên HTTPS staging thật | Cert hợp lệ, HTTPS redirect/HSTS theo policy; DB/API/RAG không public |
| OPS-10 | Client từ hai IP thật sau proxy/CDN | Quota tách IP đúng; XFF giả không bypass; tránh gom mọi khách thành một IP |
| OPS-11 | Load/soak trên VPS giống production, provider giả trước | Có p50/p95/error/CPU/RAM/DB/quota, ngưỡng đã duyệt; không suy capacity từ smoke test |
| OPS-12 | Backup thật mã hóa off-host, scheduler/restore/incident drill | Có RPO/RTO đo được, người giữ key/on-call, cảnh báo backup lỗi đã nhận |
| OPS-13 | Quota/billing cap và failure policy với provider production | Có ngân sách/alert, không retry vô hạn; staging xác nhận 429/5xx/timeout |
| OPS-14 | Release promotion và rollback tương thích schema V9 hiện tại | Có team approval, maintenance/writer drain và recovery plan; xác minh phiên bản writer đích, không tự rollback writer cũ không tương thích |

## Quyết định GO / NO-GO

NO-GO nếu có lỗi P0/P1 chưa được xử lý hoặc chưa có approval chấp nhận rủi ro; nếu những gate về TLS/firewall, secret rotation, backup/off-host restore, capacity, provider budget hoặc migration dữ liệu thật còn BLOCKED/NOT RUN, chỉ được kết luận **sẵn sàng tiếp tục staging**, không phải mở production.

CI xanh và manual test local đạt không thay thế xác minh trên máy chủ/domain/database thật. Người deploy phải lưu kết quả các gate còn lại cùng release và chịu trách nhiệm promotion theo quy trình team.
