import type { ChatError, ChatMessage } from '../types';

export function chatErrorMetadata(code?: string, status?: number): ChatError {
  // Codes are data, never upstream text, URLs, configuration or stack traces.
  const safeCode = code && /^[A-Z][A-Z0-9_]{0,79}$/.test(code) ? code : undefined;
  const category: ChatError['category'] =
    status === 401 || status === 403 || status === 404 ? 'access'
      : safeCode === 'LLM_RESPONSE_LANGUAGE_MISMATCH' || safeCode === 'REPLY_TRANSLATION_FAILED' ? 'language'
      : status === 429 || status === 413 || safeCode === 'SESSION_TURN_LIMIT'
        || ['AI_TOKEN_QUOTA_EXHAUSTED', 'AI_PROJECT_RATE_LIMITED', 'AI_PROVIDER_RATE_LIMITED', 'AI_REQUEST_TOO_LARGE', 'RAG_RATE_LIMITED'].includes(safeCode || '') ? 'quota'
      : status === 0 || safeCode === 'API_UNREACHABLE' ? 'network'
      : 'service';
  return { category, ...(safeCode ? { code: safeCode } : {}) };
}

export function chatErrorToMessage(error: unknown): ChatMessage {
  const details = error instanceof Error ? error as Error & { code?: unknown; status?: unknown; retryAfterSeconds?: unknown } : undefined;
  const code = typeof details?.code === 'string' ? details.code : undefined;
  const status = typeof details?.status === 'number' ? details.status : error instanceof TypeError ? 0 : undefined;
  const metadata = chatErrorMetadata(code, status);
  const seconds = typeof details?.retryAfterSeconds === 'number' && Number.isFinite(details.retryAfterSeconds)
    && details.retryAfterSeconds > 0 ? Math.ceil(details.retryAfterSeconds) : undefined;
  const messages: Record<ChatError['category'], string> = {
    language: 'Chưa thể tạo phản hồi đúng ngôn ngữ. Vui lòng thử lại hoặc liên hệ hỗ trợ.',
    quota: code === 'AI_REQUEST_TOO_LARGE' || status === 413
      ? 'Câu hỏi hoặc ngữ cảnh quá dài. Hãy rút ngắn câu hỏi hoặc tạo hội thoại mới rồi gửi lại.'
      : code === 'SESSION_TURN_LIMIT'
      ? 'Hội thoại đã đạt giới hạn tin nhắn. Hãy tạo hội thoại mới để tiếp tục.'
      : code === 'AI_TOKEN_QUOTA_EXHAUSTED'
      ? 'Đã đạt hạn mức sử dụng AI. Vui lòng chờ hạn mức được làm mới rồi gửi lại.'
      : `Đã đạt giới hạn gửi câu hỏi. Vui lòng ${seconds ? `chờ ${seconds} giây` : 'chờ một lúc'} rồi gửi lại.`,
    network: 'Không thể kết nối dịch vụ. Hãy kiểm tra kết nối mạng rồi gửi lại câu hỏi.',
    access: status === 401
      ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để tiếp tục.'
      : 'Không thể truy cập hội thoại này. Hãy mở hội thoại khác hoặc liên hệ quản trị viên để kiểm tra quyền truy cập.',
    service: 'Dịch vụ tạm thời chưa thể trả lời. Vui lòng thử lại sau hoặc liên hệ hỗ trợ nếu lỗi tiếp diễn.',
  };
  return {
    id: `error-${crypto.randomUUID()}`,
    sender: 'system',
    // Never forward Error.message: even known API codes may contain upstream configuration.
    content: messages[metadata.category],
    timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    error: metadata,
  };
}
