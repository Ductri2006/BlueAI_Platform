import React from 'react';
import { blueAiApi, type QuotaOverview } from '../../api/blueAiApi';

export function QuotaOverviewPanel() {
  const [overview, setOverview] = React.useState<QuotaOverview>();
  const [error, setError] = React.useState('');
  const [revision, setRevision] = React.useState(0);
  React.useEffect(() => {
    let active = true;
    void blueAiApi.getQuotaOverview().then(result => {
      if (active) { setOverview(result); setError(''); }
    }).catch(() => { if (active) { setOverview(undefined); setError('Chưa tải được thông tin giới hạn.'); } });
    return () => { active = false; };
  }, [revision]);
  return <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900" aria-label="Quản trị quota">
    <div className="mb-4 flex items-center justify-between gap-3"><h2 className="font-bold">Quota và lớp giới hạn truy cập</h2>
      <button type="button" onClick={() => setRevision(value => value + 1)} className="text-sm text-cobalt-600 underline">Làm mới quota</button></div>
    {error && <p role="status" className="text-sm text-amber-700">{error}</p>}
    {overview ? <>
      <div className="grid gap-3 text-sm sm:grid-cols-3">
        <div className="rounded-xl bg-cobalt-50 p-3 dark:bg-cobalt-950"><p className="font-semibold">Chat theo tài khoản</p><p>{overview.chat.limit} lượt / {overview.chat.windowSeconds} giây</p><p>Còn {overview.chat.remaining} lượt của bạn</p></div>
        <div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-800"><p className="font-semibold">Đăng nhập theo tài khoản</p><p>{overview.loginAttemptsPerMinute} lần / phút</p><p>Nginx còn giới hạn riêng theo IP.</p></div>
        <div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-800"><p className="font-semibold">API bot mặc định</p><p>{overview.defaultApiRequestsPerMinute} request / phút / key</p><p>Xem giới hạn đã phát hành tại Custom → Tích hợp.</p></div>
      </div>
      <p className="mt-3 text-xs leading-5 text-slate-500">Widget giới hạn {overview.widgetMessagesPerWindowPerClient} tin / {overview.widgetWindowSeconds} giây / client.
        {' '}Đã chặn {overview.blockedRequestsOnThisInstance.toLocaleString('vi-VN')} lượt vượt quota trên instance backend này từ lúc khởi động. Chỉ số này chưa gồm request bị chặn ở Nginx/CDN.</p>
    </> : !error && <p className="text-sm text-slate-500">Đang tải quota…</p>}
  </section>;
}
