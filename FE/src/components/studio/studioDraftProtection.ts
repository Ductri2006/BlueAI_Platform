export interface StudioDraftGuard { dirty: boolean; busy: boolean }

export async function confirmStudioExit(guard: StudioDraftGuard, confirmDiscard: (message: string) => boolean | Promise<boolean>, onBusy: () => void): Promise<boolean> {
  if (guard.busy) { onBusy(); return false; }
  if (!guard.dirty) return true;
  const accepted = await confirmDiscard('Rời cấu hình này sẽ bỏ các thay đổi chưa lưu. Chọn “Ở lại chỉnh sửa” để quay lại lưu nháp.');
  if (guard.busy) { onBusy(); return false; }
  return accepted;
}

export function beginStudioOperation(guard: StudioDraftGuard): boolean {
  if (guard.busy) return false;
  guard.busy = true;
  return true;
}

export function isStudioExitControl(control: Element): boolean {
  if (control.matches('a[href]')) return control.getAttribute('target') !== '_blank' && !control.hasAttribute('download');
  if (control.getAttribute('aria-label') === 'Đăng xuất') return true;
  // Current app changes workspace through these SidebarRail controls rather than a router.
  return Boolean(control.closest('aside[aria-label="Thanh điều hướng chính"]'))
    && ['Hỏi BlueAI', 'Tài liệu & Tri thức RAG', 'Token Gemini', 'Chi phí API', 'Quản trị Hệ thống'].includes(control.getAttribute('title') || '');
}

export function installStudioExitProtection(root: () => HTMLElement | null, guard: StudioDraftGuard, onBusy: () => void,
  confirmDiscard: (message: string) => Promise<boolean>): () => void {
  let active = true;
  let replaying = false;
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!guard.dirty && !guard.busy) return;
    event.preventDefault();
    event.returnValue = '';
  };
  const click = (event: MouseEvent) => {
    if (replaying || (event.button ?? 0) !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (!(event.target instanceof Element)) return;
    const control = event.target.closest('button, a[href]');
    if (!control || root()?.contains(control) || !isStudioExitControl(control)) return;
    if (!guard.dirty && !guard.busy) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    void confirmStudioExit(guard, confirmDiscard, onBusy).then(accepted => {
      if (!accepted || !active || !control.isConnected) return;
      replaying = true;
      try { (control as HTMLElement).click(); } finally { replaying = false; }
    });
  };
  window.addEventListener('beforeunload', beforeUnload);
  document.addEventListener('click', click, true);
  return () => {
    active = false;
    window.removeEventListener('beforeunload', beforeUnload);
    document.removeEventListener('click', click, true);
  };
}
