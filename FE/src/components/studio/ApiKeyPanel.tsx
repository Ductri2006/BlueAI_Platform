import React from 'react';
import { Check, Copy, KeyRound, LoaderCircle } from 'lucide-react';
import { blueAiApi, type StudioApiKeySettings } from '../../api/blueAiApi';
import { validApiRateLimit } from './apiKeySettings';

interface Props {
  botId: string;
  releaseId?: string;
  limitOverride?: number;
  onLimitChange: (limit: number) => void;
}

export function ApiKeyPanel({ botId, releaseId, limitOverride, onLimitChange }: Props) {
  const initialLimit = React.useRef(limitOverride);
  const [settings, setSettings] = React.useState<StudioApiKeySettings>();
  const [limit, setLimit] = React.useState(() => limitOverride === undefined ? '30' : Number.isFinite(limitOverride) ? String(limitOverride) : '');
  const [error, setError] = React.useState('');
  const [copied, setCopied] = React.useState(false);
  const [loading, setLoading] = React.useState(true);
  React.useEffect(() => {
    let active = true;
    setLoading(true);
    void blueAiApi.getStudioApiKey(botId).then(result => {
      if (!active) return;
      setSettings(result);
      if (initialLimit.current === undefined) setLimit(String(result.requestsPerMinute));
    }).catch(reason => {
      if (active) setError(reason instanceof Error ? reason.message : 'Không tải được cấu hình API key.');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [botId, releaseId]);

  const copy = async () => {
    if (!settings?.apiKey) return;
    try {
      await navigator.clipboard.writeText(settings.apiKey);
      setCopied(true);
      setError('');
    } catch {
      setError('Trình duyệt chưa cho phép sao chép. Hãy cấp quyền clipboard và thử lại.');
    }
  };
  const invalid = !validApiRateLimit(Number(limit)) || limit === '';
  const origin = (import.meta.env.VITE_API_BASE_URL || (typeof window !== 'undefined' ? window.location.origin : '')).replace(/\/$/, '');
  const example = [
    `curl -X POST '${origin}/api/v1/ai/tasks/chat'`,
    "  -H 'Authorization: Bearer <API_KEY>'",
    "  -H 'Content-Type: application/json'",
    `  -d '{"userId":"customer-123","message":"Xin chào","conversationId":"chat-456","messageId":"msg-789"}'`,
  ].join(' \\\n');

  return <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]" aria-label="API key và giới hạn truy cập">
    <div className="flex items-center gap-2"><KeyRound className="h-5 w-5 text-cobalt-600" /><h2 className="text-sm font-black">API key và giới hạn truy cập</h2>{loading && <LoaderCircle className="h-4 w-4 animate-spin" aria-label="Đang tải API key" />}</div>
    <p className="mt-2 text-xs leading-5 text-slate-500">API key tự được tạo khi phát hành. CEP hoặc website khác dùng key này để gửi ID khách và nội dung chat đến đúng bot. Lưu key ở backend của hệ thống gọi.</p>
    <div className="mt-4 flex flex-wrap items-end gap-3">
      <label className="min-w-0 flex-1"><span className="mb-1.5 block text-xs font-bold">API key</span><input aria-label="API key của bot" type="password" readOnly value={settings?.apiKey || ''} placeholder={loading ? 'Đang tải…' : 'Phát hành bot để tự tạo API key'} className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm dark:border-slate-700 dark:bg-slate-900" autoComplete="off" spellCheck={false} /></label>
      <button type="button" onClick={() => void copy()} disabled={!settings?.apiKey || loading} className="inline-flex items-center gap-2 rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-50">{copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}{copied ? 'Đã sao chép' : 'Sao chép API key'}</button>
    </div>
    <div className="mt-4 max-w-sm"><label htmlFor="api-requests-per-minute" className="mb-1.5 block text-xs font-bold">Giới hạn request / phút / API key</label><input id="api-requests-per-minute" type="number" min={1} max={1000} step={1} value={limit} disabled={loading || !settings} aria-invalid={invalid} aria-describedby="api-rate-help" onChange={event => { setLimit(event.target.value); onLimitChange(event.target.value === '' ? Number.NaN : Number(event.target.value)); }} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" /><p id="api-rate-help" className={`mt-1.5 text-xs ${invalid ? 'text-rose-600' : 'text-slate-500'}`}>{invalid ? 'Nhập số nguyên từ 1 đến 1.000.' : 'Lưu bản nháp, sau đó phát hành để áp dụng giới hạn mới.'}</p></div>
    {settings?.published && <p className="mt-2 text-xs text-slate-500">Đang áp dụng: <strong>{settings.publishedRequestsPerMinute} request/phút</strong>. API key được giữ nguyên khi phát hành lại.</p>}
    {copied && <p role="status" className="mt-2 text-xs text-emerald-600">Đã sao chép API key.</p>}
    {error && <p role="alert" className="mt-3 text-sm text-rose-600">{error}</p>}
    <details className="mt-4"><summary className="cursor-pointer text-xs font-bold text-cobalt-700 dark:text-cobalt-300">Xem request mẫu</summary><pre className="mt-3 overflow-x-auto rounded-xl bg-slate-950 p-4 text-xs leading-6 text-slate-100">{example}</pre><p className="mt-2 text-xs text-slate-500">Response trả về answer và các ID đã gửi. Vượt giới hạn nhận HTTP 429 cùng Retry-After.</p></details>
  </section>;
}
