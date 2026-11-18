import React from 'react';
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
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(percent * 10) / 10}
      aria-valuetext={`${number.format(window.used)} trên ${number.format(window.limit)} token; đang giữ ${number.format(window.reserved)} token`}
      className="mt-2 flex h-1.5 overflow-hidden rounded-full bg-slate-200/70 dark:bg-slate-700">
      <div style={{ width: `${percent}%` }} className={`h-full rounded-full ${warning ? 'bg-amber-500' : 'bg-cobalt-600 dark:bg-cobalt-400'}`} />
      {held > 0 && <div style={{ width: `${held}%` }} className="h-full bg-cobalt-300 dark:bg-cobalt-700" />}
    </div>
    <p className="mt-1.5 text-xs tabular-nums text-slate-600 dark:text-slate-300">
      {number.format(window.used)} / {number.format(window.limit)} token · còn {number.format(window.remaining)}
    </p>
    {window.reserved > 0 && <p className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">Đang chờ xác nhận {number.format(window.reserved)} token.</p>}
    {window.uncertain > 0 && <p className="mt-1 text-[11px] text-amber-700 dark:text-amber-300">Trong số đã dùng có {number.format(window.uncertain)} token tạm tính.</p>}
    <div className="mt-1.5 flex flex-wrap justify-between gap-x-3 gap-y-1 text-[11px] text-slate-500 dark:text-slate-400">
      <span>{resetLabel(window)}</span>
      {window.remaining === 0 ? <span className="text-amber-700 dark:text-amber-300">{window.used >= window.limit ? 'Đã dùng hết phần khả dụng' : 'Phần khả dụng đang được giữ trước'}</span>
        : warning && <span className="text-amber-700 dark:text-amber-300">Sắp hết hạn mức</span>}
    </div>
  </div>;
}

export function AiTokenQuotaPanel({ quota, error = '' }: { quota?: AiTokenQuota; error?: string }) {
  const windows = quota?.enabled && quota.fiveHour && quota.weekly
    ? [{label:'Mức sử dụng hiện tại · 5 giờ', window:quota.fiveHour}, {label:'Hạn mức hằng tuần · 7 ngày', window:quota.weekly}] : [];
  if (quota && !quota.enabled) return null;
  return <div aria-label="Hạn mức token AI">
    <div className="space-y-2">{windows.map(item => <TokenQuotaBar key={item.label} {...item} />)}</div>
    {error && <p className="py-1 text-xs text-amber-700 dark:text-amber-300" role="status">{error}</p>}
    {!quota && !error && <p className="text-xs text-slate-500" role="status">Đang đọc hạn mức AI…</p>}
    {windows.some(item => item.window.used >= item.window.limit) && <p className="mt-2 text-xs text-amber-700 dark:text-amber-300" role="status">Hạn mức AI đã hết. Các câu trả lời thuần kịch bản Dialog vẫn sử dụng được.</p>}
    {windows.length > 0 && <details className="mt-3 space-y-2 border-t border-slate-100 pt-2 text-[11px] text-slate-500 dark:border-slate-800 dark:text-slate-400">
      <summary className="cursor-pointer py-1 font-medium">Cách tính token</summary>
      <p>Tính cả ngữ cảnh, câu trả lời, embedding câu hỏi và tóm tắt hội thoại. Gemini báo usage cho phần sinh câu trả lời; embedding dùng API đếm token đầu vào khi không có usage. Dialog thuần kịch bản không dùng token AI. Hai chu kỳ hồi phục độc lập.</p>
      <p>Gemini Free hiện không tính phí API. Token là hạn mức tài nguyên dành cho tài khoản.</p>
    </details>}
  </div>;
}
