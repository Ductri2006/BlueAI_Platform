import React from 'react';
import { blueAiApi, type ChatQuota } from '../../api/blueAiApi';

export function useChatQuota() {
  const [quota, setQuota] = React.useState<ChatQuota>();
  const [error, setError] = React.useState('');
  const [now, setNow] = React.useState(Date.now());
  const [blockedUntil, setBlockedUntil] = React.useState(0);
  const [resetDeadline, setResetDeadline] = React.useState(0);
  const active = React.useRef(false);
  const generation = React.useRef(0);
  const refresh = React.useCallback(async () => {
    const request = ++generation.current;
    try {
      const result = await blueAiApi.getChatQuota();
      if (active.current && request === generation.current) {
        setQuota(result);
        setError('');
        // Relative TTL keeps the countdown accurate when the user's clock differs from the server.
        const lifetime = result.resetAfterSeconds ?? (result.resetsAt
          ? Math.max(0, (Date.parse(result.resetsAt) - Date.now()) / 1000) : 0);
        setResetDeadline(lifetime > 0 ? Date.now() + lifetime * 1000 : 0);
      }
    } catch {
      if (active.current && request === generation.current) {
        setQuota(undefined);
        setResetDeadline(0);
        setError('Chưa đọc được quota. Bạn có thể thử tải lại.');
      }
    }
  }, []);
  React.useEffect(() => {
    active.current = true;
    void refresh();
    const update = (retryAfterSeconds?: number) => {
      if (retryAfterSeconds) setBlockedUntil(Date.now() + retryAfterSeconds * 1000);
      void refresh();
    };
    const unsubscribe = blueAiApi.onQuotaChanged(update);
    const onFocus = () => { if (document.visibilityState !== 'hidden') void refresh(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    const poll = window.setInterval(onFocus, 15000);
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    return () => {
      active.current = false;
      generation.current++;
      unsubscribe();
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
      window.clearInterval(poll);
      window.clearInterval(tick);
    };
  }, [refresh]);
  const deadline = Math.max(resetDeadline, blockedUntil);
  React.useEffect(() => {
    if (!deadline) return;
    const timeout = window.setTimeout(() => { setBlockedUntil(0); void refresh(); }, Math.max(0, deadline - Date.now()) + 100);
    return () => window.clearTimeout(timeout);
  }, [deadline, refresh]);
  return {
    quota, error, refresh,
    seconds: Math.max(0, Math.ceil((deadline - now) / 1000)),
    blocked: blockedUntil > now || quota?.remaining === 0,
  };
}
