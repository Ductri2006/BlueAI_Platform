# BlueAI Platform — UI/UX Frontend

Frontend prototype cho nền tảng BlueAI Enterprise, tập trung vào trải nghiệm hỏi đáp có căn cứ, hỗ trợ bán hàng, quản trị tri thức và vận hành hệ thống. Giao diện sử dụng tiếng Việt, hỗ trợ Light/Dark mode và được tối ưu cho desktop lẫn màn hình nhỏ.

## Các không gian làm việc

- **Hỏi BlueAI:** hội thoại RAG, câu hỏi gợi ý, trích dẫn nguồn, đính kèm tệp, nhập giọng nói và mạng tri thức 3D.
- **Trợ lý Bán hàng & CSKH:** hàng chờ khách hàng, hội thoại trực tiếp, Customer 360, gợi ý phản hồi và phê duyệt HITL.
- **Tài liệu & Tri thức RAG:** KPI kho tri thức, tìm kiếm, lọc và kiểm tra tài liệu/chunks.
- **Quản trị Hệ thống:** lưu lượng AI, độ trễ, độ chính xác RAG, chi phí token, model routing và RBAC.
- **Cổng Đại lý:** doanh số, chiết khấu, hạn mức tín dụng và đơn hàng gần đây.
- **Hỗ trợ Khách hàng B2C:** mã QR eSIM, hướng dẫn kích hoạt và trợ lý hỏi đáp nhanh.

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
├── components/
│   ├── 3d/          # Orb AI dựng bằng Three.js
│   ├── chat/        # Hội thoại, composer, nguồn dẫn và modal
│   ├── copilot/     # Sales Copilot và luồng phê duyệt HITL
│   ├── dashboards/  # Admin, Knowledge, Reseller và B2C
│   ├── layout/      # App shell, sidebar và top header
│   └── ui/          # Button, Card và Badge dùng chung
├── data/            # Dữ liệu mẫu cho prototype
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
npm run dev
```

## Build production

```bash
npm run build
npm run preview
```

Build output được tạo trong `dist/` và không được commit lên Git.
