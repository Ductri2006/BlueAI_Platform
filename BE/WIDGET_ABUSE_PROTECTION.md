# Public widget: chống spam và lưu context

Tài liệu này mô tả ranh giới bảo vệ cho hai endpoint public:

- `POST /api/v1/widget/{publicKey}/sessions`
- `POST /api/v1/runtime/sessions/{sessionId}/messages`

## Nguyên tắc

CORS chỉ giới hạn website mà trình duyệt được phép gọi API; nó không ngăn một
script gọi thẳng API. Vì vậy widget dùng nhiều lớp: WAF/CDN ở biên, rate limit
trong application, session token, idempotency và context ngắn hạn.

## Luồng một tin nhắn

1. Backend xác thực session token và kiểm tra session chưa hết hạn.
2. Nếu `requestId` đã tồn tại với cùng nội dung, backend trả lại response cũ
   ngay. Đây là retry hợp lệ, không tiêu thêm quota hay gọi AI lần nữa.
3. Backend tăng bộ đếm rate limit theo client pseudonymous và theo session.
4. Nếu nội dung giống hệt câu ngay trước đó trong cửa sổ ngắn, backend replay
   response cũ. Chỉ sau nhiều lần lặp bất thường mới trả `429`.
5. Một Redis lock theo session bảo đảm hai request đồng thời không cùng thay
   đổi dialog state hoặc cùng gọi model.
6. Request hợp lệ mới được chuyển vào dialog/RAG. Kết quả sau đó được lưu vào
   MySQL (idempotency bền vững) và Redis (context ngắn hạn có TTL).

## Vì sao không block người dùng đang chưa hiểu

Chỉ **nội dung giống hệt** mới là duplicate. Một câu như “giải thích lại”,
“cho ví dụ”, hoặc câu hỏi cùng chủ đề nhưng có diễn đạt/thông tin mới luôn đi
vào luồng hội thoại bình thường. Redis lưu `clarificationCount` ngắn hạn để
phục vụ observability sau này, nhưng không tự động block người dùng vì điểm đó.

## Redis và quyền riêng tư

Redis chỉ giữ state tạm thời; Compose không publish cổng Redis. IP được hash
kèm salt `APP_WIDGET_CLIENT_KEY_SALT` trước khi dùng làm rate-limit key. Đặt
salt ngẫu nhiên dài ở production và không log IP thô cho mục đích rate limit.

| Key pattern | TTL | Nội dung |
|---|---:|---|
| `blueai:widget:rate:*` | 1 phút mặc định | Counter IP/session |
| `blueai:widget:context:{sessionId}` | 2 phút mặc định | Hash câu gần nhất, response cache, số lần lặp |
| `blueai:widget:lock:session:{sessionId}` | 60 giây mặc định | Khoá chống request đồng thời |

`BotSession` trong MySQL có TTL 30 phút và cleanup job tiếp tục xoá session và
turn đã hết hạn. Nếu Redis tạm lỗi, ứng dụng log một cảnh báo và chuyển sang
fallback in-memory của instance hiện tại; production cần monitor cảnh báo này
vì fallback không chia sẻ được giữa nhiều replica.

## Cấu hình mặc định

| Biến | Giá trị mặc định | Mục đích |
|---|---:|---|
| `APP_WIDGET_REDIS_ENABLED` | `false` ngoài Compose | Bật Redis shared state |
| `APP_WIDGET_MAX_SESSION_CREATIONS` | 10/phút/IP | Hạn chế tạo hàng loạt session |
| `APP_WIDGET_MAX_MESSAGES_PER_CLIENT` | 30/phút/IP | Chặn burst từ một client |
| `APP_WIDGET_MAX_MESSAGES_PER_SESSION` | 12/phút/session | Giữ tốc độ chat hợp lý |
| `APP_WIDGET_MAX_MESSAGES_PER_SESSION_TOTAL` | 120 | Giới hạn chi phí một session |
| `APP_WIDGET_DUPLICATE_REPLAY_LIMIT` | 2 | Số retry nội dung giống hệt được replay |
| `APP_WIDGET_DUPLICATE_WINDOW` | 2m | Cửa sổ phát hiện duplicate |
| `APP_WIDGET_REQUEST_LOCK_TTL` | 60s | Thời gian tối đa xử lý một turn |

Các số này là điểm khởi đầu. Chạy shadow monitoring hoặc theo dõi `429`, chi
phí model, session creation và false-positive trước khi siết/chỉnh giới hạn.

## Proxy tin cậy, edge limit và WAF

Mặc định backend dùng `request.getRemoteAddr()` và bỏ qua `X-Forwarded-For` để
không nhận IP giả mạo. Chỉ bật `APP_WIDGET_TRUST_FORWARDED_HEADERS=true` khi
backend không thể bị gọi trực tiếp, reverse proxy tin cậy **thay thế** header
và `APP_WIDGET_TRUSTED_PROXY_CIDRS` chỉ rõ CIDR nguồn của proxy đó. Nếu thiếu
CIDR, backend fail-closed và tiếp tục dùng địa chỉ remote thay vì tin header.

`FE/nginx.conf` là lớp edge trong stack Compose: nó thay, không append,
`X-Forwarded-For`, rate-limit hai endpoint public trước Java và không cho
`/actuator/**` đi ra frontend. Preflight `OPTIONS` không mất quota. Nginx có
ngưỡng cao hơn backend để lọc flood; Redis limiter vẫn là nguồn quota chính
xác. Khi CDN đứng trước Nginx, cấu hình `real_ip` ở edge chỉ với dải IP CDN
chính thức trước khi ghi header client.

Production public vẫn cần rate-limit/challenge tại Cloudflare, Nginx, API
Gateway hoặc WAF tương đương. Các lớp này chặn flood trước khi nó tiêu connection
pool hoặc CPU ở Java; Redis limiter là lớp bảo vệ application, không thay thế WAF.

## Metric không chứa dữ liệu người dùng

Metric `blueai.widget.protection.events` dùng hai tag enum cố định `event` và
`scope`, nên không thể vô tình tạo tag từ IP, token, session/request ID hay nội
dung chat. Theo dõi đặc biệt `redis_fallback`, `rate_limited`,
`duplicate_blocked` và `request_in_progress`. `/actuator/metrics` yêu cầu
quyền `ADMIN`; health check vẫn public. Quy trình deploy, alert và test staging
đầy đủ nằm trong [`PRODUCTION_DEPLOYMENT.md`](PRODUCTION_DEPLOYMENT.md).
