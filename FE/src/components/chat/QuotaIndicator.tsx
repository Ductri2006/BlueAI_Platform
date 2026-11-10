import React from 'react';
import { ShieldCheck } from 'lucide-react';
import type { ChatQuota } from '../../api/blueAiApi';

export function QuotaIndicator({ quota, seconds, error, refresh, blocked }: {
  quota?: ChatQuota; seconds: number; error: string; refresh: () => void; blocked: boolean;
}) {
  return <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500 shadow-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400" aria-label="Quota gửi câu hỏi">
    <ShieldCheck className="h-3.5 w-3.5" aria-hidden="true" />
    {error ? <span>{error}</span> : quota ? <>
      <span>Giới hạn {quota.limit} lượt / {quota.windowSeconds} giây</span>
      <span className={blocked ? 'font-semibold text-amber-700 dark:text-amber-300' : ''}>Còn {quota.remaining} lượt</span>
      {seconds > 0 && <span>{blocked ? 'Có thể gửi lại' : 'Phục hồi'} sau {seconds} giây</span>}
    </> : <span>Đang đọc quota…</span>}
    <button type="button" onClick={refresh} className="underline underline-offset-2" aria-label="Tải lại quota">Làm mới</button>
    <span className="w-full pl-6 text-[11px]">Quota theo tài khoản, dùng chung giữa các tab và Thử BlueAI.</span>
  </div>;
}
