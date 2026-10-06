# Đánh giá sẵn sàng deploy BlueAI — 29/09/2026

> Đây là ảnh chụp đánh giá trước khi bắt đầu sửa. Tiến độ mới được theo dõi
> trong [kế hoạch production](PRODUCTION_PLAN_2026-09-29.md): lỗi test V6 đã
> sửa và full backend local 174/174 pass; ingress/health/restart đã cập nhật
> cùng gate CI, còn chờ Nginx/MySQL runtime và staging. Chưa chấm lại readiness
> hoặc xác nhận đủ điều kiện mở production.

## Kết luận

**Điểm tạm thời: 65/100, tương đương 6,5/10. Chưa đạt điều kiện mở production cho người dùng thật.**

Phạm vi: working tree hiện tại, luồng Java/Python/React, cấu hình Docker Compose một server, CI và script vận hành. Chưa có cấu hình máy chủ, domain, quota provider hay quyền truy cập staging/production. Điểm này phản ánh mức chuẩn bị, không phải bảo đảm an toàn hoặc số người dùng hệ thống chịu được.

| Hạng mục | Điểm | Cơ sở |
| --- | ---: | --- |
| Chức năng và độ đúng | 17/20 | Dialog, RAG, phân quyền, token usage có test; full backend còn một test migration fail. |
| Bảo mật | 16/20 | JWT, refresh rotation/revocation, account status, ADMIN boundary, worker key, Redis/widget protection và production startup gate; ingress internal API, login edge protection và CSP còn việc. |
| Release và triển khai | 9/15 | Có Dockerfile, production override, CI, deploy/rollback script; chưa có pipeline build/publish image bất biến và stack acceptance. |
| Dữ liệu và phục hồi | 9/15 | Flyway, volume, backup mã hóa/checksum và restore script; scheduler/off-host storage/restore thực tế chưa được xác nhận. |
| Giám sát và vận hành | 9/15 | Có Prometheus, Loki, Alertmanager và alert rules; Promtail đã EOL, chưa kiểm chứng delivery và thiếu một số health/resource/AI cost signals. |
| Chịu tải và mở rộng | 5/15 | Có staging load script và widget/integration concurrency protection; chat transaction, DB pool, RAG lock và giới hạn container chưa được chứng minh ở tải thật. |
| **Tổng** | **65/100** | |

## Bằng chứng đã kiểm tra

- Chạy full `mvn verify`: **174 test, 173 pass, 1 failure, 0 error, 0 skip**. Maven dừng ở test gate, chưa hoàn thành verify/package release.
- Failure: `FlywayMigrationIntegrationTest.appliesFullMigrationChainAndLeavesExpectedSchemaForHibernateValidation` mong version `5`, database test đã apply đến `6`.
- Bộ frontend của lượt sửa ngay trước: **43/43 test pass**, TypeScript/Vite build pass.
- Bộ Python của lượt sửa ngay trước: **59/59 test pass**; lần đánh giá này chạy `pip check`, không có dependency conflict.
- `npm audit --omit=dev`: **0 advisory** tại thời điểm chạy. Không bao phủ dev dependencies, Java dependencies hoặc OS packages trong image.
- Python `pip_audit` có trong CI nhưng chưa được chạy ở máy local này vì chưa cài module; chưa kết luận Python không có CVE.
- Render/validate production Compose bằng credential giả: pass, chỉ frontend publish port `80`; API/MySQL/RAG không publish host port.
- Test validator rollback bundle, Promtail config và load-test metric denominator: pass. Đây là test logic/config, không phải diễn tập rollout/rollback, load hoặc gửi cảnh báo thật.
- Docker CLI có sẵn nhưng Docker Engine local chưa chạy; chưa build/run các Linux image hoặc stack đầy đủ trong lần đánh giá này.
- Test Gemini thật trong lượt trước: 2 completion, 2.415 input token, 19 output token; UI tự cập nhật. Không chứng minh tải cao hoặc billing toàn provider.
- Working tree còn nhiều thay đổi; V5/V6, usage service và trang token đang là file untracked. Chúng phải nằm trong release được kiểm thử trước khi deploy từ Git/CI.

## P0 — Hoàn thành trước khi mở traffic

### P0-01. Làm xanh full CI và kiểm chứng schema V6

**Trạng thái: lỗi đã tái hiện.**

- Nguồn: `BE/src/test/java/com/blueai/migration/FlywayMigrationIntegrationTest.java:61`, `BE/src/main/resources/db/migration/V6__gemini_usage_events.sql`.
- Cần cập nhật expectation/schema coverage cho V6, gồm bảng `gemini_usage_events` và index tương ứng; không bỏ test hoặc tắt Flyway validation.
- Điều kiện hoàn thành: full `mvn verify` pass; fresh MySQL 8.4 và nâng cấp DB staging apply đúng V1–V6, không sửa checksum migration đã phát hành.

### P0-02. Đồng bộ giới hạn upload Nginx và Java

**Trạng thái: khoảng trống xác nhận từ cấu hình, chưa chạy qua container.**

- `FE/nginx.conf` chưa có `client_max_body_size`; Nginx mặc định `1m`, quá giới hạn sẽ trả `413`. Java cho phép file 20 MB và request 21 MB (`BE/src/main/resources/application.properties:35`). [Tài liệu Nginx](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size).
- Đặt giới hạn phù hợp tại route upload, tính cả multipart overhead; đồng bộ giới hạn ở CDN/TLS proxy. Giữ giới hạn nhỏ hợp lý cho các JSON API.
- Điều kiện hoàn thành: upload qua domain staging với file nhỏ, 2 MB, gần 20 MB thành công; vượt mức bị từ chối đúng; UI hiển thị lỗi rõ.

### P0-03. Chặn worker/internal API tại public ingress

**Trạng thái: repo chưa đạt ingress contract.**

- `FE/nginx.conf:60` proxy toàn `/api/`, chưa chặn `/api/v1/internal/**`.
- `BE/ops/security-e2e.mjs:80` yêu cầu public worker route trả 403/404. Deployment guide cũng ghi rõ đây là ingress blocker còn tồn tại.
- Worker key vẫn bảo vệ backend; finding này không có nghĩa anonymous đã đọc được tài liệu. Cần đóng public đường đi tới API nội bộ.
- Điều kiện hoàn thành: mọi internal path bị chặn 403/404 từ public; worker ở private network vẫn hoạt động với shared key, wrong key/stale attempt bị từ chối đúng.

### P0-04. Hoàn tất domain, TLS, firewall, origin và secret production

**Trạng thái: cần cấu hình và kiểm chứng trên hạ tầng thật.**

- Production override chỉ publish `80:80` (`BE/compose.production.yaml:114`); TLS/WAF phải được đặt phía trước theo deployment guide.
- Chỉ deploy cùng production override và profile `prod`; không dùng Compose development để mở API/MySQL/RAG ra Internet.
- Điền origin/domain thật, JWT/DB/provider/worker/salt/webhook secret mới, bootstrap admin lần đầu rồi tắt cả email/password bootstrap. Thu hồi credential đã lộ nếu có; không tái dùng secret local.
- Xác định đường đi IP qua CDN → TLS proxy → frontend Nginx → Java. FE đang ghi X-Forwarded-For bằng `$remote_addr`; nếu đứng sau proxy mà chưa cấu hình real IP đáng tin cậy, nhiều khách có thể bị tính chung một IP/quota.
- Điều kiện hoàn thành: HTTPS hợp lệ, origin allowlist đúng, chỉ public ingress cần thiết mở, proxy spoof test pass, hai client thật có quota riêng.

### P0-05. Bảo vệ login và giới hạn chi phí AI cho luồng chat

**Trạng thái: widget/integration đã có bảo vệ; chat đăng nhập và edge login cần bổ sung/kiểm chứng.**

- Nginx hiện rate limit widget/session message; `/api/v1/auth/login` đi qua generic API proxy. Production guide yêu cầu WAF/edge rule login, chưa có rule hạ tầng thực tế để xác nhận.
- `RagChatController`/`RagChatService.ask` chưa có rate limit theo user, budget token hoặc idempotency key như widget. Không áp kết luận này cho machine-task API vì `AiTaskService` đã có quota/concurrency riêng.
- Thêm quota request và concurrent completion theo user/toàn hệ thống, budget token/chi phí theo ngày, replay an toàn khi retry, xử lý provider 429 rõ. Dashboard usage hiện quan sát được completion có metadata; chưa tính embedding hoặc tự ngắt chi phí.
- Kiểm tra active quota/billing trên project Gemini thật. Google có project spend cap nhưng ghi rõ có độ trễ xử lý và khả năng vượt cap; cần kiểm soát tại ứng dụng khi muốn chặn tức thời. [Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits), [Billing/spend caps](https://ai.google.dev/gemini-api/docs/billing/).
- Điều kiện hoàn thành: vượt quota không gọi Gemini thêm, retry không nhân đôi turn/chi phí, các ngưỡng và alert có test; edge login 429/Retry-After được xác nhận ở staging.

### P0-06. Chốt release và chạy acceptance trên stack production

**Trạng thái: chưa có bằng chứng release hoàn chỉnh.**

- Đưa đầy đủ migration, code và test mới vào commit/release đã duyệt. CI chỉ kiểm thử code/Compose/Nginx syntax; chưa build/publish ba image và chạy stack acceptance.
- Build trên Linux với Java 21/Python 3.13/Node 22 đúng Dockerfile, publish image có digest và lưu bundle release/rollback. Validator rollback đã yêu cầu image digest nhưng pipeline chưa tạo bundle này.
- Chạy trên HTTPS staging: login/refresh/admin, quyền user A/B, upload → READY → RAG → citation, publish Dialog → widget, usage token, restart với volume giữ nguyên, security E2E, provider failure và rollback tương thích schema.
- Điều kiện hoàn thành: pipeline xanh, release trỏ image digest cụ thể, staging report lưu cùng release và không còn gate fail.

### P0-07. Có backup khôi phục được ngoài server

**Trạng thái: đã có công cụ; lịch chạy và diễn tập chưa được xác nhận.**

- `BE/ops/backup.ps1` hỗ trợ age/checksum, chụp DB + documents + FAISS nhất quán bằng cách dừng writers; `restore.ps1` có dry-run/import vào MySQL disposable.
- `BE/PRODUCTION_DEPLOYMENT.md:258` ghi rõ script chưa tự chạy hoặc tạo lịch. Cần scheduler, off-host storage, retention và alert backup lỗi.
- Chốt RPO (có thể mất tối đa bao nhiêu dữ liệu) và RTO (thời gian khôi phục cho phép). Full backup hằng ngày có thể mất khoảng một ngày dữ liệu; nếu không chấp nhận thì cần binlog/PITR và thiết kế snapshot dữ liệu tương ứng.
- Điều kiện hoàn thành: bản backup thật mã hóa nằm ngoài host, restore trên môi trường cô lập và đọc được hội thoại/tài liệu/index, có người giữ khóa phục hồi và thời gian khôi phục đo được.

### P0-08. Hoàn tất startup/restart/health cơ bản

**Trạng thái: thiếu trong Compose hiện tại.**

- MySQL có healthcheck nhưng chưa có restart policy trong base hay production override. Thêm policy phù hợp và kiểm chứng reboot server.
- Auth API/frontend chưa có container healthcheck. Deploy script chỉ đợi Java `/actuator/health`; RAG `/ready` mới kiểm tra có key, chưa chứng minh key/model/index/worker dùng được.
- Thêm smoke gate cho RAG/provider và document worker, tránh gọi provider trả phí ở health probe liên tục.
- Điều kiện hoàn thành: reboot/crash/restart hồi phục đúng, readiness không tuyên bố healthy khi chức năng trọng yếu không dùng được, traffic được giữ lại cho đến khi acceptance pass.

## P1 — Hoàn thành trước khi tăng tải/người dùng

### P1-01. Đo và xử lý DB pool/transaction khi chờ AI

- `RagChatService.ask` giữ `@Transactional` xuyên qua retrieval/completion (`BE/src/main/java/com/blueai/rag/chat/service/RagChatService.java:102`). Pool mặc định chỉ 10 connection.
- Ghi usage mở thêm transaction `REQUIRES_NEW`. Đây là nguy cơ chờ/cạn pool khi nhiều request cùng giữ connection ngoài; chưa đo được giới hạn trên server.
- Tách chờ network khỏi transaction ghi DB ngắn, kiểm soát số request đồng thời và deadline; xử lý thứ tự/idempotency trong cùng conversation.
- Có staging load script ở `BE/load-tests/staging-scenarios.mjs`, mặc định 5/10/15 concurrency; gọi chat thật là opt-in. Script không thay thế soak test hoặc xác nhận 50/100 khách.
- Điều kiện hoàn thành: load/soak với dữ liệu và quota giống production, theo dõi từng endpoint, p95/p99, error/429, Hikari pending/timeouts, CPU/RAM và document backlog; giới hạn hỗ trợ được công bố theo số đo.

### P1-02. Giới hạn tài nguyên, log và dung lượng

- Compose render chưa có CPU/RAM limits hoặc Docker log rotation cho các service. Chốt heap JVM, memory FAISS, MySQL buffer/pool và dung lượng theo cấu hình server.
- Bật log rotation, disk alert, retention phù hợp cho hội thoại/tài liệu/usage. Retention ứng dụng hiện opt-in; chưa có cleanup/rollup usage table.
- Báo cáo token tính aggregate lifetime bằng SUM toàn bảng và trang poll mỗi 5 giây; cần rollup/cache khi số event lớn.

### P1-03. Chuyển Promtail sang collector còn được hỗ trợ và kiểm chứng alert

- Production đang dùng `grafana/promtail:3.4.2`. Promtail đã EOL từ **02/03/2026**; chuyển sang Alloy hoặc collector còn hỗ trợ. [Grafana chính thức](https://grafana.com/docs/loki/latest/send-data/promtail/).
- Repo có alert API 5xx/p95/login/Redis; chưa có scrape/export/alert đầy đủ cho RAG, worker backlog, Gemini 429/chi phí, filesystem và backup.
- Kiểm tra structured logs, trace/request correlation giữa Nginx/Java/Python và redaction. Config Promtail mong ECS JSON, production chưa khai báo format ECS trong application properties/Compose.
- Điều kiện hoàn thành: test alert thật có firing/resolved tới kênh người trực, log tìm được theo request, các probe/metrics chỉ ở private boundary.

### P1-04. Harden phiên ADMIN và dependency/image supply chain

- Access và refresh token nằm trong `localStorage` (`FE/src/api/blueAiApi.ts:294`). Đây là rủi ro nếu có XSS, không phải bằng chứng đã có XSS. Repo chưa có CSP cho UI; thiết kế CSP phù hợp và cân nhắc thêm bảo vệ ADMIN/MFA theo phạm vi triển khai.
- Contract hiện dùng bearer, security E2E kiểm tra không Set-Cookie; chuyển refresh sang HttpOnly cookie cần sửa CSRF/CORS/client/test đồng bộ nếu chọn phương án đó.
- CI có Python pip-audit; bổ sung audit JS, Java và container images, secret scan nguồn/history, pin image digest và lưu kết quả cùng release. Audit JS production hiện 0 advisory không bao phủ các phần khác.

### P1-05. Chốt giới hạn RAG và quy trình reindex

- Python search đang giữ một process RLock quanh `similarity_search_with_score`; retrieval có thể phải chờ lần lượt và ảnh hưởng p95. FAISS cùng local volume phù hợp triển khai một instance; chưa có coordination cho nhiều writer/replica/process.
- Khi embedding model thay đổi, `_get_vectorstore` rebuild từ Papers và cảnh báo tài liệu từng upload phải re-upload. Cần quy trình reindex từ documents hiện có, backup, đổi version index và rollback trước khi nâng model.
- Kiểm chứng isolation/citations và câu chưa có entity, typo, đổi chủ đề, slot-filling, nhiều intent/entity với bộ dữ liệu thật. Hiện có regression tests nhưng chưa có đánh giá AI theo tập câu hỏi nghiệp vụ và tiêu chí pass được chốt.

## Thứ tự đề nghị

1. Sửa gate V6, upload Nginx và public internal API.
2. Chốt domain/TLS/proxy/firewall/secrets và login/AI quota.
3. Hoàn thiện restart/health; tạo release bất biến, chạy Docker staging E2E.
4. Diễn tập backup/restore, rollback và alert delivery.
5. Load/soak, chỉnh DB transaction/pool, giới hạn container; chỉ tăng traffic theo số đo.

## Điều kiện để quyết định mở production

- [ ] Full CI xanh, V1–V6 kiểm chứng trên MySQL staging.
- [ ] Upload thực qua Nginx/CDN, public/private route và phân quyền pass.
- [ ] HTTPS/secrets/proxy-IP/login protection/AI quota được cấu hình và thử.
- [ ] Release/rollback bundle có image digest; smoke sau restart/rollout pass.
- [ ] Backup off-host và restore thật pass theo RPO/RTO đã chọn.
- [ ] Cảnh báo đến đúng người; mức tải hỗ trợ có kết quả đo.

Thông tin cần để chấm sát hơn: hệ điều hành/CPU/RAM/disk, domain/TLS/WAF, số người dùng đồng thời, số tài liệu/vector, quota Gemini, yêu cầu RPO/RTO và có cần chạy nhiều replica hay không.
