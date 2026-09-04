# Chính sách và Quy trình eSIM Bluecom — Bản nội bộ 2026.09

> Tài liệu nội bộ dùng để kiểm thử pipeline RAG. Số liệu trong đây là dữ liệu kiểm thử,
> không phải chính sách thương mại đang hiệu lực.

## 1. Danh mục gói dữ liệu quốc tế

| Mã gói | Tên thương mại | Vùng | Dung lượng tốc độ cao | Hiệu lực | Giá niêm yết |
|---|---|---|---|---|---|
| BLUE-ESIM-JP15 | Sao Băng 47 | Nhật Bản | 15 GB mỗi tháng | 30 ngày | 429.000 VND |
| BLUE-ESIM-KR10 | Sao Băng 32 | Hàn Quốc | 10 GB mỗi tháng | 30 ngày | 359.000 VND |
| BLUE-ESIM-EU25 | Thiên Hà 25 | 32 nước châu Âu | 25 GB mỗi tháng | 45 ngày | 1.190.000 VND |

Sau khi dùng hết dung lượng tốc độ cao, thuê bao bị hạ băng thông xuống 512 Kbps đến
hết chu kỳ, không tự động trừ thêm tiền.

## 2. Điều kiện hoàn tiền

Khách hàng được hoàn 100% giá gói khi thỏa mãn đồng thời hai điều kiện:

1. Yêu cầu được gửi trong vòng **7 ngày** kể từ thời điểm thanh toán.
2. Mã QR của eSIM **chưa được quét** để kích hoạt trên thiết bị.

Nếu eSIM đã kích hoạt nhưng chưa phát sinh quá 500 MB lưu lượng, khách hàng được hoàn
**50%** giá gói trong vòng 3 ngày đầu. Quá 500 MB hoặc quá 7 ngày thì không hoàn tiền.

Thời gian xử lý hoàn tiền là **5 ngày làm việc** kể từ khi bộ phận CSKH duyệt yêu cầu,
tiền về theo phương thức thanh toán gốc. Yêu cầu hoàn tiền phải do chính chủ đơn hàng gửi.

## 3. Quy trình đổi thiết bị

eSIM đã kích hoạt gắn với một thiết bị. Khi khách hàng đổi máy:

1. Gửi yêu cầu kèm số đơn hàng và IMEI thiết bị mới qua kênh hỗ trợ.
2. CSKH kiểm tra lịch sử kích hoạt, thời gian phản hồi cam kết là **4 giờ làm việc**.
3. Hệ thống phát hành mã QR mới, mã cũ bị vô hiệu ngay lập tức.

Mỗi thuê bao được đổi thiết bị tối đa **2 lần** trong một chu kỳ 30 ngày. Lần thứ ba trở
đi thu phí hành chính 99.000 VND cho mỗi lần phát hành lại.

## 4. Mã lỗi kích hoạt thường gặp

- **ESIM-401**: mã QR đã được sử dụng trên thiết bị khác. Cần yêu cầu phát hành lại.
- **ESIM-409**: thiết bị không hỗ trợ eSIM hoặc đang bị khóa mạng. Kiểm tra IMEI trước.
- **ESIM-503**: nhà mạng đối tác tạm thời không cấp phát được profile, thử lại sau 30 phút.

Nếu khách hàng gặp ESIM-503 quá 3 lần trong 24 giờ, CSKH phải chuyển ticket lên bộ phận
Vận hành đối tác trong vòng 1 giờ.

## 5. Kênh hỗ trợ và SLA

| Kênh | Thời gian hoạt động | SLA phản hồi đầu tiên |
|---|---|---|
| Hotline 1900 6060 | 07:00 – 22:00 hàng ngày | 3 phút |
| Zalo OA Bluecom | 08:00 – 21:00 hàng ngày | 15 phút |
| Email cskh@bluecom.example | 24/7 tiếp nhận | 8 giờ làm việc |

Ticket mức độ nghiêm trọng cao (khách hàng đang ở nước ngoài và mất kết nối hoàn toàn)
phải được phản hồi trong **30 phút**, bất kể kênh tiếp nhận.

## 6. Giới hạn cam kết

Nhân viên không được cam kết vùng phủ sóng cụ thể của nhà mạng đối tác, không được hứa
thời gian hoàn tiền ngắn hơn 5 ngày làm việc, và không được tự quyết miễn phí hành chính
đổi thiết bị mà chưa có phê duyệt của Trưởng nhóm CSKH.
