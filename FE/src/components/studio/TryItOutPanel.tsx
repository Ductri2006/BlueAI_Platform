import React from 'react';
import { Bot, RotateCcw, Send, UserRound, X } from 'lucide-react';
import { blueAiApi, type StudioPreviewContext } from '../../api/blueAiApi';

interface TryItOutPanelProps {
  botId: string;
  botName: string;
  onClose: () => void;
}

interface TranscriptItem {
  id: string;
  role: 'assistant' | 'user';
  text: string;
  matchedIntent?: string;
  recognizedEntities?: Record<string, string>;
  fallback?: boolean;
}

const INITIAL_MESSAGE: TranscriptItem = {
  id: 'welcome',
  role: 'assistant',
  text: 'Bản nháp đã sẵn sàng. Hãy nhập một câu để kiểm tra intent, entity và luồng hội thoại.',
};

export const TryItOutPanel: React.FC<TryItOutPanelProps> = ({ botId, botName, onClose }) => {
  const [items, setItems] = React.useState<TranscriptItem[]>([INITIAL_MESSAGE]);
  const [context, setContext] = React.useState<StudioPreviewContext>();
  const [message, setMessage] = React.useState('');
  const [isSending, setIsSending] = React.useState(false);
  const [error, setError] = React.useState('');
  const endRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [items, isSending]);

  const reset = () => {
    setItems([INITIAL_MESSAGE]);
    setContext(undefined);
    setMessage('');
    setError('');
  };

  const send = async (event: React.FormEvent) => {
    event.preventDefault();
    const value = message.trim();
    if (!value || isSending) return;

    setItems(current => [...current, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setMessage('');
    setError('');
    setIsSending(true);

    try {
      const response = await blueAiApi.previewStudioBot(botId, value, context);
      setContext(response.context);
      setItems(current => [...current, {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: response.message,
        matchedIntent: response.matchedIntent,
        recognizedEntities: response.recognizedEntities,
        fallback: response.fallback,
      }]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Không thể chạy bản thử nghiệm.');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <aside className="absolute inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-slate-200 bg-slate-950 text-slate-100 shadow-2xl dark:border-slate-700" aria-label="Thử BlueAI Custom">
      <div className="flex items-center justify-between border-b border-white/10 px-5 py-4">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-cyan-400">Try it out</p>
          <h2 className="mt-1 text-base font-black">{botName}</h2>
        </div>
        <div className="flex items-center gap-1">
          <button type="button" onClick={reset} className="rounded-lg p-2 text-slate-400 hover:bg-white/10 hover:text-white" title="Xoá ngữ cảnh" aria-label="Xoá ngữ cảnh thử nghiệm">
            <RotateCcw className="h-4 w-4" />
          </button>
          <button type="button" onClick={onClose} className="rounded-lg p-2 text-slate-400 hover:bg-white/10 hover:text-white" title="Đóng" aria-label="Đóng bảng thử nghiệm">
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto px-4 py-5">
        {items.map(item => (
          <div key={item.id} className={`flex gap-2.5 ${item.role === 'user' ? 'flex-row-reverse' : ''}`}>
            <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl ${item.role === 'assistant' ? 'bg-cobalt-600' : 'bg-slate-700'}`}>
              {item.role === 'assistant' ? <Bot className="h-4 w-4" /> : <UserRound className="h-4 w-4" />}
            </div>
            <div className={`max-w-[82%] rounded-2xl px-3.5 py-3 text-sm leading-6 ${item.role === 'assistant' ? 'rounded-tl-md bg-slate-800' : 'rounded-tr-md bg-cobalt-600'}`}>
              <p className="whitespace-pre-wrap">{item.text}</p>
              {item.role === 'assistant' && (item.matchedIntent || Object.keys(item.recognizedEntities || {}).length > 0 || item.fallback) && (
                <div className="mt-2 flex flex-wrap gap-1.5 border-t border-white/10 pt-2 text-[10px] font-bold">
                  {item.matchedIntent && <span className="rounded-full bg-cobalt-500/20 px-2 py-0.5 text-cobalt-200">#{item.matchedIntent}</span>}
                  {Object.entries(item.recognizedEntities || {}).map(([name, value]) => (
                    <span key={name} className="rounded-full bg-cyan-500/20 px-2 py-0.5 text-cyan-200">@{name}:{value}</span>
                  ))}
                  {item.fallback && <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-amber-200">fallback</span>}
                </div>
              )}
            </div>
          </div>
        ))}
        {isSending && <p className="pl-11 text-xs text-slate-400">Đang phân tích intent và entity…</p>}
        <div ref={endRef} />
      </div>

      <div className="border-t border-white/10 p-4">
        {error && <p className="mb-2 rounded-lg bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{error}</p>}
        {context && (
          <div className="mb-3 flex flex-wrap gap-1.5 text-[10px] text-slate-400">
            <span className="font-bold uppercase tracking-wider">Context</span>
            {context.waitingNode && <span className="rounded bg-violet-500/20 px-1.5 py-0.5 text-violet-200">node: {context.waitingNode}</span>}
            {context.pendingEntity && <span className="rounded bg-white/10 px-1.5 py-0.5">đợi @{context.pendingEntity}</span>}
            {Object.entries(context.variables || {}).map(([key, value]) => <span key={key} className="rounded bg-white/10 px-1.5 py-0.5">{key}={value}</span>)}
          </div>
        )}
        <form onSubmit={send} className="flex gap-2">
          <input
            value={message}
            onChange={event => setMessage(event.target.value)}
            placeholder="Nhập câu để thử BlueAI…"
            className="min-w-0 flex-1 rounded-xl border border-white/10 bg-slate-900 px-3.5 py-2.5 text-sm text-white placeholder:text-slate-500"
            autoFocus
          />
          <button type="submit" disabled={!message.trim() || isSending} className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-cobalt-600 text-white hover:bg-cobalt-500 disabled:cursor-not-allowed disabled:opacity-40" aria-label="Gửi câu thử nghiệm">
            <Send className="h-4 w-4" />
          </button>
        </form>
      </div>
    </aside>
  );
};
