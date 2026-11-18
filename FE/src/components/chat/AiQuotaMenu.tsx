import React from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown, Gauge, RefreshCw, X } from 'lucide-react';
import type { AiTokenQuota } from '../../api/blueAiApi';
import { AiTokenQuotaPanel } from './AiTokenQuotaPanel';

export function aiQuotaStatus(quota?: AiTokenQuota, error = '') {
  if (quota && !quota.enabled) return { label: 'Hạn mức AI', tone: 'normal', hidden: true };
  if (error) return { label: 'Chưa đọc được hạn mức AI', tone: 'warning', hidden: false };
  const windows = [quota?.fiveHour, quota?.weekly].filter(window => window != null);
  if (windows.some(window => window.limit > 0 && window.used >= window.limit)) {
    return { label: 'Hết hạn mức AI', tone: 'exhausted', hidden: false };
  }
  if (windows.some(window => window.remaining === 0 && window.reserved > 0)) {
    return { label: 'Đang chờ xác nhận token', tone: 'warning', hidden: false };
  }
  if (windows.some(window => window.limit > 0 && window.used + window.reserved >= window.limit * 0.8)) {
    return { label: 'Sắp hết hạn mức AI', tone: 'warning', hidden: false };
  }
  return { label: 'Hạn mức AI', tone: 'normal', hidden: false };
}

export function AiQuotaMenu({ quota, error = '', refresh, iconOnly = false }: {
  quota?: AiTokenQuota; error?: string; refresh: () => Promise<void>; iconOnly?: boolean;
}) {
  const status = aiQuotaStatus(quota, error);
  const [open, setOpen] = React.useState(false);
  const [refreshing, setRefreshing] = React.useState(false);
  const [position, setPosition] = React.useState<{ top: number; left: number; width: number }>();
  const trigger = React.useRef<HTMLButtonElement>(null);
  const panel = React.useRef<HTMLDivElement>(null);
  const id = React.useId();
  const titleId = React.useId();
  const close = React.useCallback((restoreFocus = false) => {
    setOpen(false);
    if (restoreFocus) trigger.current?.focus();
  }, []);

  React.useEffect(() => {
    if (!open || status.hidden) return;
    const dismiss = (event: Event) => {
      const target = event.target;
      if (target instanceof Node && !panel.current?.contains(target) && !trigger.current?.contains(target)) close();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
    };
    document.addEventListener('pointerdown', dismiss, true);
    document.addEventListener('focusin', dismiss);
    document.addEventListener('keydown', escape, true);
    panel.current?.querySelector<HTMLButtonElement>('[aria-label="Đóng hạn mức AI"]')?.focus();
    return () => {
      document.removeEventListener('pointerdown', dismiss, true);
      document.removeEventListener('focusin', dismiss);
      document.removeEventListener('keydown', escape, true);
    };
  }, [open, status.hidden, close]);

  React.useLayoutEffect(() => {
    if (!open || status.hidden) return;
    const place = () => {
      const anchor = trigger.current?.getBoundingClientRect();
      if (!anchor) return;
      const width = Math.min(352, window.innerWidth - 24);
      const height = panel.current?.getBoundingClientRect().height ?? 320;
      const below = anchor.bottom + 8;
      setPosition({ width, left: Math.max(12, Math.min(anchor.right - width, window.innerWidth - width - 12)),
        top: below + height <= window.innerHeight - 12 ? below : Math.max(12, anchor.top - height - 8) });
    };
    place();
    const resize = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(place) : undefined;
    if (panel.current) resize?.observe(panel.current);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      resize?.disconnect();
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open, status.hidden]);

  if (status.hidden) return null;
  const tone = status.tone === 'exhausted' ? 'text-rose-700 dark:text-rose-300' : status.tone === 'warning'
    ? 'text-amber-700 dark:text-amber-300' : 'text-slate-600 dark:text-slate-300';
  return <>
    <button ref={trigger} type="button" aria-label={status.label} aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => { setPosition(undefined); setOpen(value => !value); }}
      className={`relative flex min-h-10 min-w-10 items-center justify-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2.5 text-xs font-medium hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cobalt-500 dark:border-slate-700 dark:bg-slate-900 dark:hover:bg-slate-800 sm:min-h-9 ${tone}`}>
      <Gauge className="h-4 w-4 shrink-0" aria-hidden="true" />
      {!iconOnly && <span className="hidden sm:inline">{status.tone === 'normal' ? 'Hạn mức AI' : status.tone === 'exhausted' ? 'Hết hạn mức' : 'Kiểm tra hạn mức'}</span>}
      {!iconOnly && <ChevronDown className="hidden h-3 w-3 sm:block" aria-hidden="true" />}
      {status.tone !== 'normal' && <span className={`absolute right-1 top-1 h-1.5 w-1.5 rounded-full ${status.tone === 'exhausted' ? 'bg-rose-500' : 'bg-amber-500'}`} aria-hidden="true" />}
    </button>
    {open && createPortal(<div ref={panel} id={id} role="dialog" aria-labelledby={titleId}
      style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
      className="fixed z-[90] max-h-[calc(100dvh_-_1.5rem)] overflow-y-auto rounded-2xl border border-slate-200 bg-white p-4 text-slate-900 shadow-2xl dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 id={titleId} className="text-sm font-semibold">Hạn mức sử dụng AI</h2>
        <div className="flex items-center gap-1">
          <button type="button" disabled={refreshing} aria-label="Làm mới hạn mức AI"
            onClick={async () => { setRefreshing(true); try { await refresh(); } finally { setRefreshing(false); } }}
            className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 disabled:opacity-40 dark:text-slate-400 dark:hover:bg-slate-800">
            <RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin motion-reduce:animate-none' : ''}`} aria-hidden="true" />
          </button>
          <button type="button" aria-label="Đóng hạn mức AI" onClick={() => close(true)}
            className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"><X className="h-4 w-4" aria-hidden="true" /></button>
        </div>
      </div>
      <AiTokenQuotaPanel quota={quota} error={error} />
    </div>, document.body)}
  </>;
}
