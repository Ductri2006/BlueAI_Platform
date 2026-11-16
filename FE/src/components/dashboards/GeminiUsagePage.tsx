import React from 'react';
import { Activity, ArrowDownToLine, ArrowUpFromLine, CalendarDays, Check, ChevronLeft, ChevronRight, CircleDollarSign, ExternalLink, Info, RefreshCw } from 'lucide-react';
import { blueAiApi, type GeminiUsageRangeReport } from '../../api/blueAiApi';
import { startGeminiUsagePolling } from './geminiUsagePolling';
import { monthRange, usageMoney, usageRangeError, vietnamToday, type UsageCurrency, type UsagePeriod } from './geminiUsageDisplay';

const number = (value: number) => value.toLocaleString('vi-VN');
const dateLabel = (date: string) => date.split('-').reverse().join('/');
const timestamp = (value: string) => new Date(value).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' });
const inputClass = 'min-w-0 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-700 outline-none focus:border-cobalt-500 focus:ring-2 focus:ring-cobalt-500/15 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 [color-scheme:light] dark:[color-scheme:dark]';
const buttonClass = 'inline-flex items-center justify-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-bold text-slate-600 transition hover:border-cobalt-300 hover:text-cobalt-700 focus-visible:outline-cobalt-500 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300';

function StatCard({ label, value, icon: Icon, hint }: {
  label: string; value: string; icon: React.ComponentType<{ className?: string }>; hint: string;
}) {
  return <div className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-[#0B1324]">
    <div className="flex items-center justify-between gap-2"><span className="text-xs font-semibold text-slate-500">{label}</span><Icon className="h-4 w-4 text-cobalt-500" /></div>
    <p className="mt-3 text-2xl font-black tabular-nums tracking-tight text-slate-900 dark:text-white">{value}</p>
    <p className="mt-2 text-[11px] leading-5 text-slate-500">{hint}</p>
  </div>;
}

function UsagePagination({ page, pages, count, size, loading, onPage, onSize }: {
  page: number; pages: number; count: number; size: number; loading: boolean;
  onPage: (page: number) => void; onSize: (size: number) => void;
}) {
  return <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-slate-500">
    <span>{count ? `${number((page - 1) * size + 1)}–${number(Math.min(page * size, count))} / ${number(count)} lượt gọi` : '0 lượt gọi'}</span>
    <div className="flex flex-wrap items-center gap-2">
      <label className="mr-2 flex items-center gap-2">Số dòng<select aria-label="Số dòng mỗi trang" value={size} onChange={event => onSize(Number(event.target.value))} className={`${inputClass} !py-1.5 !text-xs`}><option value={10}>10</option><option value={25}>25</option><option value={50}>50</option></select></label>
      <button type="button" aria-label="Trang trước" disabled={page <= 1 || loading} onClick={() => onPage(page - 1)} className={buttonClass}><ChevronLeft className="h-3.5 w-3.5" /></button>
      <span className="min-w-20 text-center tabular-nums">Trang <strong className="text-slate-800 dark:text-slate-200">{page}</strong> / {number(pages)}</span>
      <button type="button" aria-label="Trang sau" disabled={page >= pages || loading} onClick={() => onPage(page + 1)} className={buttonClass}><ChevronRight className="h-3.5 w-3.5" /></button>
    </div>
  </div>;
}

export const GeminiUsagePage: React.FC = () => {
  const today = vietnamToday();
  const [period, setPeriod] = React.useState<UsagePeriod>('month');
  const [month, setMonth] = React.useState(() => vietnamToday().slice(0, 7));
  const [day, setDay] = React.useState(today);
  const [custom, setCustom] = React.useState(() => ({ startDate: `${vietnamToday().slice(0, 7)}-01`, endDate: vietnamToday() }));
  const [filters, setFilters] = React.useState(() => ({ ...monthRange(vietnamToday().slice(0, 7)), page: 1, pageSize: 25 }));
  const [report, setReport] = React.useState<GeminiUsageRangeReport>();
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState('');
  const [rangeError, setRangeError] = React.useState('');
  const [currency, setCurrency] = React.useState<UsageCurrency>('USD');
  const [exchangeInput, setExchangeInput] = React.useState('26000');
  const exchange = Number(exchangeInput);
  const exchangeValid = Number.isFinite(exchange) && exchange > 0 && exchange <= 1_000_000;
  const polling = React.useRef<ReturnType<typeof startGeminiUsagePolling<GeminiUsageRangeReport>> | null>(null);

  React.useEffect(() => {
    setReport(current => current?.startDate === filters.startDate && current.endDate === filters.endDate ? current : undefined);
    setError('');
    const controller = startGeminiUsagePolling({
      fetchReport: () => blueAiApi.getGeminiUsage(filters),
      onReport: next => { setReport(next); setError(''); },
      onError: reason => setError(reason instanceof Error ? reason.message : 'Không tải được thống kê chi phí.'),
      onLoading: setLoading,
    });
    polling.current = controller;
    return () => { controller.dispose(); polling.current = null; };
  }, [filters]);

  const applyRange = (startDate: string, endDate: string) => {
    const problem = usageRangeError(startDate, endDate);
    setRangeError(problem);
    if (!problem) setFilters(current => ({ ...current, startDate, endDate, page: 1 }));
  };
  const choosePeriod = (next: UsagePeriod) => {
    setPeriod(next);
    setRangeError('');
    if (next === 'month') { const range = monthRange(month); applyRange(range.startDate, range.endDate); }
    if (next === 'day') applyRange(day, day);
  };
  const changePage = (page: number) => setFilters(current => ({ ...current, page }));
  const changeSize = (pageSize: number) => setFilters(current => ({ ...current, pageSize, page: 1 }));
  const money = (usd: number) => currency === 'VND' && !exchangeValid ? '—' : usageMoney(usd, currency, exchange);
  const total = report?.totals;
  const unpriced = total?.cost.unpricedRequests || 0;
  const totalMoney = (usd: number) => total && total.requests === unpriced && unpriced > 0 ? 'Chưa có giá' : money(usd);
  const maxDaily = Math.max(0.000001, ...(report?.daily.map(entry => entry.cost.totalUsd) || []));
  const paginationProps = {
    page: report?.page || filters.page, pages: report?.totalPages || 1,
    count: total?.requests || 0, size: filters.pageSize, loading,
    onPage: changePage, onSize: changeSize,
  };
  const rangeLabel = filters.startDate === filters.endDate ? dateLabel(filters.startDate) : `${dateLabel(filters.startDate)} – ${dateLabel(filters.endDate)}`;
  const pendingCustom = period === 'custom' && (custom.startDate !== filters.startDate || custom.endDate !== filters.endDate);

  return <section className="h-full min-w-0 flex-1 overflow-y-auto bg-slate-50/80 p-4 dark:bg-[#080E1B] sm:p-6 xl:p-8" aria-label="Theo dõi chi phí API">
    <div className="mx-auto max-w-7xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div><p className="text-[10px] font-black uppercase tracking-[0.2em] text-cobalt-600 dark:text-cobalt-400">BlueAI · API usage</p><h1 className="mt-1.5 text-2xl font-black tracking-tight text-slate-900 dark:text-white">Chi phí API</h1><p className="mt-2 text-sm text-slate-500">Theo dõi chi tiêu Gemini theo tháng, ngày hoặc khoảng thời gian bạn chọn.</p></div>
        <div className="flex items-center gap-2">
          <div className="flex rounded-lg border border-slate-200 bg-white p-1 dark:border-slate-700 dark:bg-slate-900" role="group" aria-label="Đơn vị tiền tệ">{(['USD', 'VND'] as const).map(unit => <button key={unit} type="button" aria-pressed={currency === unit} onClick={() => setCurrency(unit)} className={`rounded-md px-3 py-1.5 text-xs font-bold ${currency === unit ? 'bg-slate-900 text-white dark:bg-cobalt-600' : 'text-slate-500 hover:text-cobalt-600'}`}>{unit === 'VND' ? 'VNĐ' : 'USD'}</button>)}</div>
          <button type="button" onClick={() => void polling.current?.refresh()} disabled={loading} className={buttonClass}><RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /><span className="hidden sm:inline">Làm mới</span></button>
        </div>
      </header>

      <section className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-[#0B1324]" aria-label="Bộ lọc thời gian">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex rounded-xl bg-slate-100 p-1 dark:bg-slate-900" role="group" aria-label="Xem chi phí theo">{([['month', 'Theo tháng'], ['day', 'Theo ngày'], ['custom', 'Khoảng ngày']] as const).map(([key, label]) => <button key={key} type="button" aria-pressed={period === key} onClick={() => choosePeriod(key)} className={`rounded-lg px-4 py-2 text-xs font-bold transition ${period === key ? 'bg-white text-cobalt-700 shadow-sm dark:bg-slate-800 dark:text-cobalt-300' : 'text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'}`}>{label}</button>)}</div>
          <div className="flex flex-wrap items-center gap-2">
            {period === 'month' && <label className="flex items-center gap-2 text-xs font-semibold text-slate-500"><CalendarDays className="h-4 w-4" /><span className="sr-only">Tháng thống kê</span><input type="month" aria-label="Tháng thống kê" value={month} max={today.slice(0, 7)} onChange={event => { if (event.target.value) { setMonth(event.target.value); const range = monthRange(event.target.value); applyRange(range.startDate, range.endDate); } }} className={inputClass} /></label>}
            {period === 'day' && <label className="flex items-center gap-2 text-xs font-semibold text-slate-500"><CalendarDays className="h-4 w-4" /><span className="sr-only">Ngày thống kê</span><input type="date" aria-label="Ngày thống kê" value={day} max={today} onChange={event => { if (event.target.value) { setDay(event.target.value); applyRange(event.target.value, event.target.value); } }} className={inputClass} /></label>}
            {period === 'custom' && <form className="flex flex-wrap items-end gap-2" onSubmit={event => { event.preventDefault(); applyRange(custom.startDate, custom.endDate); }}>
              <label className="flex flex-col gap-1 text-[11px] font-semibold text-slate-500">Từ ngày<input type="date" required value={custom.startDate} max={today} onChange={event => setCustom(current => ({ ...current, startDate: event.target.value }))} className={inputClass} /></label>
              <label className="flex flex-col gap-1 text-[11px] font-semibold text-slate-500">Đến ngày<input type="date" required value={custom.endDate} max={today} onChange={event => setCustom(current => ({ ...current, endDate: event.target.value }))} className={inputClass} /></label>
              <button type="submit" className="inline-flex items-center gap-1.5 rounded-lg bg-cobalt-600 px-4 py-2.5 text-xs font-bold text-white hover:bg-cobalt-700"><Check className="h-3.5 w-3.5" />Áp dụng</button>
            </form>}
          </div>
        </div>
        {rangeError && <p role="alert" className="mt-3 text-xs font-medium text-rose-600">{rangeError}</p>}
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-3 text-[11px] text-slate-500 dark:border-slate-800"><span>Đang xem: <strong className="text-slate-700 dark:text-slate-200">{rangeLabel}</strong> · Giờ Việt Nam (UTC+7){pendingCustom && <span className="ml-2 text-amber-600">Chọn Áp dụng để cập nhật.</span>}</span><span role="status" className="inline-flex items-center gap-1.5"><span className={`h-1.5 w-1.5 rounded-full ${error ? 'bg-amber-500' : 'bg-emerald-500'}`} />{loading ? 'Đang cập nhật…' : 'Tự cập nhật mỗi 5 giây'}</span></div>
      </section>

      {currency === 'VND' && <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500"><label htmlFor="usage-exchange">Tỷ giá quy đổi tự nhập: 1 USD =</label><input id="usage-exchange" type="number" min="1" max="1000000" step="any" value={exchangeInput} onChange={event => setExchangeInput(event.target.value)} aria-invalid={!exchangeValid} className={`${inputClass} w-28 !py-1.5 !text-xs`} /><span>VNĐ · Không phải tỷ giá trực tiếp từ ngân hàng.</span>{!exchangeValid && <span role="alert" className="text-rose-600">Nhập tỷ giá lớn hơn 0, tối đa 1.000.000.</span>}</div>}
      {error && <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300">{error}{report ? ' Đang giữ số liệu từ lần cập nhật thành công gần nhất.' : ''}</div>}
      {unpriced > 0 && <div role="status" className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300"><Info className="mt-0.5 h-4 w-4 shrink-0" />{number(unpriced)} lượt gọi chưa có đơn giá model. Tổng bên dưới chỉ gồm phần đã quy đổi; các lượt còn lại hiển thị “Chưa có giá”.</div>}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-busy={loading && !report}>
        <div className="rounded-2xl bg-slate-900 p-5 text-white dark:bg-cobalt-950"><div className="flex items-center justify-between gap-2 text-xs font-semibold text-slate-300"><span>{unpriced ? 'Chi phí đã quy đổi' : 'Tổng chi phí ước tính'}</span><CircleDollarSign className="h-4 w-4 text-cyan-300" /></div><p className="mt-3 text-3xl font-black tabular-nums tracking-tight">{total ? totalMoney(total.cost.totalUsd) : '—'}</p><p className="mt-2 text-[11px] leading-5 text-slate-400">Trong khoảng thời gian đang chọn · {currency}</p></div>
        <StatCard label="Chi phí đầu vào" value={total ? totalMoney(total.cost.inputUsd) : '—'} icon={ArrowUpFromLine} hint="Hướng dẫn, lịch sử và ngữ cảnh gửi lên" />
        <StatCard label="Chi phí đầu ra" value={total ? totalMoney(total.cost.outputUsd) : '—'} icon={ArrowDownToLine} hint="Bao gồm phần suy luận được ghi nhận" />
        <StatCard label="Lượt gọi được ghi nhận" value={total ? number(total.requests) : '—'} icon={Activity} hint={total && total.requests > unpriced ? `Trung bình ${money(total.cost.totalUsd / (total.requests - unpriced))} / lượt có giá` : 'Các lời gọi trả về số liệu sử dụng'} />
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
        <section className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-[#0B1324]" aria-label="Biểu đồ chi phí theo ngày">
          <div className="flex flex-wrap items-center justify-between gap-2"><div><h2 className="text-sm font-black text-slate-900 dark:text-white">Chi phí theo ngày</h2><p className="mt-1 text-[11px] text-slate-500">{rangeLabel} · Chọn một cột để xem ngày đó</p></div><span className="rounded-md bg-cobalt-50 px-2 py-1 text-[10px] font-bold text-cobalt-600 dark:bg-cobalt-950 dark:text-cobalt-300">{currency}</span></div>
          {!report ? <div role="status" className="flex h-56 items-center justify-center text-sm text-slate-500">{loading ? 'Đang tải biểu đồ…' : 'Không tải được số liệu.'}</div> : !total?.requests ? <div className="mt-4 flex h-52 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-slate-200 text-center dark:border-slate-700"><CalendarDays className="h-6 w-6 text-slate-300" /><p className="text-sm font-semibold text-slate-500">Chưa có lượt gọi trong thời gian này</p><p className="px-4 text-xs text-slate-400">Thử chọn một tháng hoặc khoảng ngày khác.</p></div> : <div className="mt-5 overflow-x-auto pb-2"><div className="flex h-52 items-stretch gap-1" style={{ minWidth: `${Math.max(240, report.daily.length * 26)}px` }}>{report.daily.map(entry => <button key={entry.date} type="button" onClick={() => { setPeriod('day'); setDay(entry.date); applyRange(entry.date, entry.date); }} className="group flex min-w-0 flex-1 flex-col items-center justify-end rounded-lg px-1 pt-2 outline-none hover:bg-slate-50 focus-visible:ring-2 focus-visible:ring-cobalt-500 dark:hover:bg-slate-900" title={`${dateLabel(entry.date)}: ${money(entry.cost.totalUsd)} · ${number(entry.requests)} lượt${entry.cost.unpricedRequests ? ' · Có lượt chưa có giá' : ''}`} aria-label={`${dateLabel(entry.date)}, ${money(entry.cost.totalUsd)}, ${number(entry.requests)} lượt gọi`}>
            <span className="flex w-full flex-1 items-end justify-center border-b border-slate-100 dark:border-slate-800"><span className={`w-full max-w-10 rounded-t-md transition-colors ${entry.cost.totalUsd ? 'bg-cobalt-500 group-hover:bg-cyan-500' : entry.cost.unpricedRequests ? 'bg-amber-300' : 'bg-slate-100 dark:bg-slate-800'}`} style={{ height: entry.cost.totalUsd ? `${Math.max(2, entry.cost.totalUsd / maxDaily * 100)}%` : '2px' }} /></span>
            <span className="mt-2 text-[10px] tabular-nums text-slate-400">{entry.date.slice(8)}/{entry.date.slice(5, 7)}</span>
          </button>)}</div></div>}
          <p className="mt-3 text-[10px] leading-5 text-slate-400">Di chuột hoặc chọn cột để xem chi phí cụ thể. Ngày không có lượt gọi vẫn được hiển thị.</p>
        </section>
        <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-[#0B1324]"><h2 className="text-sm font-black text-slate-900 dark:text-white">Chi tiêu theo model</h2><p className="mt-1 text-[11px] text-slate-500">Cùng khoảng thời gian đang chọn</p><div className="mt-5 max-h-60 space-y-5 overflow-y-auto">{report?.models.length ? report.models.map(model => <div key={model.model}><div className="flex items-start justify-between gap-3 text-xs"><span className="min-w-0 break-all font-semibold text-slate-700 dark:text-slate-200">{model.model}</span><strong className="shrink-0 tabular-nums text-slate-900 dark:text-white">{model.cost.unpricedRequests ? 'Chưa có giá' : money(model.cost.totalUsd)}</strong></div><div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800"><div className="h-full rounded-full bg-cobalt-500" style={{ width: `${total?.cost.totalUsd ? model.cost.totalUsd / total.cost.totalUsd * 100 : 0}%` }} /></div><p className="mt-1.5 text-[10px] text-slate-400">{number(model.requests)} lượt gọi</p></div>) : <p className="py-10 text-center text-xs text-slate-400">{loading ? 'Đang tải…' : 'Chưa có dữ liệu model'}</p>}</div></section>
      </div>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-[#0B1324]" aria-label="Lịch sử chi phí API" aria-busy={loading}>
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4 dark:border-slate-800"><div><h2 className="text-sm font-black text-slate-900 dark:text-white">Lịch sử lượt gọi</h2><p className="mt-1 text-[11px] text-slate-500">Chi phí từng lần gọi API trong {rangeLabel}</p></div><span className="text-[10px] text-slate-400">Cập nhật: {report ? timestamp(report.generatedAt) : '—'}</span></div>
        <div className="border-b border-slate-100 px-5 py-3 dark:border-slate-800"><UsagePagination {...paginationProps} /></div>
        <div className="overflow-x-auto"><table className="w-full min-w-[660px] text-left text-xs"><thead className="bg-slate-50 text-[10px] font-bold uppercase tracking-wider text-slate-400 dark:bg-slate-900/70"><tr><th scope="col" className="px-5 py-3.5">Thời gian (UTC+7)</th><th scope="col" className="px-5 py-3.5">Model</th><th scope="col" className="px-5 py-3.5 text-right">Đầu vào</th><th scope="col" className="px-5 py-3.5 text-right">Đầu ra</th><th scope="col" className="px-5 py-3.5 text-right">Chi phí ({currency})</th></tr></thead><tbody className="divide-y divide-slate-100 dark:divide-slate-800">{report?.recent.map(event => <tr key={event.id} className="hover:bg-slate-50/70 dark:hover:bg-slate-900/50"><td className="whitespace-nowrap px-5 py-4 tabular-nums text-slate-500">{timestamp(event.recordedAt)}</td><td className="px-5 py-4 font-semibold text-slate-700 dark:text-slate-200">{event.model}</td><td className="px-5 py-4 text-right tabular-nums text-slate-500">{event.cost.unpricedRequests ? '—' : money(event.cost.inputUsd)}</td><td className="px-5 py-4 text-right tabular-nums text-slate-500">{event.cost.unpricedRequests ? '—' : money(event.cost.outputUsd)}</td><td className="px-5 py-4 text-right font-bold tabular-nums text-slate-900 dark:text-white">{event.cost.unpricedRequests ? <span className="text-amber-600">Chưa có giá</span> : money(event.cost.totalUsd)}</td></tr>)}</tbody></table>{!report?.recent.length && <p role="status" className="px-5 py-12 text-center text-sm text-slate-500">{loading ? 'Đang tải lịch sử lượt gọi…' : report ? 'Không có lượt gọi trong khoảng ngày này.' : 'Không tải được lịch sử. Hãy thử làm mới.'}</p>}</div>
        <div className="border-t border-slate-100 px-5 py-3 dark:border-slate-800"><UsagePagination {...paginationProps} /></div>
      </section>

      <details className="rounded-xl border border-slate-200 bg-white px-5 py-4 text-xs text-slate-500 dark:border-slate-800 dark:bg-[#0B1324]"><summary className="cursor-pointer font-semibold text-slate-600 dark:text-slate-300">Cách tính chi phí & phạm vi số liệu</summary><div className="mt-3 space-y-2 leading-6"><p>Chi phí ước tính theo đơn giá trả phí tiêu chuẩn của từng model, gồm đầu vào và đầu ra (kể cả phần suy luận được ghi nhận). <a href={report?.pricingSource || 'https://ai.google.dev/gemini-api/docs/pricing'} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-semibold text-cobalt-600 hover:underline">Bảng giá Google<ExternalLink className="h-3 w-3" /></a>{report?.pricingAsOf ? ` · Đối chiếu ngày ${dateLabel(report.pricingAsOf)}.` : ''}</p><p>Đây là chi phí quy đổi từ dữ liệu sử dụng, chưa phải số tiền Google đã thu. Chưa trừ hạn mức miễn phí, chiết khấu, cache; chưa gồm thuế, embedding hoặc dịch vụ bổ sung. Model chưa có đơn giá được đánh dấu riêng.</p><p>Số liệu cộng gộp các lời gọi Gemini có usage metadata được BlueAI ghi nhận từ khi bật thống kê; hiện chưa tách chi phí theo từng API key. Câu trả lời Dialog không gọi Gemini sẽ không phát sinh chi phí ở đây.</p></div></details>
    </div>
  </section>;
};
