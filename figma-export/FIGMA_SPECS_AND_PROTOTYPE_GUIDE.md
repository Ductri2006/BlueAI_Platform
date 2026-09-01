# HƯỚNG DẪN FIGMA DESIGN SYSTEM & PROTOTYPE DEMO — BLUEAI ENTERPRISE

Tài liệu này cung cấp toàn bộ tài nguyên thiết kế, Design Tokens chuẩn W3C và hướng dẫn tạo Interactive Prototype trên Figma cho dự án **BlueAI Platform**.

---

## 1. Danh Sách Tệp Thiết Kế Figma Có Sẵn Trong Thư Mục

Các tệp được lưu trữ trực tiếp tại:
📁 `d:\@@@@@@@@@Thực Tập\UI UX\figma-export\`

| Tên Tệp | Định Dạng | Mô Tả & Mục Đích Sử Dụng |
|---|---|---|
| **`blueai-figma-tokens.json`** | JSON (W3C Tokens Studio) | Chứa toàn bộ Color Palette, Typography scale, Spacing, Border Radius, Drop Shadows sẵn sàng import vào plugin **Tokens Studio for Figma**. |
| **`BlueAI_Chat_1440x900_Desktop_Frame.svg`** | Vector SVG (1440x900) | Frame màn hình **Hỏi BlueAI** với đầy đủ Sidebar Rail, Hero 3D Stage, 4 Bento Prompt Cards và Floating Composer. Kéo-thả trực tiếp vào Figma sẽ thành các Vector Layer & Text có thể chỉnh sửa. |
| **`BlueAI_Sales_Copilot_1440x900_Frame.svg`** | Vector SVG (1440x900) | Frame màn hình **Trợ lý Bán hàng & CSKH (Sales Copilot)** bố cục 3 cột (Queue List, Live Chat, Customer 360 & Grounded Suggestions). |

---

## 2. Cách Nhập (Import) Vào Figma Nhanh Chóng

### Cách 1: Kéo thả trực tiếp tệp SVG vào Figma (1 giây)
1. Mở phần mềm hoặc trang web **Figma** (tạo một Design file mới).
2. Mở thư mục `d:\@@@@@@@@@Thực Tập\UI UX\figma-export\`.
3. Kéo tệp `BlueAI_Chat_1440x900_Desktop_Frame.svg` hoặc `BlueAI_Sales_Copilot_1440x900_Frame.svg` thả trực tiếp vào Canvas Figma.
4. Figma sẽ tự động chuyển đổi thành **Frame 1440x900px** với đầy đủ Group, Text layer, Vector shape và Gradient màu sắc.

### Cách 2: Import Design Tokens bằng Plugin "Tokens Studio for Figma"
1. Trong Figma, mở tab **Plugins** → Tìm kiếm **"Tokens Studio for Figma"** (hoặc Figma Tokens).
2. Nhấn vào mục **Settings / Tools** → Chọn **Load from file / Import Tokens**.
3. Chọn tệp `blueai-figma-tokens.json` trong thư mục `figma-export`.
4. Toàn bộ mã màu Hex, Kiểu chữ Be Vietnam Pro / IBM Plex Mono và các lớp bóng đổ sẽ được áp dụng tự động vào Styles của Figma file.

---

## 3. Thiết Lập Interactive Prototype Trên Figma (Demo Flow)

Để tạo hiệu ứng chuyển động tương tác (Interactive Prototype) chuyên nghiệp:

### Flow 1: Nhấn vào Bento Card để gửi câu hỏi tự động
- **Trigger**: `On Click` trên thẻ **"Hoàn tiền eSIM chưa kích hoạt"**.
- **Action**: `Navigate to` → Frame `Chat_Active_Stream`.
- **Animation**: `Smart Animate` (Ease Out, `300ms`).

### Flow 2: Mở Modal Mạng 3D Tri thức (Knowledge Graph)
- **Trigger**: `On Click` trên nút `🌐 Mạng 3D Tri thức`.
- **Action**: `Open Overlay` → Frame `Modal_3D_Knowledge_Graph`.
- **Overlay Settings**: Centered, Close when clicking outside, Background blur `20px` với màu `#000000 60%`.
- **Animation**: `Dissolve` hoặc `Move In` từ đáy (`250ms`).

### Flow 3: Chèn câu trả lời từ BlueAI Copilot vào ô soạn thảo
- **Trigger**: `On Click` trên nút `↵ Chèn vào ô trả lời`.
- **Action**: `Change to` / `Set Variable` → Cập nhật Text trong khung Soạn thảo thành đoạn phản hồi có sẵn.

---

## 4. Bảng Quy Chuẩn Thiết Kế (Design System Specs)

### Màu Sắc (Color Tokens):
- **Brand Primary**: Electric Cobalt `#1E4FD8` (CTA & Active state).
- **Brand Hover / Glow**: Vivid Blue `#2563EB` & Cyber Ice Cyan `#38BDF8`.
- **Dark Canvas**: Deep Void Navy `#060B14`, Bề mặt Raised `#0B1324` & `#0E1A33`.
- **Semantic Badges**:
  - `Grounded / Verified`: Emerald `#10B981` (Nền `#064E3B`)
  - `Inferred / Approximate`: Amber `#F59E0B` (Nền `#78350F`)
  - `Danger / HITL Review`: Rose `#EF4444` (Nền `#7F1D1D`)

### Typography:
- **Font chính**: `Be Vietnam Pro` (Headers, Body text, Button labels).
- **Font dữ liệu kỹ thuật**: `IBM Plex Mono` (Order ID, ICCID, Token Cost, Latency metrics).
- **Bo góc (Border Radius)**:
  - Button & Input: `10px - 12px`
  - Bento Card & Message Bubble: `16px - 20px`
  - Modal Window: `24px`
