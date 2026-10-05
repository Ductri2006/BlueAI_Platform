import React from 'react';
import { AlertTriangle } from 'lucide-react';

export function useStudioConfirmation() {
  const [message, setMessage] = React.useState<string | null>(null);
  const pending = React.useRef<((accepted: boolean) => void) | null>(null);
  const confirm = React.useCallback((text: string): Promise<boolean> => {
    // Never overwrite a pending decision when an action is clicked twice.
    if (pending.current) return Promise.resolve(false);
    return new Promise(resolve => {
      pending.current = resolve;
      setMessage(text);
    });
  }, []);
  const decide = React.useCallback((accepted: boolean) => {
    const resolve = pending.current;
    pending.current = null;
    setMessage(null);
    resolve?.(accepted);
  }, []);
  React.useEffect(() => () => {
    pending.current?.(false);
    pending.current = null;
  }, []);
  return { message, confirm, decide };
}

export function StudioDiscardDialog({ message, onDecision }: {
  message: string;
  onDecision: (accepted: boolean) => void;
}) {
  const dialog = React.useRef<HTMLDialogElement>(null);
  const stayButton = React.useRef<HTMLButtonElement>(null);
  const discardButton = React.useRef<HTMLButtonElement>(null);
  const titleId = React.useId();
  const descriptionId = React.useId();
  React.useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousFocus = document.activeElement;
    element.showModal();
    return () => {
      element.close();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);
  return (
    <dialog ref={dialog} role="alertdialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId}
      onCancel={event => { event.preventDefault(); onDecision(false); }}
      onKeyDown={event => {
        if (event.key !== 'Tab') return;
        const boundary = event.shiftKey ? stayButton.current : discardButton.current;
        const next = event.shiftKey ? discardButton.current : stayButton.current;
        if (boundary && next && document.activeElement === boundary) {
          event.preventDefault();
          next.focus();
        }
      }}
      className="w-[calc(100vw_-_2rem)] max-w-md rounded-2xl border border-slate-200 bg-white p-6 text-slate-800 shadow-2xl backdrop:bg-slate-950/55 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
      <div className="flex items-center gap-2"><AlertTriangle className="h-5 w-5 shrink-0 text-amber-600" /><h2 id={titleId} className="text-lg font-bold">Có thay đổi chưa lưu</h2></div>
      <p id={descriptionId} className="mt-3 text-sm leading-6 text-slate-600 dark:text-slate-300">{message}</p>
      <div className="mt-5 flex flex-wrap justify-end gap-2">
        <button ref={stayButton} type="button" autoFocus onClick={() => onDecision(false)} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-semibold hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cobalt-500 dark:border-slate-600 dark:hover:bg-slate-800">Ở lại chỉnh sửa</button>
        <button ref={discardButton} type="button" onClick={() => onDecision(true)} className="rounded-xl bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-rose-500">Bỏ thay đổi và tiếp tục</button>
      </div>
    </dialog>
  );
}
