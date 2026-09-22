import React, { useState } from 'react';
import {
  ArrowRight,
  Bot,
  BrainCircuit,
  CheckCircle2,
  Eye,
  EyeOff,
  FileSearch,
  LockKeyhole,
  Mail,
  ShieldCheck,
} from 'lucide-react';
import { BlueAiApiError } from '../../api/blueAiApi';
import { useAuth } from '../../auth/AuthContext';

function friendlyError(error: unknown): string {
  if (error instanceof BlueAiApiError) {
    if (error.code === 'INVALID_CREDENTIALS' || error.status === 401) {
      return 'Email hoặc mật khẩu không đúng.';
    }
    return error.message;
  }
  const detail = error instanceof Error ? ` (${error.message})` : '';
  return `Không thể kết nối máy chủ. Vui lòng kiểm tra Backend và thử lại.${detail}`;
}

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      await login({ email: email.trim(), password });
    } catch (submitError) {
      setError(friendlyError(submitError));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="relative min-h-[100dvh] overflow-hidden bg-[#071225] text-slate-100">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_15%_18%,rgba(37,99,235,0.24),transparent_34%),radial-gradient(circle_at_82%_76%,rgba(14,165,233,0.17),transparent_32%)]" />
      <div className="absolute inset-0 opacity-20 bg-ambient-grid" />

      <div className="relative mx-auto grid min-h-[100dvh] max-w-7xl lg:grid-cols-[1.08fr_0.92fr]">
        <section className="hidden flex-col justify-between px-12 py-10 lg:flex xl:px-20 xl:py-14">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-cobalt-600 shadow-[0_12px_40px_rgba(37,99,235,0.35)]">
              <BrainCircuit className="h-6 w-6" />
            </div>
            <div>
              <p className="text-lg font-extrabold tracking-tight">BlueAI Enterprise</p>
              <p className="text-xs font-medium text-sky-300">Knowledge you can verify</p>
            </div>
          </div>

          <div className="max-w-xl">
            <div className="mb-5 inline-flex items-center gap-2 rounded-full border border-sky-400/20 bg-sky-400/10 px-3 py-1.5 text-xs font-semibold text-sky-200">
              <Bot className="h-3.5 w-3.5" />
              Trợ lý AI có dẫn nguồn
            </div>
            <h1 className="text-4xl font-black leading-tight tracking-[-0.035em] xl:text-5xl">
              Hỏi dữ liệu doanh nghiệp.
              <span className="block bg-gradient-to-r from-sky-300 to-blue-400 bg-clip-text text-transparent">Nhận câu trả lời có căn cứ.</span>
            </h1>
            <p className="mt-5 max-w-lg text-base leading-7 text-slate-300">
              Một không gian an toàn để trò chuyện với kho tri thức RAG, kiểm tra nguồn trích dẫn và quản lý tài liệu theo tài khoản của bạn.
            </p>

            <div className="mt-9 grid gap-4 sm:grid-cols-3">
              {[
                { icon: FileSearch, title: 'RAG có nguồn', text: 'Truy xuất đúng tài liệu' },
                { icon: ShieldCheck, title: 'Phân quyền', text: 'Dữ liệu theo tài khoản' },
                { icon: CheckCircle2, title: 'Minh bạch', text: 'Kiểm tra từng trích dẫn' },
              ].map(({ icon: Icon, title, text }) => (
                <div key={title} className="rounded-2xl border border-white/10 bg-white/[0.045] p-4 backdrop-blur-sm">
                  <Icon className="mb-3 h-5 w-5 text-sky-300" />
                  <p className="text-sm font-bold">{title}</p>
                  <p className="mt-1 text-xs leading-5 text-slate-400">{text}</p>
                </div>
              ))}
            </div>
          </div>

          <p className="text-xs text-slate-500">© 2026 Bluecom Solutions · Enterprise AI Platform</p>
        </section>

        <section className="flex items-center justify-center px-5 py-8 sm:px-8 lg:bg-white/[0.025]">
          <div className="w-full max-w-md">
            <div className="mb-8 flex items-center gap-3 lg:hidden">
              <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-cobalt-600">
                <BrainCircuit className="h-5 w-5" />
              </div>
              <div>
                <p className="font-extrabold">BlueAI Enterprise</p>
                <p className="text-xs text-sky-300">Knowledge you can verify</p>
              </div>
            </div>

            <div className="rounded-[2rem] border border-white/10 bg-[#0B1930]/95 p-6 shadow-2xl sm:p-8">
              <div>
                <p className="text-xs font-bold uppercase tracking-[0.18em] text-sky-300">Tài khoản BlueAI</p>
                <h2 className="mt-2 text-2xl font-black tracking-tight">Chào mừng trở lại</h2>
                <p className="mt-2 text-sm leading-6 text-slate-400">
                  Đăng nhập bằng tài khoản do quản trị viên BlueAI cung cấp.
                </p>
              </div>

              <form onSubmit={submit} className="mt-6 space-y-4">
                <label className="block">
                  <span className="mb-1.5 block text-xs font-bold text-slate-300">Email</span>
                  <span className="relative block">
                    <Mail className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                    <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required maxLength={190} autoComplete="email" placeholder="ban@congty.vn" className="w-full rounded-xl border border-white/10 bg-slate-950/35 py-3 pl-10 pr-4 text-sm text-white placeholder:text-slate-600 focus:border-sky-500" />
                  </span>
                </label>

                <label className="block">
                  <span className="mb-1.5 block text-xs font-bold text-slate-300">Mật khẩu</span>
                  <span className="relative block">
                    <LockKeyhole className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                    <input type={showPassword ? 'text' : 'password'} value={password} onChange={(event) => setPassword(event.target.value)} required maxLength={72} autoComplete="current-password" placeholder="Nhập mật khẩu" className="w-full rounded-xl border border-white/10 bg-slate-950/35 py-3 pl-10 pr-11 text-sm text-white placeholder:text-slate-600 focus:border-sky-500" />
                    <button type="button" onClick={() => setShowPassword((visible) => !visible)} className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1 text-slate-500 hover:text-white" aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}>
                      {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                    </button>
                  </span>
                </label>

                {error && <p className="rounded-xl border border-rose-400/20 bg-rose-400/10 px-3.5 py-3 text-sm text-rose-200" role="alert">{error}</p>}

                <button type="submit" disabled={submitting} className="group flex w-full items-center justify-center gap-2 rounded-xl bg-cobalt-600 px-4 py-3 text-sm font-extrabold text-white shadow-[0_12px_28px_rgba(37,99,235,0.3)] transition hover:bg-cobalt-500 disabled:cursor-wait disabled:opacity-60">
                  {submitting ? 'Đang xử lý...' : 'Vào BlueAI'}
                  {!submitting && <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />}
                </button>
              </form>

              <p className="mt-5 text-center text-xs leading-5 text-slate-500">
                Phiên đăng nhập được bảo vệ bằng access token và refresh token từ Backend.
              </p>
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}
