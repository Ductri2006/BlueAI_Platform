# Triển khai production an toàn cho widget public

Tài liệu này áp dụng cho stack Docker Compose trong thư mục `BE`. Mục tiêu là
để widget vẫn cho phép khách thật hỏi lại khi chưa hiểu, nhưng chặn flood trước
khi nó tốn connection, Redis hoặc quota AI.

## 1. Chuẩn bị secret và origin

1. Sao chép `.env.production.example` thành `.env` **trên máy deploy**. File
   `.env` đã bị Git bỏ qua; không commit hay gửi vào chat/ticket.
2. Thay mọi placeholder bằng secret mới. `APP_JWT_SECRET` phải là Base64 của ít
   nhất 32 byte; `APP_WORKER_API_KEY` và `APP_WIDGET_CLIENT_KEY_SALT` phải là
   chuỗi ngẫu nhiên từ 32 ký tự trở lên.
3. Điền origin chính xác, không có dấu `/` cuối:

   ```dotenv
   APP_CORS_ALLOWED_ORIGINS=https://app.example.com
   APP_WIDGET_ALLOWED_ORIGINS=https://www.example.com,https://shop.example.com
   ```

   Origin thứ nhất dành cho ứng dụng đăng nhập/quản trị. Origin thứ hai là các
   website được phép nhúng widget. CORS là lớp bảo vệ trình duyệt, không thay
   thế rate limit hay WAF.
4. Trong lần deploy đầu tiên, đặt email và mật khẩu bootstrap admin. Khi admin
   đã tồn tại, đặt **cả hai** `APP_BOOTSTRAP_ADMIN_EMAIL` và
   `APP_BOOTSTRAP_ADMIN_PASSWORD` thành chuỗi rỗng để không còn bootstrap.
5. Tài khoản đăng nhập bằng email và mật khẩu do ADMIN cấp. User quên mật khẩu
   liên hệ admin để được cấp mật khẩu tạm riêng, mặc định 15 phút. Admin xác
   nhận bằng mật khẩu hiện tại; User dùng mật khẩu tạm một lần để mở phiên chỉ
   được đặt mật khẩu mới. Các phiên cũ bị thu hồi. Admin/User có thể tự đổi
   mật khẩu hiện tại; sau khi đổi phải đăng nhập lại. Public password-reset
   qua email và Google/Gmail SSO vẫn đóng. `GOOGLE_API_KEY`
   trong `.env` phục vụ Gemini AI, không phải credential đăng nhập. Không in
   `docker compose config` ra terminal/log vì cấu hình đã render chứa các
   secret như JWT và webhook; dùng `ops/deploy.ps1 -Action Validate` hoặc
   `docker compose ... config --quiet`.

Migration V14 bổ sung trạng thái khôi phục với mặc định không yêu cầu đổi mật
khẩu cho tài khoản đang tồn tại. `app.auth.temporary-password-ttl` mặc định
`15m`, hỗ trợ thời hạn dương tối đa một ngày. API admin đặt mật khẩu hiện nhận
`currentPassword` của admin và trả mật khẩu tạm một lần với `Cache-Control:
no-store`; các tích hợp cũ gửi `newPassword` cần chuyển sang luồng này. Chỉ
xóa mềm tài khoản USER. Hướng dẫn cấu hình Dialog có tại
`/guides/blueai-custom.html` trên frontend.

Khi nâng cấp deployment cũ, xóa `APP_RESET_*`/`SMTP_*` khỏi secret bundle sau
khi bundle Java mới pass staging; thu hồi credential SMTP đã cấp riêng cho
reset nếu không còn consumer khác. Không tái sử dụng route self-service cũ.

Không dùng lại key đã từng lộ trong Git. Việc revoke/rotate tại OpenAI hoặc
provider phải được thực hiện trong dashboard của provider trước khi deploy.

## 2. Reverse proxy, IP thật và TLS

`compose.production.yaml` bỏ public port của Java API, MySQL và RAG. Chỉ
frontend Nginx bind `127.0.0.1:80:80` mặc định. Đặt CDN/WAF và TLS reverse
proxy trước cổng này. Với reverse proxy trên máy khác, đặt
`BLUEAI_ORIGIN_BIND_ADDRESS` thành IP riêng của host và dùng firewall chỉ cho
proxy tin cậy truy cập. Không expose `auth-api` trực tiếp ra Internet, vì khi đó người ngoài có
thể bỏ qua Nginx/WAF và giả `X-Forwarded-For`.

Nginx trong FE thay thế, không nối thêm, header `X-Forwarded-For`. Backend chỉ
đọc header đó khi đồng thời có:

```dotenv
APP_WIDGET_TRUST_FORWARDED_HEADERS=true
APP_WIDGET_TRUSTED_PROXY_CIDRS=<CIDR của frontend Nginx nhìn từ auth-api>
```

Tìm network/CIDR thực trên host trước khi điền:

```powershell
docker network ls
docker network inspect <network-name>
```

Chỉ nhập CIDR của proxy có thể thực sự kết nối tới `auth-api`; không dùng
`0.0.0.0/0`, CIDR mạng client, hay wildcard. Nếu CDN như Cloudflare đứng trước
Nginx, cấu hình `real_ip` ở lớp edge với dải IP chính thức của CDN trước khi
Nginx ghi header. Không tin `CF-Connecting-IP` hoặc `X-Forwarded-For` từ bất
kỳ client nào nếu edge chưa xác thực nguồn gửi.

Java chỉ gửi khóa worker trên yêu cầu completion nếu đích có cùng origin
với `APP_RAG_WORKER_ORIGIN` (mặc định trong Compose là `http://rag-ai:8000`).
Nếu LLM dùng dịch vụ bên ngoài, giữ origin worker trỏ về worker nội bộ.

## 3. Khởi động production

Từ thư mục `BE`:

```powershell
docker compose -f compose.yaml -f compose.production.yaml config --quiet
docker compose -f compose.yaml -f compose.production.yaml up -d --build
docker compose -f compose.yaml -f compose.production.yaml ps
```

Lệnh `config --quiet` phải hoàn thành trước. Nó sẽ dừng ngay nếu thiếu biến bắt
buộc mà không in giá trị secret đã render.

Compose có restart policy `unless-stopped` cho MySQL, Redis, RAG, Java API và
frontend. Java healthcheck gọi `/actuator/health` trên loopback; frontend probe
`/index.html` và đợi Java `service_healthy` lúc khởi động. Cả hai image Alpine
phải có `wget`; xác nhận command này khi build/test image release. Healthcheck
không gọi Gemini và không chứng minh provider/worker hoạt động. Container bị
`unhealthy` cũng không tự restart chỉ vì healthcheck fail: restart policy áp
dụng khi process thoát. Smoke staging và cảnh báo vẫn là gate bắt buộc.

Nginx giữ body limit 1 MB cho API thông thường và 21 MB cho đúng route
`/api/v1/documents`, phù hợp file tối đa 20 MB cộng multipart overhead của Java.
TLS/CDN/WAF phía trước cần cùng giới hạn upload; thử file 2 MB, gần 20 MB và
file vượt giới hạn qua domain thật trước khi mở traffic.

`SPRING_PROFILES_ACTIVE=prod` kích hoạt production gate của backend. Backend
từ chối khởi động nếu còn một trong các lỗi sau:

- Redis widget tắt, salt/key/JWT vẫn là default local hoặc quá ngắn.
- CORS app/widget rỗng, wildcard, localhost hoặc không phải HTTPS.
- Public registration hoặc shared demo knowledge bị bật.
- Bootstrap admin chỉ có email/mật khẩu một phía, hoặc còn credential default.
- Tin header proxy nhưng chưa khai báo CIDR tin cậy.
- Rate-limit hoặc TTL widget không hợp lệ.

Thông báo lỗi chỉ nêu tên biến cần sửa, không in secret.
Image backend trong release phải hỗ trợ luồng mật khẩu tạm tại
`POST /api/v1/admin/users/{userId}/password` và đổi mật khẩu có xác thực tại
`POST /api/v1/auth/password/change` hoặc route browser tương ứng. Public reset
qua email đóng và production startup gate không yêu cầu SMTP/reset.
Triển khai image backend và Compose override đã
kiểm thử như một bundle; không trộn image cũ với Compose mới vì ứng dụng có thể
không khởi động hoặc lộ route ngoài hợp đồng. Trước khi mở traffic, xác nhận
staging security E2E pass: route ADMIN hoạt động và các route reset qua email cũ
trả 403/404. Nếu gate không đạt, dừng rollout và rollback cả bundle.

## 4. Ba lớp chống lạm dụng

| Lớp | Vai trò | Cấu hình trong repo |
|---|---|---|
| Edge Nginx | Chặn flood trước Java/Redis/AI | `FE/nginx.conf`: login 10 request/phút/IP, burst 5; widget 20 session request/phút/IP và 90 message request/phút/IP, cộng giới hạn connection. Preflight `OPTIONS` không bị tính quota. |
| Backend + Redis | Quota chính xác, shared giữa replica; idempotency, duplicate replay và lock session | `WidgetRateLimiter`, `WidgetConversationContextStore`, `WidgetSessionRequestCoordinator` |
| CDN/WAF | Chống bot/DDoS/challenge trước khi tới container | Cấu hình trên Cloudflare/API Gateway/WAF của hạ tầng |

Nginx đặt ngưỡng cao hơn backend có chủ ý: Nginx bảo vệ tài nguyên khỏi burst,
còn backend quyết định quota sản phẩm chính xác: mặc định 10 phiên/phút/client,
30 tin/phút/client, 12 tin/phút/session và 120 lượt/session. Cùng nội dung với
request ID mới được replay tối đa hai lần trong 2 phút; diễn đạt khác như “giải
thích lại” vẫn đi vào hội thoại bình thường.

Tại CDN/WAF, tạo rule theo **method và path**, không dựa vào `Origin`:

- `POST /api/v1/auth/login` đã có Nginx edge limit theo source IP, trả JSON
  `429` và `Retry-After: 60`, không chuyển request bị chặn tới Java. CDN/WAF
  phía trước vẫn cần rule theo IP và nên thêm ASN/device reputation. Baseline
  vận hành: 10 request/5 phút/IP với challenge tăng dần; tune bằng staging.
  Edge trả JSON `429` cùng `Retry-After`, không redirect sang HTML.
- `POST /api/v1/widget/*/sessions`: rate limit/challenge theo IP; ngưỡng WAF
  nên cao hơn 10/phút của backend để edge chỉ xử lý flood.
- `POST /api/v1/runtime/sessions/*/messages`: rate limit/challenge theo IP;
  ngưỡng WAF nên cao hơn 30/phút của backend.
- Chặn request body vượt mức ứng dụng cho phép, bot reputation xấu và traffic
  từ quốc gia không nằm trong phạm vi kinh doanh nếu chính sách công ty cho
  phép.
- Không cache response POST, không cache token widget, và không biến WAF
  challenge thành redirect HTML cho API client.

Release contract yêu cầu đóng public password-reset endpoint. WAF/CDN phải
xuất counter/log cho login `429` và upstream `5xx` vào hệ thống cảnh báo. Alert
backend trong repo chỉ thấy request đã qua edge; không được coi là thay thế
telemetry của WAF.

Các thao tác CDN/WAF cần tài khoản hạ tầng và domain thật nên không thể được
tự động áp vào repo này. Lưu rule cùng ticket/change record để có thể audit và
rollback.

## 5. Quan sát và cảnh báo

Backend phát metric Micrometer:

```text
blueai.widget.protection.events{event=<event>,scope=<scope>}
```

Tag chỉ là enum cố định; không có IP, session ID, request ID, token hay nội
dung chat. Các event gồm `rate_limited`, `redis_fallback`,
`idempotency_replay`, `idempotency_conflict`, `duplicate_replay`,
`duplicate_blocked`, `session_turn_limit`, `request_in_progress`.

Actuator expose `health`, `info`, `metrics` và Prometheus exposition. `health`
chỉ được probe nội bộ cho orchestration; public FE/WAF trả 404. Auth agent chỉ permit riêng
`/actuator/prometheus` trên monitoring network nội bộ. `/actuator/metrics` và
các actuator data endpoint còn lại vẫn yêu cầu `ADMIN`, frontend Nginx trả 404
cho `/actuator/**`. Đọc metric chi tiết qua private network/host quản trị đã
xác thực, ví dụ endpoint:

```text
/actuator/metrics/blueai.widget.protection.events
```

Tạo alert tối thiểu:

- `redis_fallback > 0`: shared protection đã mất; kiểm tra Redis ngay.
- `rate_limited` hoặc `duplicate_blocked` tăng đột biến: kiểm tra bot/WAF,
  không vội siết quota nếu đó là campaign hợp lệ.
- Login `401`/`429` tăng: kiểm tra credential stuffing và WAF rule; phân biệt
  traffic bị chặn ở edge với traffic bị backend từ chối.
- Bất kỳ burst `5xx` trên login: page on-call; kiểm tra bcrypt,
  database, dependency và rollback nếu có tương quan với rollout.
- `request_in_progress` tăng: client retry quá sớm hoặc latency AI tăng.
- `/actuator/health` không `UP`, RAG `/ready` lỗi, hoặc provider trả 429/5xx.

## 6. Staging acceptance trước khi mở production

Trên một domain staging HTTPS khác production, dùng origin allowlist staging
và kiểm tra theo thứ tự:

1. Browser nhúng widget từ origin được phép gửi preflight và POST thành công.
2. Origin không có trong allowlist nhận CORS failure/403; trang quản trị vẫn
   dùng CORS riêng.
3. Gửi lại cùng `requestId` và cùng nội dung: response cũ, không tạo turn AI.
4. Cùng nội dung nhưng request ID mới: replay trong giới hạn; vượt giới hạn
   nhận `429` cùng `Retry-After`.
5. “Giải thích lại” hoặc câu hỏi diễn đạt khác: không bị coi là duplicate.
6. Vượt quota session/client/turn: nhận đúng `429`, sau cửa sổ TTL gửi lại
   được.
7. Dùng nhiều request đồng thời cho cùng session: chỉ một turn mới được ghi.

8. Gửi `X-Forwarded-For` giả từ ngoài proxy: không đổi quota client. Test cả
   source trong trusted CIDR và source ngoài CIDR.
9. Dừng Redis có chủ ý ở staging: request widget mới phải nhận `503`
   `WIDGET_PROTECTION_UNAVAILABLE`, không chuyển quota sang bộ đếm từng instance
   và không gọi provider. Log/metric `redis_fallback` (tên metric giữ tương thích)
   ghi nhận sự cố; requestId đã hoàn tất có thể replay từ database mà không gọi AI.
   Khôi phục Redis rồi xác nhận quota dùng chung hoạt động lại. Chế độ local chỉ
   dùng bộ đếm RAM khi Redis được tắt rõ ràng; tối đa 10.000 key còn sống.
10. Xác nhận auth-api/MySQL/RAG không có host port trong `docker compose ps`;
    `/actuator/metrics` không đi qua frontend public.

Không đưa login flood vào E2E tự động: test đó tự tạo đúng tải bcrypt mà rule
đang cần ngăn. Edge owner verify rule bằng WAF dry-run, vendor counter hoặc
một canary staging được phê duyệt; đối chiếu `429` và `Retry-After` từ edge với
alert telemetry. Backend security E2E chỉ kiểm tra auth/data-isolation.

Chỉ merge/deploy production sau khi lưu kết quả staging, snapshot cấu hình WAF
và kế hoạch rollback image/config trước đó.

## 7. Deploy, rollback và secret rotation có kiểm soát

Các thao tác dưới đây là script executable trong `ops/`; chạy bằng PowerShell
7.4+ từ thư mục `BE`. Script không commit, in secret, hoặc ghi đè `.env` hiện
hành.

```powershell
pwsh -File .\ops\deploy.ps1 -Action Validate
pwsh -File .\ops\deploy.ps1 -Action Deploy
pwsh -File .\ops\deploy.ps1 -Action Rollback `
  -RollbackComposeDir D:\releases\blueai-previous `
  -IUnderstandRollback
```

Deploy và rollback chỉ báo PASS sau post-start acceptance: actuator nội bộ
`auth-api` trả HTTP 200/status UP; RAG `/readiness` trả HTTP 200/status ready;
frontend phục vụ HTML, proxy `/api/v1/auth/me` trả 401/JSON
`AUTHENTICATION_REQUIRED`, và chặn actuator/private worker bằng 403/404.
Các probe không có credential, chạy trong Compose network, không gọi paid
provider và không cần mở port API/RAG. `/ready` vẫn là startup gate của Compose
(kiểm tra cấu hình key, không chờ worker poll Java); `/health` vẫn là liveness.
`/readiness` chỉ kiểm tra sau startup để tránh vòng chờ auth-api/rag-ai; endpoint
kiểm tra worker running, recent successful poll và sustained polling failures.
Timeout mặc định 180 giây (`-AcceptanceTimeoutSeconds`); lỗi acceptance làm
script fail, không tự rollback. Giữ external ingress/traffic promotion đóng,
điều tra hoặc chạy approved rollback; vẫn phải hoàn thành security/staging gates.

Rollback dùng bundle compose/.env đã duyệt trước đó, không dùng source tree
đang có thay đổi chưa review. Ba service ứng dụng (`auth-api`, `rag-ai`,
`frontend`) trong bundle bắt buộc dùng image digest `sha256` bất biến và không
có `build:`; script fail closed trước khi khởi động nếu thiếu điều kiện này.
Giữ image cũ trên registry hoặc cache của host trong suốt rollback window.
`pwsh -File .\ops\test-deploy-rollback.ps1` kiểm tra validator và cả deploy/
rollback acceptance offline bằng Docker giả; test không kết nối Docker hay
thay đổi stack. API UP không thể che RAG 503, frontend 502/SPA fallback,
private ingress mở hoặc curl/native process lỗi.
Compose/image rollback does not reverse Flyway migrations or restore database
contents. For an incompatible schema/data change, prefer a reviewed forward
migration; otherwise use the maintenance-window restore procedure below with a
verified backup and an explicit data-loss approval.

### Backup và restore

Backup production bắt buộc dùng `age` recipient để artifact lưu lâu dài được
mã hóa. Dump/archive vẫn có plaintext trong thư mục staging tạm của host;
chỉ chạy trên recovery host được bảo vệ (encrypted disk, restricted access),
và kiểm tra cleanup sau sự cố/process crash. Script tạm dừng `auth-api` và `rag-ai`, giữ MySQL chạy để
`mysqldump --single-transaction` có transaction nhất quán, rồi archive cả ba
nguồn tại cùng snapshot: database `blueai_auth`, uploaded documents
(`/app/storage`) và FAISS index (`/app/.rag-index`). Redis không backup vì chỉ
chứa state ngắn hạn; sau restore các session widget phải được tạo lại.

```powershell
pwsh -File .\ops\backup.ps1 -AgeRecipient age1...
pwsh -File .\ops\restore.ps1 -BackupDir .\ops\backups\20260924T120000Z `
  -AgeIdentity C:\secure\blueai-backup-key.txt -DryRun
pwsh -File .\ops\restore.ps1 -BackupDir .\ops\backups\20260924T120000Z `
  -AgeIdentity C:\secure\blueai-backup-key.txt -TestRestore
pwsh -File .\ops\restore.ps1 -BackupDir .\ops\backups\20260924T120000Z `
  -AgeIdentity C:\secure\blueai-backup-key.txt -Apply -IUnderstandDataLoss -KeepQuiesced
```

`-DryRun` kiểm tra checksum, giải mã, cấu trúc SQL và toàn bộ tar/gzip stream
(không dừng ở member đầu tiên); backup cũng validate archive trước khi lưu
manifest thành công. Không có production mutation trong archive validation.
`-TestRestore` import
SQL vào MySQL disposable và kiểm tra table, không đụng production. Live restore
dừng frontend/auth-api/rag-ai và thực hiện import dưới maintenance window.
PowerShell **7.4+** bắt buộc trước native stdout redirection để giữ nguyên byte
của tar.gz (7.0–7.3 không được chấp nhận). Bind archive dùng Compose
`--volume <absolute-source>:<container-target>:ro` như một argument, hỗ trợ
đường dẫn có spaces; không dùng `compose run --mount`.
SQL import, document extraction và FAISS extraction phải thành công, sau đó
SQL table validation phải pass trước khi flag restore-success được đặt và
service có thể start. Bất kỳ partial failure nào giữ API/RAG/frontend stopped,
cảnh báo manual recovery; không chạy `start` trong `finally`. Không chỉ restart
service để xử lý dữ liệu đã thay một phần: giữ traffic đóng, kiểm tra lỗi và
phục hồi lại cả ba nguồn từ snapshot đã verify. SQL scratch trong container và
host staging được cleanup khi script thoát bình thường hoặc throw; process
kill/power loss vẫn cần operator kiểm tra. `-KeepQuiesced` giữ service stopped
cả khi restore thành công (khuyến nghị); nếu bỏ flag, script chỉ start sau
validation, còn external traffic phải đóng tới khi readiness/ingress/security
smoke đạt. Plaintext
chỉ được phép cho recovery test với `-AllowPlaintextForRecoveryTesting` và
artifact plaintext không mang đuôi `.age`.

Các script này **không tự chạy và không tạo lịch backup**. Platform/ops phải
đặt scheduler ngoài repo, chọn và ghi rõ policy trước production. Baseline vận
hành được khuyến nghị: chạy full backup mỗi đêm trong maintenance window, đẩy
ngay các artifact `.age` và manifest/checksum sang object storage off-host khác
account/region có retention lock, giữ 35 bản daily + 12 bản weekly; age private
key nằm trong KMS/secret store riêng, không ở cùng bucket. Nếu policy chọn lịch
khác, RPO/RTO phải được ghi trên service record và kiểm tra lại khi traffic thay
đổi. Script hiện không cung cấp binlog/PITR hay continuous replication: lịch
full backup 24 giờ tương ứng RPO tối đa xấp xỉ 24 giờ (chưa tính lỗi upload).

Ownership tối thiểu: platform/on-call sở hữu scheduler, off-host copy và alert
backup-failure; service owner xác nhận manifest gồm MySQL, uploaded files và
FAISS cùng snapshot; security/key custodian sở hữu age/KMS key; incident lead
quyết định restore. Chạy `-TestRestore` ít nhất hàng tháng trên bản backup ngẫu
nhiên; diễn tập full restore cô lập ít nhất hàng quý, đo thời gian từ quiesce
đến smoke test và ghi kết quả. Mục tiêu tham khảo là RPO ≤24 giờ/RTO ≤4 giờ;
muốn RPO thấp hơn phải bổ sung DB PITR/storage replication ngoài các script này.

Smoke test offline:

```powershell
pwsh -File .\ops\test-backup-restore.ps1
# Opt-in do main/operator chạy, không trỏ tới production:
pwsh -File .\ops\test-backup-restore.ps1 -RunDockerRestore
```

Default offline test mock toàn bộ Docker, thực sự gọi hai archive restore
command qua argument parser giả (read-only bind có spaces), inject lỗi stop/
SQL/documents/FAISS/final validation và xác nhận không resume traffic.
Native-process stdout roundtrip độc lập với Docker mock kiểm tra đủ 256 giá trị
byte qua redirection (không chỉ kiểm tra version hoặc hash fixture tạo bằng tar).
`-RunDockerRestore` tạo Compose project/DB/document/FAISS volumes có tên GUID
riêng, explicit fake env file, không host ports, không production config/key
hay provider call. Test apply restore vào fixture SQL row/document/index,
kiểm tra nội dung và stale/hidden files đã được thay, rồi `down --volumes`
chỉ project fixture. Nếu cleanup lỗi, test fail và giữ fixture/config để manual
cleanup. Mode này dùng `mysql:8.4` và `alpine:3.21`, không build application.

### Rotation

```powershell
pwsh -File .\ops\rotate-secrets.ps1 -Mode Plan
pwsh -File .\ops\rotate-secrets.ps1 -Mode Generate `
  -OutputEnvFile C:\secure\blueai-next.env -IUnderstandRotation
```

Runtime hiện tại có một `APP_JWT_SECRET` HS256 active, chưa có dual-key
verifier: rotation làm access token đang hoạt động invalid ngay lập tức. Chọn
maintenance window và thông báo đăng nhập lại. `APP_WORKER_API_KEY` cũng là một
shared key duy nhất giữa auth-api/rag-ai, chưa có dual-key env: dừng hai writer,
đổi key trong cùng bundle rồi start đồng thời. Revoke provider key cũ sau khi
staging smoke test key mới pass. Giữ sealed bundle cũ cho rollback window và
không đưa vào Git/log. Script từ chối overwrite `.env.next` nếu thiếu `-Force`.

## 8. Monitoring, log aggregation và alerting

Production compose thêm Prometheus, Alertmanager, Loki và Grafana Alloy; các service
này không bind host port. Vì Alloy đọc Docker JSON logs qua host Docker
socket, relabel rule đầu tiên chỉ giữ container có Compose project `blueai-auth`
và loại các project khác trước khi ship log. Loki gắn nhãn project/service/level/logger;
ECS `log.level` và `log.logger` được trích xuất, còn `trace.id` giữ trong JSON body
để tránh label cardinality cao. Alloy hỗ trợ cả ECS nested object và field tên
literal `log.level`; pipeline che Bearer/token/password trước khi gửi. Loki cấu
hình retention 7 ngày trong volume; phải xác minh compactor/expiry trên staging,
không coi config là bằng chứng đã quan sát xóa dữ liệu sau 7 ngày. Truy cập công cụ
quan sát qua private network hoặc SSH tunnel.

Kiểm tra Alloy static và ingestion thật trên fixture (không gửi tới Loki thật):

```powershell
node .\ops\test-alloy-config.mjs
node .\ops\test-alloy-ingestion.mjs
```

Alloy dùng volume `blueai-alloy-data` riêng, không tái sử dụng positions Promtail.
File Promtail/test cũ chỉ là tham chiếu lịch sử, không được Production Compose
sử dụng. Chuyển collector có thể tạo một lần replay log; review retention, disk,
rate và dashboard trước rollout. Bind Docker socket `:ro` không chặn Docker API
ghi: cần review quyền host/socket proxy trên staging. Không mở cổng Alloy ra public.

Ops chọn management scrape private thay vì giữ JWT tĩnh: auth agent cần permit
riêng `/actuator/prometheus` trên Docker monitoring network; `/actuator/metrics`
vẫn `ADMIN`, và không có auth-api host port. FE/Nginx tiếp tục chặn
`/actuator/**`, nên public request phải 404. Prometheus scrape nội bộ qua
`auth-api:8080` trên network `internal: true`, không có `bearer_token_file` và
không có admin credential trong monitoring.

Contract bắt buộc cho auth agent:

```text
internal Docker network -> GET /actuator/prometheus -> 200 text/plain
public FE/WAF           -> GET /actuator/prometheus -> 404
authenticated ADMIN     -> /actuator/metrics        -> vẫn theo policy ADMIN
```

Kiểm tra contract sau deploy (script probe cả health nội bộ 200/UP và
Prometheus nội bộ 200; sẽ fail nếu Java chưa permit endpoint nội bộ hoặc
public boundary không kín):

```powershell
pwsh -File .\ops\check-monitoring.ps1 `
  -ExternalBaseUrl https://app.staging.example.com
```

Alert rules ở `ops/prometheus/alerts.yml`: scrape down, login 401/429 burst,
login 5xx >5%, tổng 5xx >5%, p95 >2s, Redis fallback và burst abuse.
WAF phải cảnh báo riêng `429` chặn tại edge vì backend không nhận được các
request đó. Rule p95 yêu cầu application/migration agent
bật HTTP server request histogram để có
`http_server_requests_seconds_bucket`; trước khi metric này xuất hiện, p95
alert chưa được coi là đã verify. Alertmanager gửi firing/resolved tới
`ALERTMANAGER_WEBHOOK_URL`; không đặt webhook public.

Kiểm thử delivery mà không gọi webhook thật:

```powershell
pwsh -File .\ops\test-alertmanager.ps1
```

Test tạo Docker network riêng, chạy receiver stub, inject một alert qua
Alertmanager API và assert stub nhận payload; production Alertmanager có thêm
`default` network chỉ để egress, không publish port.

```powershell
docker compose -f compose.yaml -f compose.production.yaml ps
docker compose -f compose.yaml -f compose.production.yaml logs --tail=100 prometheus alertmanager loki alloy
```

## 9. Staging load và security E2E

Load chỉ chạy trên HTTPS staging. Script tự từ chối localhost/HTTP và cần
confirmation token:

```powershell
$env:STAGING_BASE_URL = 'https://api.staging.example.com'
$env:STAGING_CONFIRM = 'I_UNDERSTAND_STAGING_LOAD'
$env:AUTH_EMAIL = 'load-user@example.com'; $env:AUTH_PASSWORD = '...'
$env:WIDGET_PUBLIC_KEY = 'published-staging-public-key'
$env:WIDGET_ORIGIN = 'https://widget.staging.example.com'
node .\load-tests\test-metrics.mjs # Offline denominator/error-rate regression test
node .\load-tests\staging-scenarios.mjs

# Tuỳ chọn: gọi provider/RAG thật, có cost/quota staging.
$env:RUN_CONVERSATION_ASK = 'true'
node .\load-tests\staging-scenarios.mjs
```

Mỗi scenario kiểm tra login/me, JSON content-type, không có `Set-Cookie`,
token fields, conversation create/list, widget CORS/session/token correlation
và status. Fail nếu p95 vượt `MAX_P95_MS` (mặc định 3s) hoặc error rate vượt
`MAX_ERROR_RATE` (mặc định 1%); 429 không bị che bằng retry.

Security E2E dùng hai user và một ADMIN:

```powershell
$env:STAGING_CONFIRM = 'I_UNDERSTAND_STAGING_SECURITY_E2E'
$env:USER_A_EMAIL = 'user-a@staging.example.com'; $env:USER_A_PASSWORD = '...'
$env:USER_B_EMAIL = 'user-b@staging.example.com'; $env:USER_B_PASSWORD = '...'
$env:ADMIN_EMAIL = 'ops-admin@staging.example.com'; $env:ADMIN_PASSWORD = '...'
$env:ALLOWED_ORIGIN = 'https://widget.staging.example.com'
$env:DENIED_ORIGIN = 'https://evil.example.com'
$env:WIDGET_PUBLIC_KEY = 'published-staging-public-key'
node .\ops\security-e2e.mjs
```

Script kiểm tra anonymous/invalid bearer 401, USER admin/operational metrics
403, ADMIN operational metrics 200, public actuator health/prometheus 404,
allowed/denied CORS headers, token/header assertions, và data isolation: user B không thấy, không
GET được và không DELETE được conversation của user A. Không in token hay
secret response ra output. Nó cũng kiểm tra paging/filter cho admin users và
conversation search, soft-delete conversation, và yêu cầu hai endpoint public
password-reset request/confirm trả 403/404. Đổi mật khẩu có xác thực được kiểm
tra trong luồng account mutation khi bật cờ kiểm tra tương ứng.
Public worker route phải trả 403/404 tại edge; script không gửi
`WORKER_API_KEY` qua staging ingress.

Worker auth/retry chạy riêng từ private Docker network, không qua frontend:

```powershell
$env:APP_WORKER_API_KEY = 'secret-from-staging-secret-store'
pwsh -File .\ops\worker-private-e2e.ps1
# Tuỳ chọn: WORKER_DOCUMENT_ID là document đang PROCESSING/được claim để kiểm
# tra attempt header/body mismatch -> 409 mà không ghi thay đổi.
```

`worker-private-e2e.ps1` kiểm tra wrong key 401, đúng key nhưng `limit=0`
400 trước khi claim, và stale attempt 409 nếu có `WORKER_DOCUMENT_ID`.
Key chỉ truyền từ process environment bằng `docker run --env WORKER_API_KEY`
(tên variable, không literal key trong command line); không ghi temp `.env`
hay file credential. `finally` khôi phục process variable cũ cả khi probe lỗi.
Không bật shell tracing hoặc log/inspect environment của probe container.
`pwsh -File .\ops\test-worker-private-e2e.ps1` kiểm tra offline với key giả:
success/network failure/probe failure đều không tạo file secret và không để
key tạm trong process environment; không cần Docker hay real key.
`FE/nginx.conf` trả 404 cho `/api/v1/internal` và `/api/v1/internal/**` trước
generic API proxy, cũng như `/actuator` và `/actuator/**`. Nếu public probe vẫn
trả 401 thay vì 403/404, kiểm tra image FE, CDN/WAF và đường đi trực tiếp tới
API; dừng rollout và không gửi worker secret qua public ingress để thử.

CI chạy `node BE/ops/test-nginx-ingress.mjs` từ repo root để kiểm tra Nginx
thật với backend giả trong Docker network riêng: private boundary, upload
2/20 MB, body quá lớn, IP giả, login 429/JSON/Retry-After và OPTIONS miễn quota.
Script tự dọn container/network test; không dùng credential hoặc gọi Gemini.
`node BE/ops/test-production-compose.mjs` kiểm tra Compose bằng credential giả
và không in cấu hình chứa secret. Job MySQL 8.4 chạy
`MySqlMigrationIntegrationTest` với database disposable `blueai_migration_test`,
kiểm tra schema V5, nâng V6 giữ dữ liệu cũ rồi migrate/validate tới latest V14,
ghi usage/index và migrate lặp không làm thay đổi schema. Engine-upgrade test
riêng kiểm tra history/data từ Flyway 11.7.2 tới 11.20.3 trên MySQL 8.4; kết quả
CI không thay thế maintenance/recovery prerequisites ở section 11.
Test không bật nếu thiếu biến
`MYSQL_MIGRATION_TEST_URL`; không trỏ test này tới database ứng dụng.

ADMIN cấp user qua `POST /api/v1/admin/users` và đặt lại mật khẩu qua
`POST /api/v1/admin/users/{userId}/password` với body
`{"currentPassword":"..."}` để xác nhận mật khẩu admin. Response 200 trả
`temporaryPassword` và `expiresAt`, kèm `Cache-Control: no-store`. Kiểm tra live
bằng account throwaway trên staging.

```powershell
$env:RUN_ACCOUNT_MUTATION_E2E = 'true'
node .\ops\security-e2e.mjs
```

Flow xác nhận USER bị 403 khi gọi route ADMIN, ADMIN nhận mật khẩu tạm một lần,
access JWT và refresh token cũ bị từ chối, mật khẩu cũ không đăng nhập được.
Phiên dùng mật khẩu tạm bị chặn truy cập nghiệp vụ, phải đặt mật khẩu riêng qua
route đổi mật khẩu rồi đăng nhập lại để gọi `/auth/me`. `finally`
soft-delete user throwaway, kiểm tra `includeDeleted=false/true` và login sau
xóa bị từ chối. Không chạy cờ này trên production hoặc user thật.

Khi user thật quên mật khẩu, helpdesk xác minh danh tính theo quy trình nội bộ;
ADMIN cấp mật khẩu tạm bằng route trên qua kênh quản trị HTTPS, truyền riêng
cho user qua kênh bảo mật đã duyệt và kiểm tra audit event `ADMIN_PASSWORD_SET`.
Không đưa mật khẩu vào URL, shell history, ticket hay log. Thao tác này làm
access JWT và refresh token cũ mất hiệu lực. User dùng mật khẩu tạm một lần,
đặt mật khẩu riêng trong thời hạn khôi phục rồi đăng nhập lại. Admin/User có
thể tự đổi mật khẩu trong giao diện khi biết mật khẩu hiện tại.

Alertmanager reads `ALERTMANAGER_WEBHOOK_URL` from the deployment `.env` as an
environment-backed Compose secret, mounted read-only at
`/run/secrets/alertmanager_webhook_url`. Its config uses `url_file`, so the
webhook value is not embedded in YAML or the container environment. Run
`pwsh -File .\ops\test-alertmanager.ps1` to validate the pinned Alertmanager and
Prometheus configs, verify Compose resolves an env-file secret, and test delivery
against a local receiver stub without contacting the production webhook.

## 10. Data retention

Document tombstones are purged after `APP_DOCUMENT_RETENTION` (30 days by
default) when the RAG worker calls the internal purge endpoint. Failed vector
or storage purges stay retryable; the API rotates attempted tombstones behind
untouched records within bounded batches. Conversation cleanup stays disabled
until `APP_RETENTION_ENABLED=true`; its age is controlled by
`APP_RETENTION_CONVERSATION` (365 days by default). Auth token/audit cleanup has
its own opt-in `APP_AUTH_RETENTION_SCHEDULED_ENABLED` and
`APP_AUTH_RETENTION_RETENTION_DAYS` settings. Soft-deleted user rows are audit
tombstones and are retained indefinitely; current retention jobs only purge
expired auth tokens and audit records. They do not hard-delete users or cascade
delete user-owned domain records.

## 11. Flyway schema migration cho database hiện hữu

Migration chain hiện tại là **V1–V9**, latest schema version **V9**. V9 thêm
`context_reuse_policy` với default `NONE`, không tự bật dùng lịch sử cho draft cũ. Trong
`BE/pom.xml`, `flyway.version` pin **11.20.3**; giữ `flyway-core` và
`flyway-mysql` cùng version này, không nâng riêng một module. Runner/CLI cho
baseline/migrate phải dùng engine 11.20.3 và MySQL support module tương ứng;
kiểm tra resolved dependencies và kết quả validate trước rollout.

Database mới để ứng dụng chạy Flyway bình thường. Với database đã có schema từ
trước khi Flyway được bật, **không** bật `baseline-on-migrate` và không khởi động
ứng dụng lần đầu trực tiếp trên database đó: `V1__baseline_entity_schema.sql`
tạo các bảng ban đầu nên Flyway sẽ dừng khi gặp bảng đã tồn tại. Quy trình nâng
cấp yêu cầu maintenance window:

1. Đóng traffic/task admission, dừng và drain mọi writer/worker cũ (kể cả
   instance ngoài Compose stack và task đang chạy), rồi tạo và kiểm tra backup
   có thể restore. Phải hoàn tất trước V7: migration backfill `expires_at` rồi
   đặt `NOT NULL`; writer cũ không ghi field này không được chạy đồng thời.
   Sau V7 **không rollback về image chứa writer cũ**. Chỉ dùng image đã xác
   nhận tương thích V7/V8; nếu cần schema/data cũ, giữ maintenance và dùng
   backup/restore đã duyệt, không chỉ rollback image.
2. So sánh database với từng schema legacy đã được kiểm chứng (bảng, cột,
   kiểu, nullability, index/constraint). Chỉ dùng baseline ở **version cao nhất
   mà schema thực sự khớp**: dùng `baselineVersion=3` khi database khớp đầy đủ
   V1–V3; chỉ dùng `baselineVersion=1` khi database chỉ khớp V1. Không được
   đoán version, baseline mù, hoặc sửa tay `flyway_schema_history`; schema lệch
   phải được xử lý bằng migration forward có review.
3. Dùng Flyway CLI/runner 11.20.3 với module MySQL cùng version và cùng kết nối/
   database đã kiểm tra để chạy một lần. Ví dụ, chọn đúng một
   lệnh sau theo kết quả preflight:

   ```text
   # Legacy schema khớp V1 only:
   flyway -url=<JDBC URL> -user=<migration user> -baselineVersion=1 -baselineDescription="Verified V1 legacy baseline" baseline

   # Legacy schema khớp đầy đủ V1–V3:
   flyway -url=<JDBC URL> -user=<migration user> -baselineVersion=3 -baselineDescription="Verified V3 legacy baseline" baseline
   ```

   Cấp mật khẩu qua secret/env của runner, không đặt trong command line, shell
   history hay log. Lệnh `baseline` chỉ ghi lịch sử, không chạy migration ở
   version đã baseline.
4. Kiểm tra `flyway_schema_history` ghi đúng baseline version và trạng thái hợp
   lệ; sau đó triển khai ứng dụng. Với baseline V1, Flyway phải validate/chạy
   V2–V9 theo thứ tự; với baseline V3, chạy V4–V9. Database mới chạy V1–V9.
   Xác nhận history thành công tới V9, checksum/schema/index đúng và Hibernate
   `ddl-auto=validate` pass trước readiness/security acceptance và mở traffic.
5. Nếu preflight hoặc validate thất bại, dừng rollout và restore backup hoặc
   chuẩn bị migration forward đã review. Không xóa schema history, không chạy
   `clean`, không bật baseline tự động và **không chạy `repair` mù** để vượt
   lỗi, bỏ failed migration hoặc chấp nhận checksum mới.

### MySQL partial-migration recovery và UTC session prerequisites

MySQL DDL như `ALTER TABLE`/`CREATE INDEX` gây implicit commit; toàn bộ Flyway
migration nhiều statement không có transactional rollback. Atomic DDL của một
statement không bảo đảm rollback cả V7/V8: nếu statement sau lỗi, statement
trước có thể đã áp dụng. Vì vậy backup đã verify và rehearsal **partial
migration recovery** trên bản restore/staging là gate bắt buộc. Rehearsal phải
bao gồm failure giữa các statement, đối chiếu schema/data thật với
`flyway_schema_history`, rồi phục hồi snapshot hoặc thực hiện remediation
forward có review trước khi validate/migrate tiếp. Không retry mù khi còn DDL
áp dụng một phần; `repair` chỉ chỉnh metadata, không undo DDL hay khôi phục
data. Mọi history reconciliation phải có bằng chứng schema/checksum đã khớp,
approval và rehearsal riêng. Xem [MySQL implicit commits](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html).

Yêu cầu mọi JDBC session của ứng dụng và migration runner ở UTC. Với
Connector/J, cấu hình đồng thời `connectionTimeZone=UTC` và
`forceConnectionTimeZoneToSession=true` trong URL/properties; giữ các option
TLS/production đã duyệt. `serverTimezone=UTC` một mình chỉ là alias cấu hình
connection timezone, **không ép MySQL session timezone**. Kiểm tra
`SELECT @@session.time_zone` trên connection thật từ từng pool/runner, xác
nhận UTC (`+00:00` hoặc UTC được server nhận diện), và kiểm tra expiry/recovery
timestamp trên staging. Không coi JVM timezone hoặc `@@global.time_zone` là
bằng chứng session UTC. Xem [Connector/J datetime properties](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html).

Đây là **staging/production prerequisites**, không phải xác nhận đã chạy
migration, ép UTC session hay recovery rehearsal trên production. Trước
promotion phải lưu kết quả rehearsal fresh V1–V14, legacy baseline V1 hoặc V3
lên V14 theo schema fingerprint, data preservation, migrate/validate lặp lại,
writer drain và partial-migration recovery. `FlywayMigrationIntegrationTest`,
MySQL migration/engine-upgrade tests và CI verify là gate tự động, không thay
thế preflight trên legacy schema thật hay production maintenance approval.
Chỉ chọn baseline sau khi schema diff thực tế khớp mốc tương ứng. Baseline cần
phản ánh schema đang tồn tại trước khi áp dụng các migration còn thiếu.
