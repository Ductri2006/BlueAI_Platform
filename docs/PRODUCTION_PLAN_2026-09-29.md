# Kế hoạch chuẩn bị production BlueAI

Ngày bắt đầu: 29/09/2026. Cơ sở: [báo cáo readiness](PRODUCTION_READINESS_2026-09-29.md).

## Mục tiêu và cách theo dõi

Chuẩn bị một release kiểm thử được, triển khai trên HTTPS staging, chứng minh phục hồi dữ liệu và mức tải hỗ trợ trước khi mở traffic production. Tạm thiết kế theo Docker Compose một server; CPU/RAM, domain, proxy và số người dùng đồng thời cần được xác nhận để chốt cấu hình.

`Chưa làm` → `Đang làm` → `Đã kiểm chứng trong repo` → `Đã kiểm chứng staging`.
Không đánh dấu hạng mục hoàn thành chỉ vì đã có script hoặc Compose validate pass.

## Các đợt triển khai

| Đợt | Công việc | Điều kiện kết thúc | Trạng thái |
| --- | --- | --- | --- |
| **1 — CI và ingress** | Schema V6; upload Nginx; chặn public internal/actuator; giới hạn login; restart/healthcheck; đưa kiểm thử ingress thực vào CI. | Full Java verify xanh; Nginx kiểm thử upload và boundary; Compose chỉ publish frontend, health/restart đúng. MySQL/runtime Docker được kiểm chứng bằng CI/staging. | **Code đã sửa; local gates pass; chờ container/CI/staging** |
| **2 — Quota AI và transaction** | Quota request/user, concurrency toàn hệ thống, token/ngày; xử lý retry/idempotency; tách thời gian chờ AI khỏi transaction dài. | Khi bị từ chối không gọi provider thêm; retry không nhân đôi turn; kiểm thử concurrency/pool và budget pass. | Chưa làm |
| **3 — Release và staging** | Build/publish image có digest; bundle release/rollback; quét dependency/image; cấu hình domain/TLS/origins/secrets/firewall/IP thật. | Release tái lập được; HTTPS staging pass login/refresh/admin, user isolation, upload → READY → RAG/citation, Dialog/widget, usage và private worker API. | Chưa làm |
| **4 — Vận hành và monitoring** | Chốt RAM/CPU/heap/log rotation/disk; chuyển Promtail sang Alloy; alert RAG/worker/AI/backup; bảo vệ UI bằng CSP phù hợp. | Metrics/logs đọc được; gửi firing/resolved thật; service hồi phục sau crash/reboot; tài nguyên có giới hạn theo server đã chọn. | Chưa làm |
| **5 — Phục hồi dữ liệu** | Chọn RPO/RTO; backup định kỳ mã hóa ngoài host; giữ khóa phục hồi; restore và rollback rehearsal. | Restore DB + documents + FAISS ở môi trường cô lập, đọc được dữ liệu và có thời gian phục hồi đo được; backup lỗi phát cảnh báo. | Chưa làm |
| **6 — Tải và mở production** | Load/soak, failure/retry; đánh giá Dialog/context/RAG theo dữ liệu thật; chốt capacity và quy trình reindex. | Không còn P0; p95/error/quota/pool/RAM đạt ngưỡng đã chốt; có người theo dõi rollout và bộ rollback đã kiểm chứng. | Chưa làm |

## Đợt 1 — checklist thực thi

- [x] **1.1** Cập nhật migration test lên V6, kiểm tra bảng/columns/index usage; không sửa các migration đã phát hành. H2 gate pass.
- [x] **1.2** Cấu hình body limit upload 21 MB, API khác 1 MB; đã viết gate upload 2/20 MB và vượt 21 MB. Gate Nginx container còn chờ chạy.
- [x] **1.3** Cấu hình chặn `/api/v1/internal` và `/api/v1/internal/**`, `/actuator` và `/actuator/**`; đã viết gate xác nhận backend giả không nhận request private. Gate container/staging còn chờ.
- [x] **1.4** Cấu hình login 10 request/phút/IP, burst 5, OPTIONS miễn quota, JSON 429/Retry-After 60. Gate container và IP thật staging còn chờ.
- [x] **1.5** Thêm MySQL restart, API/frontend healthcheck và frontend đợi API healthy. Compose gate pass; runtime/reboot còn chờ.
- [x] **1.6a** Thêm job MySQL 8.4, gate Nginx với backend giả và gate production Compose vào CI. Workflow YAML parse pass.
- [ ] **1.6b** Chạy CI/runtime: Nginx upload/boundary/login và MySQL migration phải pass trước khi chốt đợt 1.
- [x] **1.7** Full `mvn verify` pass với 174 test; GitNexus scope review đã chạy. Test MySQL mới compile và skip có chủ ý khi chưa có DB disposable.
- [ ] **1.8** Trên Docker/staging: khởi động image runtime, reboot/crash recovery, upload qua ingress thật, login 429 và private worker gate.

## Các thông tin cần để chốt đợt sau

- OS, CPU/RAM/disk; domain và TLS/CDN/WAF; kiểu deploy/pipeline/registry.
- Số người dùng đồng thời, lượng tài liệu/vector và quota/billing Gemini.
- Ngưỡng request/concurrency/token/ngày theo user và toàn hệ thống.
- RPO/RTO, nơi lưu backup ngoài host và kênh nhận alert.

Các việc chỉ phụ thuộc repo được thực hiện trước. Các gate hạ tầng được giữ ở trạng thái chờ kiểm chứng cho đến khi có môi trường tương ứng.

## Nhật ký kết quả

- **Bắt đầu:** điểm readiness 65/100; full backend 173/174 pass, lỗi test mong schema V5 trong khi thực tế V6. Docker Engine local chưa chạy.
- **Đợt 1 — local:** full backend 174/174 pass, Maven verify/package thành công; migration test kiểm tra V6 usage table/columns/index. Sau khi thêm gate MySQL, tổng report là 175 test, 174 pass và 1 skip vì chưa có `MYSQL_MIGRATION_TEST_URL`.
- **Config:** `node BE/ops/test-production-compose.mjs` pass; workflow YAML parse pass; hai script Node kiểm tra syntax pass; `git diff --check` không có lỗi whitespace trong các file của đợt này.
- **GitNexus:** impact của migration method/class là LOW, 0 caller và 0 process bị ảnh hưởng. Lần scope check cuối `detect_changes(scope=all)` bao gồm nhiều thay đổi auth/security đã có trước, trả CRITICAL (352 symbols/57 tracked files); đây không phải bằng chứng chỉ riêng đợt này có 352 thay đổi. Review các thay đổi cũ cùng CI/staging là gate release bắt buộc. Tool chưa bao phủ các test/file mới untracked; chúng đã được đọc và kiểm tra riêng.
- **Runtime:** đã thử mở Docker Desktop; Linux Engine vẫn không kết nối được. Chưa chạy Nginx/MySQL container gate, chưa build image/reboot stack và chưa chạy HTTPS staging. Các mục 1.6b/1.8 được giữ mở.
- **Tiếp theo:** hoàn tất runtime gates đợt 1 khi Docker/CI có sẵn; bắt đầu đợt 2 quota AI với cấu hình ngưỡng theo server/quota provider, không cần phê duyệt lại cho công việc trong repo đã được yêu cầu.

