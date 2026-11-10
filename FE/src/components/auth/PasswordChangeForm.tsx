import React from 'react';
import { blueAiApi } from '../../api/blueAiApi';
import { useAuth } from '../../auth/AuthContext';

export function PasswordChangeForm() {
  const { user, logout } = useAuth();
  const forced = Boolean(user?.passwordChangeRequired);
  const [currentPassword, setCurrentPassword] = React.useState('');
  const [newPassword, setNewPassword] = React.useState('');
  const [confirmation, setConfirmation] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState('');
  const inputClass = 'mt-1 block w-full rounded-xl border border-slate-300 bg-transparent px-3 py-2.5 dark:border-slate-600';
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (newPassword !== confirmation) { setError('Hai lần nhập mật khẩu mới chưa khớp.'); return; }
    if (new TextEncoder().encode(newPassword).length > 72) { setError('Mật khẩu mới tối đa 72 byte UTF-8.'); return; }
    setBusy(true);
    setError('');
    try {
      await blueAiApi.changeMyPassword(newPassword, forced ? undefined : currentPassword);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmation('');
      await logout();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Không thể đổi mật khẩu.');
    } finally { setBusy(false); }
  };
  return <form onSubmit={event => void submit(event)} className="space-y-4">
    <p className="text-sm leading-6 text-slate-600 dark:text-slate-300">{forced
      ? 'Bạn đang dùng phiên khôi phục tài khoản. Hãy đặt mật khẩu mới để sử dụng BlueAI. Phiên này hết hạn cùng thời hạn mật khẩu tạm.'
      : 'Đổi mật khẩu sẽ thu hồi các phiên hiện tại. Sau khi lưu, bạn đăng nhập lại bằng mật khẩu mới.'}</p>
    {!forced && <label className="block text-sm font-semibold">Mật khẩu hiện tại<input type="password" autoComplete="current-password" required maxLength={72}
      value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} className={inputClass} disabled={busy} /></label>}
    <label className="block text-sm font-semibold">Mật khẩu mới<input type="password" autoComplete="new-password" required minLength={8} maxLength={72}
      value={newPassword} onChange={event => setNewPassword(event.target.value)} className={inputClass} disabled={busy} /></label>
    <label className="block text-sm font-semibold">Nhập lại mật khẩu mới<input type="password" autoComplete="new-password" required minLength={8} maxLength={72}
      value={confirmation} onChange={event => setConfirmation(event.target.value)} className={inputClass} disabled={busy} /></label>
    <p className="text-xs text-slate-500">Ít nhất 8 ký tự và tối đa 72 byte UTF-8. Chọn mật khẩu riêng, khác mật khẩu đang dùng.</p>
    {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-300">{error}</p>}
    <button type="submit" disabled={busy} className="w-full rounded-xl bg-cobalt-600 px-4 py-2.5 font-bold text-white disabled:opacity-50">{busy ? 'Đang đổi mật khẩu…' : 'Lưu mật khẩu và đăng nhập lại'}</button>
  </form>;
}

export function PasswordChangePage() {
  const { user, logout } = useAuth();
  return <main className="flex min-h-[100dvh] items-center justify-center bg-canvas-light p-5 dark:bg-canvas-dark dark:text-slate-100">
    <section className="w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-7 shadow-xl dark:border-slate-700 dark:bg-slate-900">
      <p className="text-sm font-bold text-cobalt-600">BlueAI · Khôi phục tài khoản</p>
      <h1 className="mt-2 text-2xl font-bold">Đặt mật khẩu mới</h1>
      <p className="mb-5 mt-1 text-sm text-slate-500">{user?.email}</p>
      <PasswordChangeForm />
      <button type="button" onClick={() => void logout()} className="mt-5 text-sm text-slate-500 underline">Đăng xuất và liên hệ admin</button>
    </section>
  </main>;
}
