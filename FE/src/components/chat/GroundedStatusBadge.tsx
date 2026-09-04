import React from 'react';
import { ConfidenceLevel } from '../../types';
import { CheckCircle2, GitFork, Lightbulb, AlertOctagon, HelpCircle } from 'lucide-react';

interface GroundedStatusBadgeProps {
  confidence?: ConfidenceLevel;
  sourcesCount?: number;
  className?: string;
}

export const GroundedStatusBadge: React.FC<GroundedStatusBadgeProps> = ({
  confidence = 'confirmed',
  sourcesCount = 1,
  className = ''
}) => {
  const configs = {
    confirmed: {
      icon: CheckCircle2,
      label: `Đã xác nhận từ ${sourcesCount} nguồn chính thức`,
      classes: 'bg-emerald-50 text-emerald-700 border-emerald-200/80 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800/60',
    },
    inferred: {
      icon: GitFork,
      label: 'Suy ra từ dữ liệu tổng hợp (Cần lưu ý phạm vi)',
      classes: 'bg-amber-50 text-amber-800 border-amber-200/80 dark:bg-amber-950/40 dark:text-amber-300 dark:border-amber-800/60',
    },
    proposal: {
      icon: Lightbulb,
      label: 'Đề xuất tham khảo cho nhân viên',
      classes: 'bg-indigo-50 text-indigo-700 border-indigo-200/80 dark:bg-indigo-950/40 dark:text-indigo-300 dark:border-indigo-800/60',
    },
    ungrounded: {
      icon: HelpCircle,
      label: 'Ngoài phạm vi kho tri thức nội bộ',
      classes: 'bg-slate-100 text-slate-600 border-slate-200 dark:bg-slate-800/70 dark:text-slate-300 dark:border-slate-700',
    },
    danger: {
      icon: AlertOctagon,
      label: 'Chưa đủ căn cứ xác thực',
      classes: 'bg-rose-50 text-rose-700 border-rose-200/80 dark:bg-rose-950/40 dark:text-rose-300 dark:border-rose-800/60',
    },
  };

  const current = configs[confidence] || configs.confirmed;
  const Icon = current.icon;

  return (
    <div className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-full border shadow-2xs ${current.classes} ${className}`}>
      <Icon className="w-3.5 h-3.5 shrink-0" />
      <span>{current.label}</span>
    </div>
  );
};
