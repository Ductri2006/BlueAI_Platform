import { describe, expect, it } from 'vitest';
import { BlueAiApi } from '../../api/blueAiApi';
import { monthRange, usageMoney, usageRangeError, vietnamToday } from './geminiUsageDisplay';

describe('Gemini usage periods and costs', () => {
  it('uses Vietnam dates across UTC midnight and month boundaries', () => {
    expect(vietnamToday(new Date('2026-10-31T16:59:59Z'))).toBe('2026-10-31');
    expect(vietnamToday(new Date('2026-10-31T17:00:00Z'))).toBe('2026-11-01');
    expect(monthRange('2024-02')).toEqual({ startDate: '2024-02-01', endDate: '2024-02-29' });
    expect(monthRange('2026-12').endDate).toBe('2026-12-31');
  });
  it('accepts a single day and rejects reversed or oversized ranges', () => {
    expect(usageRangeError('2026-11-01', '2026-11-01')).toBe('');
    expect(usageRangeError('2026-11-02', '2026-11-01')).not.toBe('');
    expect(usageRangeError('', '2026-11-01')).not.toBe('');
    expect(usageRangeError('2024-01-01', '2024-12-31')).toBe('');
    expect(usageRangeError('2024-01-01', '2025-01-01')).not.toBe('');
  });
  it('preserves small nonzero costs instead of rounding them to free', () => {
    expect(usageMoney(0.000001, 'USD', 26000)).toContain('0,000001');
    expect(usageMoney(0.000001, 'VND', 26000)).toBe('< 1 ₫');
    expect(usageMoney(1.5, 'VND', 26000)).toContain('39.000');
    expect(usageMoney(0, 'VND', 26000)).toContain('0');
  });
  it('sends dates and server pagination in the authenticated API request', async () => {
    let requested = '';
    const api = new BlueAiApi({ accessToken: 'test-token', fetchImpl: (async (url: string | URL | Request) => {
      requested = String(url);
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch });
    await api.getGeminiUsage({ startDate: '2026-10-01', endDate: '2026-10-31', page: 3, pageSize: 10 });
    expect(requested).toContain('/api/v1/system/gemini-usage?startDate=2026-10-01&endDate=2026-10-31&page=3&pageSize=10');
  });
});
