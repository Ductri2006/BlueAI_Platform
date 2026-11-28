import React from 'react';
import type { StudioRuntimeResponse } from '../../api/blueAiApi';

/** Missing-slot reprompts remain in Dialog; they are not the global fallback route. */
export function StudioPreviewFallbackBadge({ fallback, pendingEntity, answerStatus }: {
  fallback?: boolean;
  pendingEntity?: string;
  answerStatus?: StudioRuntimeResponse['answerStatus'];
}) {
  if (answerStatus === 'SAFE_FALLBACK') return <span role="status" className="rounded-full bg-rose-500/20 px-2 py-0.5 text-rose-200">Lỗi dịch vụ</span>;
  if (answerStatus === 'INSUFFICIENT_EVIDENCE') return <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-amber-200">Chưa đủ căn cứ xác thực</span>;
  if (answerStatus === 'NEEDS_DETAILS') return <span className="rounded-full bg-white/10 px-2 py-0.5 text-slate-300">Cần thêm thông tin</span>;
  if (answerStatus === 'ANSWERED') return null;
  if (!fallback || pendingEntity) return null;
  return <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-amber-200">Dự phòng</span>;
}
