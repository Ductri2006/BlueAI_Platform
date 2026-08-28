# BlueAI Authentication API

Backend authentication đầu tiên của BlueAI, viết bằng Java 21, Spring Boot, Spring Security và MySQL.

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

- Đăng ký bằng email/mật khẩu; email được chuẩn hóa và mật khẩu được băm bằng encoder thích ứng của Spring Security.
- Đăng nhập trả về JWT access token HS256 (mặc định 15 phút) và refresh token ngẫu nhiên (mặc định 30 ngày).
- Refresh token chỉ được lưu dưới dạng SHA-256, được rotate sau mỗi lần dùng và có phát hiện reuse để thu hồi cả session family.
- Đăng xuất thu hồi refresh-token family. Access token đã phát hành tiếp tục hợp lệ đến khi hết hạn.
- Endpoint `me` được bảo vệ bằng Bearer JWT.
- Flyway quản lý schema MySQL, lỗi API có cấu trúc thống nhất, CORS được cấu hình bằng biến môi trường.

## Chạy bằng Docker

```powershell
cd backend
Copy-Item .env.example .env
# Thay APP_JWT_SECRET và các mật khẩu trong .env trước khi dùng ngoài local.
docker compose up --build
```

API: `http://localhost:8080`; health check: `http://localhost:8080/actuator/health`; MySQL local: `localhost:33069`.

## API

### Đăng ký

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "StrongPassword123!",
  "fullName": "BlueAI User"
}
```

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

## Chạy test khi máy chưa cài Maven

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-21-alpine mvn test
```

Các test integration dùng H2 ở MySQL compatibility mode. Docker startup đồng thời chạy Flyway và Hibernate schema validation trên MySQL thật.
