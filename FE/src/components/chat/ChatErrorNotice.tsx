import React from 'react';
import { AlertTriangle } from 'lucide-react';
import { useAuth } from '../../auth/AuthContext';
import type { ChatError } from '../../types';
import { Badge } from '../ui/Badge';

const labels: Record<ChatError['category'], string> = {
  language: 'Lỗi ngôn ngữ phản hồi',
  quota: 'Giới hạn sử dụng',
  network: 'Lỗi kết nối',
  service: 'Lỗi dịch vụ',
  access: 'Lỗi truy cập',
};

export function ChatErrorNotice({ error, content }: { error: ChatError; content: string }) {
  const { status, user } = useAuth();
  const showCode = status === 'authenticated' && user?.role === 'ADMIN'
    && error.code && /^[A-Z][A-Z0-9_]{0,79}$/.test(error.code);
  return (
    <div className="min-w-0 space-y-2 text-sm text-slate-800 dark:text-slate-200">
      <div role="status" className="space-y-2">
        <Badge variant="danger" className="max-w-full whitespace-normal">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>{labels[error.category]}</span>
        </Badge>
        <p className="whitespace-pre-wrap [overflow-wrap:anywhere] leading-relaxed">{content}</p>
      </div>
      {showCode && (
        <details className="text-xs text-slate-600 dark:text-slate-300">
          <summary className="cursor-pointer rounded-md py-1 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cobalt-500">Chi tiết kỹ thuật</summary>
          <p className="mt-1 [overflow-wrap:anywhere]">Mã lỗi: <code>{error.code}</code></p>
        </details>
      )}
    </div>
  );
}
