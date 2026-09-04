import React, { useRef } from 'react';
import { UploadCloud, X } from 'lucide-react';
import { Button } from '../ui/Button';

export interface AttachedFile {
  id: string;
  name: string;
  size: string;
  type: 'image' | 'pdf' | 'doc';
  file: File;
}

interface AttachmentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectFile: (file: AttachedFile) => void;
}

export const AttachmentModal: React.FC<AttachmentModalProps> = ({ isOpen, onClose, onSelectFile }) => {
  const input = useRef<HTMLInputElement>(null);
  if (!isOpen) return null;

  const select = (file?: File) => {
    if (!file) return;
    const extension = file.name.split('.').pop()?.toLowerCase();
    onSelectFile({
      id: crypto.randomUUID(),
      name: file.name,
      size: `${(file.size / 1024).toFixed(1)} KB`,
      type: file.type.startsWith('image/') ? 'image' : extension === 'pdf' ? 'pdf' : 'doc',
      file,
    });
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/70 backdrop-blur-sm" role="dialog" aria-modal="true">
      <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-2xl p-5 space-y-5">
        <div className="flex justify-between"><h3 className="font-bold">Tải tài liệu vào kho RAG</h3><button onClick={onClose}><X className="w-4 h-4" /></button></div>
        <button onClick={() => input.current?.click()} className="w-full rounded-2xl border-2 border-dashed border-slate-300 dark:border-slate-700 p-10 text-center hover:border-cobalt-500">
          <UploadCloud className="w-10 h-10 mx-auto text-cobalt-600 mb-3" />
          <p className="font-semibold text-sm">Chọn PDF, DOCX, TXT hoặc Markdown</p>
          <p className="text-xs text-slate-500 mt-1">Tệp sẽ được gửi thật đến Document API.</p>
        </button>
        <input ref={input} type="file" accept=".pdf,.docx,.txt,.md" className="hidden" onChange={event => select(event.target.files?.[0])} />
        <Button variant="outline" className="w-full" onClick={onClose}>Đóng</Button>
      </div>
    </div>
  );
};
