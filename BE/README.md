# BlueAI Authentication API

Backend xác thực và RAG của BlueAI, viết bằng Java 21, Spring Boot, Spring Security và MySQL.

## Kiến trúc Spring MVC

Đây là REST API sử dụng Spring MVC. JSON response DTO đóng vai trò representation/view, vì vậy dự án không dùng server-side HTML template.

```text
src/main/java/com/blueai/auth/
├── controller/   # Nhận HTTP request, validate DTO và trả HTTP response
├── service/      # Authentication use cases, JWT và refresh-token rotation
├── model/        # JPA entities và domain enums
├── repository/   # Spring Data JPA access tới MySQL
├── dto/          # Request/response representation của REST API
├── config/       # Spring Security, JWT, CORS
└── exception/    # API errors, 401/403 và validation handlers
```

Luồng xử lý: `HTTP request → Controller → Service → Repository → MySQL`; response đi ngược lại qua DTO và được Spring MVC serialize thành JSON.

## Chức năng

- Chỉ có hai vai trò `ADMIN` và `USER`; không có đăng ký công khai. Tài khoản chỉ được tạo bởi quản trị viên.
- Tài khoản admin đầu tiên được bootstrap từ biến môi trường; mật khẩu được băm bằng encoder thích ứng của Spring Security.
- `ADMIN` quản lý tài khoản, trạng thái hoạt động, số liệu hệ thống và nguồn tài liệu RAG dùng chung.
- `USER` đăng nhập để sử dụng chat RAG và đọc nguồn do admin cung cấp.
- Đăng nhập trả về JWT access token HS256 (mặc định 15 phút) và refresh token ngẫu nhiên (mặc định 30 ngày).
- Refresh token chỉ được lưu dưới dạng SHA-256, được rotate sau mỗi lần dùng và có phát hiện reuse để thu hồi cả session family.
- Đăng xuất thu hồi refresh-token family. Access token đã phát hành tiếp tục hợp lệ đến khi hết hạn.
- Endpoint `me` được bảo vệ bằng Bearer JWT.
- Flyway quản lý schema MySQL, lỗi API có cấu trúc thống nhất, CORS được cấu hình bằng biến môi trường.

## Chạy bằng Docker

```powershell
cd backend/BE
$env:GOOGLE_API_KEY = "your-gemini-api-key"
# Bắt buộc thay APP_JWT_SECRET, APP_BOOTSTRAP_ADMIN_PASSWORD và mật khẩu DB trước khi dùng ngoài local.
docker compose up --build
```

API: `http://localhost:8080`; health check: `http://localhost:8080/actuator/health`; MySQL local: `localhost:33069`.

Compose khởi động đồng thời FE, Java API, MySQL và Python RAG/Gemini service.
Hợp đồng giữa Java và Python được mô tả tại [`RAG_WORKER_CONTRACT.md`](RAG_WORKER_CONTRACT.md).
Nếu một trong hai upstream chưa sẵn sàng, API trả lỗi `503/502` có mã rõ ràng để
frontend hiển thị, không tự chuyển sang câu trả lời giả.

## API

### Tài khoản admin bootstrap

```dotenv
APP_PUBLIC_REGISTRATION_ENABLED=false
APP_BOOTSTRAP_ADMIN_EMAIL=admin@blueai.local
APP_BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
APP_BOOTSTRAP_ADMIN_FULL_NAME=BlueAI Administrator
```

Admin bootstrap chỉ được tạo khi email chưa tồn tại. Endpoint đăng ký công khai bị tắt mặc định.

### Đăng nhập

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "StrongPassword123!"
}
```

### Làm mới token

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "<refresh-token>"
}
```

Client phải thay refresh token cũ bằng refresh token mới trong response. Dùng lại token cũ sẽ thu hồi toàn bộ session family.

### Người dùng hiện tại

```http
GET /api/v1/auth/me
Authorization: Bearer <access-token>
```

### Đăng xuất

```http
POST /api/v1/auth/logout
Content-Type: application/json

{
  "refreshToken": "<refresh-token>"
}
```

### Quản lý tài khoản (chỉ ADMIN)

```http
GET /api/v1/admin/users
Authorization: Bearer <admin-access-token>

POST /api/v1/admin/users
Authorization: Bearer <admin-access-token>
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "StrongPassword123!",
  "fullName": "BlueAI User",
  "role": "USER"
}

PATCH /api/v1/admin/users/{userId}/status
Authorization: Bearer <admin-access-token>
Content-Type: application/json

{
  "status": "DISABLED"
}
```

`ADMIN` cũng có quyền gọi `/api/v1/documents/**` để tải lên/quản lý nguồn RAG và `/api/v1/system/**` để xem số liệu vận hành.

## Chạy test khi máy chưa cài Maven

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-21-alpine mvn test
```

Các test integration dùng H2 ở MySQL compatibility mode. Docker startup đồng thời chạy Flyway và Hibernate schema validation trên MySQL thật.
