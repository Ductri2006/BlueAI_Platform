import React, { useEffect, useState } from 'react';
import { Activity, Database, FileText, MessageSquare, Plus, RefreshCw, ShieldCheck, UserCog, Users } from 'lucide-react';
import { blueAiApi, type SystemMetricsResponse, type UserResponse } from '../../api/blueAiApi';
import { useAuth } from '../../auth/AuthContext';
import { useAppStore } from '../../store/useAppStore';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';

export const AdminDashboard: React.FC = () => {
  const { user: currentUser } = useAuth();
  const { showToast } = useAppStore();
  const [metrics, setMetrics] = useState<SystemMetricsResponse>();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');

  const load = async () => {
    setLoading(true);
    try {
      const [nextMetrics, nextUsers] = await Promise.all([
        blueAiApi.getSystemMetrics(),
        blueAiApi.listManagedUsers(),
      ]);
      setMetrics(nextMetrics);
      setUsers(nextUsers);
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Không thể tải dữ liệu quản trị.', 'warning');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const createUser = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCreating(true);
    try {
      const created = await blueAiApi.createManagedUser({
        fullName: fullName.trim(), email: email.trim(), password, role,
      });
      setUsers(previous => [created, ...previous]);
      setFullName('');
      setEmail('');
      setPassword('');
      setRole('USER');
      showToast(`Đã cấp tài khoản cho ${created.email}.`, 'success');
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Không thể tạo tài khoản.', 'warning');
    } finally {
      setCreating(false);
    }
  };

  const toggleUserStatus = async (managedUser: UserResponse) => {
    const nextStatus = managedUser.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      const updated = await blueAiApi.updateManagedUserStatus(managedUser.id, nextStatus);
      setUsers(previous => previous.map(user => user.id === updated.id ? updated : user));
      showToast(`${updated.email}: ${updated.status === 'ACTIVE' ? 'đã kích hoạt' : 'đã vô hiệu hóa'}.`, 'success');
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Không thể cập nhật tài khoản.', 'warning');
    }
  };

  const cards = [
    { label: 'Người dùng', value: metrics?.totalUsers, icon: Users },
    { label: 'Hội thoại', value: metrics?.totalConversations, icon: MessageSquare },
    { label: 'Tin nhắn', value: metrics?.totalMessages, icon: Activity },
    { label: 'Tài liệu', value: metrics?.totalDocuments, icon: FileText },
  ];

  const runtimeStatus = metrics?.aiRuntimeStatus;
  const runtimeLabel = runtimeStatus === 'READY'
    ? 'Sẵn sàng'
    : runtimeStatus === 'NOT_CONFIGURED'
      ? 'Chưa khai báo'
      : runtimeStatus === 'NOT_READY'
        ? 'Thiếu cấu hình AI'
        : runtimeStatus === 'UNREACHABLE'
          ? 'Không kết nối được'
          : 'Chưa kiểm tra';
  const runtimeHint = runtimeStatus === 'NOT_READY'
    ? 'Dịch vụ RAG đang chạy nhưng chưa sẵn sàng. Hãy cấu hình GEMINI_API_KEY rồi khởi động lại dịch vụ.'
    : runtimeStatus === 'UNREACHABLE'
      ? 'Backend chưa gọi được dịch vụ RAG. Kiểm tra tiến trình Python và địa chỉ APP_RAG_RETRIEVAL_BASE_URL.'
      : runtimeStatus === 'NOT_CONFIGURED'
        ? 'Backend chưa được khai báo địa chỉ dịch vụ RAG.'
        : runtimeStatus === 'READY'
          ? 'Backend đã xác nhận dịch vụ RAG và Gemini sẵn sàng nhận câu hỏi.'
          : 'Backend phiên bản hiện tại chưa cung cấp kết quả kiểm tra readiness.';

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2"><ShieldCheck className="w-6 h-6 text-cobalt-600" />Quản trị Hệ thống</h1>
          <p className="text-sm text-slate-500 mt-1">Cấp tài khoản, kiểm soát quyền truy cập và theo dõi runtime AI.</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void load()}><RefreshCw className="w-4 h-4 mr-1" />Làm mới</Button>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {cards.map(card => <Card key={card.label} className="p-4"><div className="flex justify-between text-xs text-slate-500"><span>{card.label}</span><card.icon className="w-4 h-4 text-cobalt-600" /></div><p className="text-2xl font-bold mt-1">{loading ? '…' : card.value ?? 0}</p></Card>)}
      </div>

      <div className="grid xl:grid-cols-[0.8fr_1.2fr] gap-6">
        <Card className="p-5">
          <h2 className="font-bold flex items-center gap-2"><UserCog className="w-4 h-4 text-cobalt-600" />Cấp tài khoản mới</h2>
          <p className="mt-1 text-xs leading-5 text-slate-500">Không có đăng ký công khai. Chỉ quản trị viên mới tạo được tài khoản.</p>
          <form onSubmit={createUser} className="mt-5 space-y-3">
            <label className="block"><span className="mb-1 block text-xs font-semibold text-slate-600 dark:text-slate-300">Họ và tên</span><input required maxLength={100} value={fullName} onChange={event => setFullName(event.target.value)} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-900" placeholder="Nguyễn Minh Anh" /></label>
            <label className="block"><span className="mb-1 block text-xs font-semibold text-slate-600 dark:text-slate-300">Email đăng nhập</span><input required type="email" maxLength={190} value={email} onChange={event => setEmail(event.target.value)} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-900" placeholder="user@company.vn" /></label>
            <label className="block"><span className="mb-1 block text-xs font-semibold text-slate-600 dark:text-slate-300">Mật khẩu tạm thời</span><input required type="password" minLength={8} maxLength={72} value={password} onChange={event => setPassword(event.target.value)} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-900" placeholder="Tối thiểu 8 ký tự" /></label>
            <label className="block"><span className="mb-1 block text-xs font-semibold text-slate-600 dark:text-slate-300">Quyền</span><select value={role} onChange={event => setRole(event.target.value as 'USER' | 'ADMIN')} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-900"><option value="USER">USER — sử dụng chat RAG</option><option value="ADMIN">ADMIN — quản trị và nguồn RAG</option></select></label>
            <Button type="submit" variant="primary" className="w-full" disabled={creating}><Plus className="mr-1.5 h-4 w-4" />{creating ? 'Đang tạo...' : 'Cấp tài khoản'}</Button>
          </form>
        </Card>

        <Card className="overflow-hidden">
          <div className="border-b border-slate-200 p-5 dark:border-slate-800"><h2 className="font-bold flex items-center gap-2"><Users className="w-4 h-4" />Danh sách tài khoản</h2><p className="mt-1 text-xs text-slate-500">Vô hiệu hóa tài khoản sẽ chặn lần đăng nhập và refresh token tiếp theo.</p></div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs text-slate-500 dark:bg-slate-900"><tr><th className="p-4">Người dùng</th><th className="p-4">Quyền</th><th className="p-4">Trạng thái</th><th className="p-4 text-right">Thao tác</th></tr></thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {users.map(managedUser => (
                  <tr key={managedUser.id}>
                    <td className="p-4"><p className="font-semibold">{managedUser.fullName}</p><p className="text-xs text-slate-500">{managedUser.email}</p></td>
                    <td className="p-4"><Badge variant={managedUser.role === 'ADMIN' ? 'inferred' : 'subtle'} size="sm">{managedUser.role}</Badge></td>
                    <td className="p-4"><Badge variant={managedUser.status === 'ACTIVE' ? 'confirmed' : 'danger'} size="sm">{managedUser.status}</Badge></td>
                    <td className="p-4 text-right"><Button variant="outline" size="sm" disabled={managedUser.id === currentUser?.id} onClick={() => void toggleUserStatus(managedUser)}>{managedUser.status === 'ACTIVE' ? 'Vô hiệu hóa' : 'Kích hoạt'}</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!loading && users.length === 0 && <p className="p-8 text-center text-sm text-slate-500">Chưa có tài khoản.</p>}
          </div>
        </Card>
      </div>

      <div className="grid lg:grid-cols-2 gap-6">
        <Card className="p-5 space-y-4">
          <h2 className="font-bold flex items-center gap-2"><Database className="w-4 h-4" />Document pipeline</h2>
          <div className="grid grid-cols-3 gap-3 text-center"><div className="rounded-xl bg-emerald-50 dark:bg-emerald-950 p-3"><p className="text-xl font-bold text-emerald-600">{metrics?.readyDocuments ?? 0}</p><p className="text-xs">READY</p></div><div className="rounded-xl bg-amber-50 dark:bg-amber-950 p-3"><p className="text-xl font-bold text-amber-600">{metrics?.pendingDocuments ?? 0}</p><p className="text-xs">PENDING</p></div><div className="rounded-xl bg-rose-50 dark:bg-rose-950 p-3"><p className="text-xl font-bold text-rose-600">{metrics?.failedDocuments ?? 0}</p><p className="text-xs">FAILED</p></div></div>
        </Card>
        <Card className="p-5 space-y-4">
          <h2 className="font-bold">Kết nối AI runtime</h2>
          <div className="flex justify-between"><span className="text-sm text-slate-500">AI readiness</span><Badge variant={metrics?.aiRuntimeReady ? 'confirmed' : 'danger'} size="sm">{runtimeLabel}</Badge></div>
          <div className="flex justify-between"><span className="text-sm text-slate-500">Retriever API</span><Badge variant={metrics?.retrieverConfigured ? 'confirmed' : 'danger'} size="sm">{metrics?.retrieverConfigured ? 'Đã khai báo' : 'Chưa khai báo'}</Badge></div>
          <div className="flex justify-between"><span className="text-sm text-slate-500">LLM API</span><Badge variant={metrics?.llmConfigured ? 'confirmed' : 'danger'} size="sm">{metrics?.llmConfigured ? 'Đã khai báo' : 'Chưa khai báo'}</Badge></div>
          <div className="flex justify-between"><span className="text-sm text-slate-500">Model</span><span className="text-sm font-mono">{metrics?.llmModel || '—'}</span></div>
          <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs leading-5 text-slate-500 dark:bg-slate-900">{runtimeHint}</p>
          <p className="text-xs text-slate-400">Cập nhật: {metrics?.generatedAt ? new Date(metrics.generatedAt).toLocaleString() : '—'}</p>
        </Card>
      </div>
    </div>
  );
};
