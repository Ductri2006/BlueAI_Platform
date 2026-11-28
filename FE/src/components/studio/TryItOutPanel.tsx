import React from 'react';
import { Bot, RotateCcw, Send, UserRound, X } from 'lucide-react';
import { blueAiApi, type ConversationResponse, type StudioCustomization, type StudioPreviewContext, type StudioRuntimeResponse } from '../../api/blueAiApi';
import { StudioPreviewFallbackBadge } from './StudioPreviewFallbackBadge';
import { useSharedChatQuota } from '../chat/ChatQuotaContext';
import { QuotaIndicator } from '../chat/QuotaIndicator';
import { AiQuotaMenu } from '../chat/AiQuotaMenu';
import { useChatInputComposition } from '../chat/useChatInputComposition';
import { withoutDocumentSources } from '../chat/answerText';

interface TryItOutPanelProps {
  botId: string;
  botName: string;
  blueAiCoreEnabled: boolean;
  dialogEnabled?: boolean;
  customization?: StudioCustomization;
  onClose: () => void;
  welcomeMessage?: string;
}

interface TranscriptItem {
  id: string;
  role: 'assistant' | 'user';
  text: string;
  matchedIntent?: string;
  matchedNode?: string;
  recognizedEntities?: Record<string, string>;
  contextVariables?: Record<string, string>;
  pendingEntity?: string;
  fallback?: boolean;
  result?: StudioRuntimeResponse;
}

const INITIAL_MESSAGE: TranscriptItem = {
  id: 'welcome',
  role: 'assistant',
  text: 'Bản nháp đã sẵn sàng. Intent/entity/Dialog chạy trước; các preset phong cách được áp dụng khi câu hỏi chuyển sang BlueAI Core.',
};

const toneLabels: Record<string, string> = {
  professional: 'Chuyên nghiệp',
  friendly: 'Thân thiện',
  concise: 'Ngắn gọn',
  formal: 'Trang trọng',
};

const lengthLabels: Record<string, string> = {
  short: 'Trả lời ngắn',
  balanced: 'Độ dài cân bằng',
  detailed: 'Trả lời chi tiết',
};

const structureLabels: Record<string, string> = {
  direct: 'Trực tiếp',
  steps: 'Theo bước',
  summary_then_detail: 'Tóm tắt → chi tiết',
};

export const TryItOutPanel: React.FC<TryItOutPanelProps> = ({ botId, botName, blueAiCoreEnabled, dialogEnabled = true, customization, onClose, welcomeMessage }) => {
  const quota = useSharedChatQuota();
  const composition = useChatInputComposition();
  const welcome = { ...INITIAL_MESSAGE, text: welcomeMessage || (dialogEnabled ? INITIAL_MESSAGE.text : 'Chế độ chỉ RAG: hỏi đáp trực tiếp từ kho tri thức, không chạy kịch bản Dialog.') };
  const [items, setItems] = React.useState<TranscriptItem[]>([welcome]);
  const [conversations, setConversations] = React.useState<ConversationResponse[]>([]);
  const [conversationId, setConversationId] = React.useState('');
  const [context, setContext] = React.useState<StudioPreviewContext>();
  const [message, setMessage] = React.useState('');
  const [isSending, setIsSending] = React.useState(false);
  const [error, setError] = React.useState('');
  const endRef = React.useRef<HTMLDivElement>(null);
  const generation = React.useRef(0);

  React.useEffect(() => {
    let active = true;
    void blueAiApi.listConversations().then(value => { if (active) setConversations(value); })
      .catch(() => { if (active) setError('Không tải được danh sách hội thoại. Bạn vẫn có thể thử kịch bản độc lập.'); });
    return () => { active = false; generation.current += 1; };
  }, [botId]);

  React.useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [items, isSending]);

  const reset = () => {
    generation.current += 1;
    setItems([welcome]);
    setContext(undefined);
    setMessage('');
    setError('');
    setIsSending(false);
  };

  const send = async (event: React.FormEvent) => {
    event.preventDefault();
    const value = message.trim();
    if (!value || isSending || quota.blocked) return;

    setItems(current => [...current, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setMessage('');
    setError('');
    setIsSending(true);
    const currentGeneration = generation.current;

    try {
      const response = await blueAiApi.previewStudioBot(botId, value, context, {
        conversationId: conversationId || undefined,
        history: items.slice(-20).map(item => ({ role: item.role, content: item.text.slice(0, 4000) })),
      });
      if (currentGeneration !== generation.current) return;
      setContext(response.context);
      setItems(current => [...current, {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: response.message,
        matchedIntent: response.matchedIntent,
        matchedNode: response.matchedNode,
        recognizedEntities: response.recognizedEntities,
        contextVariables: response.context.variables,
        pendingEntity: response.context.pendingEntity,
        fallback: response.fallback,
        result: response,
      }]);
    } catch (requestError) {
      if (currentGeneration === generation.current) {
        setMessage(value);
        setError(requestError instanceof Error ? requestError.message : 'Không thể chạy bản thử nghiệm.');
      }
    } finally {
      if (currentGeneration === generation.current) setIsSending(false);
    }
  };

  return (
    <aside className="absolute inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-slate-200 bg-slate-950 text-slate-100 shadow-2xl dark:border-slate-700" aria-label="Thử BlueAI Custom">
      <div className="flex items-center justify-between border-b border-white/10 px-5 py-4">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-cyan-400">Try it out</p>
          <h2 className="mt-1 text-base font-black">{botName}</h2>
          <div className="mt-2 flex flex-wrap gap-1 text-[10px] font-bold">
            <span className={`rounded-full px-2 py-0.5 ${blueAiCoreEnabled ? 'bg-emerald-500/20 text-emerald-200' : 'bg-amber-500/20 text-amber-200'}`}>{blueAiCoreEnabled ? (dialogEnabled ? 'RAG + Dialog' : 'Chỉ RAG') : 'Chỉ Dialog'}</span>
            {blueAiCoreEnabled && <>
              <span className="rounded-full bg-white/10 px-2 py-0.5 text-slate-300">{toneLabels[customization?.tonePreset || 'professional'] || customization?.tonePreset}</span>
              <span className="rounded-full bg-white/10 px-2 py-0.5 text-slate-300">{lengthLabels[customization?.answerLength || 'balanced'] || customization?.answerLength}</span>
              <span className="rounded-full bg-white/10 px-2 py-0.5 text-slate-300">{structureLabels[customization?.responseStructure || 'direct'] || customization?.responseStructure}</span>
            </>}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <AiQuotaMenu quota={quota.aiQuota} error={quota.aiError} refresh={quota.refresh} iconOnly />
          <button type="button" onClick={reset} disabled={isSending} className="rounded-lg p-2 text-slate-400 hover:bg-white/10 hover:text-white disabled:opacity-40" title="Xoá ngữ cảnh" aria-label="Xoá ngữ cảnh thử nghiệm">
            <RotateCcw className="h-4 w-4" />
          </button>
          <button type="button" onClick={onClose} className="rounded-lg p-2 text-slate-400 hover:bg-white/10 hover:text-white" title="Đóng" aria-label="Đóng bảng thử nghiệm">
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="border-b border-white/10 px-4 py-3 text-xs">
        <label className="block font-bold text-slate-200">Context nền cho lượt thử
          <select aria-label="Hội thoại nền cho thử nghiệm" value={conversationId} disabled={isSending}
            onChange={event => { setConversationId(event.target.value); reset(); }}
            className="mt-2 w-full rounded-lg border border-white/10 bg-slate-900 px-2 py-2 text-slate-100 disabled:opacity-40">
            <option value="">Không dùng hội thoại có sẵn</option>
            {conversations.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
          </select>
        </label>
        <p className="mt-2 leading-5 text-slate-400">Dùng cùng bộ xử lý context và KB với màn sales. Lượt thử không ghi vào hội thoại nền; đóng bảng sẽ xoá lịch sử thử.</p>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto px-4 py-5">
        {items.map(item => (
          <div key={item.id} className={`flex gap-2.5 ${item.role === 'user' ? 'flex-row-reverse' : ''}`}>
            <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl ${item.role === 'assistant' ? 'bg-cobalt-600' : 'bg-slate-700'}`}>
              {item.role === 'assistant' ? <Bot className="h-4 w-4" /> : <UserRound className="h-4 w-4" />}
            </div>
            <div className={`max-w-[82%] rounded-2xl px-3.5 py-3 text-sm leading-6 ${item.role === 'assistant' ? 'rounded-tl-md bg-slate-800' : 'rounded-tr-md bg-cobalt-600'}`}>
              <p className="whitespace-pre-wrap">{item.role === 'user' ? item.text : withoutDocumentSources(item.text)}</p>
              {item.role === 'assistant' && item.id !== 'welcome' && (
                <div className="mt-3 space-y-1.5 border-t border-white/10 pt-2 text-[10px]">
                  <div className="flex flex-wrap gap-1.5 font-bold">
                  {item.matchedIntent && <span className="rounded-full bg-cobalt-500/20 px-2 py-0.5 text-cobalt-200">#{item.matchedIntent}</span>}
                  {item.matchedNode && <span className="rounded-full bg-violet-500/20 px-2 py-0.5 text-violet-200">Node: {item.matchedNode}</span>}
                  {Object.entries(item.recognizedEntities || {}).map(([name, value]) => (
                    <span key={name} className="rounded-full bg-cyan-500/20 px-2 py-0.5 text-cyan-200">@{name}:{value}</span>
                  ))}
                  <StudioPreviewFallbackBadge fallback={item.fallback} pendingEntity={item.pendingEntity} />
                  </div>
                  <p className="text-slate-400">Entity nhận diện ở câu này: {Object.keys(item.recognizedEntities || {}).length ? Object.entries(item.recognizedEntities || {}).map(([name, value]) => `@${name}=${value}`).join(', ') : 'không có'}</p>
                  <p className="text-slate-400">Context sau câu này: {Object.keys(item.contextVariables || {}).length ? Object.entries(item.contextVariables || {}).map(([name, value]) => `@${name}=${value}`).join(', ') : 'không giữ entity'}{item.pendingEntity ? ` · Đang đợi @${item.pendingEntity}` : ''}</p>
                  {item.result?.trace && <details className="rounded-lg bg-white/5 p-2">
                    <summary className="cursor-pointer font-bold text-cyan-200">Vì sao bot trả lời như vậy? · {item.result.trace.route}</summary>
                    <p className="mt-2 text-slate-300">{item.result.trace.reason}</p>
                    {item.result.trace.visitedNodes.length > 0 && <p className="mt-1">Các bước: {item.result.trace.visitedNodes.join(' → ')}</p>}
                    {item.result.trace.contextSource && <p className="mt-1">Nguồn dữ kiện: {item.result.trace.contextSource}</p>}
                    {Object.entries(item.result.trace.confirmedEntities).map(([key, value]) => <p key={key}>Đã xác nhận: {key} = {value}</p>)}
                  </details>}
                  {(item.result?.contextSources || []).map((source, index) => <p key={index} className="text-violet-200">Context: {source.title}{source.memoryKey ? ` · ${source.memoryKey}` : ''}</p>)}
                </div>
              )}
            </div>
          </div>
        ))}
        {isSending && <p className="pl-11 text-xs text-slate-400">{dialogEnabled ? (blueAiCoreEnabled ? 'Đang chạy Dialog và gọi RAG khi cần…' : 'Đang xử lý Dialog…') : 'Đang tra cứu RAG…'}</p>}
        <div ref={endRef} />
      </div>

      <div className="border-t border-white/10 p-4">
        {quota.blocked && <div className="mb-2"><QuotaIndicator seconds={quota.seconds} blocked /></div>}
        {error && <p className="mb-2 rounded-lg bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{error}</p>}
        {context && (
          <div className="mb-3 flex flex-wrap gap-1.5 text-[10px] text-slate-400">
            <span className="font-bold uppercase tracking-wider">Context cho câu tiếp theo</span>
            {context.waitingNode && <span className="rounded bg-violet-500/20 px-1.5 py-0.5 text-violet-200">node: {context.waitingNode}</span>}
            {context.pendingEntity && <span className="rounded bg-white/10 px-1.5 py-0.5">đợi @{context.pendingEntity}</span>}
            {Object.entries(context.suggestedVariables || {}).map(([key, value]) => <span key={`suggestion-${key}`} className="rounded bg-amber-500/20 px-1.5 py-0.5 text-amber-200">Chưa xác nhận: {key}={value}</span>)}
            {Object.entries(context.variables || {}).map(([key, value]) => <span key={key} className="rounded bg-white/10 px-1.5 py-0.5">{key}={value}</span>)}
          </div>
        )}
        <form onSubmit={send} className="flex gap-2">
          <input
            value={message}
            onChange={event => setMessage(event.target.value)}
            onKeyDown={event => { if (composition.shouldSubmitOnEnter(event)) event.currentTarget.form?.requestSubmit(); }}
            onCompositionStart={composition.onCompositionStart}
            onCompositionEnd={composition.onCompositionEnd}
            placeholder="Nhập câu để thử BlueAI…"
            className="min-w-0 flex-1 rounded-xl border border-white/10 bg-slate-900 px-3.5 py-2.5 text-sm text-white placeholder:text-slate-500"
            autoFocus
          />
          <button type="submit" disabled={!message.trim() || isSending || quota.blocked} className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-cobalt-600 text-white hover:bg-cobalt-500 disabled:cursor-not-allowed disabled:opacity-40" aria-label="Gửi câu thử nghiệm">
            <Send className="h-4 w-4" />
          </button>
        </form>
      </div>
    </aside>
  );
};
