import { CustomerQueueItem, PolicyDocument, AdminSystemMetrics, ChatSession, Citation } from '../types';

export const INITIAL_CITATIONS: Record<string, Citation> = {
  'cit-1': {
    id: 'cit-1',
    sourceId: 'DOC-POL-042',
    title: 'Chính sách Hoàn hủy & Đổi gói cước eSIM Quốc tế',
    section: 'Mục 3.2 — Điều kiện hoàn tiền 100% khi chưa quét QR',
    version: 'v4.2.0 (Hiệu lực từ 15/01/2026)',
    effectiveDate: '15/01/2026',
    expiryDate: '31/12/2026',
    owner: 'Phòng Vận hành Sản phẩm & Đối tác Viễn thông',
    scope: 'Toàn hệ thống Bluecom (B2B, B2C, Sales nội bộ)',
    snippet: 'Khách hàng được quyền yêu cầu hoàn tiền 100% giá trị gói cước nếu mã eSIM chưa được cài đặt trên thiết bị (trạng thái ICCID: Released / Unused) và yêu cầu được gửi trong vòng 14 ngày kể từ thời điểm phát hành đơn hàng. Thao tác hoàn tiền vượt quá 200.000 VNĐ bắt buộc phải qua xác thực Trưởng nhóm CSKH (HITL Review).',
    status: 'valid',
  },
  'cit-2': {
    id: 'cit-2',
    sourceId: 'DOC-TECH-108',
    title: 'Quy chuẩn kỹ thuật mạng SoftBank & Docomo Nhật Bản 2026',
    section: 'Mục 4.1 — Tương thích băng tần APN 5G',
    version: 'v3.1.0',
    effectiveDate: '01/02/2026',
    owner: 'Đội ngũ Kỹ thuật Mạng Quốc tế',
    scope: 'Khu vực Đông Bắc Á (Nhật Bản, Hàn Quốc)',
    snippet: 'Đối với eSIM du lịch Nhật Bản mạng SoftBank, thiết bị cần mở khóa mạng (quốc tế) và hỗ trợ băng tần LTE Band 1 (2100MHz), Band 8 (900MHz) hoặc Band 3 (1800MHz). APN tự động nhận diện: `vmobile.jp`. Trường hợp không tự kích hoạt, nhân viên CSKH hướng dẫn khách bật Chuyển vùng dữ liệu (Data Roaming).',
    status: 'valid',
  },
  'cit-3': {
    id: 'cit-3',
    sourceId: 'DOC-B2B-019',
    title: 'Chính sách Chiết khấu & Hạn mức Tín dụng Đại lý Cấp 1',
    section: 'Mục 2.4 — Hoa hồng theo bậc doanh số tháng',
    version: 'v2.8.0',
    effectiveDate: '01/01/2026',
    owner: 'Khối Phát triển Kênh B2B',
    scope: 'Đại lý Vàng & Kim Cương',
    snippet: 'Đại lý đạt mức doanh số trên 50.000.000 VNĐ/tháng được hưởng mức chiết khấu bổ sung 4.5% trên giá niêm yết cho tất cả các gói cước khu vực Châu Á. Quyền cấp credit line 30 ngày chỉ áp dụng cho tài khoản đã xác thực KYC doanh nghiệp.',
    status: 'valid',
  }
};

export const INITIAL_CHAT_SESSIONS: ChatSession[] = [
  {
    id: 'sess-1',
    title: 'Quy định hoàn tiền eSIM chưa kích hoạt',
    role: 'employee',
    category: 'today',
    isPinned: true,
    updatedAt: '10 phút trước',
    messages: [
      {
        id: 'm-1',
        sender: 'user',
        content: 'Khách hàng muốn hoàn tiền gói eSIM Nhật Bản 7 ngày vì chuyến đi bị hoãn, mã QR chưa quét thì chính sách xử lý như thế nào?',
        timestamp: '14:20',
      },
      {
        id: 'm-2',
        sender: 'assistant',
        content: 'Theo quy định hiện hành tại **Chính sách Hoàn hủy eSIM Quốc tế**, trường hợp này đủ điều kiện để **hoàn tiền 100%** cho khách hàng.\n\n### Chi tiết điều kiện áp dụng:\n1. **Trạng thái mã QR**: Mã eSIM chưa từng được quét/cài đặt lên bất kỳ thiết bị nào (Hệ thống Core sẽ kiểm tra ICCID ở trạng thái *Unused*).\n2. **Thời hạn yêu cầu**: Yêu cầu được gửi trong vòng **14 ngày** kể từ ngày tạo đơn hàng thành công.\n3. **Quy trình thực hiện**: Nhân viên CSKH tạo yêu cầu hoàn tiền trên hệ thống $\\rightarrow$ Hệ thống gửi duyệt **HITL (Human-in-the-loop)** tới Trưởng ca $\\rightarrow$ Sau khi duyệt, mã QR sẽ bị hủy và tiền chuyển về phương thức thanh toán ban đầu của khách trong 1-3 ngày làm việc.',
        timestamp: '14:21',
        confidence: 'confirmed',
        citations: [INITIAL_CITATIONS['cit-1']],
        toolsUsed: ['OMS_Check_Policy', 'RAG_Vector_Search'],
        suggestedFollowUps: [
          'Tạo yêu cầu hoàn tiền cho đơn hàng cụ thể',
          'Kiểm tra trạng thái ICCID của khách qua mã đơn',
          'Mẫu tin nhắn trả lời khách hàng về thời gian nhận tiền'
        ]
      }
    ]
  },
  {
    id: 'sess-2',
    title: 'So sánh gói cước Nhật Bản 5G SoftBank vs Docomo',
    role: 'employee',
    category: 'today',
    updatedAt: '1 giờ trước',
    messages: [
      {
        id: 'm-3',
        sender: 'user',
        content: 'Khách đi Tokyo và núi Phú Sĩ nên tư vấn gói SoftBank hay Docomo?',
        timestamp: '13:05',
      },
      {
        id: 'm-4',
        sender: 'assistant',
        content: 'Khuyên dùng **Mạng Docomo** cho hành trình có đi núi Phú Sĩ và các vùng ngoại ô, và **SoftBank** nếu khách chủ yếu hoạt động tại trung tâm đô thị Tokyo.\n\n- **Docomo (Khuyên dùng)**: Độ phủ sóng vùng đồi núi, rừng quốc gia và các trạm dừng chân Phú Sĩ vượt trội hơn 20% so với SoftBank.\n- **SoftBank**: Tốc độ 5G nội đô Tokyo rất cao và ổn định, giá thành tiết kiệm hơn ~10-15%.',
        timestamp: '13:06',
        confidence: 'inferred',
        citations: [INITIAL_CITATIONS['cit-2']],
        toolsUsed: ['Telco_Coverage_Map', 'Catalog_Price_Lookup'],
        suggestedFollowUps: [
          'Xem bảng giá chi tiết 2 gói Docomo và SoftBank',
          'Gửi hướng dẫn cấu hình APN vmobile.jp'
        ]
      }
    ]
  },
  {
    id: 'sess-3',
    title: 'Hạn mức chiết khấu Đại lý Kim Cương',
    role: 'employee',
    category: 'last_7_days',
    updatedAt: '2 ngày trước',
    messages: []
  }
];

export const MOCK_CUSTOMER_QUEUE: CustomerQueueItem[] = [
  {
    id: 'queue-1',
    customerName: 'Nguyễn Hoàng Nam',
    channel: 'Zalo',
    lastMessage: 'Mình vừa hạ cánh sân bay Narita nhưng bật mạng chưa thấy có sóng 5G, shop hỗ trợ gấp với!',
    waitTime: '2 phút',
    priority: 'high',
    slaStatus: 'warning',
    assignedTo: 'Hà CSKH',
    unreadCount: 2,
    customerData: {
      id: 'CUST-8812',
      name: 'Nguyễn Hoàng Nam',
      phone: '0988 ••• 129',
      email: 'nam.nguyen•••@gmail.com',
      tier: 'Diamond',
      avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80',
      activeOrder: {
        orderId: 'ORD-JP-98214',
        plan: 'eSIM Nhật Bản 7 Ngày 5G Không Giới Hạn',
        country: 'Nhật Bản (Tokyo/Osaka)',
        status: 'active',
        dataUsed: '1.2 GB',
        totalData: 'Không giới hạn (FUP 3GB/ngày)',
        expiry: '04/03/2026',
        iccid: '8984040000008819234'
      },
      pastOrdersCount: 8,
      openIssuesCount: 1,
      sentiment: 'frustrated'
    }
  },
  {
    id: 'queue-2',
    customerName: 'Trần Thị Mai Phương',
    channel: 'Website',
    lastMessage: 'Em ơi cho chị hỏi mã QR này gửi cho bạn chị quét ở máy khác được không?',
    waitTime: '5 phút',
    priority: 'medium',
    slaStatus: 'ok',
    assignedTo: 'Hà CSKH',
    unreadCount: 1,
    customerData: {
      id: 'CUST-4509',
      name: 'Trần Thị Mai Phương',
      phone: '0912 ••• 884',
      email: 'mai.phuong•••@vnn.vn',
      tier: 'Gold',
      avatar: 'https://images.unsplash.com/photo-1580489944761-15a19d654956?w=100&auto=format&fit=crop&q=80',
      activeOrder: {
        orderId: 'ORD-KR-55102',
        plan: 'eSIM Hàn Quốc 5 Ngày Data Tốc độ cao',
        country: 'Hàn Quốc (Seoul)',
        status: 'pending',
        dataUsed: '0 GB',
        totalData: '10 GB',
        expiry: '10/03/2026',
        iccid: '8982050000004491029'
      },
      pastOrdersCount: 3,
      openIssuesCount: 0,
      sentiment: 'neutral'
    }
  },
  {
    id: 'queue-3',
    customerName: 'Lê Minh Tuấn (Đại lý GoTravel)',
    channel: 'Facebook',
    lastMessage: 'Bên mình muốn xuất hóa đơn VAT cho 20 mã eSIM đi Thái Lan xuất hôm qua.',
    waitTime: '12 phút',
    priority: 'normal',
    slaStatus: 'ok',
    assignedTo: 'Minh Tuấn B2B',
    unreadCount: 0,
    customerData: {
      id: 'CUST-B2B-102',
      name: 'Lê Minh Tuấn',
      phone: '0903 ••• 991',
      email: 'tuan.le@gotravel•••.vn',
      tier: 'Diamond',
      avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&auto=format&fit=crop&q=80',
      pastOrdersCount: 45,
      openIssuesCount: 0,
      sentiment: 'positive'
    }
  }
];

export const MOCK_DOCUMENTS: PolicyDocument[] = [
  {
    id: 'DOC-POL-042',
    title: 'Chính sách Hoàn hủy & Đổi gói cước eSIM Quốc tế 2026',
    category: 'Chính sách CSKH & Đơn hàng',
    version: 'v4.2.0',
    lastUpdated: '15/01/2026',
    expiryDate: '31/12/2026',
    owner: 'Nguyễn Thu Trang (CS Lead)',
    status: 'active',
    chunksCount: 24,
    retrievalHitCount: 1420,
    scope: ['B2C', 'B2B', 'Internal Sales']
  },
  {
    id: 'DOC-TECH-108',
    title: 'Hướng dẫn Kỹ thuật Xử lý Sự cố Sóng & Cấu hình APN eSIM',
    category: 'Tài liệu Kỹ thuật & Telco',
    version: 'v3.1.0',
    lastUpdated: '01/02/2026',
    expiryDate: '30/06/2026',
    owner: 'Vũ Đức Thịnh (Network Lead)',
    status: 'active',
    chunksCount: 38,
    retrievalHitCount: 2890,
    scope: ['CSKH', 'NOC', 'B2C Helpdesk']
  },
  {
    id: 'DOC-B2B-019',
    title: 'Chính sách Chiết khấu & Thưởng Quý Đại lý B2B Bluecom',
    category: 'Chính sách Kênh Phân Phối',
    version: 'v2.8.0',
    lastUpdated: '01/01/2026',
    expiryDate: '31/03/2026',
    owner: 'Phạm Hải Đăng (B2B Director)',
    status: 'expiring_soon',
    chunksCount: 16,
    retrievalHitCount: 830,
    scope: ['Reseller', 'Sales B2B']
  },
  {
    id: 'DOC-PROD-201',
    title: 'Bảng Thông số Kỹ thuật Toàn bộ Gói cước Châu Âu 33 Nước',
    category: 'Thông số Sản phẩm',
    version: 'v5.0.1',
    lastUpdated: '20/02/2026',
    expiryDate: '31/12/2026',
    owner: 'Đặng Mai Lan (Product Owner)',
    status: 'active',
    chunksCount: 52,
    retrievalHitCount: 3100,
    scope: ['All']
  }
];

export const MOCK_ADMIN_METRICS: AdminSystemMetrics = {
  totalRequestsToday: 14820,
  avgLatencyMs: 412,
  ragAccuracyScore: 98.4,
  tokenCostTodayUsd: 18.65,
  activeUsersNow: 86,
  hitlActionsPending: 3,
  modelsRouting: [
    { name: 'Gemini 1.5 Pro / Flash Enterprise', provider: 'Google Vertex AI', sharePercent: 68, avgCostPer1k: '$0.00035' },
    { name: 'Claude 3.5 Sonnet (Audit & Legal)', provider: 'Anthropic AWS Bedrock', sharePercent: 22, avgCostPer1k: '$0.00300' },
    { name: 'Local Blue-Embedding v3 (On-Prem)', provider: 'Bluecom Private Cluster', sharePercent: 10, avgCostPer1k: '$0.00000' },
  ]
};
