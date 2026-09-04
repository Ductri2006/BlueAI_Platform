import React, { useRef, useState } from 'react';
import { Send, Sparkles } from 'lucide-react';
import { blueAiApi, ragAnswerToChatMessage } from '../../api/blueAiApi';
import type { ChatMessage } from '../../types';
import { Button } from '../ui/Button';

interface RagMiniChatProps {
  title: string;
  placeholder: string;
  conversationTitle: string;
}

export const RagMiniChat: React.FC<RagMiniChatProps> = ({ title, placeholder, conversationTitle }) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
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
        conversationId.current = (await blueAiApi.createConversation(conversationTitle)).id;
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
    <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 overflow-hidden flex flex-col min-h-[360px]">
      <div className="p-4 border-b border-slate-200 dark:border-slate-800 flex items-center gap-2 font-bold text-sm">
        <Sparkles className="w-4 h-4 text-cobalt-600" />
        {title}
      </div>
      <div className="flex-1 p-4 space-y-3 overflow-y-auto">
        {messages.length === 0 && <p className="text-sm text-slate-500">Chưa có hội thoại. Câu trả lời sẽ được lấy từ RAG API và kèm nguồn khi có căn cứ.</p>}
        {messages.map(message => (
          <div key={message.id} className={`flex ${message.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[86%] rounded-2xl px-4 py-3 text-sm whitespace-pre-wrap ${message.sender === 'user' ? 'bg-cobalt-600 text-white' : 'bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100'}`}>
              {message.content}
              {!!message.citations?.length && (
                <div className="mt-2 pt-2 border-t border-current/20 text-xs opacity-80">
                  Nguồn: {message.citations.map(citation => citation.title).join(', ')}
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
          onKeyDown={event => { if (event.key === 'Enter') void send(); }}
          placeholder={placeholder}
          className="flex-1 px-3 py-2 text-sm rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
        />
        <Button size="sm" variant="primary" onClick={() => void send()} disabled={loading || !text.trim()}>
          <Send className="w-4 h-4" />
        </Button>
      </div>
    </div>
  );
};
