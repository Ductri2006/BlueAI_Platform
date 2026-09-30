import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { startGeminiUsagePolling } from './geminiUsagePolling';

describe('Gemini usage dashboard refresh', () => {
  let page: EventTarget & { visibilityState: string };
  let focusTarget: EventTarget;
  const controllers: ReturnType<typeof startGeminiUsagePolling>[] = [];
  const fetchReport = vi.fn<() => Promise<{ totalTokens: number }>>();
  const onReport = vi.fn();
  const onError = vi.fn();
  const onLoading = vi.fn();
  const start = () => {
    const controller = startGeminiUsagePolling({ fetchReport, onReport, onError, onLoading });
    controllers.push(controller);
    return controller;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    vi.resetAllMocks();
    page = Object.assign(new EventTarget(), { visibilityState: 'visible' });
    focusTarget = new EventTarget();
    vi.stubGlobal('document', page);
    vi.stubGlobal('window', focusTarget);
    fetchReport.mockResolvedValue({ totalTokens: 10 });
  });

  afterEach(() => {
    controllers.splice(0).forEach(controller => controller.dispose());
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('loads immediately and reports loading state', async () => {
    await start().refresh();
    expect(fetchReport).toHaveBeenCalledTimes(1);
    expect(onReport).toHaveBeenCalledWith({ totalTokens: 10 });
    expect(onLoading.mock.calls).toEqual([[true], [false]]);
  });

  it('shows new token totals on the next automatic refresh', async () => {
    await start().refresh();
    fetchReport.mockResolvedValue({ totalTokens: 893 });
    await vi.advanceTimersByTimeAsync(4_999);
    expect(fetchReport).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchReport).toHaveBeenCalledTimes(2);
    expect(onReport).toHaveBeenLastCalledWith({ totalTokens: 893 });
  });

  it('pauses automatic requests while the tab is hidden', async () => {
    await start().refresh();
    page.visibilityState = 'hidden';
    page.dispatchEvent(new Event('visibilitychange'));
    focusTarget.dispatchEvent(new Event('focus'));
    await vi.advanceTimersByTimeAsync(15_000);
    expect(fetchReport).toHaveBeenCalledTimes(1);
  });

  it('refreshes immediately when returning to a visible tab', async () => {
    const controller = start();
    await controller.refresh();
    page.visibilityState = 'hidden';
    await vi.advanceTimersByTimeAsync(10_000);
    page.visibilityState = 'visible';
    page.dispatchEvent(new Event('visibilitychange'));
    await controller.refresh();
    expect(fetchReport).toHaveBeenCalledTimes(2);
  });

  it('refreshes when the browser regains focus', async () => {
    const controller = start();
    await controller.refresh();
    focusTarget.dispatchEvent(new Event('focus'));
    await controller.refresh();
    expect(fetchReport).toHaveBeenCalledTimes(2);
  });

  it('permits an explicit refresh even if the tab is hidden', async () => {
    const controller = start();
    await controller.refresh();
    page.visibilityState = 'hidden';
    await controller.refresh();
    expect(fetchReport).toHaveBeenCalledTimes(2);
  });

  it('shares one in-flight request across timer, focus and manual refresh', async () => {
    let resolve!: (report: { totalTokens: number }) => void;
    fetchReport.mockImplementation(() => new Promise(done => { resolve = done; }));
    const controller = start();
    const pending = controller.refresh();
    expect(controller.refresh()).toBe(pending);
    focusTarget.dispatchEvent(new Event('focus'));
    page.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(15_000);
    expect(fetchReport).toHaveBeenCalledTimes(1);
    resolve({ totalTokens: 20 });
    await pending;
    expect(onReport).toHaveBeenCalledTimes(1);
  });

  it('retains the last report after a failure and recovers on the next poll', async () => {
    await start().refresh();
    const error = new Error('Network unavailable');
    fetchReport.mockRejectedValueOnce(error).mockResolvedValue({ totalTokens: 30 });
    await vi.advanceTimersByTimeAsync(5_000);
    expect(onError).toHaveBeenCalledWith(error);
    expect(onReport).toHaveBeenCalledTimes(1);
    expect(onReport).toHaveBeenLastCalledWith({ totalTokens: 10 });
    expect(onLoading).toHaveBeenLastCalledWith(false);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(onReport).toHaveBeenLastCalledWith({ totalTokens: 30 });
  });

  it('recovers after the initial request fails', async () => {
    fetchReport.mockRejectedValueOnce(new Error('Initial failure'));
    await start().refresh();
    expect(onReport).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(onReport).toHaveBeenCalledWith({ totalTokens: 10 });
  });

  it('ignores results arriving after unmount', async () => {
    let resolve!: (report: { totalTokens: number }) => void;
    fetchReport.mockImplementation(() => new Promise(done => { resolve = done; }));
    const controller = start();
    const pending = controller.refresh();
    await vi.advanceTimersByTimeAsync(0);
    controller.dispose();
    onLoading.mockClear();
    resolve({ totalTokens: 99 });
    await pending;
    expect(onReport).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    expect(onLoading).not.toHaveBeenCalled();
  });

  it('ignores errors arriving after unmount', async () => {
    let reject!: (reason: Error) => void;
    fetchReport.mockImplementation(() => new Promise((_resolve, fail) => { reject = fail; }));
    const controller = start();
    const pending = controller.refresh();
    await vi.advanceTimersByTimeAsync(0);
    controller.dispose();
    reject(new Error('Late failure'));
    await pending;
    expect(onError).not.toHaveBeenCalled();
  });

  it('removes timers and event listeners on unmount', async () => {
    const controller = start();
    await controller.refresh();
    controller.dispose();
    page.dispatchEvent(new Event('visibilitychange'));
    focusTarget.dispatchEvent(new Event('focus'));
    await controller.refresh();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchReport).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
