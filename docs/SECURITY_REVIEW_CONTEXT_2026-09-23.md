# Rà soát trước khi push conversation context — 23/09/2026

## Kết luận và phạm vi

Branch đích: `feature/conversation-context`, tách từ `debugg_AI` tại
`35b4e3697335b172d65ae67a1655d36e69239940`. Không merge main, không force-push,
không viết lại lịch sử và không triển khai production trong đợt này.

Bản chức năng có thể đưa lên feature branch để lưu trữ/review và UAT nội bộ.
**Chưa phê duyệt production:** quét Python phát hiện các dependency cần cập nhật.
Việc test thành công không chứng minh không còn lỗ hổng.

## Secret và phạm vi dữ liệu được đưa lên Git

- Gitleaks quét 21 commit reachable từ HEAD nền: 5 kết quả cảnh báo.
- Quét toàn bộ snapshot của Git index, bao gồm các file mới: 3 kết quả cảnh báo.
- Đã phân loại: JWT mẫu của job CI trong `.github/workflows/verify.yml` và JWT
  test trong `application-test.properties`/bản build test lịch sử. Giá trị CI
  giải mã thành chuỗi mẫu lặp, giá trị test có dấu hiệu `test-only`.
- Không phát hiện provider key thật trong phạm vi quét; không coi kết quả này
  là chứng minh mọi loại secret đều được nhận diện hoặc mọi ref GitHub đã sạch.
- Không thêm allowlist rộng và không bỏ qua cảnh báo một cách tự động.
- `.env` thật, `BE/target`, `FE/node_modules` được ignore. Các file env tracked
  chỉ là `.env.example`/`.env.production.example`; không đưa database, upload,
  vector index, log hoặc token phiên thử nghiệm vào commit.
- Không gọi API provider để kiểm tra hoặc rotate key. Key từng lộ trước đây vẫn
  phải được chủ tài khoản xác nhận đã revoke, dù repository là private.

## Kiểm tra code và bản vá bổ sung

- API context lấy owner từ JWT trên server. Hội thoại nguồn và đích đều được
  kiểm tra quyền sở hữu; link ID còn phải thuộc hội thoại đích.
- Memory chỉ được sửa theo owner; source message được kiểm tra owner và phạm vi
  hội thoại. Memory hết hạn/không confirmed không được chọn vào context.
- Lịch sử tự động được tìm trong hội thoại của owner, không tìm toàn bộ user.
- Context có giới hạn ký tự/số tin nhắn; prompt phân biệt context không đáng tin
  với hướng dẫn hệ thống. Đây là giảm thiểu, không bảo đảm chống prompt injection tuyệt đối.
- Tin nhắn và tiêu đề context được render thành React text, không chèn HTML thô
  trong các thành phần đã rà. Không bổ sung truy vấn SQL ghép chuỗi từ input.
- Phát hiện API cấu hình/liên kết context chưa kiểm tra trạng thái user bị khóa
  khi JWT vẫn còn hạn. Đã sửa `ConversationLinkService.findOwnedConversation`
  để từ chối owner không ACTIVE với HTTP 403 / `USER_DISABLED`.
- Thêm 3 bài integration test trong `ConversationContextIntegrationTest`:
  anonymous bị chặn trên cả 5 thao tác; user khác không đọc/sửa/liên kết/làm mới/
  xóa context; user bị khóa không sử dụng lại access token cũ cho các thao tác này.
- GitNexus impact cho hàm kiểm tra owner: 5 caller trực tiếp, 5 luồng context,
  mức CRITICAL. Vì vậy đã chạy toàn bộ backend thay vì chỉ test riêng bản vá.

## Kết quả kiểm thử trước commit

| Hạng mục | Kết quả |
| --- | --- |
| Java 21: clean compile + toàn bộ test, mount mã nguồn hiện tại read-only | 72 passed, 0 failed/errors/skipped |
| Frontend Vitest | 20 passed |
| Frontend TypeScript + production build | Thành công |
| Python unittest từ source hiện tại | 36 passed |
| Nginx cấu hình đang chạy | Hợp lệ |
| Git whitespace check | Đạt |
| npm audit, gồm development dependencies | 0 cảnh báo tại thời điểm quét |

Bản vá trạng thái tài khoản đã được clean compile và test trong container kiểm
thử riêng; chưa rebuild/restart web đang chạy để áp dụng bản vá đó. Không rerun
UI nghiệp vụ ở đợt push này; kết quả UI trước đó được lưu trong
`QA_CONTEXT_KB_2026-09-23.md` (đó là báo cáo lịch sử trước commit).

## Tồn tại cần xử lý trước production

1. **Dependency Python:** chạy pip-audit trong container tạm từ image RAG hiện
   tại trả về 87 bản ghi cảnh báo ở 2 package: `pypdf 6.0.0` (75),
   `starlette 0.47.3` (12). Có ID lặp trong dữ liệu advisory, nên đây không phải
   khẳng định có 87 CVE độc lập. Không khai thác thử các lỗ hổng này.
   Các advisory bao gồm vấn đề tiêu thụ tài nguyên khi parse PDF và xử lý HTTP;
   khả năng khai thác phụ thuộc đường chạy/config thực tế.
   `requirements.txt` ghim pypdf 6.0.0 và FastAPI 0.116.1; Starlette là phụ thuộc
   gián tiếp. Cần cập nhật pypdf và bộ FastAPI/Starlette tương thích, rebuild sạch,
   quét lại, test upload/ingestion/retrieval/API. Không tự nâng framework major
   chỉ để làm báo cáo hết cảnh báo trong đợt push feature này.
2. Chưa chạy công cụ CVE đầy đủ cho Maven dependencies hoặc Docker OS packages;
   backend test pass không thay thế các bước quét đó.
3. Chưa kiểm chứng cấu hình mã hóa ổ đĩa/database, HTTPS thực tế, cookie/session
   production, secret manager hoặc quyền hạ tầng. Không chứng nhận đủ toàn bộ
   checklist 20 quy tắc bảo mật chỉ từ review tính năng context.
4. Giới hạn context là giới hạn prompt; số record memory/link và tải đồng thời
   còn cần quota/phân trang/load test trước khi mở rộng người dùng.
5. Chỉ quét lịch sử reachable từ branch nền, không chứng nhận các branch/tag
   khác trên remote không còn secret cũ. Việc revoke và purge toàn bộ ref là
   công việc riêng cần phối hợp với những người cùng dùng repository.

Khuyến nghị: giữ thay đổi ở feature branch để review; xử lý dependency và các
kiểm tra production còn thiếu trước khi quyết định phát hành.
