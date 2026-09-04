import React from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './components/auth/LoginPage';
import { AppShell } from './components/layout/AppShell';

function AuthenticatedApp() {
  const { status } = useAuth();

  if (status === 'checking') {
    return (
      <div className="flex min-h-[100dvh] items-center justify-center bg-[#071225] text-slate-100">
        <div className="flex items-center gap-3 text-sm font-semibold text-slate-300" role="status">
          <span className="h-5 w-5 animate-spin rounded-full border-2 border-sky-400 border-t-transparent" />
          Đang kiểm tra phiên đăng nhập...
        </div>
      </div>
    );
  }

  return status === 'authenticated' ? <AppShell /> : <LoginPage />;
}

export function App() {
  return (
    <AuthProvider>
      <AuthenticatedApp />
    </AuthProvider>
  );
}

export default App;
