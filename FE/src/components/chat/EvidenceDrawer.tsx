import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { X, ExternalLink, ShieldCheck, Clock, User, Layers, AlertTriangle, Copy, Check } from 'lucide-react';
import { Button } from '../ui/Button';

export const EvidenceDrawer: React.FC = () => {
  const { isEvidenceOpen, selectedCitation, closeEvidence, showToast } = useAppStore();
  const [copied, setCopied] = React.useState(false);

  if (!isEvidenceOpen || !selectedCitation) return null;

  const handleCopySnippet = () => {
    navigator.clipboard.writeText(selectedCitation.snippet);
    setCopied(true);
    showToast('Đã sao chép đoạn trích căn cứ', 'info');
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <aside 
      className="fixed inset-y-0 right-0 z-40 w-full sm:w-[400px] bg-white dark:bg-[#0F172A] border-l border-slate-200 dark:border-slate-800 shadow-2xl flex flex-col transition-transform duration-300 animate-in slide-in-from-right"
      role="dialog"
      aria-modal="true"
      aria-label="Chi tiết nguồn trích dẫn"
    >
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200 dark:border-slate-800/80 bg-slate-50/50 dark:bg-slate-900/50">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-md bg-cobalt-50 dark:bg-cobalt-950/60 flex items-center justify-center text-cobalt-600 dark:text-cobalt-400">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">Căn cứ & Nguồn dẫn</h3>
            <p className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">{selectedCitation.sourceId}</p>
          </div>
        </div>
        <button
          onClick={closeEvidence}
          className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Body */}
      <div className="flex-1 overflow-y-auto p-5 space-y-5">
        {/* Document Title Card */}
        <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-900/80 border border-slate-200/80 dark:border-slate-800">
          <span className="text-[11px] font-semibold text-cobalt-600 dark:text-cobalt-400 uppercase tracking-wider">Tài liệu tham chiếu</span>
          <h4 className="text-sm font-semibold text-slate-900 dark:text-slate-100 mt-1 leading-snug">
            {selectedCitation.title}
          </h4>
          <p className="text-xs text-slate-600 dark:text-slate-400 mt-1 font-medium">
            {selectedCitation.section}
          </p>
        </div>

        {/* Metadata Grid */}
        <div className="grid grid-cols-2 gap-3 text-xs">
          <div className="p-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950/40">
            <div className="flex items-center gap-1.5 text-slate-400 dark:text-slate-500 mb-1">
              <Clock className="w-3.5 h-3.5" />
              <span>Phiên bản</span>
            </div>
            <p className="font-medium text-slate-800 dark:text-slate-200 font-mono text-[11.5px]">{selectedCitation.version}</p>
          </div>

          <div className="p-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950/40">
            <div className="flex items-center gap-1.5 text-slate-400 dark:text-slate-500 mb-1">
              <User className="w-3.5 h-3.5" />
              <span>Chủ sở hữu</span>
            </div>
            <p className="font-medium text-slate-800 dark:text-slate-200 truncate">{selectedCitation.owner}</p>
          </div>

          <div className="col-span-2 p-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950/40">
            <div className="flex items-center gap-1.5 text-slate-400 dark:text-slate-500 mb-1">
              <Layers className="w-3.5 h-3.5" />
              <span>Phạm vi áp dụng (Scope)</span>
            </div>
            <p className="font-medium text-slate-800 dark:text-slate-200">{selectedCitation.scope}</p>
          </div>
        </div>

        {/* Highlighted Snippet */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">Đoạn trích dẫn gốc:</span>
            <button
              onClick={handleCopySnippet}
              className="text-[11px] flex items-center gap-1 text-cobalt-600 dark:text-cobalt-400 hover:underline"
            >
              {copied ? <Check className="w-3 h-3 text-emerald-600" /> : <Copy className="w-3 h-3" />}
              {copied ? 'Đã chép' : 'Sao chép'}
            </button>
          </div>
          <div className="p-3.5 rounded-lg bg-cobalt-50/50 dark:bg-slate-900 border border-cobalt-200/60 dark:border-cobalt-900/40 text-xs text-slate-700 dark:text-slate-300 leading-relaxed font-sans border-l-4 border-l-cobalt-600">
            {selectedCitation.snippet}
          </div>
        </div>

        {/* Validity Status Alert — must reflect the citation, never assert blanket trust */}
        {selectedCitation.status === 'valid' ? (
          <div className="flex items-start gap-2.5 p-3 rounded-lg bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800/40 text-emerald-800 dark:text-emerald-300 text-xs">
            <ShieldCheck className="w-4 h-4 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold">Nguồn đã được backend xác minh</p>
              <p className="text-[11px] text-emerald-700/80 dark:text-emerald-400/80 mt-0.5">
                Tài liệu thuộc phạm vi truy cập của tài khoản và ở trạng thái READY tại thời điểm truy xuất.
                {' '}Trạng thái: {selectedCitation.effectiveDate}.
              </p>
            </div>
          </div>
        ) : (
          <div className="flex items-start gap-2.5 p-3 rounded-lg bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800/40 text-amber-900 dark:text-amber-300 text-xs">
            <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold">Nguồn chưa được phê duyệt</p>
              <p className="text-[11px] text-amber-800/80 dark:text-amber-400/80 mt-0.5">
                {selectedCitation.effectiveDate}. Đây là dữ liệu mẫu hoặc ngoài phạm vi đã duyệt, không phải chính
                sách chính thức của Bluecom — hãy đối chiếu với tài liệu do admin phê duyệt trước khi sử dụng.
              </p>
            </div>
          </div>
        )}
      </div>

      {/* Footer Actions */}
      <div className="p-4 border-t border-slate-200 dark:border-slate-800 bg-slate-50/80 dark:bg-slate-900/80 flex items-center justify-between gap-3">
        <Button 
          variant="outline" 
          size="sm" 
          className="flex-1 text-xs"
          onClick={() => showToast('Đã gửi thông báo rà soát tới Quản lý tài liệu', 'info')}
        >
          <AlertTriangle className="w-3.5 h-3.5 mr-1 text-amber-500" />
          Báo nguồn sai
        </Button>
        <Button 
          variant="primary" 
          size="sm" 
          className="flex-1 text-xs"
          onClick={() => showToast('Đang mở file PDF gốc trong tab bảo mật', 'info')}
        >
          <ExternalLink className="w-3.5 h-3.5 mr-1" />
          Mở tài liệu gốc
        </Button>
      </div>
    </aside>
  );
};
