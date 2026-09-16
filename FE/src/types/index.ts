export type UserRole = 
  | 'employee'        // Người dùng nội bộ (Hỏi BlueAI)
  | 'sales_copilot'   // Sales / CSKH Copilot (Trợ lý bán hàng)
  | 'knowledge_owner' // Quản lý tài liệu (Tài liệu nội bộ / RAG Admin)
  | 'studio'          // Thiết kế và phát hành chatbot
  | 'admin'           // Quản trị viên hệ thống (System & Security Admin)
  | 'reseller'        // Đại lý B2B (Cổng đại lý)
  | 'b2c_customer';   // Khách hàng B2C (Cổng hỗ trợ khách hàng)

export type ConfidenceLevel = 'confirmed' | 'inferred' | 'proposal' | 'ungrounded' | 'danger';

export type AIStreamStatus = 'idle' | 'searching' | 'tool_calling' | 'streaming' | 'complete' | 'abstained' | 'error';

export interface Citation {
  id: string;
  sourceId: string;
  title: string;
  section: string;
  version: string;
  effectiveDate: string;
  expiryDate?: string;
  owner: string;
  scope: string;
  snippet: string;
  status: 'valid' | 'expiring_soon' | 'expired' | 'scope_mismatch';
}

export interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: string;
  confidence?: ConfidenceLevel;
  citations?: Citation[];
  toolsUsed?: string[];
  hitlAction?: {
    actionType: 'refund' | 'cancel_order' | 'change_package';
    title: string;
    amount?: string;
    targetOrder: string;
    status: 'pending_review' | 'approved' | 'rejected';
    policyRef: string;
  };
  suggestedFollowUps?: string[];
}

export interface ChatSession {
  id: string;
  title: string;
  role: UserRole;
  category: 'today' | 'last_7_days' | 'older';
  isPinned?: boolean;
  messages: ChatMessage[];
  updatedAt: string;
}

export interface CustomerData {
  id: string;
  name: string;
  phone: string;
  email: string;
  tier: 'Diamond' | 'Gold' | 'Silver' | 'Standard';
  avatar: string;
  activeOrder?: {
    orderId: string;
    plan: string;
    country: string;
    status: 'active' | 'pending' | 'expired' | 'issue';
    dataUsed: string;
    totalData: string;
    expiry: string;
    iccid: string;
  };
  pastOrdersCount: number;
  openIssuesCount: number;
  sentiment: 'positive' | 'neutral' | 'frustrated';
}

export interface CustomerQueueItem {
  id: string;
  customerName: string;
  channel: 'Zalo' | 'Website' | 'Facebook' | 'Email';
  lastMessage: string;
  waitTime: string;
  priority: 'high' | 'medium' | 'normal';
  slaStatus: 'ok' | 'warning' | 'breached';
  assignedTo: string;
  unreadCount: number;
  customerData: CustomerData;
}

export interface PolicyDocument {
  id: string;
  title: string;
  category: string;
  version: string;
  lastUpdated: string;
  expiryDate: string;
  owner: string;
  status: 'active' | 'in_review' | 'expiring_soon' | 'archived';
  chunksCount: number;
  retrievalHitCount: number;
  scope: string[];
}

export interface AdminSystemMetrics {
  totalRequestsToday: number;
  avgLatencyMs: number;
  ragAccuracyScore: number;
  tokenCostTodayUsd: number;
  activeUsersNow: number;
  hitlActionsPending: number;
  modelsRouting: {
    name: string;
    provider: string;
    sharePercent: number;
    avgCostPer1k: string;
  }[];
}
