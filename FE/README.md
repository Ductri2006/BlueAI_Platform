# BlueAI Platform — UI/UX Frontend

Frontend cho nền tảng BlueAI Enterprise, tập trung vào hỏi đáp RAG, quản trị nguồn tri thức và tài khoản hệ thống. Giao diện sử dụng tiếng Việt, hỗ trợ Light/Dark mode và được tối ưu cho desktop lẫn màn hình nhỏ.

## Các không gian làm việc

- **Hỏi BlueAI:** hội thoại RAG, câu hỏi gợi ý, trích dẫn nguồn, đính kèm tệp, nhập giọng nói và mạng tri thức 3D.
- **Tài liệu & Tri thức RAG (ADMIN):** tải nguồn lên, theo dõi trạng thái xử lý và quản lý kho tri thức dùng chung.
- **Quản trị Hệ thống (ADMIN):** cấp tài khoản `USER`/`ADMIN`, bật/tắt tài khoản và xem số liệu runtime.

`USER` chỉ thấy không gian Hỏi BlueAI. Các màn quản trị được ẩn trên FE và tiếp tục được chặn bằng phân quyền ở Backend.

## Design system

- Font giao diện: **Be Vietnam Pro**.
- Font dữ liệu kỹ thuật: **IBM Plex Mono**.
- Màu thương hiệu: Cobalt `#1E4FD8`, Cyan `#38BDF8`.
- Canvas sáng/tối: `#F8FAFD` / `#060B14`.
- Card bo góc `20px`, modal `24px`, button `12px`.
- Có focus state bằng bàn phím, semantic dialog, reduced-motion và responsive navigation.

## Cấu trúc source

```text
src/
├── auth/              # Phiên đăng nhập và thông tin người dùng
├── components/
│   ├── auth/        # Màn hình đăng nhập (không có đăng ký công khai)
│   ├── 3d/          # Orb AI dựng bằng Three.js
│   ├── chat/        # Hội thoại, composer, nguồn dẫn và modal
│   ├── copilot/     # Sales Copilot và luồng phê duyệt HITL
│   ├── dashboards/  # Admin, Knowledge, Reseller và B2C
│   ├── layout/      # App shell, sidebar và top header
│   └── ui/          # Button, Card và Badge dùng chung
├── store/           # Trạng thái và tương tác toàn ứng dụng
├── types/           # Kiểu dữ liệu TypeScript
├── App.tsx
├── index.css
└── main.tsx
```

Các module Three.js được lazy-load thành chunk riêng để giao diện chính hiển thị nhanh hơn.

## Chạy local

Yêu cầu Node.js 20 trở lên.

```bash
npm install
Copy-Item .env.example .env.local
npm run dev
```

Chat box gọi Spring Boot qua đường dẫn cùng origin `/api`; Vite local proxy sang
`http://127.0.0.1:8080`, còn nginx trong Docker proxy sang service `auth-api`.
Có thể đặt `VITE_API_BASE_URL` nếu cần gọi một API origin khác. Người dùng chỉ
đăng nhập bằng tài khoản do admin cấp; FE lưu access token và refresh token do
Backend cấp, tự làm mới access token khi hết hạn và thu hồi refresh token khi đăng xuất.

Spring Boot cần được cấu hình cả retriever và LLM như mô tả trong
`../BE/RAG_WORKER_CONTRACT.md`; nếu thiếu, UI sẽ hiển thị mã lỗi có cấu trúc từ
backend thay vì trả câu trả lời giả lập.

## Build production

```bash
npm run build
npm run preview
```

Build output được tạo trong `dist/` và không được commit lên Git.

## Test

```bash
npm test
```
