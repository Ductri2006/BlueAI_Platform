import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { useAuth } from '../../auth/AuthContext';
import { Moon, Sun, Shield, LogOut, UserRound, KeyRound } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { PasswordChangeForm } from '../auth/PasswordChangeForm';
import { useSharedChatQuota } from '../chat/ChatQuotaContext';
import { AiQuotaMenu } from '../chat/AiQuotaMenu';

export const TopHeader: React.FC = () => {
  const { isDarkMode, toggleDarkMode } = useAppStore();
  const { user, logout } = useAuth();
  const quota = useSharedChatQuota();
  const [changingPassword, setChangingPassword] = React.useState(false);

  return (
    <header className="h-14 border-b border-slate-200/80 dark:border-slate-800 bg-white/90 dark:bg-[#0B1528]/90 backdrop-blur-md px-4 sm:px-6 flex items-center justify-between shrink-0 z-20 transition-colors">
      {/* Left: Organization & Role Scope Badge */}
      <div className="hidden lg:flex items-center gap-3 min-w-0">
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse shrink-0" />
          <span className="text-xs font-bold text-slate-800 dark:text-slate-100 truncate">
            Bluecom Enterprise Core
          </span>
        </div>

        <span className="text-slate-300 dark:text-slate-700 hidden 2xl:inline">|</span>

        <div className="hidden 2xl:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 text-xs font-medium border border-slate-200/60 dark:border-slate-700">
          <Shield className="w-3 h-3 text-cobalt-600 dark:text-cobalt-400" />
          <span className="truncate">{user?.role === 'ADMIN' ? 'Phạm vi: Quản trị hệ thống' : 'Phạm vi: Chat RAG'}</span>
        </div>
      </div>

      {/* Right: server-issued role, theme and session controls */}
      <div className="ml-auto flex items-center gap-1.5 sm:gap-2.5 shrink-0">
        <AiQuotaMenu quota={quota.aiQuota} error={quota.aiError} refresh={quota.refresh} />
        <button type="button" onClick={() => setChangingPassword(true)} aria-label="Đổi mật khẩu" title="Đổi mật khẩu"
          className="rounded-xl border border-slate-200 p-2 text-slate-600 hover:bg-slate-100 dark:border-slate-800 dark:text-slate-300 dark:hover:bg-slate-800"><KeyRound className="h-4 w-4" /></button>
        {changingPassword && <Modal title="Đổi mật khẩu" onClose={() => setChangingPassword(false)}><PasswordChangeForm /></Modal>}
        <div className="hidden 2xl:flex items-center gap-1.5 bg-cobalt-50/80 dark:bg-cobalt-950/70 border border-cobalt-200/80 dark:border-cobalt-800 rounded-xl px-2.5 py-1.5 text-xs font-bold text-cobalt-700 dark:text-cobalt-300">
          <Shield className="w-3.5 h-3.5" />
          {user?.role === 'ADMIN' ? 'Quản trị viên' : 'Người dùng'}
        </div>
        {/* Dark Mode Toggle */}
        <button
          onClick={toggleDarkMode}
          className="p-2 rounded-xl border border-slate-200 dark:border-slate-800 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-300 transition-colors shadow-2xs"
          title={isDarkMode ? 'Chuyển sang chế độ Sáng (Light Mode)' : 'Chuyển sang chế độ Tối (Dark Mode)'}
          aria-label={isDarkMode ? 'Chuyển sang chế độ sáng' : 'Chuyển sang chế độ tối'}
        >
          {isDarkMode ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-slate-600" />}
        </button>

        <div className="hidden xl:flex items-center gap-2 border-l border-slate-200 dark:border-slate-800 pl-2.5">
          <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-cobalt-50 text-cobalt-700 dark:bg-cobalt-950 dark:text-cobalt-300">
            <UserRound className="h-4 w-4" />
          </div>
          <div className="max-w-36 leading-tight">
            <p className="truncate text-xs font-bold text-slate-800 dark:text-slate-100">{user?.fullName}</p>
            <p className="truncate text-[10px] text-slate-500 dark:text-slate-400">{user?.email}</p>
          </div>
        </div>

        <button
          onClick={() => void logout()}
          className="p-2 rounded-xl border border-slate-200 dark:border-slate-800 hover:border-rose-200 hover:bg-rose-50 hover:text-rose-600 dark:hover:border-rose-900 dark:hover:bg-rose-950/40 dark:hover:text-rose-300 text-slate-600 dark:text-slate-300 transition-colors shadow-2xs"
          title="Đăng xuất"
          aria-label="Đăng xuất"
        >
          <LogOut className="w-4 h-4" />
        </button>
      </div>
    </header>
  );
};
