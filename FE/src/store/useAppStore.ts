import { useState, useEffect } from 'react';
import { UserRole, ChatSession, Citation, AIStreamStatus, CustomerQueueItem, ConfidenceLevel } from '../types';
import { INITIAL_CHAT_SESSIONS, MOCK_CUSTOMER_QUEUE, INITIAL_CITATIONS } from '../data/mockData';

export interface AppState {
  activeRole: UserRole;
  setActiveRole: (role: UserRole) => void;
  
  isDarkMode: boolean;
  toggleDarkMode: () => void;
  
  isSidebarCollapsed: boolean;
  toggleSidebar: () => void;
  
  sessions: ChatSession[];
  activeSessionId: string;
  setActiveSessionId: (id: string) => void;
  createNewSession: () => void;
  deleteSession: (id: string) => void;
  togglePinSession: (id: string) => void;
  
  streamStatus: AIStreamStatus;
  currentStreamActivity: string;
  sendMessage: (text: string) => void;
  
  selectedCitation: Citation | null;
  isEvidenceOpen: boolean;
  openEvidence: (citation: Citation) => void;
  closeEvidence: () => void;
  
  // Sales Copilot state
  customerQueue: CustomerQueueItem[];
  activeQueueItem: CustomerQueueItem | null;
  setActiveQueueItem: (item: CustomerQueueItem) => void;
  salesComposerText: string;
  setSalesComposerText: (text: string) => void;
  insertToSalesComposer: (text: string) => void;
  
  // HITL state
  isHITLModalOpen: boolean;
  hitlActionData: {
    title: string;
    customerName: string;
    orderId: string;
    amount: string;
    policyRef: string;
    reason: string;
  } | null;
  openHITLModal: (data: { title: string; customerName: string; orderId: string; amount: string; policyRef: string; reason: string }) => void;
  closeHITLModal: () => void;
  approveHITL: () => void;
  
  // Notification Toast
  toast: { message: string; type: 'success' | 'info' | 'warning' } | null;
  showToast: (message: string, type?: 'success' | 'info' | 'warning') => void;
}

let state: {
  activeRole: UserRole;
  isDarkMode: boolean;
  isSidebarCollapsed: boolean;
  sessions: ChatSession[];
  activeSessionId: string;
  streamStatus: AIStreamStatus;
  currentStreamActivity: string;
  selectedCitation: Citation | null;
  isEvidenceOpen: boolean;
  customerQueue: CustomerQueueItem[];
  activeQueueItem: CustomerQueueItem | null;
  salesComposerText: string;
  isHITLModalOpen: boolean;
  hitlActionData: any;
  toast: { message: string; type: 'success' | 'info' | 'warning' } | null;
} = {
  activeRole: 'employee',
  isDarkMode: false,
  isSidebarCollapsed: false,
  sessions: INITIAL_CHAT_SESSIONS,
  activeSessionId: 'sess-1',
  streamStatus: 'idle',
  currentStreamActivity: '',
  selectedCitation: null,
  isEvidenceOpen: false,
  customerQueue: MOCK_CUSTOMER_QUEUE,
  activeQueueItem: MOCK_CUSTOMER_QUEUE[0],
  salesComposerText: '',
  isHITLModalOpen: false,
  hitlActionData: null,
  toast: null,
};

const listeners = new Set<() => void>();

function notify() {
  listeners.forEach(fn => fn());
}

export function useAppStore(): AppState {
  const [, setTick] = useState(0);

  useEffect(() => {
    const handler = () => setTick(t => t + 1);
    listeners.add(handler);
    return () => {
      listeners.delete(handler);
    };
  }, []);

  const setActiveRole = (role: UserRole) => {
    state.activeRole = role;
    notify();
  };

  const toggleDarkMode = () => {
    state.isDarkMode = !state.isDarkMode;
    if (state.isDarkMode) {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
    notify();
  };

  const toggleSidebar = () => {
    state.isSidebarCollapsed = !state.isSidebarCollapsed;
    notify();
  };

  const setActiveSessionId = (id: string) => {
    state.activeSessionId = id;
    notify();
  };

  const createNewSession = () => {
    const newId = `sess-${Date.now()}`;
    const newSession: ChatSession = {
      id: newId,
      title: 'Cuộc trò chuyện mới',
      role: state.activeRole,
      category: 'today',
      updatedAt: 'Vừa xong',
      messages: []
    };
    state.sessions = [newSession, ...state.sessions];
    state.activeSessionId = newId;
    notify();
  };

  const deleteSession = (id: string) => {
    state.sessions = state.sessions.filter(s => s.id !== id);
    if (state.activeSessionId === id && state.sessions.length > 0) {
      state.activeSessionId = state.sessions[0].id;
    }
    showToast('Đã xóa cuộc trò chuyện', 'info');
    notify();
  };

  const togglePinSession = (id: string) => {
    state.sessions = state.sessions.map(s => {
      if (s.id === id) {
        return { ...s, isPinned: !s.isPinned };
      }
      return s;
    });
    showToast('Đã cập nhật trạng thái ghim', 'info');
    notify();
  };

  const openEvidence = (citation: Citation) => {
    state.selectedCitation = citation;
    state.isEvidenceOpen = true;
    notify();
  };

  const closeEvidence = () => {
    state.isEvidenceOpen = false;
    notify();
  };

  const setActiveQueueItem = (item: CustomerQueueItem) => {
    state.activeQueueItem = item;
    notify();
  };

  const setSalesComposerText = (text: string) => {
    state.salesComposerText = text;
    notify();
  };

  const insertToSalesComposer = (text: string) => {
    state.salesComposerText = text;
    showToast('Đã chèn nội dung vào khung trả lời khách hàng', 'success');
    notify();
  };

  const showToast = (message: string, type: 'success' | 'info' | 'warning' = 'success') => {
    state.toast = { message, type };
    notify();
    setTimeout(() => {
      state.toast = null;
      notify();
    }, 3500);
  };

  const openHITLModal = (data: any) => {
    state.hitlActionData = data;
    state.isHITLModalOpen = true;
    notify();
  };

  const closeHITLModal = () => {
    state.isHITLModalOpen = false;
    state.hitlActionData = null;
    notify();
  };

  const approveHITL = () => {
    if (state.hitlActionData) {
      showToast(`Đã phê duyệt thành công yêu cầu: ${state.hitlActionData.title}`, 'success');
    }
    state.isHITLModalOpen = false;
    state.hitlActionData = null;
    notify();
  };

  const sendMessage = (text: string) => {
    if (!text.trim()) return;
    
    // Add user message
    const userMsg = {
      id: `msg-${Date.now()}`,
      sender: 'user' as const,
      content: text,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    };

    let currentSessionIndex = state.sessions.findIndex(s => s.id === state.activeSessionId);
    if (currentSessionIndex < 0 && state.sessions.length > 0) {
      currentSessionIndex = 0;
      state.activeSessionId = state.sessions[0].id;
    }

    if (currentSessionIndex >= 0) {
      state.sessions[currentSessionIndex].messages.push(userMsg);
      if (state.sessions[currentSessionIndex].title === 'Cuộc trò chuyện mới') {
        const cleanTitle = text.replace(/\[.*?\]/g, '').trim();
        state.sessions[currentSessionIndex].title = cleanTitle.slice(0, 32) + (cleanTitle.length > 32 ? '...' : '');
      }
    }
    
    state.streamStatus = 'searching';
    state.currentStreamActivity = 'Đang truy vấn kho tri thức RAG & đối chiếu chính sách...';
    notify();

    // RAG Pipeline Simulation
    setTimeout(() => {
      state.streamStatus = 'tool_calling';
      state.currentStreamActivity = 'Đang gọi Telco Gateway & kiểm tra định tuyến bảo mật...';
      notify();

      setTimeout(() => {
        state.streamStatus = 'streaming';
        state.currentStreamActivity = 'Đang biên dịch câu trả lời có dẫn nguồn...';
        notify();

        setTimeout(() => {
          state.streamStatus = 'complete';
          state.currentStreamActivity = '';

          const lower = text.toLowerCase();
          let responseContent = '';
          let confidence: ConfidenceLevel = 'confirmed';
          let citations = [INITIAL_CITATIONS['cit-1']];
          let suggestedFollowUps = [
            'Tạo yêu cầu hoàn tiền cho đơn hàng cụ thể',
            'Kiểm tra trạng thái ICCID qua mã đơn',
            'Mẫu tin nhắn trả lời khách hàng về thời gian nhận tiền'
          ];

          if (lower.includes('đính kèm') || lower.includes('ảnh') || lower.includes('tệp')) {
            responseContent = `### Kết quả Phân tích Tệp Đính Kèm (RAG Multimodal):\n\n- **Nhận diện tệp**: Đã trích xuất dữ liệu ảnh sự cố mạng tại sân bay Narita (Nhật Bản).\n- **Trạng thái thiết bị**: Thiết bị đang cố kết nối băng tần LTE Band 8 nhưng chưa kích hoạt **Chuyển vùng dữ liệu (Data Roaming)**.\n- **Đề xuất xử lý ngay**: Hướng dẫn khách bật Data Roaming và khởi động lại máy.\n\n*Đối chiếu quy chuẩn: [1] Quy chuẩn kỹ thuật mạng SoftBank & Docomo Nhật Bản 2026.*`;
            confidence = 'confirmed';
            citations = [INITIAL_CITATIONS['cit-2']];
            suggestedFollowUps = [
              'Chèn tin nhắn hướng dẫn vào khung chat',
              'Xem quy chuẩn cấu hình APN vmobile.jp'
            ];
          } else if (lower.includes('so sánh') || lower.includes('docomo') || lower.includes('softbank')) {
            responseContent = `### Bảng So Sánh Chi Tiết Mạng SoftBank 5G vs Docomo Pro:\n\n| Tiêu chí so sánh | Gói SoftBank 5G | Gói Docomo Pro |\n| :--- | :--- | :--- |\n| **Vùng phủ sóng Phú Sĩ / Ngoại ô** | 82% độ phủ | **99% độ phủ (Tốt nhất)** |\n| **Tốc độ 5G Nội đô Tokyo** | **Rất nhanh (450 Mbps)** | Nhanh & Ổn định (320 Mbps) |\n| **Giá niêm yết 7 ngày** | **220.000 VNĐ** | 260.000 VNĐ |\n| **Đề xuất phù hợp** | Khách chỉ du lịch Tokyo/Osaka | Khách đi cung đường Phú Sĩ/Kyoto/Hokkaido |\n\n> **Kết luận tư vấn:** Khách đi tour kết hợp Tokyo và núi Phú Sĩ nên ưu tiên chọn mạng Docomo [2] để tránh mất sóng khi lên cao.`;
            confidence = 'confirmed';
            citations = [INITIAL_CITATIONS['cit-2']];
            suggestedFollowUps = [
              'Gửi bảng so sánh cho khách hàng',
              'Xem hướng dẫn cấu hình APN Docomo'
            ];
          } else if (lower.includes('apn') || lower.includes('cấu hình') || lower.includes('sóng')) {
            responseContent = `### Hướng dẫn Cấu hình APN Mạng Quốc tế:\n\nĐối với mạng SoftBank Nhật Bản, cấu hình APN tiêu chuẩn là:\n\n\`\`\`text\nAPN: vmobile.jp\nUsername: (để trống)\nPassword: (để trống)\nAuthentication Type: CHAP hoặc PAP\n\`\`\`\n\n### Các bước kiểm tra nhanh trên điện thoại:\n1. **iOS**: Cài đặt → Di động → Mạng dữ liệu di động → Nhập \`vmobile.jp\` vào mục APN.\n2. **Android**: Cài đặt → Mạng & Internet → SIM → Tên điểm truy cập (APN) → Thêm mới.\n3. **Quan trọng**: Bắt buộc phải Bật **Chuyển vùng dữ liệu (Data Roaming)** [2].`;
            confidence = 'confirmed';
            citations = [INITIAL_CITATIONS['cit-2']];
          } else if (lower.includes('chiết khấu') || lower.includes('đại lý')) {
            responseContent = `### Chính Sách Chiết Khấu Đại Lý Cấp 1 [3]:\n\n- **Hạng Kim Cương**: Chiết khấu cố định **24%** trên giá niêm yết toàn bộ gói Châu Á.\n- **Thưởng doanh số tháng**: Đạt trên 50.000.000 VNĐ/tháng được cộng thêm **4.5%** thưởng quý.\n- **Hạn mức Tín dụng (Credit Line)**: Cấp tối đa **50.000.000 VNĐ** chu kỳ thanh toán 30 ngày.\n\n*Lưu ý: Quyền cấp hạn mức công nợ chỉ áp dụng cho tài khoản doanh nghiệp đã hoàn tất KYC [3].*`;
            confidence = 'confirmed';
            citations = [INITIAL_CITATIONS['cit-3']];
          } else {
            responseContent = `Theo quy định tại **Chính sách Hoàn hủy & Đổi gói cước eSIM Quốc tế** [1]:\n\n1. **Điều kiện hoàn tiền 100%**: Mã eSIM chưa từng được quét/cài đặt lên bất kỳ thiết bị nào (Hệ thống Core sẽ kiểm tra ICCID ở trạng thái *Unused*).\n2. **Thời hạn yêu cầu**: Yêu cầu được gửi trong vòng **14 ngày** kể từ ngày tạo đơn hàng thành công.\n3. **Quy trình thực hiện**: Nhân viên CSKH tạo yêu cầu hoàn tiền trên hệ thống → Hệ thống gửi duyệt **HITL (Human-in-the-loop)** tới Trưởng ca → Sau khi duyệt, mã QR sẽ bị hủy và tiền chuyển về phương thức thanh toán ban đầu trong 1-3 ngày làm việc.`;
            confidence = 'confirmed';
            citations = [INITIAL_CITATIONS['cit-1']];
          }

          const assistantMsg = {
            id: `msg-${Date.now() + 1}`,
            sender: 'assistant' as const,
            content: responseContent,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
            confidence,
            citations,
            toolsUsed: ['RAG_Doc_Search', 'OMS_Policy_Engine', 'Telco_Gateway'],
            suggestedFollowUps
          };

          if (currentSessionIndex >= 0) {
            state.sessions[currentSessionIndex].messages.push(assistantMsg);
          }
          notify();
        }, 800);
      }, 700);
    }, 600);
  };

  return {
    ...state,
    setActiveRole,
    toggleDarkMode,
    toggleSidebar,
    setActiveSessionId,
    createNewSession,
    deleteSession,
    togglePinSession,
    openEvidence,
    closeEvidence,
    setActiveQueueItem,
    setSalesComposerText,
    insertToSalesComposer,
    showToast,
    openHITLModal,
    closeHITLModal,
    approveHITL,
    sendMessage,
  };
}
