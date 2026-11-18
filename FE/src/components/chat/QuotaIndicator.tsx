import React from 'react';
// Admission remains enforced by the shared hook and server. Only an active
// cooldown needs space beside the composer.
export function QuotaIndicator({ seconds, blocked }: { seconds: number; blocked: boolean }) {
  if (!blocked) return null;
  return <p className="text-xs text-amber-700 dark:text-amber-300" role="status">
    Bạn gửi câu hỏi quá nhanh. {seconds > 0 ? `Có thể gửi lại sau ${seconds} giây.` : 'Đang cập nhật thời điểm gửi lại…'}
  </p>;
}
