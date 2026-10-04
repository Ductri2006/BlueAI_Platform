# Bàn giao branch cho team — 04/10/2026

Branch: `feature/conversation-context`. Theo yêu cầu, bản vá được chia thành **5 commit và 5 lần push tuần tự**, mỗi lần một nhóm để review/revert. Team nên đợi lần push thứ 5 rồi pull bản tổng hợp; các mốc trước đó chưa bao gồm toàn bộ bản vá. Mỗi push có thể kích hoạt CI riêng. **Đây là bản để team pull, CI và staging; chưa là bản được duyệt production.** Không merge main hoặc deploy tự động trong lần bàn giao này.

## Nhận source và chạy local

Nếu checkout đang có thay đổi, lưu công việc của bạn trước; không dùng reset hard để lấy bản mới. Với checkout sạch:

```sh
git fetch origin
git switch feature/conversation-context
git pull --ff-only origin feature/conversation-context
```

Clone mới có thể dùng:

```sh
git clone --branch feature/conversation-context https://github.com/Ductri2006/BlueAI_Platform.git
```

Đọc `README.md` và `BE/PRODUCTION_DEPLOYMENT.md`. Trên máy của bạn, tạo `BE/.env` từ example với key/provider và secret riêng rồi chạy từ `BE`:

```sh
docker compose up --build -d --wait
```

Frontend local mặc định ở `http://localhost:3000`. Máy của tác giả có API host-port override riêng; không mặc định cổng 18080 hoặc 23000 là cổng máy bạn. Những port/schema/volume QA ghi trong báo cáo không phải deployment của team.

**GitHub chỉ chứa source và bằng chứng kiểm thử tổng hợp, không chứa image runtime đã build, database, FAISS index, tài liệu đã upload, tài khoản, refresh cookie hoặc provider key của tác giả.** Máy mới cần build, bootstrap admin bằng credential riêng và upload tài liệu mẫu để có KB READY. File `fixtures/qa-sales-policy.md` là dữ liệu giả; không dùng làm báo giá thật.

Các helper `qa-*` khóa vào stack QA riêng của tác giả và một số runtime path Windows; không chạy nguyên trạng trên máy team hay production. Các file evidence ghi trạng thái “uncommitted” là ảnh chụp kết quả **tại thời điểm test**, không phải trạng thái Git sau push. Raw Trivy/npm/pip/gitleaks reports được giữ local và ignore; bản tóm tắt, sanitized findings, ledger, screenshot và script vẫn đi cùng branch. Các Docker tag `followup-20261004` là local QA, chưa publish registry.

## Phạm vi review

1. Browser login/refresh/logout: cookie HttpOnly, Origin/header guard, token chỉ ở RAM, logout/401 clear session; widget quota fail-closed khi Redis unavailable.
2. Upload/worker: kiểm file thực, DOCX expanded bounds, UTF-8, streamed size; retry/attempt isolation, durable FAISS save và diagnostics an toàn.
3. Context: relevance với khách hàng được nêu rõ trong câu hỏi dài, owner isolation, source/budget; không nhận keyword heuristic là semantic memory hoàn hảo.
4. Ops/CI: JDBC UTC session, dependency/runtime images, security headers, Alloy/redaction, restore TCP readiness, legacy Flyway V1/V3 → V9.
5. Acceptance evidence: `RESULTS_FOLLOWUP_2026-10-04.md`, ledger/manifest và `SELF_TEST_FOLLOWUP_2026-10-04.md`.

CI của commit trước đã xanh; **CI của các commit mới phải được kiểm lại trên GitHub sau push**, không suy kết quả cũ áp dụng cho source mới. Local verify gần nhất: backend 372 executed PASS/30 skipped; frontend 123 PASS; RAG 70 PASS; legacy MySQL 2 executions chạy riêng. Không cộng các conditional runs thành unique tests.

## Chưa duyệt deploy production

Ledger 90 case tích lũy: 73 PASS, 8 PARTIAL, 1 FAIL, 8 NOT RUN. SEC-05 còn 5 npm build-chain HIGH với Tailwind 3 và 45 OS HIGH trên RAG image scan (0 CRITICAL). Những finding chưa được xử lý/chấp nhận có chủ sở hữu; không ignore hoặc nhận là false positive chỉ để xanh. Giữ Tailwind 3 theo quyết định hiện tại.

Team cần review/CI, staging HTTPS với secret và proxy topology thật, VPS/load/soak, encrypted off-host backup/RPO/RTO, provider budget, secret rotation đã lộ nếu còn, migration/writer-drain/rollback và người duyệt release. Cấu hình/example và tài khoản QA không được dùng nguyên trạng ở production. Bản vá không chứng minh key lịch sử đã revoke hoặc mọi parser đã được sandbox.
