# BLUEAI PLATFORM — MASTER FIGMA PROTOTYPE SPECIFICATION & BLUEPRINT
> **Bản đặc tả thiết kế UI/UX & Kịch bản dựng Figma Interactive Prototype chi tiết 100% theo giao diện thực tế**  
> *Dành cho Figma AI, Figma Make Prototype, UI/UX Designers & Frontend Engineers*

---

## MỤC LỤC
1. [Thông Số Khung Canvas & Layout Auto-Layout Chuẩn](#1-thông-số-khung-canvas--layout-auto-layout-chuẩn)
2. [Hệ Thống Design Tokens & Visual Styles Chi Tiết](#2-hệ-thống-design-tokens--visual-styles-chi-tiết)
3. [Đặc Tả Chi Tiết 6 Màn Hình Không Gian Làm Việc (Workspaces)](#3-đặc-tả-chi-tiết-6-màn-hình-không-gian-làm-việc-workspaces)
4. [Đặc Tả Chi Tiết Các Modal & Drawers Tương Tác](#4-đặc-tả-chi-tiết-các-modal--drawers-tương-tác)
5. [Sơ Đồ & Kịch Bản Nối Dây Interactive Prototype Trên Figma](#5-sơ-đồ--kịch-bản-nối-dây-interactive-prototype-trên-figma)
6. [Master Prompt Tiếng Anh Chuẩn Cho Figma AI (Figma Make)](#6-master-prompt-tiếng-anh-chuẩn-cho-figma-ai-figma-make)

---

## 1. THÔNG SỐ KHUNG CANVAS & LAYOUT AUTO-LAYOUT CHUẨN

### 1.1. Cấu Trúc Khung Màn Hình Desktop (1440 x 900 px)
Mỗi Frame màn hình desktop trong Figma được thiết lập theo kích thước cố định **1440px (Width) x 900px (Height)**, sử dụng bố cục phân chia 2 phần:

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│ Frame: Desktop - 1440x900px (Clip content: ON, Fill: #060B14)                              │
├──────────────────────┬─────────────────────────────────────────────────────────────────────┤
│ SIDEBAR RAIL         │ TOP HEADER APP BAR (Width: 1184px, Height: 56px, Fixed Top)         │
│ Width: 256px         ├─────────────────────────────────────────────────────────────────────┤
│ Height: 900px        │ SUB-HEADER / ACTION BAR (Width: 1184px, Height: 48px)               │
│ Fill: #070D18        ├─────────────────────────────────────────────────────────────────────┤
│ Stroke Right: #182744│ MAIN WORKSPACE CONTENT CANVAS                                       │
│ Auto-Layout: Vert    │ Width: 1184px, Height: 796px (Scrollable / Auto-Layout)             │
│                      │ Padding: 32px Left/Right, 24px Top/Bottom                           │
└──────────────────────┴─────────────────────────────────────────────────────────────────────┘
```

---

## 2. HỆ THỐNG DESIGN TOKENS & VISUAL STYLES CHI TIẾT

### 2.1. Bảng Màu Hệ Thống (Color Palette)
| Tên Style trong Figma | Mã HEX | Opacity | Ứng Dụng Trong Giao Diện |
|---|---|---|---|
| `Brand/Primary` | `#1E4FD8` | 100% | Nút CTA chính, Tab active, Icon highlights |
| `Brand/Hover` | `#2563EB` | 100% | Trạng thái hover của nút bấm và viền thẻ |
| `Brand/Cyber-Cyan` | `#38BDF8` | 100% | Quỹ đạo 3D Orb, hạt lượng tử, focus ring |
| `Brand/Ice-Cyan` | `#0EA5E9` | 100% | Hào quang phát sáng, nhãn trạng thái stream |
| `Semantic/Confirmed` | `#10B981` | 100% | Huy hiệu Đã xác thực từ quy chế (Emerald) |
| `Semantic/Inferred` | `#F59E0B` | 100% | Huy hiệu Suy luận / Cảnh báo cần chú ý (Amber) |
| `Semantic/Danger-HITL` | `#EF4444` | 100% | Nút Yêu cầu hoàn tiền HITL, SLA trễ (Rose) |
| `Dark/Canvas-Void` | `#060B14` | 100% | Nền ứng dụng chính Chế độ Tối (Dark Canvas) |
| `Dark/Surface-Base` | `#0B1324` | 100% | Bề mặt Sidebar, Header, Khung soạn thảo |
| `Dark/Surface-Raised`| `#0E1A33` | 100% | Bề mặt Bento Cards, Customer Card |
| `Dark/Border-Subtle` | `#182744` | 100% | Viền ngăn cách 1px mỏng sắc nét |
| `Dark/Border-Active` | `#2B4675` | 100% | Viền phát sáng khi chọn hoặc hover |
| `Text/Dark-Primary` | `#F8FAFC` | 100% | Văn bản tiêu đề chính, độ tương phản cao |
| `Text/Dark-Secondary`| `#94A3B8` | 100% | Văn bản phụ, trích dẫn, nhãn danh mục |
| `Text/Dark-Muted` | `#64748B` | 100% | Timestamp, đơn vị tiền tệ, mã đơn hàng |

### 2.2. Quy Chuẩn Typography (Typography Scale)
- **Font Gia Đình**: `Be Vietnam Pro` (Chính) & `IBM Plex Mono` (Mã số / Kỹ thuật).
- **Text Styles**:
  - `Hero Title`: `26px` / Line-height: `34px` / Weight: `900 (Black)`
  - `Section Heading`: `18px` / Line-height: `26px` / Weight: `800 (ExtraBold)`
  - `Card Title`: `14.5px` / Line-height: `20px` / Weight: `700 (Bold)`
  - `Body Text`: `13.5px` / Line-height: `22px` / Weight: `400 (Regular)` & `500 (Medium)`
  - `Caption / Tags`: `11px` / Line-height: `16px` / Weight: `700 (Bold)` / Letter-spacing: `0.5px`
  - `Mono Code`: `11.5px` / Line-height: `18px` / Font: `IBM Plex Mono` / Weight: `600`

### 2.3. Hiệu Ứng Lớp (Effects & Shadows)
- **`Glow-Blue-Card`**: 
  - Drop Shadow 1: `X: 0, Y: 10, Blur: 30, Spread: -10, Color: rgba(30, 79, 216, 0.15)`
  - Drop Shadow 2: `X: 0, Y: 0, Blur: 25, Spread: -4, Color: rgba(56, 189, 248, 0.18)`
- **`Glassmorphism-Blur`**: Background Blur `16px`, Fill `rgba(11, 19, 36, 0.88)`
- **`Hologram-Aura-Pedestal`**: Radial Gradient `from rgba(30, 79, 216, 0.35) via rgba(56, 189, 248, 0.20) to transparent`, Blur `40px`

---

## 3. ĐẶC TẢ CHI TIẾT 6 MÀN HÌNH KHÔNG GIAN LÀM VIỆC (WORKSPACES)

---

### 3.1. WORKSPACE 1: `1. Hỏi BlueAI (Quy chế & Nghiệp vụ)`

#### A. Trạng Thái Chờ / Trống (Empty State Frame):
1. **Sidebar Rail (Trái - 256px)**:
   - **Logo**: Khối bo tròn `32x32px` (Radius 10px, Gradient `#1E4FD8` → `#38BDF8`), Chữ `b` trắng in đậm. Text: `blueAI` (Font 16px Black `#F8FAFC`), Subtext: `Trí tuệ Vận hành Doanh nghiệp` (Font 10px `#64748B`).
   - **Menu Item 1 (Active)**: Background `#1E4FD8`, Radius 12px, Icon Chat trắng, Text: `Hỏi BlueAI` (Trắng Bold).
   - **Menu Items 2-6 (Inactive)**: Text xám `#94A3B8`, icon xám mờ.
   - **Nút "+ Cuộc trò chuyện mới"**: Nút Gradient Xanh Cobalt, Radius 10px, Height 36px.
   - **Lịch sử gần đây (Recent List)**: Thẻ `Quy định hoàn tiền eSIM` (Active), `So sánh SoftBank vs Docomo`.
   - **User Profile Footer**: Avatar tròn `H`, Text: `Hà CSKH & Sales`, `Bluecom Main • ID: 8812`.

2. **Top Header (1184px x 56px)**:
   - Left: Chấm xanh lá `Emerald Pulse` `8x8px`, Text: `Bluecom Enterprise Core`, Divider `|`, Tag Scope: `Phạm vi: Toàn hệ thống (Nội bộ & Đại lý)`.
   - Right: Dropdown Pill `Vai trò: 1. Hỏi BlueAI (Quy chế & Nghiệp vụ) ▼`, Nút Dark/Light Mode, Nút Bell thông báo.

3. **Sub-Header Bar (1184px x 48px)**:
   - Left: Icon Pin ghim hội thoại, Title: `Quy định hoàn tiền eSIM chưa kích hoạt`.
   - Right: Nút `🌐 Mạng 3D Tri thức` (Border `#38BDF8`, Text `#38BDF8`), Nút `Xuất Markdown (.md)`.

4. **Hero 3D Quantum Intelligence Stage**:
   - **3D Quantum Orb Component (140x140px)**:
     - 2 Vòng quỹ đạo Gyroscopic Rings xoay góc nghiêng (Stroke `#38BDF8` & `#1E4FD8`).
     - Tâm tinh thể đa diện 3D Icosahedron phát sáng màu Cobalt.
     - Ma trận 180 hạt ánh sáng lượng tử bay lơ lửng.
     - Chân đế phát quang Hologram Aura Pedestal tỏa ánh sáng xanh cyan.
   - **Status Tag Pill**: Radius 999px, Fill `#0C1527`, Border `#182744`, Icon Sparkles Cyan, Text: `BlueAI Operational Intelligence v5.2`, Chấm xanh `● Connected`.
   - **Hero Heading**: `Chào bạn, tôi có thể hỗ trợ gì cho ca làm việc hôm nay?` (Font 26px Black `#F8FAFC`).
   - **Hero Subtitle**: `BlueAI tự động kết nối và đối chiếu với kho quy chế, chính sách hoàn hủy, bảng giá cước viễn thông và hệ thống OMS Core theo thời gian thực.` (Font 13.5px `#94A3B8`, max-width 640px).

5. **Grid 4 Thẻ Bento Prompt Cards (Auto-Layout 2x2, Gap: 16px, Card Width: 350px, Height: 120px)**:
   - **Thẻ 1 (CSKH - Emerald Accent)**:
     - Box Icon `36x36px` (Fill `#064E3B`, Border `#10B981`, Icon `$`).
     - Tag: `CHÍNH SÁCH CSKH` (Emerald `#10B981`).
     - Title: `Hoàn tiền eSIM chưa kích hoạt` (Font 14px Bold `#F8FAFC`).
     - Subtitle: `Quy định hủy đơn & điều kiện ICCID Unused theo chuẩn DOC-POL-042`.
     - Arrow Action: Tròn `28x28px` mũi tên `→`.
   - **Thẻ 2 (Telco - Cobalt Accent)**:
     - Box Icon `36x36px` (Fill `#1E3A8A`, Border `#3B82F6`, Icon `⚖`).
     - Tag: `KỸ THUẬT TELCO` (Cobalt `#60A5FA`).
     - Title: `So sánh gói mạng Nhật Bản` (Font 14px Bold `#F8FAFC`).
     - Subtitle: `Docomo phủ sóng 99% đồi núi vs SoftBank 5G tốc độ cao nội đô Tokyo`.
   - **Thẻ 3 (Cài đặt Mạng - Sky Cyan Accent)**:
     - Box Icon `36x36px` (Fill `#0C4A6E`, Border `#38BDF8`, Icon `📶`).
     - Tag: `CÀI ĐẶT MẠNG` (Cyan `#38BDF8`).
     - Title: `Cấu hình APN vmobile.jp` (Font 14px Bold `#F8FAFC`).
     - Subtitle: `Xử lý sự cố không lên sóng 5G khi vừa hạ cánh sân bay quốc tế`.
   - **Thẻ 4 (Kênh Phân phối - Amber Accent)**:
     - Box Icon `36x36px` (Fill `#78350F`, Border `#F59E0B`, Icon `💎`).
     - Tag: `KÊNH PHÂN PHỐI` (Amber `#FBBF24`).
     - Title: `Hạn mức chiết khấu Đại lý` (Font 14px Bold `#F8FAFC`).
     - Subtitle: `Chiết khấu cố định 24% hạng Kim Cương, thưởng quý và credit 50tr`.

6. **Floating Composer (Đáy màn hình - Width: 720px, Height: 96px, Radius: 20px)**:
   - Fill: `#0B1324` (88% Blur), Border: `1.5px solid #2563EB`, Shadow `Glow-Blue-Card`.
   - Top: Input text placeholder `"Hỏi bất kỳ quy chế, chính sách hoàn hủy hoặc gõ '/' để dùng lệnh tắt..."`.
   - Bottom Action Bar:
     - Icon Đính kèm tài liệu `📎`, Icon Ghi âm giọng nói `🎙`, Nút `🌐 Mạng 3D`.
     - Phím tắt hint: `Nhấn Enter ↵ để gửi`.
     - Nút Gửi: Nút tròn `36x28px` Gradient Xanh Cobalt, Mũi tên `↑` màu trắng.

---

#### B. Trạng Thái Trả Lời Trực Tiếp (Active Streaming Chat Frame):
1. **Tin nhắn Người dùng (User Message Bubble)**:
   - Căn phải, Background `#1E4FD8`, Radius `18px` (Góc trên phải `4px`), Text trắng `14px`.
   - Nội dung: *"Khách hàng muốn hoàn tiền gói eSIM Nhật Bản 7 ngày vì chuyến đi bị hoãn, mã QR chưa quét thì chính sách xử lý như thế nào?"*.
2. **Thanh Trạng Thái Streaming (Real-time Stream Bar)**:
   - Khung bo góc `12px`, Fill `#0E1B33`, Border `#38BDF8`.
   - 3 Chấm Stream Cyan nhấp nháy chuyển động.
   - Text: `⚡ Đang quét 1.842 chunk tri thức... → Áp dụng quy chế DOC-POL-042`.
3. **Tin nhắn Trả lời của blueAI (Assistant Message)**:
   - Căn trái, Avatar vuông bo góc `32x32px` Icon Sparkles Cyan.
   - Header tin nhắn: `blueAI` (Bold `#F8FAFC`) • `14:32` • Badge `[✔ 100% Xác thực]` (Màu xanh Emerald).
   - Body Markdown:
     - Văn bản phân tích rõ ràng 3 điều kiện hoàn tiền 100%.
     - **Citation Pills**: Các thẻ nguồn `[DOC-POL-042 - Mục 3.1]` (Bấm vào mở Evidence Drawer).
     - Khối mã Code APN có nút Sao chép `Copy`.
   - Gợi ý câu hỏi tiếp theo (Follow-up chips): `[Quy trình duyệt HITL hoàn tiền]`, `[Xem mẫu hoàn đơn cho khách]`.

---

### 3.2. WORKSPACE 2: `2. Trợ lý Bán hàng & CSKH (Sales Copilot)`

Bố cục 3 cột đồng bộ chuyên nghiệp:

#### Cột 1: Hàng Chờ Xử Lý (Queue List - 300px)
- **Header**: Text `Hàng chờ xử lý`, Badge đếm `3 khách` (Màu cyan).
- **Thẻ Khách Hàng 1 (Active / Đang chọn)**:
  - Fill: `#0E1F3D`, Border: `1.5px solid #38BDF8`, Radius: `16px`.
  - **Monogram Avatar**: Vuông bo `24x24px`, Gradient Xanh Cobalt, Chữ `N` trắng.
  - Tên: `Nguyễn Hoàng Nam` (Bold 13px `#F8FAFC`).
  - Badge SLA: `2 phút` (Đỏ cảnh báo `#EF4444`).
  - Tin nhắn gần nhất: `"Mình vừa hạ cánh Narita chưa có sóng 5G, shop hỗ trợ gấp với!"`.
  - Footer: Kênh `Zalo`, Hạng thành viên `Diamond` (Cyan).
- **Thẻ Khách Hàng 2**: Monogram `M` (`Trần Thị Mai Phương`), Kênh `Website`, Hạng `Gold`, SLA `5 phút`.
- **Thẻ Khách Hàng 3**: Monogram `T` (`Lê Minh Tuấn`), Kênh `Facebook`, Hạng `Diamond`, SLA `12 phút`.

#### Cột 2: Hội Thoại Trực Tiếp (Live Chat Thread - 504px)
- **Customer Header (56px)**:
  - Monogram Badge `32x32px` chữ `N` Gradient.
  - Info: `Nguyễn Hoàng Nam` • Tag `Diamond` • `0908•••129 • eSIM Nhật Bản (SoftBank)`.
  - Right Action: Nút vàng nổi bật `⚠ Yêu cầu hoàn tiền (HITL)` (Border `#F59E0B`).
- **Khung Chat**:
  - Bong bóng tin nhắn khách hàng (Nền `#111C33`, Text xám sáng `#F8FAFC`).
  - Bong bóng phản hồi của CSKH (Nền Gradient Xanh Cobalt `#1E4FD8`, Text trắng).
- **Agent Composer (Đáy cột 2)**:
  - Ô nhập phản hồi kèm nhãn kênh: `Kênh gửi: Zalo Official Account`.
  - Nút `Gửi phản hồi`.

#### Cột 3: blueAI Copilot & Customer 360 (380px)
- **3 Tab Chuyển Đổi**: `[✨ Gợi ý blueAI]` (Active) | `[Customer 360]` | `[So sánh gói]`.
- **Nội dung Tab Gợi ý blueAI**:
  - **Banner Ngữ Cảnh**: Fill `#0E2244`, Border `#2563EB`, Text: `⚡ Ngữ cảnh: Sự cố sóng sân bay Narita (DOC-TECH-108)`.
  - **Thẻ Câu Trả Lời Đề Xuất (Grounded Answer Card)**:
    - Header: `Câu trả lời đề xuất (Grounded)` • Badge `[✔ Đã xác thực]`.
    - Box text gợi ý: Soạn sẵn 2 bước hướng dẫn bật Data Roaming và APN `vmobile.jp`.
    - Nút Hành Động Lớn: `↵ Chèn vào ô trả lời` (Fill `#1E4FD8`, Radius 10px, Height 36px).
    - Link Căn cứ: `Xem căn cứ [DOC-TECH-108]`.
  - **Mẫu xử lý nhanh (Quick Objections)**: 3 nút mẫu:
    - `Hướng dẫn bật Roaming trên iOS & Android →`
    - `Chính sách hoàn tiền 100% khi lỗi trạm →`
    - `Mẫu xin lỗi & gửi voucher bồi thường 50k →`
- **Nội dung Tab Customer 360**:
  - Thẻ thông tin gói: `eSIM Nhật Bản 7 Ngày 5G (SoftBank)`.
  - Dung lượng đã dùng: `1.2GB / 3.0GB`.
  - Hạn dùng: `30/06/2026` • Mã ICCID: `8981004921094821`.
  - Điểm hài lòng: `4.9 / 5.0 ⭐ (CSAT)`.
- **Nội dung Tab So sánh gói**:
  - Bảng so sánh 3 tiêu chí giữa `SoftBank 5G` (450 Mbps, 220k) và `Docomo Pro` (Phủ sóng 99% núi, 260k).

---

### 3.3. WORKSPACE 3: `3. Quản lý Tài liệu & Tri thức RAG`
- **Thanh Công Cụ**: Search input tra cứu 42 chính sách, Bộ lọc Pill: `[Tất cả 42]` | `[Đang hiệu lực 38]` | `[Sắp hết hạn 3]` | `[Bản nháp 1]`.
- **Bảng Danh Mục Quy Chế (Data Table)**:
  - Cột 1: `Mã tài liệu` (`DOC-POL-042`, `DOC-TECH-108`, `DOC-B2B-015`).
  - Cột 2: `Tên quy chuẩn chính sách` (Ví dụ: *Quy chế hoàn hủy eSIM Unused*).
  - Cột 3: `Số Chunks nhúng` (`24 Chunks`, Vector 1536-dim).
  - Cột 4: `Lượt truy vấn RAG` (`1.420 hits`).
  - Cột 5: `Trạng thái` (Badge xanh: `Đang hiệu lực`).

---

### 3.4. WORKSPACE 4: `4. Quản trị Hệ thống (Admin Gateway)`
- **4 Thẻ Đo Lường Chỉ Số Thời Gian Thực (KPIs Metric Cards)**:
  1. `Tổng số yêu cầu hôm nay`: **14,820 requests** (Badge: `+18.4%`).
  2. `Độ trễ trung bình`: **185 ms** (Badge: `Cực nhanh`).
  3. `Độ chính xác RAG`: **98.4%** (Badge: `RAG Grounded`).
  4. `Chi phí Token hôm nay`: **$12.45 USD** (Tiết kiệm 42% nhờ Router).
- **Phân Bổ Điều Hướng Model (Model Routing Distribution)**:
  - Thanh đo tải lượng: `GPT-4o (65%)` | `Claude 3.5 Sonnet (25%)` | `Gemini 1.5 Flash (10%)`.
- **Bảng Ma Trận Phân Quyền RBAC**: Quản lý quyền theo vai trò nhân sự.

---

### 3.5. WORKSPACE 5: `5. Cổng Đại lý Phân phối`
- **Thẻ Hạng Thành Viên Đại Lý (Diamond Tier Card)**:
  - Header: `GoTravel International (Đại lý Cấp 1 - Hạng Kim Cương)`.
  - Mức chiết khấu cố định: **24%**.
  - Hạn mức công nợ tín dụng: **50.000.000 VNĐ**.
  - Doanh số tháng: **128.500.000 VNĐ** (Đạt 114% chỉ tiêu quý).
- **Bảng Giá Sỉ & Xuất Kho eSIM Hàng Loạt (Wholesale Catalog)**:
  - Danh mục: Nhật Bản 7D, Hàn Quốc 5D, Thái Lan 10D, Châu Âu 33 Nước 15D.
  - Cột: Giá bán lẻ | Giá sỉ đại lý (-24%) | Biên lợi nhuận.
  - Action: Nút `Xuất kho mã eSIM loạt` (Cấp 50 mã QR tức thì).

---

### 3.6. WORKSPACE 6: `6. Trung tâm Hỗ trợ Khách hàng`
- **Thẻ eSIM 3D Hologram & Mã QR Kích Hoạt**:
  - Khung thẻ Hologram với mã QR nét cao kích thước `160x160px`.
  - Vạch quét laser sáng cyan chuyển động dọc mã QR.
  - Tên gói: `eSIM Nhật Bản 7 Ngày 5G (SoftBank)` • Mã đơn: `#ORD-JP-98214`.
  - Nút: `Lưu ảnh mã QR về điện thoại`.
- **Hướng Dẫn 3 Bước Kích Hoạt Nhanh**:
  - `Bước 1`: Quét mã QR (`Cài đặt → Di động → Thêm eSIM`).
  - `Bước 2`: Đặt tên thẻ SIM (`Bluecom Du lịch`).
  - `Bước 3`: Bật Data Roaming khi hạ cánh sân bay Narita/Kansai.
- **Trợ Lý Chat Mini Trực Tuyến**: Khung chat hỗ trợ tương thích thiết bị 24/7.

---

## 4. ĐẶC TẢ CHI TIẾT CÁC MODAL & DRAWERS TƯƠNG TÁC

### 4.1. Modal Mạng 3D Tri Thức (Knowledge Graph 3D Modal - 800 x 600 px)
- **Vị Trí**: Center Overlay (Nền tối làm mờ `rgba(0,0,0,0.65)`, Blur `16px`).
- **Khung Modal**: Radius `24px`, Fill `#0B1324`, Border `1.5px solid #38BDF8`.
- **Nội Dung 3D**: Không gian 3 chiều Three.js chứa 6 Node chính sách kết nối bằng các tia laser phát sáng:
  - Node Trung Tâm: `Bluecom Core Hub`.
  - Node 1: `DOC-POL-042 (Hoàn hủy eSIM)`.
  - Node 2: `DOC-TECH-108 (Cấu hình APN SoftBank)`.
  - Node 3: `DOC-B2B-019 (Chiết khấu Đại lý)`.
- **Tương Tác**: Rê chuột xoay 3D, nhấp vào node để mở thẳng Evidence Drawer.

### 4.2. Evidence Drawer (Trượt từ phải sang - 380 x 900 px)
- **Vị Trí**: Top-Right Overlay, trượt từ cạnh phải `Right to Left` (300ms).
- **Khung Drawer**: Width `380px`, Height `900px`, Fill `#0B1324`, Border Left `#182744`.
- **Nội Dung**:
  - Header: Mã văn bản `DOC-POL-042` • Huy hiệu `Đang hiệu lực`.
  - Trích đoạn nguyên văn điều khoản: `Mục 3.1: Điều kiện hoàn tiền 100% khi mã ICCID Unused`.
  - So sánh phiên bản (Version Diff): `v3.2 (Hiện tại)` vs `v3.1 (Cũ)`.
  - Thông tin quản trị: Người duyệt `Phạm Hải Đăng`, Ngày hết hạn `31/12/2026`.

### 4.3. Dialog Phê Duyệt Hoàn Tiền (HITL Review Modal - 520 x 440 px)
- **Vị Trí**: Center Overlay.
- **Khung Modal**: Radius `20px`, Fill `#0E1A33`, Border `1.5px solid #F59E0B`.
- **Nội Dung**:
  - Header: Icon Cảnh báo vàng `⚠`, Tiêu đề `Xác nhận Phê duyệt Hoàn tiền (HITL)`.
  - Bảng thông tin:
    - Khách hàng: `Nguyễn Hoàng Nam`
    - Mã đơn hàng: `#ORD-JP-98214`
    - Số tiền hoàn lại: `350.000 VNĐ`
    - Căn cứ quy chế: `DOC-POL-042 (Mục 3.2)`
    - Lý do: `Sự cố lỗi mạng viễn thông trạm Narita không kết nối được sau 2 giờ`.
  - Nút Hành Động: Nút Đỏ `Từ chối yêu cầu` & Nút Xanh Emerald `Chấp thuận & Hoàn tiền ngay`.

---

## 5. SƠ ĐỒ & KỊCH BẢN NỐI DÂY INTERACTIVE PROTOTYPE TRÊN FIGMA

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 FIGMA PROTOTYPE WIREFLOW MAP                           │
└────────────────────────────────────────────────────────────────────────────────────────┘

[Frame 1: Workspace_Chat_Empty_State]
    │
    ├── (On Click: Thẻ Bento 1 "Hoàn tiền") ──► [Frame 2: Workspace_Chat_Streaming]
    │                                                    │
    │                                                    │ (After Delay: 600ms - Smart Animate)
    │                                                    ▼
    │                                           [Frame 3: Workspace_Chat_Answer_Ready]
    │                                                    │
    │                                                    ├── (On Click: Pill "[DOC-POL-042]") ──► [Overlay: Drawer_Evidence_380px]
    │                                                    └── (On Click: Nút "🌐 Mạng 3D")    ──► [Overlay: Modal_Knowledge_Graph_3D]
    │
    ├── (On Click: Dropdown Role -> Chọn "Sales") ──► [Frame 4: Workspace_Sales_Copilot]
    │                                                         │
    │                                                         ├── (On Click: Thẻ Queue 2) ──► Đổi cuộc chat sang Khách 2
    │                                                         ├── (On Click: "↵ Chèn vào ô") ──► Điền text vào Composer
    │                                                         └── (On Click: "⚠ Yêu cầu hoàn tiền") ──► [Overlay: Dialog_HITL_Modal]
    │
    ├── (On Click: Role -> Chọn "Đại lý") ──────────► [Frame 5: Workspace_Reseller_Portal]
    │
    └── (On Click: Role -> Chọn "Khách hàng") ──────► [Frame 6: Workspace_Customer_Portal]
```

### Bảng Cấu Hình Tham Số Tương Tác Chi Tiết (Prototype Interaction Matrix):

| Tên Tương Tác | Đối Tượng Bấm (Source Node) | Hành Động (Action) | Đích Đến (Destination) | Hiệu Ứng Chuyển Động (Animation) | Thời Gian (Duration) |
|---|---|---|---|---|---|
| **Bấm Thẻ Bento 1** | Card `Hoàn tiền eSIM` | `Navigate to` | `Workspace_Chat_Streaming` | `Smart Animate`, Ease Out | `250 ms` |
| **Hoàn Tất Stream** | `Workspace_Chat_Streaming` | `After Delay (600ms)` | `Workspace_Chat_Answer_Ready` | `Smart Animate`, Ease In/Out | `300 ms` |
| **Mở Căn Cứ Trích Dẫn**| Pill `[DOC-POL-042]` | `Open Overlay` | `Drawer_Evidence_380px` | `Move In` (Right to Left) | `300 ms` |
| **Mở Đồ Thị 3D** | Nút `🌐 Mạng 3D Tri thức` | `Open Overlay` | `Modal_Knowledge_Graph_3D` | `Dissolve` (Centered) | `200 ms` |
| **Mở Phê Duyệt HITL** | Nút `⚠ Yêu cầu hoàn tiền` | `Open Overlay` | `Dialog_HITL_Approval` | `Dissolve` (Centered) | `200 ms` |
| **Chuyển Tab Copilot** | Tab `Customer 360` | `Change To` | Frame Variant `Tab_360_Active` | `Instant` | `0 ms` |
| **Chuyển Vai Trò Demo**| Dropdown Item `2. Sales Copilot` | `Navigate to` | `Workspace_Sales_Copilot` | `Dissolve` | `150 ms` |

---

## 6. MASTER PROMPT TIẾNG ANH CHUẨN CHO FIGMA AI (FIGMA MAKE)

Bạn hãy sao chép nguyên văn đoạn văn bản dưới đây và dán trực tiếp vào **Figma AI (Figma Make / First Draft)**:

```text
Create a comprehensive, ultra-high-end enterprise AI Operations & Telecommunications platform named "blueAI" at 1440x900 desktop resolution.

1. Design System & Theme:
- Brand Name: "blueAI" (Subtitle: "Trí tuệ Vận hành Doanh nghiệp").
- Dark Void Theme: Canvas #060B14, Surface Cards #0B1324 and #0E1A33, 1px Divider #182744.
- Brand Colors: Electric Cobalt Blue (#1E4FD8 & #2563EB), Cyber Ice Cyan (#38BDF8 & #0EA5E9).
- Status Tags: Emerald (#10B981) for Grounded Verified RAG claims, Amber (#F59E0B) for inferred knowledge, Rose (#EF4444) for HITL financial refund triggers.
- Typography: Be Vietnam Pro for headings and UI copy, IBM Plex Mono for order IDs, ICCID, latency, and token metrics.

2. Frame Layout Hierarchy (Desktop 1440x900px):
- Left Navigation Rail (256px width): Logo blueAI, 6 Workspace navigation items (1. Hỏi BlueAI, 2. Trợ lý Bán hàng & CSKH, 3. Tài liệu & Tri thức RAG, 4. Quản trị Hệ thống, 5. Cổng Đại lý Phân phối, 6. Trung tâm Hỗ trợ Khách hàng), "+ Cuộc trò chuyện mới" button, recent history list, user profile at footer.
- Top Header Bar (56px height): Organization badge "Bluecom Enterprise Core | Toàn hệ thống", Role switcher dropdown pill, Dark/Light mode toggle, Notification bell.
- Main Workspace (1184px width):
  * Empty State: 3D Quantum Gyroscopic Core centered inside a glowing radial cyan aura.
  * Status tag: "✨ BlueAI Operational Intelligence v5.2 • Connected".
  * Heading: "Chào bạn, tôi có thể hỗ trợ gì cho ca làm việc hôm nay?".
  * 4 Bento Prompt Cards 3D (Hoàn tiền eSIM, So sánh mạng Nhật Bản, Cấu hình APN vmobile.jp, Hạn mức chiết khấu Đại lý) with glass neon badges and hover lift depth.
  * Floating frosted glass composer at bottom with voice input, attachment OCR, 3D graph trigger, and send button.

3. Complete Interactive Prototype Wireflow:
- Clicking Bento Card 1 triggers Smart Animate navigation to the streaming AI response.
- Clicking citation pills opens a 380px slide-in Evidence Drawer from the right.
- Clicking "🌐 Mạng 3D Tri thức" opens a centered 800x600px glassmorphic 3D Knowledge Graph modal.
- In Sales Copilot, clicking "⚠ Yêu cầu hoàn tiền (HITL)" opens a centered financial approval modal dialog.
```
