import React, { lazy, Suspense } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { useAuth } from '../../auth/AuthContext';
import { SidebarRail } from './SidebarRail';
import { TopHeader } from './TopHeader';
import { CheckCircle2, Info, AlertTriangle } from 'lucide-react';
import { loadChatWorkspace } from './workspaceLoaders';
import { ChatQuotaProvider } from '../chat/ChatQuotaContext';

const ChatWorkspace = lazy(() => loadChatWorkspace().then(module => ({ default: module.ChatWorkspace })));
const KnowledgeDashboard = lazy(() => import('../dashboards/KnowledgeDashboard').then(module => ({ default: module.KnowledgeDashboard })));
const AdminDashboard = lazy(() => import('../dashboards/AdminDashboard').then(module => ({ default: module.AdminDashboard })));
const GeminiUsagePage = lazy(() => import('../dashboards/GeminiUsagePage').then(module => ({ default: module.GeminiUsagePage })));
const ChatbotStudio = lazy(() => import('../studio/ChatbotStudio').then(module => ({ default: module.ChatbotStudio })));
const EvidenceDrawer = lazy(() => import('../chat/EvidenceDrawer').then(module => ({ default: module.EvidenceDrawer })));

const WorkspaceFallback: React.FC = () => (
  <section className="flex flex-1 items-center justify-center bg-canvas-light dark:bg-canvas-dark" aria-busy="true" aria-label="Đang tải không gian làm việc">
    <div className="w-full max-w-3xl space-y-4 px-6">
      <div className="h-8 w-48 animate-pulse rounded-xl bg-slate-200/80 dark:bg-slate-800" />
      <div className="h-28 w-full animate-pulse rounded-2xl bg-white/80 shadow-subtle dark:bg-slate-900/70" />
    </div>
  </section>
);

export const AppShell: React.FC = () => {
  const { activeRole, toast, isEvidenceOpen } = useAppStore();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const renderActiveWorkspace = () => {
    switch (activeRole) {
      case 'employee':
        return <ChatWorkspace />;
      case 'knowledge_owner':
        return isAdmin ? <KnowledgeDashboard /> : <ChatWorkspace />;
      case 'admin':
        return isAdmin ? <AdminDashboard /> : <ChatWorkspace />;
      case 'gemini_usage':
        return isAdmin ? <GeminiUsagePage /> : <ChatWorkspace />;
      case 'studio':
        return isAdmin ? <ChatbotStudio /> : <ChatWorkspace />;
      default:
        return <ChatWorkspace />;
    }
  };

  return (
    <ChatQuotaProvider>
    <div className="flex h-[100dvh] w-full min-w-0 overflow-hidden bg-canvas-light dark:bg-canvas-dark text-slate-900 dark:text-slate-100 font-sans select-text">
      {/* 1. Left Sidebar Rail */}
      <SidebarRail />

      {/* 2. Main Content & Header Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden relative">
        <TopHeader />
        <main className="flex-1 flex overflow-hidden relative">
          <Suspense fallback={<WorkspaceFallback />}>{renderActiveWorkspace()}</Suspense>
        </main>
      </div>

      {/* 3. Evidence / Citation Inspector Side Panel */}
      {isEvidenceOpen && <Suspense fallback={null}><EvidenceDrawer /></Suspense>}

      {/* 4. Global Toast Notification */}
      {toast && (
        <div className="fixed bottom-4 left-4 right-4 sm:bottom-5 sm:left-auto sm:right-5 z-50 animate-in fade-in slide-in-from-bottom-4 duration-300" role="status" aria-live="polite">
          <div className="flex items-center gap-2.5 px-4 py-3 rounded-2xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 shadow-2xl text-xs font-medium border border-white/10 dark:border-black/10 sm:max-w-sm">
            {toast.type === 'success' && <CheckCircle2 className="w-4 h-4 text-emerald-400 dark:text-emerald-600" />}
            {toast.type === 'info' && <Info className="w-4 h-4 text-cobalt-400 dark:text-cobalt-600" />}
            {toast.type === 'warning' && <AlertTriangle className="w-4 h-4 text-amber-400 dark:text-amber-600" />}
            <span>{toast.message}</span>
          </div>
        </div>
      )}
    </div>
    </ChatQuotaProvider>
  );
};
