import React from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './components/auth/LoginPage';
import { loadChatWorkspace } from './components/layout/workspaceLoaders';
import { blueAiApi } from './api/blueAiApi';
import { PasswordChangePage } from './components/auth/PasswordChangeForm';

const loadAppShell = () => import('./components/layout/AppShell');
const AppShell = React.lazy(() => loadAppShell().then(module => ({ default: module.AppShell })));

const SessionFallback = () => (
  <div className="flex min-h-[100dvh] items-center justify-center bg-canvas-light text-slate-900 dark:bg-canvas-dark dark:text-slate-100">
    <div className="flex items-center gap-3 text-sm font-semibold text-slate-600 dark:text-slate-300" role="status">
      <span className="h-5 w-5 animate-spin rounded-full border-2 border-cobalt-500 border-t-transparent dark:border-sky-400 dark:border-t-transparent" />
      Đang kiểm tra phiên đăng nhập...
    </div>
  </div>
);

function AuthenticatedApp() {
  const { status, user } = useAuth();

  React.useEffect(() => {
    if (status === 'checking' && blueAiApi.hasSession()) {
      void Promise.all([loadAppShell(), loadChatWorkspace()]);
    }
  }, [status]);

  if (status === 'checking') {
    return <SessionFallback />;
  }
  if (status === 'authenticated' && user?.passwordChangeRequired) return <PasswordChangePage />;

  return status === 'authenticated'
    ? <React.Suspense fallback={<SessionFallback />}><AppShell /></React.Suspense>
    : <LoginPage />;
}

export function App() {
  return (
    <AuthProvider>
      <AuthenticatedApp />
    </AuthProvider>
  );
}

export default App;
