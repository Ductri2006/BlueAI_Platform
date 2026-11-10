import React from 'react';
import { blueAiApi, type TemporaryPassword, type UserResponse } from '../../api/blueAiApi';
import { Modal } from '../ui/Modal';

export function AccountActionDialog({ user, action, onClose, onDeleted }: {
  user: UserResponse; action: 'reset' | 'delete'; onClose: () => void; onDeleted: (user: UserResponse) => void;
}) {
  const [password, setPassword] = React.useState('');
  const [result, setResult] = React.useState<TemporaryPassword>();
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState('');
  const [copied, setCopied] = React.useState(false);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      if (action === 'reset') {
        setResult(await blueAiApi.resetManagedUserPassword(user.id, password));
        setPassword('');
      } else {
        onDeleted(await blueAiApi.deleteManagedUser(user.id));
        onClose();
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Không thực hiện được thao tác.'); }
    finally { setBusy(false); }
  };
  const copy = async () => {
    if (!result) return;
    try { await navigator.clipboard.writeText(result.temporaryPassword); setCopied(true); }
    catch { setError('Không sao chép được. Bạn có thể chọn và sao chép mật khẩu trong ô bên dưới.'); }
  };
  return <Modal title={action === 'reset' ? 'Reset mật khẩu User' : 'Xóa tài khoản User'} onClose={onClose} busy={busy}>
    <p className="mb-4 break-words text-sm font-semibold">{user.fullName} · {user.email}</p>
    {result ? <div className="space-y-4">
      <p className="text-sm text-emerald-700 dark:text-emerald-300" role="status">Đã tạo mật khẩu tạm và thu hồi phiên cũ.</p>
      <label className="block text-sm font-semibold">Mật khẩu tạm<input type="text" readOnly autoComplete="off" spellCheck={false}
        value={result.temporaryPassword} className="mt-2 w-full rounded-xl border border-slate-300 bg-slate-50 p-3 font-mono dark:border-slate-600 dark:bg-slate-800" /></label>
      <button type="button" onClick={() => void copy()} className="rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white">{copied ? 'Đã sao chép' : 'Sao chép mật khẩu tạm'}</button>
      <p className="text-sm">Hết hạn lúc {new Date(result.expiresAt).toLocaleString('vi-VN')}. Chỉ dùng một lần để mở bước đặt mật khẩu mới.</p>
      <p className="text-sm text-amber-700 dark:text-amber-300">Chỉ gửi riêng sau khi xác minh đúng người yêu cầu. Sau khi đóng bảng, hệ thống chỉ giữ bản băm. Nếu mất mật khẩu tạm, hãy cấp lại.</p>
    </div> : <form onSubmit={event => void submit(event)} className="space-y-4">
      <p className="text-sm leading-6 text-slate-600 dark:text-slate-300">{action === 'reset'
        ? 'Xác minh đúng chủ tài khoản trước khi tiếp tục. Thời điểm hết hạn sẽ hiển thị khi cấp mật khẩu tạm. User bắt buộc đặt mật khẩu mới.'
        : 'Tài khoản sẽ bị xóa mềm và các phiên đăng nhập bị thu hồi. Lịch sử được giữ phục vụ kiểm tra.'}</p>
      {action === 'reset' && <label className="block text-sm font-semibold">Mật khẩu admin để xác nhận<input type="password" autoComplete="current-password" required maxLength={72}
        value={password} onChange={event => setPassword(event.target.value)} disabled={busy} className="mt-2 w-full rounded-xl border border-slate-300 bg-transparent p-3 dark:border-slate-600" /></label>}
      <button type="submit" disabled={busy} className={`rounded-xl px-4 py-2 text-sm font-bold text-white disabled:opacity-50 ${action === 'delete' ? 'bg-rose-600' : 'bg-cobalt-600'}`}>
        {busy ? 'Đang xử lý…' : action === 'reset' ? 'Tạo mật khẩu tạm' : 'Xác nhận xóa User'}
      </button>
    </form>}
    {error && <p role="alert" className="mt-4 text-sm text-rose-600 dark:text-rose-300">{error}</p>}
  </Modal>;
}
