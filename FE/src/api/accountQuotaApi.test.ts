import { describe, expect, it, vi } from 'vitest';
import { BlueAiApi } from './blueAiApi';

describe('account recovery and quota API', () => {
  it('serializes browser credential rotation across separate API clients', async () => {
    let queue: Promise<unknown> = Promise.resolve();
    let active = 0;
    let peak = 0;
    const requestLock = vi.fn((_name: string, callback: () => Promise<Response>) => {
      const next = queue.then(callback);
      queue = next.catch(() => undefined);
      return next;
    });
    vi.stubGlobal('navigator', {locks: {request: requestLock}});
    try {
      const fetchImpl = vi.fn(async (path: string | URL | Request) => {
        if (String(path).endsWith('/refresh')) {
          active++;
          peak = Math.max(peak, active);
          await new Promise(resolve => setTimeout(resolve, 5));
          active--;
          return new Response(JSON.stringify({accessToken:'qa-rotated-access', user:{id:'qa-user'}}));
        }
        return new Response(JSON.stringify({limit:30, remaining:30, windowSeconds:60, resetsAt:null}));
      });
      const first = new BlueAiApi({sessionMode:'browser', fetchImpl:fetchImpl as typeof fetch});
      const second = new BlueAiApi({sessionMode:'browser', fetchImpl:fetchImpl as typeof fetch});
      await Promise.all([first.getChatQuota(), second.getChatQuota()]);
      expect(peak).toBe(1);
      expect(requestLock).toHaveBeenCalledTimes(2);
    } finally { vi.unstubAllGlobals(); }
  });
  it('reads the current account quota without sending another user ID', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ limit: 30, remaining: 28, windowSeconds: 60, resetsAt: null })));
    const api = new BlueAiApi({ accessToken: 'qa-access', fetchImpl: fetchImpl as typeof fetch });
    expect((await api.getChatQuota()).remaining).toBe(28);
    expect(fetchImpl).toHaveBeenCalledWith('/api/v1/auth/quota', expect.objectContaining({method: 'GET'}));
  });

  it('uses the guarded browser route to complete a password change', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, {status: 204}));
    const api = new BlueAiApi({ sessionMode: 'browser', accessToken: 'qa-access', fetchImpl: fetchImpl as typeof fetch });
    await api.changeMyPassword('New-QA-Password123!');
    const [path, request] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/v1/auth/browser/password/change');
    expect(request.credentials).toBe('include');
    expect(new Headers(request.headers).get('X-BlueAI-Browser')).toBe('1');
    expect(JSON.parse(String(request.body))).toEqual({newPassword: 'New-QA-Password123!'});
  });

  it('asks the server to generate the temporary credential after admin verification', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({temporaryPassword: 'qa-one-time', expiresAt: '2026-11-10T09:00:00Z'})));
    const api = new BlueAiApi({accessToken: 'qa-access', fetchImpl: fetchImpl as typeof fetch});
    await api.resetManagedUserPassword('user-id', 'QA-admin-current-password');
    const [path, request] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/v1/admin/users/user-id/password');
    expect(JSON.parse(String(request.body))).toEqual({currentPassword: 'QA-admin-current-password'});
  });

  it('keeps the server retry delay and notifies quota subscribers on 429', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({code: 'RAG_RATE_LIMITED', message: 'limited'}), {
      status: 429, headers: {'Retry-After': '17'},
    }));
    const api = new BlueAiApi({accessToken: 'qa-access', fetchImpl: fetchImpl as typeof fetch});
    const listener = vi.fn();
    const unsubscribe = api.onQuotaChanged(listener);
    await expect(api.askQuestion('conversation-id', 'QA')).rejects.toMatchObject({status:429, retryAfterSeconds:17});
    expect(listener).toHaveBeenCalledWith(17);
    unsubscribe();
    await expect(api.askQuestion('conversation-id', 'QA')).rejects.toMatchObject({status:429});
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('notifies after a successful preview and sends deletion only to the selected account', async () => {
    const fetchImpl = vi.fn(async () => new Response('{}'));
    const api = new BlueAiApi({accessToken: 'qa-access', fetchImpl: fetchImpl as typeof fetch});
    const listener = vi.fn();
    api.onQuotaChanged(listener);
    await api.previewStudioBot('bot-id', 'QA');
    expect(listener).toHaveBeenCalledOnce();
    await api.deleteManagedUser('user-id');
    expect(fetchImpl).toHaveBeenLastCalledWith('/api/v1/admin/users/user-id', expect.objectContaining({method:'DELETE'}));
  });
});
