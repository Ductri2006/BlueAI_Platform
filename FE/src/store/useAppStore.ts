import { useEffect, useState } from 'react';
import type { AIStreamStatus, ChatMessage, ChatSession, Citation, CustomerQueueItem, UserRole } from '../types';
import { blueAiApi, BlueAiApiError, normalizeAssistantContent, ragAnswerToChatMessage } from '../api/blueAiApi';

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
  customerQueue: CustomerQueueItem[];
  activeQueueItem: CustomerQueueItem | null;
  setActiveQueueItem: (item: CustomerQueueItem) => void;
  salesComposerText: string;
  setSalesComposerText: (text: string) => void;
  insertToSalesComposer: (text: string) => void;
  isHITLModalOpen: boolean;
  hitlActionData: {
    title: string;
    customerName: string;
    orderId: string;
    amount: string;
    policyRef: string;
    reason: string;
  } | null;
  openHITLModal: (data: AppState['hitlActionData']) => void;
  closeHITLModal: () => void;
  approveHITL: () => void;
  toast: { message: string; type: 'success' | 'info' | 'warning' } | null;
  showToast: (message: string, type?: 'success' | 'info' | 'warning') => void;
}

const state: Omit<AppState,
  'setActiveRole' | 'toggleDarkMode' | 'toggleSidebar' | 'setActiveSessionId' |
  'createNewSession' | 'deleteSession' | 'togglePinSession' | 'sendMessage' |
  'openEvidence' | 'closeEvidence' | 'setActiveQueueItem' | 'setSalesComposerText' |
  'insertToSalesComposer' | 'openHITLModal' | 'closeHITLModal' | 'approveHITL' | 'showToast'
> = {
  activeRole: 'employee',
  isDarkMode: false,
  isSidebarCollapsed: false,
  sessions: [],
  activeSessionId: '',
  streamStatus: 'idle',
  currentStreamActivity: '',
  selectedCitation: null,
  isEvidenceOpen: false,
  customerQueue: [],
  activeQueueItem: null,
  salesComposerText: '',
  isHITLModalOpen: false,
  hitlActionData: null,
  toast: null,
};

const listeners = new Set<() => void>();
const apiConversationIds = new Map<string, string>();
let hydration: Promise<void> | undefined;

function notify() {
  listeners.forEach(listener => listener());
}

function toChatMessage(message: { id: string; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string }): ChatMessage {
  const createdAt = new Date(message.createdAt);
  return {
    id: message.id,
    sender: message.role === 'USER' ? 'user' : 'assistant',
    content: normalizeAssistantContent(message.content),
    timestamp: Number.isNaN(createdAt.getTime()) ? '' : createdAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
  };
}

async function hydrateFromApi() {
  try {
    const conversations = await blueAiApi.listConversations();
    const sessions = await Promise.all(conversations.map(async conversation => {
      apiConversationIds.set(conversation.id, conversation.id);
      const messages = await blueAiApi.listMessages(conversation.id);
      const updatedAt = new Date(conversation.updatedAt);
      return {
        id: conversation.id,
        title: conversation.title,
        role: 'employee' as UserRole,
        category: 'today' as const,
        updatedAt: Number.isNaN(updatedAt.getTime()) ? '' : updatedAt.toLocaleString(),
        messages: messages.map(toChatMessage),
      };
    }));
    // A session created while hydration was still in flight must survive it,
    // otherwise the chat the user just started disappears when the API answers.
    const pendingLocalSessions = state.sessions.filter(session => session.id.startsWith('local-'));
    state.sessions = [...pendingLocalSessions, ...sessions];
    state.activeSessionId = state.activeSessionId || sessions[0]?.id || '';
  } catch (error) {
    const message = error instanceof BlueAiApiError ? error.message : 'Không thể tải dữ liệu từ BlueAI API.';
    state.toast = { message, type: 'warning' };
  } finally {
    notify();
  }
}

function showToast(message: string, type: 'success' | 'info' | 'warning' = 'success') {
  state.toast = { message, type };
  notify();
  window.setTimeout(() => {
    state.toast = null;
    notify();
  }, 3500);
}

export function useAppStore(): AppState {
  const [, setTick] = useState(0);

  useEffect(() => {
    const handler = () => setTick(tick => tick + 1);
    listeners.add(handler);
    hydration ||= hydrateFromApi();
    return () => { listeners.delete(handler); };
  }, []);

  const createNewSession = () => {
    const id = `local-${crypto.randomUUID()}`;
    state.sessions = [{
      id,
      title: 'Cuộc trò chuyện mới',
      role: state.activeRole,
      category: 'today',
      updatedAt: 'Vừa xong',
      messages: [],
    }, ...state.sessions];
    state.activeSessionId = id;
    notify();
  };

  const deleteSession = (id: string) => {
    const backendId = apiConversationIds.get(id);
    state.sessions = state.sessions.filter(session => session.id !== id);
    apiConversationIds.delete(id);
    if (state.activeSessionId === id) state.activeSessionId = state.sessions[0]?.id || '';
    notify();
    if (backendId) {
      void blueAiApi.deleteConversation(backendId)
        .then(() => showToast('Đã xoá cuộc trò chuyện.', 'success'))
        .catch(() => showToast('Không thể xoá hội thoại trên máy chủ.', 'warning'));
    } else {
      showToast('Đã xoá cuộc trò chuyện.', 'success');
    }
  };

  const sendMessage = async (text: string) => {
    const question = text.trim();
    if (!question || ['searching', 'tool_calling', 'streaming'].includes(state.streamStatus)) return;
    if (!state.activeSessionId) createNewSession();

    const sessionId = state.activeSessionId;
    const session = state.sessions.find(item => item.id === sessionId);
    if (!session) return;
    session.messages.push({
      id: `local-message-${crypto.randomUUID()}`,
      sender: 'user',
      content: question,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    });
    if (session.title === 'Cuộc trò chuyện mới') session.title = question.slice(0, 60);
    state.streamStatus = 'searching';
    state.currentStreamActivity = 'Đang truy vấn dịch vụ RAG...';
    notify();

    try {
      let conversationId = apiConversationIds.get(sessionId);
      if (!conversationId) {
        const conversation = await blueAiApi.createConversation(session.title);
        conversationId = conversation.id;
        apiConversationIds.set(sessionId, conversationId);
      }
      state.streamStatus = 'tool_calling';
      state.currentStreamActivity = 'Đang tổng hợp câu trả lời có căn cứ...';
      notify();
      const answer = await blueAiApi.askQuestion(conversationId, question);
      state.sessions.find(item => item.id === sessionId)?.messages.push(ragAnswerToChatMessage(answer));
      state.streamStatus = 'complete';
      state.currentStreamActivity = '';
      notify();
    } catch (error) {
      const apiError = error instanceof BlueAiApiError ? error : new BlueAiApiError('Không thể kết nối BlueAI API.', 0, 'API_UNREACHABLE');
      state.sessions.find(item => item.id === sessionId)?.messages.push({
        id: `error-${crypto.randomUUID()}`,
        sender: 'system',
        content: `Không thể nhận câu trả lời từ RAG API.\n\n**${apiError.code}**: ${apiError.message}`,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        confidence: 'danger',
      });
      state.streamStatus = 'error';
      state.currentStreamActivity = '';
      showToast('RAG API đang lỗi hoặc chưa được cấu hình.', 'warning');
    }
  };

  return {
    ...state,
    setActiveRole: role => { state.activeRole = role; notify(); },
    toggleDarkMode: () => {
      state.isDarkMode = !state.isDarkMode;
      document.documentElement.classList.toggle('dark', state.isDarkMode);
      notify();
    },
    toggleSidebar: () => { state.isSidebarCollapsed = !state.isSidebarCollapsed; notify(); },
    setActiveSessionId: id => { state.activeSessionId = id; notify(); },
    createNewSession,
    deleteSession,
    togglePinSession: id => {
      state.sessions = state.sessions.map(session => session.id === id ? { ...session, isPinned: !session.isPinned } : session);
      notify();
    },
    sendMessage,
    openEvidence: citation => { state.selectedCitation = citation; state.isEvidenceOpen = true; notify(); },
    closeEvidence: () => { state.isEvidenceOpen = false; notify(); },
    setActiveQueueItem: item => { state.activeQueueItem = item; notify(); },
    setSalesComposerText: text => { state.salesComposerText = text; notify(); },
    insertToSalesComposer: text => { state.salesComposerText = text; notify(); },
    openHITLModal: data => { state.hitlActionData = data; state.isHITLModalOpen = true; notify(); },
    closeHITLModal: () => { state.hitlActionData = null; state.isHITLModalOpen = false; notify(); },
    approveHITL: () => { state.hitlActionData = null; state.isHITLModalOpen = false; notify(); },
    showToast,
  };
}
