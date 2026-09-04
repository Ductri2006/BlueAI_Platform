import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { useAuth } from '../../auth/AuthContext';
import { SidebarRail } from './SidebarRail';
import { TopHeader } from './TopHeader';
import { ChatWorkspace } from '../chat/ChatWorkspace';
import { KnowledgeDashboard } from '../dashboards/KnowledgeDashboard';
import { AdminDashboard } from '../dashboards/AdminDashboard';
import { EvidenceDrawer } from '../chat/EvidenceDrawer';
import { CheckCircle2, Info, AlertTriangle } from 'lucide-react';

export const AppShell: React.FC = () => {
  const { activeRole, toast } = useAppStore();
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
      default:
        return <ChatWorkspace />;
    }
  };

  return (
    <div className="flex h-[100dvh] w-full min-w-0 overflow-hidden bg-canvas-light dark:bg-canvas-dark text-slate-900 dark:text-slate-100 font-sans select-text">
      {/* 1. Left Sidebar Rail */}
      <SidebarRail />

      {/* 2. Main Content & Header Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden relative">
        <TopHeader />
        <main className="flex-1 flex overflow-hidden relative">
          {renderActiveWorkspace()}
        </main>
      </div>

      {/* 3. Evidence / Citation Inspector Side Panel */}
      <EvidenceDrawer />

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
  );
};
