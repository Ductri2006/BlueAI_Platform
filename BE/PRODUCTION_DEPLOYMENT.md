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
5. Cấu hình SMTP và password reset trong `.env`: production yêu cầu
   `APP_RESET_ENABLED=true`, `APP_RESET_FRONTEND_URL` HTTPS,
   `APP_RESET_MAIL_FROM`, `SMTP_HOST` và thông tin xác thực relay phù hợp.
   Không in `docker compose config` ra terminal/log vì cấu hình đã render chứa
   `SMTP_PASSWORD` và webhook; dùng `ops/deploy.ps1 -Action Validate` hoặc
   `docker compose ... config --quiet`.

Không dùng lại key đã từng lộ trong Git. Việc revoke/rotate tại OpenAI hoặc
provider phải được thực hiện trong dashboard của provider trước khi deploy.

## 2. Reverse proxy, IP thật và TLS

`compose.production.yaml` bỏ public port của Java API, MySQL và RAG. Chỉ
frontend Nginx còn bind `80:80`; đặt CDN/WAF hoặc TLS reverse proxy trước cổng
này. Không expose `auth-api` trực tiếp ra Internet, vì khi đó người ngoài có
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

## 3. Khởi động production

Từ thư mục `BE`:

```powershell
docker compose -f compose.yaml -f compose.production.yaml config --quiet
docker compose -f compose.yaml -f compose.production.yaml up -d --build
docker compose -f compose.yaml -f compose.production.yaml ps
```

Lệnh `config --quiet` phải hoàn thành trước. Nó sẽ dừng ngay nếu thiếu biến bắt
buộc mà không in giá trị secret đã render.
`SPRING_PROFILES_ACTIVE=prod` kích hoạt production gate của backend. Backend
từ chối khởi động nếu còn một trong các lỗi sau:

- Redis widget tắt, salt/key/JWT vẫn là default local hoặc quá ngắn.
- CORS app/widget rỗng, wildcard, localhost hoặc không phải HTTPS.
- Public registration hoặc shared demo knowledge bị bật.
- Bootstrap admin chỉ có email/mật khẩu một phía, hoặc còn credential default.
- Tin header proxy nhưng chưa khai báo CIDR tin cậy.
- Rate-limit hoặc TTL widget không hợp lệ.

Thông báo lỗi chỉ nêu tên biến cần sửa, không in secret.

## 4. Ba lớp chống lạm dụng

| Lớp | Vai trò | Cấu hình trong repo |
|---|---|---|
| Edge Nginx | Chặn flood trước Java/Redis/AI | `FE/nginx.conf`: 20 session request/phút/IP và 90 message request/phút/IP, cộng giới hạn connection. Preflight `OPTIONS` không bị tính quota. |
| Backend + Redis | Quota chính xác, shared giữa replica; idempotency, duplicate replay và lock session | `WidgetRateLimiter`, `WidgetConversationContextStore`, `WidgetSessionRequestCoordinator` |
| CDN/WAF | Chống bot/DDoS/challenge trước khi tới container | Cấu hình trên Cloudflare/API Gateway/WAF của hạ tầng |

Nginx đặt ngưỡng cao hơn backend có chủ ý: Nginx bảo vệ tài nguyên khỏi burst,
còn backend quyết định quota sản phẩm chính xác: mặc định 10 phiên/phút/client,
30 tin/phút/client, 12 tin/phút/session và 120 lượt/session. Cùng nội dung với
request ID mới được replay tối đa hai lần trong 2 phút; diễn đạt khác như “giải
thích lại” vẫn đi vào hội thoại bình thường.

Tại CDN/WAF, tạo rule theo **method và path**, không dựa vào `Origin`:

- **Bắt buộc:** `POST /api/v1/auth/password-reset/request` phải có edge
  rate-limit/WAF trước FE/API, tối thiểu theo source IP và nên thêm ASN/device
  reputation. Baseline vận hành: tối đa 5 request/10 phút/IP, burst tối đa 2;
  tune bằng staging và không dùng email làm key duy nhất. `POST
  /api/v1/auth/login` cũng bắt buộc edge limit/challenge, baseline 10
  request/5 phút/IP với lockout/challenge tăng dần. Cả hai route phải trả JSON
  `429` cùng `Retry-After`, không redirect sang HTML.
- `POST /api/v1/widget/*/sessions`: rate limit/challenge theo IP; ngưỡng WAF
  nên cao hơn 10/phút của backend để edge chỉ xử lý flood.
- `POST /api/v1/runtime/sessions/*/messages`: rate limit/challenge theo IP;
  ngưỡng WAF nên cao hơn 30/phút của backend.
- Chặn request body vượt mức ứng dụng cho phép, bot reputation xấu và traffic
  từ quốc gia không nằm trong phạm vi kinh doanh nếu chính sách công ty cho
  phép.
- Không cache response POST, không cache token widget, và không biến WAF
  challenge thành redirect HTML cho API client.

Throttle password-reset hiện tại là per-email trong memory: random-email flood
vẫn có thể đẩy request vào bcrypt và state không được chia sẻ giữa replicas.
Đây không phải lớp chống abuse đủ dùng; edge rule trên là production gate bắt
buộc. WAF/CDN phải xuất counter/log cho `429` và upstream `5xx` theo hai route
auth vào hệ thống cảnh báo. Alert backend trong repo chỉ thấy request đã qua
edge; không được coi là thay thế telemetry của WAF.

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
- Tỷ lệ `429` trên login/password-reset tăng: kiểm tra credential stuffing,
  random-email flood và WAF rule; phân biệt traffic bị chặn ở edge với traffic
  bị backend từ chối.
- Bất kỳ burst `5xx` trên login/password-reset: page on-call; kiểm tra bcrypt,
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

Không đưa password-reset/login flood vào E2E tự động: test đó tự tạo đúng tải
bcrypt mà rule đang cần ngăn. Edge owner phải verify rule bằng WAF dry-run,
vendor counter hoặc một canary staging được phê duyệt; đối chiếu `429` và
`Retry-After` từ edge với alert telemetry. Backend security E2E chỉ kiểm tra
auth/data-isolation và không thay thế gate này.
8. Gửi `X-Forwarded-For` giả từ ngoài proxy: không đổi quota client. Test cả
   source trong trusted CIDR và source ngoài CIDR.
9. Dừng Redis có chủ ý ở staging: log/metric `redis_fallback` xuất hiện; khôi
   phục Redis rồi xác nhận metric không tiếp tục tăng và shared state hoạt động.
10. Xác nhận auth-api/MySQL/RAG không có host port trong `docker compose ps`;
    `/actuator/metrics` không đi qua frontend public.

Chỉ merge/deploy production sau khi lưu kết quả staging, snapshot cấu hình WAF
và kế hoạch rollback image/config trước đó.

## 7. Deploy, rollback và secret rotation có kiểm soát

Các thao tác dưới đây là script executable trong `ops/`; chạy bằng PowerShell
7+ từ thư mục `BE`. Script không commit, in secret, hoặc ghi đè `.env` hiện
hành.

```powershell
pwsh -File .\ops\deploy.ps1 -Action Validate
pwsh -File .\ops\deploy.ps1 -Action Deploy
pwsh -File .\ops\deploy.ps1 -Action Rollback `
  -RollbackComposeDir D:\releases\blueai-previous `
  -IUnderstandRollback
```

Rollback dùng bundle compose/.env đã duyệt trước đó, không dùng source tree
đang có thay đổi chưa review. Ba service ứng dụng (`auth-api`, `rag-ai`,
`frontend`) trong bundle bắt buộc dùng image digest `sha256` bất biến và không
có `build:`; script fail closed trước khi khởi động nếu thiếu điều kiện này.
Giữ image cũ trên registry hoặc cache của host trong suốt rollback window.
`pwsh -File .\ops\test-deploy-rollback.ps1` kiểm tra validator offline; test
không kết nối Docker hay thay đổi stack.
Compose/image rollback does not reverse Flyway migrations or restore database
contents. For an incompatible schema/data change, prefer a reviewed forward
migration; otherwise use the maintenance-window restore procedure below with a
verified backup and an explicit data-loss approval.

### Backup và restore

Backup production bắt buộc dùng `age` recipient để dữ liệu không nằm plaintext
trên đĩa. Script tạm dừng `auth-api` và `rag-ai`, giữ MySQL chạy để
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
  -AgeIdentity C:\secure\blueai-backup-key.txt -Apply -IUnderstandDataLoss
```

`-DryRun` kiểm tra checksum, giải mã và cấu trúc SQL/tar; `-TestRestore` import
SQL vào MySQL disposable và kiểm tra table, không đụng production. Live restore
dừng frontend/auth-api/rag-ai và thực hiện import dưới maintenance window.
PowerShell 7+ bắt buộc để native stdout giữ nguyên byte của tar.gz. Plaintext
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
# Thêm -RunDockerRestore để import fixture vào mysql:8.4 disposable.
```

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

Production compose thêm Prometheus, Alertmanager, Loki và Promtail; các service
này không bind host port. Vì Promtail đọc Docker JSON logs qua host Docker
socket, relabel rule đầu tiên chỉ giữ container có Compose project `blueai-auth`
và loại các project khác trước khi ship log. Loki gắn nhãn project/service/level/logger;
ECS `log.level` và `log.logger` được trích xuất, còn `trace.id` giữ trong JSON body
để tránh label cardinality cao. Loki giữ 7 ngày trong volume. Truy cập công cụ
quan sát qua private network hoặc SSH tunnel.

Kiểm tra allowlist Promtail offline (bao gồm fixture project ngoài stack):

```powershell
node .\ops\test-promtail-config.mjs
```

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

Alert rules ở `ops/prometheus/alerts.yml`: scrape down, auth login/password-reset
429 burst, auth endpoint 5xx >5%, tổng 5xx >5%, p95 >2s, Redis fallback và
burst abuse. Rule p95 yêu cầu application/migration agent
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
docker compose -f compose.yaml -f compose.production.yaml logs --tail=100 prometheus alertmanager loki promtail
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
conversation search, soft-delete conversation, và yêu cầu public worker route
trả 403/404 tại edge. Script này tuyệt đối không gửi `WORKER_API_KEY` qua
staging ingress.

Worker auth/retry chạy riêng từ private Docker network, không qua frontend:

```powershell
$env:APP_WORKER_API_KEY = 'secret-from-staging-secret-store'
pwsh -File .\ops\worker-private-e2e.ps1
# Tuỳ chọn: WORKER_DOCUMENT_ID là document đang PROCESSING/được claim để kiểm
# tra attempt header/body mismatch -> 409 mà không ghi thay đổi.
```

`worker-private-e2e.ps1` kiểm tra wrong key 401, đúng key nhưng `limit=0`
400 trước khi claim, và stale attempt 409 nếu có `WORKER_DOCUMENT_ID`. Nếu
public probe trả 401 thay vì 403/404, đó là residual ingress gap. Trạng thái
hiện tại đã biết: `FE/nginx.conf` dùng generic `/api` proxy và **không chặn**
`/api/v1/internal/**`, nên request không có key vẫn có thể được chuyển tiếp tới
API và public security E2E sẽ fail ở assertion 403/404. Đây là blocker có chủ ý
cho tới khi edge/WAF rule chặn path này hoặc có thay đổi FE được phê duyệt và
triển khai. Phạm vi BE/ops này không tự sửa FE; dừng rollout nếu gate còn fail
và không dùng worker secret để “chứng minh” public route.

Các ca đổi/reset password và soft-delete user là destructive nên mặc định tắt.
Chỉ bật trên staging cô lập với account throwaway và cleanup bắt buộc:

```powershell
$env:RUN_ACCOUNT_MUTATION_E2E = 'true'
# Nếu staging mailbox/test harness trả raw token ngoài band:
$env:PASSWORD_RESET_TOKEN = '...'
node .\ops\security-e2e.mjs
```

Flow tạo user throwaway, đổi password và xác nhận mật khẩu cũ bị từ chối; reset
request chỉ chấp nhận generic response và invalid token nếu không có mailbox
token. Sau đó admin soft-delete user và kiểm tra `includeDeleted=false/true`.
Không chạy cờ này trên production hoặc user thật.

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

Database mới để ứng dụng chạy Flyway bình thường. Với database đã có schema từ
trước khi Flyway được bật, **không** bật `baseline-on-migrate` và không khởi động
ứng dụng lần đầu trực tiếp trên database đó: `V1__baseline_entity_schema.sql`
tạo các bảng ban đầu nên Flyway sẽ dừng khi gặp bảng đã tồn tại. Quy trình nâng
cấp yêu cầu maintenance window:

1. Dừng mọi writer, tạo và kiểm tra backup có thể restore.
2. So sánh database với từng schema legacy đã được kiểm chứng (bảng, cột,
   kiểu, nullability, index/constraint). Chỉ dùng baseline ở **version cao nhất
   mà schema thực sự khớp**: dùng `baselineVersion=3` khi database khớp đầy đủ
   V1–V3; chỉ dùng `baselineVersion=1` khi database chỉ khớp V1. Không được
   đoán version, baseline mù, hoặc sửa tay `flyway_schema_history`; schema lệch
   phải được xử lý bằng migration forward có review.
3. Dùng Flyway CLI cùng major/version tương thích với `flyway-core` của ứng dụng
   và cùng kết nối/database đã kiểm tra để chạy một lần. Ví dụ, chọn đúng một
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
   V2, V3 rồi V4. Với baseline V3, Flyway chỉ chạy V4. Hibernate
   `ddl-auto=validate` phải kiểm tra entity/schema sau đó.
5. Nếu preflight hoặc validate thất bại, dừng rollout và restore backup hoặc
   chuẩn bị migration forward đã review. Không xóa schema history, không chạy
   `clean`, và không bật baseline tự động để vượt lỗi.

Thực hiện rehearsal quy trình này trên bản restore/staging trước production.
Fresh schema và migration chain V1–V4 được kiểm tra bởi
`FlywayMigrationIntegrationTest`; test đó không thay thế preflight trên schema
legacy thật. Rehearsal isolated-MySQL đã xác nhận schema legacy V1–V3 với
`baselineVersion=3` rồi chạy V4; production vẫn chỉ được chọn baseline sau khi
fingerprint/schema diff thực tế khớp mốc V1 hoặc V1–V3 tương ứng.
