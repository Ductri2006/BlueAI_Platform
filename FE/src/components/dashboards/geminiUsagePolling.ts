type PollingOptions<T> = {
  fetchReport: () => Promise<T>;
  onReport: (report: T) => void;
  onError: (error: unknown) => void;
  onLoading: (loading: boolean) => void;
  intervalMs?: number;
};

/** Refresh visible dashboards without overlapping requests or updating an unmounted page. */
export function startGeminiUsagePolling<T>({
  fetchReport, onReport, onError, onLoading, intervalMs = 5_000,
}: PollingOptions<T>) {
  let disposed = false;
  let pending: Promise<void> | undefined;

  const refresh = (): Promise<void> => {
    if (disposed) return Promise.resolve();
    if (pending) return pending;
    onLoading(true);
    pending = Promise.resolve()
      .then(fetchReport)
      .then(report => { if (!disposed) onReport(report); })
      .catch(error => { if (!disposed) onError(error); })
      .finally(() => {
        pending = undefined;
        if (!disposed) onLoading(false);
      });
    return pending;
  };

  const refreshWhenVisible = () => {
    if (document.visibilityState !== 'hidden') void refresh();
  };
  const timer = setInterval(refreshWhenVisible, intervalMs);
  document.addEventListener('visibilitychange', refreshWhenVisible);
  window.addEventListener('focus', refreshWhenVisible);
  void refresh();

  return {
    refresh,
    dispose: () => {
      disposed = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
      window.removeEventListener('focus', refreshWhenVisible);
    },
  };
}
