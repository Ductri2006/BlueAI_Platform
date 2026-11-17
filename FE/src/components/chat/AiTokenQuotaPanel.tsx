import React from 'react';
import { ChevronDown, Info } from 'lucide-react';
import type { AiTokenQuota, TokenWindow } from '../../api/blueAiApi';

const number = new Intl.NumberFormat('vi-VN');
function resetLabel(window: TokenWindow) {
  if (!window.resetsAt) return 'Bắt đầu chu kỳ khi bạn dùng AI';
  const date = new Date(window.resetsAt);
  if (Number.isNaN(date.getTime())) return 'Đang cập nhật thời điểm hồi phục';
  return `Hồi phục lúc ${new Intl.DateTimeFormat('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit',
  }).format(date)}`;
}

export function TokenQuotaBar({ label, window }: { label: string; window: TokenWindow }) {
  const percent = window.limit > 0 ? Math.min(100, 100 * window.used / window.limit) : 0;
  const held = window.limit > 0 ? Math.min(100 - percent, 100 * window.reserved / window.limit) : 0;
  const warning = window.used + window.reserved >= window.limit * 0.8;
  return <div className="rounded-xl bg-slate-50 px-3 py-2.5 dark:bg-slate-800/60">
    <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs">
      <span className="font-medium text-slate-700 dark:text-slate-200">{label}</span>
      <span className={`tabular-nums ${warning ? 'font-semibold text-amber-700 dark:text-amber-300' : 'text-slate-600 dark:text-slate-300'}`}>
        Đã dùng {number.format(Math.round(percent * 10) / 10)}%
      </span>
    </div>
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(percent)}
      aria-valuetext={`${number.format(window.used)} trên ${number.format(window.limit)} token; đang giữ ${number.format(window.reserved)} token`}
      className="mt-2 flex h-1.5 overflow-hidden rounded-full bg-slate-200/70 dark:bg-slate-700">
      <div style={{ width: `${percent}%` }} className={`h-full rounded-full ${warning ? 'bg-amber-500' : 'bg-cobalt-600 dark:bg-cobalt-400'}`} />
      {held > 0 && <div style={{ width: `${held}%` }} className="h-full bg-cobalt-300 dark:bg-cobalt-700" />}
    </div>
    <div className="mt-1.5 flex flex-wrap justify-between gap-x-3 gap-y-1 text-[11px] text-slate-500 dark:text-slate-400">
      <span>{resetLabel(window)}</span>
      {window.remaining === 0 ? <span className="text-amber-700 dark:text-amber-300">Đã dùng hết phần khả dụng</span>
        : warning && <span className="text-amber-700 dark:text-amber-300">Sắp hết hạn mức</span>}
    </div>
  </div>;
}

export function AiTokenQuotaPanel({ quota, error = '' }: { quota?: AiTokenQuota; error?: string }) {
  const [details, setDetails] = React.useState(false);
  const id = React.useId();
  const windows = quota?.enabled && quota.fiveHour && quota.weekly
    ? [{label:'Mức sử dụng hiện tại · 5 giờ', window:quota.fiveHour}, {label:'Hạn mức hằng tuần · 7 ngày', window:quota.weekly}] : [];
  if (quota && !quota.enabled) return null;
  return <div className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-900" aria-label="Hạn mức token AI">
    <div className="mb-2 flex items-center justify-between gap-3 text-xs">
      <div className="flex items-center gap-1.5 font-semibold text-slate-700 dark:text-slate-200">
        Hạn mức AI <Info className="h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
      </div>
      {windows.length > 0 && <button type="button" onClick={() => setDetails(value => !value)} aria-expanded={details} aria-controls={id}
        className="flex min-h-6 items-center gap-1 rounded text-slate-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cobalt-500 dark:text-slate-400">
        Chi tiết <ChevronDown className={`h-3 w-3 ${details ? 'rotate-180' : ''}`} aria-hidden="true" />
      </button>}
    </div>
    <div className="space-y-2">{windows.map(item => <TokenQuotaBar key={item.label} {...item} />)}</div>
    {error && <p className="py-1 text-xs text-amber-700 dark:text-amber-300" role="status">{error}</p>}
    {!quota && !error && <p className="text-xs text-slate-500" role="status">Đang đọc hạn mức AI…</p>}
    {windows.some(item => item.window.remaining === 0) && <p className="mt-2 text-xs text-amber-700 dark:text-amber-300" role="status">Hạn mức AI đã hết. Các câu trả lời thuần kịch bản Dialog vẫn sử dụng được.</p>}
    {details && <div id={id} className="mt-3 space-y-2 border-t border-slate-100 pt-2 text-[11px] text-slate-500 dark:border-slate-800 dark:text-slate-400">
      {windows.map(({label, window}) => <p key={label}>
        <span className="font-medium">{label}</span> — {number.format(window.used)} / {number.format(window.limit)} token; còn {number.format(window.remaining)}.
        {window.reserved > 0 && <> Đang chờ xác nhận {number.format(window.reserved)} token.</>}
        {window.uncertain > 0 && <> Trong số đã dùng có {number.format(window.uncertain)} token tạm tính do chưa nhận đủ số liệu.</>}
      </p>)}
      <p>Tính cả ngữ cảnh, câu trả lời, embedding câu hỏi và tóm tắt hội thoại. Gemini báo usage cho phần sinh câu trả lời; embedding dùng API đếm token đầu vào khi không có usage. Dialog thuần kịch bản không dùng token AI. Hai chu kỳ hồi phục độc lập.</p>
      <p>Gemini Free hiện không tính phí API. Token là hạn mức tài nguyên dành cho tài khoản.</p>
    </div>}
  </div>;
}
