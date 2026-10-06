import React from 'react';
import { Activity, ArrowDownToLine, ArrowUpFromLine, RefreshCw, Sparkles } from 'lucide-react';
import { blueAiApi, type GeminiUsageReport, type GeminiUsageTotals } from '../../api/blueAiApi';
import { startGeminiUsagePolling } from './geminiUsagePolling';

const number = (value: number) => value.toLocaleString('vi-VN');

function StatCard({ label, value, icon: Icon, tone }: {
  label: string;
  value: number;
  icon: React.ComponentType<{ className?: string }>;
  tone: string;
}) {
  return <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
    <div className="flex items-center justify-between"><span className="text-xs font-bold text-slate-500">{label}</span><Icon className={`h-4 w-4 ${tone}`} /></div>
    <p className="mt-3 text-2xl font-black tabular-nums text-slate-900 dark:text-white">{number(value)}</p>
  </div>;
}

export const GeminiUsagePage: React.FC = () => {
  const [report, setReport] = React.useState<GeminiUsageReport>();
  const [period, setPeriod] = React.useState<'last24Hours' | 'last7Days' | 'lifetime'>('last7Days');
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState('');
  const polling = React.useRef<ReturnType<typeof startGeminiUsagePolling<GeminiUsageReport>> | null>(null);

  React.useEffect(() => {
    const controller = startGeminiUsagePolling({
      fetchReport: () => blueAiApi.getGeminiUsage(),
      onReport: nextReport => { setReport(nextReport); setError(''); },
      onError: requestError => setError(requestError instanceof Error ? requestError.message : 'Không tải được thống kê Gemini.'),
      onLoading: setLoading,
    });
    polling.current = controller;
    return () => { controller.dispose(); polling.current = null; };
  }, []);
  const totals: GeminiUsageTotals = report?.[period] || { requests: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 };
  const maximum = Math.max(1, ...(report?.daily || []).map(day => day.totalTokens));

  return <section className="h-full min-w-0 flex-1 overflow-y-auto bg-slate-50/60 p-4 dark:bg-[#080E1B] sm:p-6 xl:p-8" aria-label="Theo dõi token Gemini">
    <div className="mx-auto max-w-7xl space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div><p className="text-[11px] font-black uppercase tracking-[0.2em] text-cobalt-600">AI usage</p><h1 className="mt-1 text-2xl font-black text-slate-900 dark:text-white">Token Gemini</h1><p className="mt-1 max-w-2xl text-sm leading-6 text-slate-500">Theo dõi token đầu vào và đầu ra từ các lời gọi Gemini chat. Số liệu lấy từ phản hồi của nhà cung cấp và chỉ tính từ khi tính năng này được bật.</p></div>
        <button type="button" onClick={() => void polling.current?.refresh()} disabled={loading} className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-xs font-bold text-slate-700 shadow-sm hover:border-cobalt-300 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"><RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />Làm mới</button>
      </header>

      {error && <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300">{error}</div>}
      <p role="status" className="text-xs text-slate-500">{!report && loading ? 'Đang tải số liệu Gemini…' : 'Tự cập nhật mỗi 5 giây khi trang đang hiển thị và khi quay lại tab.'}{error && report ? ' Đang hiển thị số liệu từ lần tải thành công gần nhất.' : ''}</p>

      <div className="flex flex-wrap gap-2" role="group" aria-label="Khoảng thời gian">
        {([['last24Hours', '24 giờ'], ['last7Days', '7 ngày'], ['lifetime', 'Toàn bộ']] as const).map(([key, label]) => <button key={key} type="button" onClick={() => setPeriod(key)} aria-pressed={period === key} className={`rounded-full px-4 py-2 text-xs font-bold ${period === key ? 'bg-cobalt-600 text-white shadow-sm' : 'border border-slate-200 bg-white text-slate-600 hover:border-cobalt-300 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300'}`}>{label}</button>)}
      </div>

      {report && <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Lượt gọi có số liệu" value={totals.requests} icon={Activity} tone="text-cobalt-500" />
        <StatCard label="Token gửi lên" value={totals.promptTokens} icon={ArrowUpFromLine} tone="text-cyan-500" />
        <StatCard label="Token phản hồi" value={totals.completionTokens} icon={ArrowDownToLine} tone="text-emerald-500" />
        <StatCard label="Tổng token" value={totals.totalTokens} icon={Sparkles} tone="text-violet-500" />
      </div>}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(330px,0.8fr)]">
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
          <div className="flex items-center justify-between gap-3"><h2 className="text-sm font-black">7 ngày gần đây</h2><span className="text-[11px] text-slate-500">Tổng token theo ngày</span></div>
          {report?.daily.length ? <div className="mt-7 flex h-48 items-end gap-3 border-b border-slate-200 pb-2 dark:border-slate-700">{report.daily.map(day => <div key={day.date} className="group flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-2" title={`${day.date}: ${number(day.totalTokens)} token`}><span className="text-[10px] font-bold tabular-nums text-slate-500">{number(day.totalTokens)}</span><div className="w-full max-w-16 rounded-t-lg bg-gradient-to-t from-cobalt-700 to-cyan-400" style={{ height: `${Math.max(6, day.totalTokens / maximum * 100)}%` }} /><span className="whitespace-nowrap text-[10px] text-slate-500">{day.date.slice(5)}</span></div>)}</div> : <p className="mt-6 rounded-xl border border-dashed border-slate-200 px-4 py-12 text-center text-sm text-slate-500 dark:border-slate-700">{report ? 'Chưa có lời gọi Gemini chat nào trả số liệu token trong 7 ngày qua.' : loading ? 'Đang tải số liệu…' : 'Chưa tải được số liệu. Hãy thử làm mới.'}</p>}
        </section>
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
          <h2 className="text-sm font-black">Cách đọc số liệu</h2>
          <div className="mt-4 space-y-3 text-sm leading-6 text-slate-600 dark:text-slate-300"><p><strong>Token gửi lên</strong> là toàn bộ prompt Gemini nhận, gồm hướng dẫn hệ thống, lịch sử và ngữ cảnh RAG.</p><p><strong>Token phản hồi</strong> là phần Gemini sinh ra. <strong>Tổng token</strong> dùng số liệu Gemini cung cấp, nên có thể lớn hơn tổng hai phần khi mô hình tính thêm token suy luận.</p><p>Những câu được Dialog trả lời trực tiếp không gọi Gemini, nên không làm tăng số token.</p><p className="rounded-xl bg-slate-50 p-3 text-xs text-slate-500 dark:bg-slate-900">Lời gọi embedding và các phản hồi không có usage metadata chưa được tính. Hệ thống không lưu nội dung prompt trong bảng thống kê này.</p></div>
        </section>
      </div>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
        <div className="flex items-center justify-between border-b border-slate-200 p-5 dark:border-slate-800"><h2 className="text-sm font-black">Lời gọi gần đây</h2><span className="text-xs text-slate-500">Tối đa 50 lượt · {report?.generatedAt ? new Date(report.generatedAt).toLocaleString('vi-VN') : '—'}</span></div>
        <div className="overflow-x-auto"><table className="w-full min-w-[650px] text-left text-xs"><thead className="bg-slate-50 text-slate-500 dark:bg-slate-900"><tr><th className="px-5 py-3">Thời gian</th><th className="px-5 py-3">Model</th><th className="px-5 py-3 text-right">Gửi lên</th><th className="px-5 py-3 text-right">Phản hồi</th><th className="px-5 py-3 text-right">Tổng</th></tr></thead><tbody className="divide-y divide-slate-100 dark:divide-slate-800">{report?.recent.map(event => <tr key={event.id}><td className="whitespace-nowrap px-5 py-3 text-slate-500">{new Date(event.recordedAt).toLocaleString('vi-VN')}</td><td className="px-5 py-3 font-bold">{event.model}</td><td className="px-5 py-3 text-right tabular-nums">{number(event.promptTokens)}</td><td className="px-5 py-3 text-right tabular-nums">{number(event.completionTokens)}</td><td className="px-5 py-3 text-right font-bold tabular-nums">{number(event.totalTokens)}</td></tr>)}</tbody></table>{!loading && !report?.recent.length && <p className="px-5 py-8 text-center text-sm text-slate-500">Chưa có lượt gọi được ghi nhận.</p>}</div>
      </section>
    </div>
  </section>;
};
