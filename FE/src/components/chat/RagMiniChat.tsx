import React, { useRef, useState } from 'react';
import { Layers, Send, Sparkles } from 'lucide-react';
import { blueAiApi, ragAnswerToChatMessage } from '../../api/blueAiApi';
import type { ChatMessage } from '../../types';
import type { ConversationContextMode } from '../../api/blueAiApi';
import { Button } from '../ui/Button';
import { ConversationContextPanel } from './ConversationContextPanel';
import { useChatInputComposition } from './useChatInputComposition';
import { visibleMessageText } from './answerText';

interface RagMiniChatProps {
  title: string;
  placeholder: string;
  conversationTitle: string;
}

export const RagMiniChat: React.FC<RagMiniChatProps> = ({ title, placeholder, conversationTitle }) => {
  const composition = useChatInputComposition();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
  const [persistedConversationId, setPersistedConversationId] = useState<string>();
  const [contextMode, setContextMode] = useState<ConversationContextMode>('CURRENT_CONVERSATION');
  const [isContextOpen, setIsContextOpen] = useState(false);
  const conversationId = useRef<string | undefined>(undefined);

  const send = async () => {
    const question = text.trim();
    if (!question || loading) return;
    setText('');
    setMessages(current => [...current, {
      id: `local-${crypto.randomUUID()}`,
      sender: 'user',
      content: question,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    }]);
    setLoading(true);
    try {
      if (!conversationId.current) {
        const conversation = await blueAiApi.createConversation(conversationTitle);
        conversationId.current = conversation.id;
        setPersistedConversationId(conversation.id);
        setContextMode(conversation.contextMode);
      }
      const answer = await blueAiApi.askQuestion(conversationId.current, question);
      setMessages(current => [...current, ragAnswerToChatMessage(answer)]);
    } catch (error) {
      setMessages(current => [...current, {
        id: `error-${crypto.randomUUID()}`,
        sender: 'system',
        content: error instanceof Error ? error.message : 'Không thể kết nối dịch vụ RAG.',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        confidence: 'danger',
      }]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 overflow-hidden flex flex-col min-h-[360px]">
      <div className="p-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between gap-2 font-bold text-sm">
        <div className="flex min-w-0 items-center gap-2">
          <Sparkles className="w-4 h-4 shrink-0 text-cobalt-600" />
          <span className="truncate">{title}</span>
        </div>
        <button
          onClick={() => setIsContextOpen(true)}
          disabled={!persistedConversationId}
          className="rounded-lg p-1.5 text-slate-500 transition-colors hover:bg-cobalt-50 hover:text-cobalt-700 disabled:cursor-not-allowed disabled:opacity-40 dark:text-slate-400 dark:hover:bg-cyan-950/30 dark:hover:text-cyan-300"
          title={persistedConversationId ? `Context: ${contextMode}` : 'Gửi tin nhắn đầu tiên để tạo phiên và cấu hình context'}
          aria-label="Quản lý context và ghi nhớ"
        >
          <Layers className="h-4 w-4" />
        </button>
      </div>
      <div className="flex-1 p-4 space-y-3 overflow-y-auto">
        {messages.length === 0 && <p className="text-sm text-slate-500">Chưa có hội thoại. Hãy nhập câu hỏi để bắt đầu.</p>}
        {messages.map(message => (
          <div key={message.id} className={`flex ${message.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[86%] rounded-2xl px-4 py-3 text-sm whitespace-pre-wrap ${message.sender === 'user' ? 'bg-cobalt-600 text-white' : 'bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100'}`}>
              {visibleMessageText(message)}
              {!!message.contextSources?.length && (
                <div className="mt-2 pt-2 border-t border-current/20 text-xs opacity-80">
                  Context: {message.contextSources.map(source => source.title).join(', ')}
                </div>
              )}
            </div>
          </div>
        ))}
        {loading && <p className="text-xs text-slate-500 animate-pulse">Đang truy xuất kho tri thức và gọi mô hình…</p>}
      </div>
      <div className="p-3 border-t border-slate-200 dark:border-slate-800 flex gap-2">
        <input
          value={text}
          onChange={event => setText(event.target.value)}
          onKeyDown={event => { if (composition.shouldSubmitOnEnter(event)) void send(); }}
          onCompositionStart={composition.onCompositionStart}
          onCompositionEnd={composition.onCompositionEnd}
          placeholder={placeholder}
          className="flex-1 px-3 py-2 text-sm rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
        />
        <Button size="sm" variant="primary" onClick={() => void send()} disabled={loading || !text.trim()}>
          <Send className="w-4 h-4" />
        </Button>
      </div>
      <ConversationContextPanel
        conversationId={persistedConversationId}
        isOpen={isContextOpen}
        onClose={() => setIsContextOpen(false)}
        onModeChange={setContextMode}
      />
    </div>
  );
};
