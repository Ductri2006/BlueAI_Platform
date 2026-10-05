import React from 'react';

/** Missing-slot reprompts remain in Dialog; they are not the global fallback route. */
export function StudioPreviewFallbackBadge({ fallback, pendingEntity }: {
  fallback?: boolean;
  pendingEntity?: string;
}) {
  if (!fallback || pendingEntity) return null;
  return <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-amber-200">Dự phòng</span>;
}
