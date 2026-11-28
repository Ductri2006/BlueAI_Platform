# Products — danh mục Bluecom và tư vấn AI

Backend có endpoint GET đọc trực tiếp API đối tác, không có trang Products trong frontend. Danh mục cho AI được đồng bộ riêng vào MySQL, không cần upload CSV, tạo embedding hay chờ worker tài liệu. Khi cần tư vấn, backend lọc tối đa 6 sản phẩm phù hợp rồi đưa các trường công khai vào ngữ cảnh của AI.

## Cấu hình

Đặt trên máy chủ backend (hoặc `BE/.env` khi chạy Compose):

```dotenv
BLUECOM_PRODUCTS_BASE_URL=https://api.sandbox.bluesim.com.vn/api/open/v1
BLUECOM_PRODUCTS_API_KEY=<API key đối tác>
BLUECOM_PRODUCTS_TIMEOUT=30s
BLUECOM_PRODUCTS_AUTO_SYNC_ENABLED=true
BLUECOM_PRODUCTS_REFRESH_INTERVAL=15m
```

URL mặc định là **sandbox**. Thay bằng host production do Bluecom cấp khi cần dùng danh mục production. Không dùng biến `VITE_*`, không đưa API key vào frontend, URL truy vấn hay Git. Khởi động lại backend sau khi cập nhật cấu hình; Flyway tự chạy migration V17. Compose đọc `BE/.env`. Khi chạy Java/Maven/IntelliJ trực tiếp, truyền các biến trên vào môi trường tiến trình hoặc thêm cùng các dòng `BLUECOM_PRODUCTS_*` vào `BE/.env.worker.properties` (file local được Git bỏ qua, backend đã import từ working directory BE/backend/workspace). Java không tự đọc `BE/.env`. Mật khẩu trang tài liệu không được gửi trong request API.

Xác nhận trên **đúng backend mà frontend đang gọi**: GET `/api/v1/products/summary` phải có `configured=true`, `available>0` và `lastSyncedAt` gần hiện tại. Nếu `configured=false` hoặc `available=0`, AI chưa có danh mục để tư vấn dù endpoint trên một bản preview khác đã đồng bộ thành công. Có thể dùng POST `/api/v1/products/sync` bằng ADMIN để đồng bộ ngay.

## IP allowlist khi lên server

Bluecom kiểm tra IP public **đi ra của tiến trình backend**. IP laptop chỉ áp dụng cho request từ mạng laptop. Khi triển khai, đăng ký IP egress của VM/container/NAT với Bluecom. Domain, IP trình duyệt khách và header `X-Forwarded-For` không thay thế IP này. Nếu nền tảng dùng IP đi ra thay đổi, cần static egress/NAT hoặc chạy phần tích hợp trên máy chủ có IP cố định.

Kiểm tra từ đúng container/VM chạy backend, ví dụ `curl -4 https://api.ipify.org`; không chạy từ laptop rồi dùng kết quả cho cloud. Đối chiếu hướng dẫn xác thực trong [tài liệu Bluecom](https://api.esim.blueconnect.vn/esim-bluesim-partner-api-2487322m0). Backend gửi key qua header `apiKey`; lỗi upstream 401 được đổi thành `PRODUCT_UPSTREAM_UNAUTHORIZED`, 403 thành `PRODUCT_UPSTREAM_IP_FORBIDDEN` (HTTP 502 của BlueAI). Thông tin lỗi riêng của đối tác không được trả về hoặc ghi log.

## API

Các endpoint cần phiên đăng nhập BlueAI. Trạng thái ACCOUNT/PASSWORD_CHANGE và phân quyền hiện tại vẫn áp dụng.

| Method | Endpoint | Chức năng |
| --- | --- | --- |
| GET | `/api/v1/products/sandbox?page=1&size=200` | Chỉ ADMIN: đọc ngay một trang từ Bluecom, trả `packageList`, `count`, `page`, `size`, `totalPages`. Trang bắt đầu từ 1, tối đa 200 sản phẩm/trang. Không cập nhật danh mục AI. |
| GET | `/api/v1/products?query=Japan&country=JP&days=7&budget=200000&page=0&size=20` | Tìm theo tên/SKU/khu vực, phạm vi phủ sóng, thời hạn tối thiểu và ngân sách VND. Trang bắt đầu từ 0, tối đa 100 sản phẩm/trang. |
| GET | `/api/v1/products/{sku}` | Chi tiết sản phẩm đang bán. |
| GET | `/api/v1/products/summary` | Tổng sản phẩm đang bán, thời điểm đồng bộ, nguồn dữ liệu và trạng thái cấu hình. |
| POST | `/api/v1/products/sync` | Chỉ ADMIN: đọc toàn bộ các trang Bluecom (`size=200`), kiểm tra số lượng và SKU duy nhất rồi cập nhật nguyên tử. |

Gọi GET với từng `page` từ 1 đến `totalPages` để lấy đủ dữ liệu; không gom toàn bộ danh mục lớn vào một response. Endpoint này dùng JWT BlueAI của ADMIN, không nhận partner key từ trình duyệt:

```bash
curl 'http://localhost:8080/api/v1/products/sandbox?page=1&size=200' \
  -H "Authorization: Bearer $BLUEAI_ADMIN_TOKEN"
```

Khi `BLUECOM_PRODUCTS_AUTO_SYNC_ENABLED=true`, backend bắt đầu đồng bộ sau 10 giây và làm mới theo khoảng thời gian cấu hình (mặc định 15 phút), chạy nền để không chặn các scheduled task khác. Mặc định tắt để không tự gọi đối tác khi chưa chủ động cấu hình. Có thể gọi POST `/sync` để cập nhật ngay. Đồng bộ lỗi không xóa danh mục hiện tại; SKU biến mất khỏi lần đồng bộ hoàn chỉnh bị đánh dấu ngừng bán và bị loại khỏi tìm kiếm/AI. Trong triển khai nhiều instance, chỉ bật đồng bộ nền trên **một instance**, và gửi POST `/sync` tới instance đó; khóa đồng bộ hiện tại chỉ bảo vệ trong một tiến trình.

## Tư vấn AI

- Dùng danh mục trong chat nội bộ, xem thử BlueAI Core và widget/bot public khi luồng Dialog chuyển sang AI Core. Các node Dialog trả lời cố định vẫn giữ hành vi hiện có.
- Nhận diện quốc gia theo tên tiếng Việt/Anh hoặc mã ISO viết hoa; hỗ trợ số ngày và ngân sách VND đơn giản (ví dụ “eSIM Nhật Bản 7 ngày dưới 200k”). Câu hỏi tiếp có thể dùng quốc gia/thời hạn từ tin nhắn USER gần nhất. Yêu cầu phức tạp hoặc nhiều quốc gia cần làm rõ.
- AI chỉ nhận SKU, mô tả, thời hạn, dung lượng, phủ sóng, chính sách và **giá bán lẻ đề xuất**. Giá nhập `price` chỉ xuất hiện trong API ADMIN; không được đưa vào prompt tư vấn. Giá bán không thay báo giá cuối cùng. AI được thông báo rõ khi nguồn dữ liệu là sandbox.
- AI dẫn SKU và có nguồn loại `PRODUCT_CATALOG` trong chat nội bộ để người dùng mở xem căn cứ. Nguồn sản phẩm được lưu cùng câu trả lời; không giả làm tài liệu RAG. Thời điểm và môi trường đồng bộ được cung cấp trong prompt; kết quả phản ánh bản đồng bộ gần nhất.
- Các sản phẩm đang bán là danh mục dùng chung cho toàn bộ trợ lý của công ty, bao gồm widget/bot đã phát hành. Chỉ đồng bộ danh mục được phép tư vấn công khai.
- Không thực hiện đặt đơn, thanh toán hoặc kích hoạt eSIM. Danh mục chỉ cung cấp dữ kiện để tư vấn.

## Kiểm chứng

Backend có kiểm thử phân trang, danh mục trùng/thiếu, lỗi key/IP không lộ chẩn đoán đối tác, đồng bộ giữ dữ liệu cũ, SKU ngừng bán, phân quyền GET trực tiếp, bộ lọc và ngữ cảnh AI không lộ giá nhập. Kiểm thử chat dùng provider giả để xác nhận prompt thực tế; không đánh giá độ chính xác câu trả lời của mô hình production. Frontend kiểm thử nguồn dẫn chứng theo SKU; chạy `npm test` và `npm run build` trong FE.
