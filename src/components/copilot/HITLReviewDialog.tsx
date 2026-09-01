import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { ShieldAlert, CheckCircle2, XCircle, FileText, AlertTriangle, UserCheck } from 'lucide-react';
import { Button } from '../ui/Button';

export const HITLReviewDialog: React.FC = () => {
  const { isHITLModalOpen, hitlActionData, closeHITLModal, approveHITL } = useAppStore();

  if (!isHITLModalOpen || !hitlActionData) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in" role="dialog" aria-modal="true" aria-label="Phê duyệt hành động có giám sát">
      <div className="w-full max-w-lg rounded-modal bg-white dark:bg-[#0F172A] border border-slate-200 dark:border-slate-800 shadow-2xl overflow-hidden animate-in zoom-in-95 duration-200">
        {/* Header */}
        <div className="p-5 border-b border-slate-100 dark:border-slate-800/80 bg-rose-50/50 dark:bg-rose-950/20 flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-rose-100 dark:bg-rose-900/40 text-rose-600 dark:text-rose-400 flex items-center justify-center shrink-0">
            <ShieldAlert className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-900 dark:text-slate-100">
              Yêu cầu Phê duyệt Hành động Tài chính (HITL)
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Nguyên tắc bắt buộc: AI không tự thực hiện giao dịch hoàn tiền hoặc đổi đơn
            </p>
          </div>
        </div>

        {/* Content Details */}
        <div className="p-5 space-y-4 text-xs">
          <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900/80 border border-slate-200 dark:border-slate-800 space-y-2">
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Loại hành động:</span>
              <span className="font-semibold text-slate-800 dark:text-slate-200">{hitlActionData.title}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Khách hàng thụ hưởng:</span>
              <span className="font-semibold text-slate-800 dark:text-slate-200">{hitlActionData.customerName}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Mã đơn hàng:</span>
              <span className="font-mono font-semibold text-cobalt-600 dark:text-cobalt-400">{hitlActionData.orderId}</span>
            </div>
            <div className="flex justify-between border-t border-slate-200 dark:border-slate-800 pt-2 text-sm">
              <span className="font-semibold text-slate-700 dark:text-slate-300">Số tiền đề xuất:</span>
              <span className="font-bold text-rose-600 dark:text-rose-400">{hitlActionData.amount}</span>
            </div>
          </div>

          <div className="space-y-1.5">
            <span className="font-semibold text-slate-700 dark:text-slate-300">Lý do & Căn cứ kiểm toán:</span>
            <div className="p-3 rounded-lg bg-amber-50/60 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-900/40 text-amber-900 dark:text-amber-300 leading-relaxed">
              {hitlActionData.reason}
            </div>
          </div>

          <div className="flex items-center gap-2 text-[11px] text-slate-500 dark:text-slate-400 pt-1">
            <FileText className="w-3.5 h-3.5 text-cobalt-500" />
            <span>Đối chiếu quy chế: <strong>{hitlActionData.policyRef}</strong></span>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-900/50 flex items-center justify-end gap-2.5">
          <Button variant="outline" size="sm" onClick={closeHITLModal}>
            Từ chối / Hủy
          </Button>
          <Button variant="danger" size="sm" onClick={approveHITL}>
            <UserCheck className="w-4 h-4 mr-1.5" />
            Xác nhận Phê duyệt
          </Button>
        </div>
      </div>
    </div>
  );
};
