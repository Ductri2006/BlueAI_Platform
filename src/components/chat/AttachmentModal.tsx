import React from 'react';
import { FileText, Image as ImageIcon, CheckCircle2, X, UploadCloud } from 'lucide-react';
import { Button } from '../ui/Button';

export interface AttachedFile {
  id: string;
  name: string;
  size: string;
  type: 'image' | 'pdf' | 'doc';
  previewUrl?: string;
}

interface AttachmentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectFile: (file: AttachedFile) => void;
}

export const AttachmentModal: React.FC<AttachmentModalProps> = ({ isOpen, onClose, onSelectFile }) => {
  if (!isOpen) return null;

  const mockSamples: AttachedFile[] = [
    { id: 'f-1', name: 'Anh_Chup_Loi_Song_Narita_5G.png', size: '1.4 MB', type: 'image', previewUrl: 'https://images.unsplash.com/photo-1544717305-2782549b5136?w=200&auto=format&fit=crop&q=80' },
    { id: 'f-2', name: 'Hoa_Don_VAT_ORD-JP-98214.pdf', size: '420 KB', type: 'pdf' },
    { id: 'f-3', name: 'Bien_Ban_Kiem_Tra_ICCID_Unused.docx', size: '280 KB', type: 'doc' },
  ];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/70 backdrop-blur-sm animate-in fade-in" role="dialog" aria-modal="true" aria-label="Chọn tệp đính kèm">
      <div className="w-full max-w-md rounded-2xl bg-white dark:bg-[#0F172A] border border-slate-200 dark:border-slate-800 shadow-2xl p-5 space-y-4 animate-in zoom-in-95">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <UploadCloud className="w-4 h-4 text-cobalt-600 dark:text-cobalt-400" />
            <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100">
              Đính kèm Tài liệu & Ảnh kiểm tra
            </h3>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-2">
          <span className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
            Chọn tệp mẫu kiểm thử RAG OCR:
          </span>
          <div className="space-y-2">
            {mockSamples.map((f) => (
              <button
                key={f.id}
                onClick={() => {
                  onSelectFile(f);
                  onClose();
                }}
                className="w-full text-left p-3 rounded-xl border border-slate-200 dark:border-slate-800 hover:border-cobalt-500 dark:hover:border-cobalt-400 bg-slate-50/60 dark:bg-slate-900/60 transition-colors flex items-center justify-between group"
              >
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-lg bg-cobalt-50 dark:bg-cobalt-950/80 text-cobalt-600 flex items-center justify-center shrink-0">
                    {f.type === 'image' ? <ImageIcon className="w-4 h-4" /> : <FileText className="w-4 h-4" />}
                  </div>
                  <div>
                    <p className="text-xs font-bold text-slate-800 dark:text-slate-200 group-hover:text-cobalt-600 transition-colors">
                      {f.name}
                    </p>
                    <span className="text-[11px] text-slate-400 font-mono">{f.size}</span>
                  </div>
                </div>
                <span className="text-xs font-semibold text-cobalt-600 opacity-0 group-hover:opacity-100 transition-opacity">
                  Đính kèm →
                </span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
