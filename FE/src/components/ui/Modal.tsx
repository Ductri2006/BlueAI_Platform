import React from 'react';

export function Modal({ title, children, onClose, busy = false }: {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
  busy?: boolean;
}) {
  const dialog = React.useRef<HTMLDialogElement>(null);
  const titleId = React.useId();
  React.useEffect(() => {
    const previousFocus = document.activeElement;
    const element = dialog.current;
    element?.showModal();
    return () => {
      element?.close();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);
  return <dialog ref={dialog} aria-labelledby={titleId} aria-modal="true"
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}
    className="w-[calc(100vw_-_2rem)] max-w-lg max-h-[90dvh] overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 text-slate-900 shadow-2xl backdrop:bg-slate-950/55 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
    <div className="mb-5 flex items-start justify-between gap-4">
      <h2 id={titleId} className="text-lg font-bold">{title}</h2>
      <button type="button" onClick={onClose} disabled={busy} aria-label="Đóng hộp thoại"
        className="rounded-lg px-2 py-1 text-sm text-slate-500 hover:bg-slate-100 disabled:opacity-40 dark:hover:bg-slate-800">Đóng</button>
    </div>
    {children}
  </dialog>;
}
