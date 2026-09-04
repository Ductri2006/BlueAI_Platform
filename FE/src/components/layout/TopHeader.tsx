import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { UserRole } from '../../types';
import { Moon, Sun, Shield, Bell, Sparkles } from 'lucide-react';

export const TopHeader: React.FC = () => {
  const { activeRole, setActiveRole, isDarkMode, toggleDarkMode, showToast } = useAppStore();

  const rolesList: { id: UserRole; label: string; tag: string }[] = [
    { id: 'employee', label: '1. Hỏi BlueAI (Quy chế & Nghiệp vụ)', tag: 'Staff' },
    { id: 'sales_copilot', label: '2. Trợ lý Bán hàng & CSKH (Sales Copilot)', tag: 'Sales' },
    { id: 'knowledge_owner', label: '3. Quản lý Tài liệu & Tri thức RAG', tag: 'Knowledge' },
    { id: 'admin', label: '4. Quản trị Hệ thống (Admin Gateway)', tag: 'Admin' },
    { id: 'reseller', label: '5. Cổng Đại lý Phân phối', tag: 'Đại lý' },
    { id: 'b2c_customer', label: '6. Trung tâm Hỗ trợ Khách hàng', tag: 'Khách hàng' },
  ];

  return (
    <header className="h-14 border-b border-slate-200/80 dark:border-slate-800 bg-white/90 dark:bg-[#0B1528]/90 backdrop-blur-md px-4 sm:px-6 flex items-center justify-between shrink-0 z-20 transition-colors">
      {/* Left: Organization & Role Scope Badge */}
      <div className="hidden md:flex items-center gap-3 min-w-0">
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse shrink-0" />
          <span className="text-xs font-bold text-slate-800 dark:text-slate-100 truncate">
            Bluecom Enterprise Core
          </span>
        </div>

        <span className="text-slate-300 dark:text-slate-700 hidden sm:inline">|</span>

        <div className="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 text-xs font-medium border border-slate-200/60 dark:border-slate-700">
          <Shield className="w-3 h-3 text-cobalt-600 dark:text-cobalt-400" />
          <span className="truncate">Phạm vi: Toàn hệ thống (Nội bộ & Đại lý)</span>
        </div>
      </div>

      {/* Right: Demo Role Switcher, Theme & Notification */}
      <div className="flex items-center gap-2.5 shrink-0">
        {/* Role Switcher Pill */}
        <div className="flex min-w-0 items-center gap-1.5 bg-cobalt-50/80 dark:bg-cobalt-950/70 border border-cobalt-200/80 dark:border-cobalt-800 rounded-xl px-2.5 py-1 text-xs">
          <Sparkles className="w-3.5 h-3.5 text-cobalt-600 dark:text-cobalt-400 shrink-0" />
          <span className="text-slate-500 dark:text-slate-400 text-[11px] font-semibold hidden md:inline">
            Vai trò:
          </span>
          <select
            value={activeRole}
            onChange={(e) => {
              setActiveRole(e.target.value as any);
              showToast(`Đã chuyển đổi sang không gian: ${e.target.options[e.target.selectedIndex].text}`, 'info');
            }}
            aria-label="Chuyển đổi vai trò làm việc"
            className="max-w-[10rem] sm:max-w-[15rem] lg:max-w-xs bg-transparent text-xs font-bold text-cobalt-700 dark:text-cobalt-300 focus:outline-none cursor-pointer pr-1 truncate"
          >
            {rolesList.map((r) => (
              <option key={r.id} value={r.id} className="bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-200">
                {r.label}
              </option>
            ))}
          </select>
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

        {/* Notifications */}
        <button
          onClick={() => showToast('Hệ thống RAG đang hoạt động ổn định (Accuracy 98.4%)', 'info')}
          className="p-2 rounded-xl border border-slate-200 dark:border-slate-800 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-300 transition-colors shadow-2xs relative"
          title="Thông báo hệ thống"
          aria-label="Xem thông báo hệ thống"
        >
          <Bell className="w-4 h-4" />
          <span className="w-2 h-2 rounded-full bg-cobalt-500 absolute top-1.5 right-1.5" />
        </button>
      </div>
    </header>
  );
};
