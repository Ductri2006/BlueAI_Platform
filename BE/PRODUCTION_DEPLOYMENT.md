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
docker compose -f compose.yaml -f compose.production.yaml config
docker compose -f compose.yaml -f compose.production.yaml up -d --build
docker compose -f compose.yaml -f compose.production.yaml ps
```

Lệnh `config` phải hoàn thành trước. Nó sẽ dừng ngay nếu thiếu biến bắt buộc.
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

- `POST /api/v1/widget/*/sessions`: rate limit/challenge theo IP; ngưỡng WAF
  nên cao hơn 10/phút của backend để edge chỉ xử lý flood.
- `POST /api/v1/runtime/sessions/*/messages`: rate limit/challenge theo IP;
  ngưỡng WAF nên cao hơn 30/phút của backend.
- Chặn request body vượt mức ứng dụng cho phép, bot reputation xấu và traffic
  từ quốc gia không nằm trong phạm vi kinh doanh nếu chính sách công ty cho
  phép.
- Không cache response POST, không cache token widget, và không biến WAF
  challenge thành redirect HTML cho API client.

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

Actuator expose `health`, `info` và `metrics`; chỉ `health` public cho health
check. Các endpoint Actuator khác yêu cầu `ADMIN`, và frontend Nginx trả 404
cho `/actuator/**`. Đọc metric qua private network/host quản trị đã xác thực,
ví dụ endpoint:

```text
/actuator/metrics/blueai.widget.protection.events
```

Tạo alert tối thiểu:

- `redis_fallback > 0`: shared protection đã mất; kiểm tra Redis ngay.
- `rate_limited` hoặc `duplicate_blocked` tăng đột biến: kiểm tra bot/WAF,
  không vội siết quota nếu đó là campaign hợp lệ.
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
9. Dừng Redis có chủ ý ở staging: log/metric `redis_fallback` xuất hiện; khôi
   phục Redis rồi xác nhận metric không tiếp tục tăng và shared state hoạt động.
10. Xác nhận auth-api/MySQL/RAG không có host port trong `docker compose ps`;
    `/actuator/metrics` không đi qua frontend public.

Chỉ merge/deploy production sau khi lưu kết quả staging, snapshot cấu hình WAF
và kế hoạch rollback image/config trước đó.
