import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { useAuth } from '../../auth/AuthContext';
import { UserRole } from '../../types';
import {
  MessageSquare, BookOpen, ShieldCheck, Bot, Gauge,
  Plus, ChevronLeft,
  ChevronRight, Pin, Clock, Trash2, Check, X
} from 'lucide-react';
import { Button } from '../ui/Button';

export const SidebarRail: React.FC = () => {
  const { 
    activeRole, 
    setActiveRole, 
    isSidebarCollapsed, 
    toggleSidebar, 
    sessions, 
    activeSessionId, 
    setActiveSessionId,
    createNewSession,
    deleteSession
  } = useAppStore();
  const { user } = useAuth();
  const [pendingDeleteId, setPendingDeleteId] = React.useState<string | null>(null);

  const allRoleNavItems: { role: UserRole; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
    { role: 'employee', label: 'Hỏi BlueAI', icon: MessageSquare },
    { role: 'knowledge_owner', label: 'Tài liệu & Tri thức RAG', icon: BookOpen },
    { role: 'studio', label: 'BlueAI Custom', icon: Bot },
    { role: 'gemini_usage', label: 'Token Gemini', icon: Gauge },
    { role: 'admin', label: 'Quản trị Hệ thống', icon: ShieldCheck },
  ];
  const roleNavItems = allRoleNavItems.filter(item => item.role === 'employee' || user?.role === 'ADMIN');

  return (
    <aside
      aria-label="Thanh điều hướng chính"
      className={`h-[100dvh] border-r border-slate-200/80 dark:border-slate-800 bg-white/95 dark:bg-[#070D18]/95 backdrop-blur-xl flex flex-col shrink-0 transition-[width] duration-300 z-30 ${
        isSidebarCollapsed ? 'w-18' : 'w-18 lg:w-64'
      }`}
    >
      {/* Brand Header */}
      <div className="relative h-14 px-3 lg:px-4 border-b border-slate-200/80 dark:border-slate-800 flex items-center justify-between">
        {!isSidebarCollapsed && (
          <div className="hidden lg:flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-cobalt-600 via-cobalt-500 to-cyan-400 flex items-center justify-center text-white font-bold text-base shadow-md">
              b
            </div>
            <div>
              <span className="font-black text-base text-slate-900 dark:text-slate-100 tracking-tight leading-none">
                blueAI
              </span>
              <p className="text-[10px] text-slate-400 font-medium">Trí tuệ Vận hành Doanh nghiệp</p>
            </div>
          </div>
        )}

        <div className={`${isSidebarCollapsed ? 'flex' : 'flex lg:hidden'} mx-auto w-8 h-8 rounded-xl bg-gradient-to-tr from-cobalt-600 to-cyan-500 items-center justify-center text-white font-bold text-base shadow-md`} aria-hidden="true">
          b
        </div>

        <button
          onClick={toggleSidebar}
          className={`hidden lg:inline-flex p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors ${isSidebarCollapsed ? 'absolute -right-3 top-1/2 -translate-y-1/2 rounded-full bg-white dark:bg-[#0B1528] border border-slate-200 dark:border-slate-700 shadow-sm z-10' : 'rounded-lg'}`}
          title={isSidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'}
          aria-label={isSidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'}
        >
          {isSidebarCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
        </button>
      </div>

      {/* Main Role Navigation Section */}
      <div className="p-3 border-b border-slate-200/80 dark:border-slate-800 space-y-1">
        {!isSidebarCollapsed && (
          <div className="hidden lg:block px-2.5 py-1 text-[10.5px] font-bold text-slate-400 uppercase tracking-wider">
            Không gian làm việc
          </div>
        )}

        {roleNavItems.map((item) => {
          const Icon = item.icon;
          const isActive = activeRole === item.role;
          return (
            <button
              key={item.role}
              onClick={() => setActiveRole(item.role)}
              title={item.label}
              aria-current={isActive ? 'page' : undefined}
              className={`w-full flex items-center justify-center lg:justify-start gap-3 px-3 py-2.5 rounded-xl text-xs font-bold transition-all relative ${
                isActive
                  ? 'bg-cobalt-600 text-white shadow-md shadow-cobalt-500/20'
                  : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100 hover:bg-slate-100/80 dark:hover:bg-slate-800/60'
              }`}
            >
              <Icon className={`w-4 h-4 shrink-0 ${isActive ? 'text-white' : 'text-slate-400'}`} />
              {!isSidebarCollapsed && <span className="hidden lg:inline truncate">{item.label}</span>}
            </button>
          );
        })}
      </div>

      {/* Chat History Section (When in Employee Mode) */}
      {!isSidebarCollapsed && activeRole === 'employee' && (
        <div className="hidden lg:block flex-1 overflow-y-auto p-3 space-y-3">
          <Button
            size="sm"
            variant="primary"
            className="w-full text-xs font-bold h-9 rounded-xl shadow-sm bg-gradient-to-r from-cobalt-600 to-cobalt-500 hover:from-cobalt-700 hover:to-cobalt-600"
            onClick={createNewSession}
          >
            <Plus className="w-3.5 h-3.5 mr-1.5" />
            Cuộc trò chuyện mới
          </Button>

          <div className="space-y-1">
            <div className="px-2 py-1 text-[10.5px] font-bold text-slate-400 uppercase tracking-wider flex items-center gap-1">
              <Clock className="w-3 h-3" />
              <span>Gần đây</span>
            </div>

            {sessions.map((sess) => {
              const isSelected = activeSessionId === sess.id;
              const isConfirming = pendingDeleteId === sess.id;
              return (
                <div
                  key={sess.id}
                  className={`group w-full px-3 py-2 rounded-xl text-xs transition-colors flex items-center gap-2 ${
                    isSelected
                      ? 'bg-slate-100 dark:bg-slate-800/90 text-slate-900 dark:text-slate-100 font-bold border border-slate-200 dark:border-slate-700'
                      : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800/40'
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => {
                      setActiveSessionId(sess.id);
                      setActiveRole('employee');
                    }}
                    className="flex items-center gap-2 min-w-0 flex-1 text-left"
                  >
                    <MessageSquare className="w-3.5 h-3.5 shrink-0 text-slate-400" />
                    <span className="truncate flex-1">{sess.title}</span>
                  </button>

                  {sess.isPinned && !isConfirming && <Pin className="w-3 h-3 text-cobalt-500 shrink-0" />}

                  {isConfirming ? (
                    <span className="flex items-center gap-0.5 shrink-0">
                      <button
                        type="button"
                        title="Xác nhận xoá"
                        aria-label={`Xác nhận xoá cuộc trò chuyện ${sess.title}`}
                        onClick={() => {
                          setPendingDeleteId(null);
                          deleteSession(sess.id);
                        }}
                        className="p-1 rounded-md text-rose-600 hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/50 transition-colors"
                      >
                        <Check className="w-3.5 h-3.5" />
                      </button>
                      <button
                        type="button"
                        title="Huỷ"
                        aria-label="Huỷ xoá cuộc trò chuyện"
                        onClick={() => setPendingDeleteId(null)}
                        className="p-1 rounded-md text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                      >
                        <X className="w-3.5 h-3.5" />
                      </button>
                    </span>
                  ) : (
                    <button
                      type="button"
                      title="Xoá cuộc trò chuyện"
                      aria-label={`Xoá cuộc trò chuyện ${sess.title}`}
                      onClick={() => setPendingDeleteId(sess.id)}
                      className="p-1 rounded-md shrink-0 text-slate-400 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/50 dark:hover:text-rose-400 transition-opacity"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div className={`${isSidebarCollapsed ? 'flex-1' : 'flex-1 lg:hidden'}`} />

      {/* Footer User Info */}
      <div className="p-3 border-t border-slate-200/80 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-900/40">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-cobalt-600 to-cyan-500 text-white font-bold flex items-center justify-center text-xs shrink-0 shadow-sm">
            {user?.fullName?.charAt(0).toUpperCase() || 'U'}
          </div>
          {!isSidebarCollapsed && (
            <div className="hidden lg:block min-w-0 flex-1">
              <p className="text-xs font-bold text-slate-800 dark:text-slate-200 truncate">{user?.fullName}</p>
              <p className="text-[10px] text-slate-400 font-mono truncate">{user?.role === 'ADMIN' ? 'ADMIN' : 'USER'} • {user?.email}</p>
            </div>
          )}
        </div>
      </div>
    </aside>
  );
};
