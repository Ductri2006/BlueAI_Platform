import { describe, expect, it, vi } from 'vitest';
import { BlueAiApi } from './blueAiApi';

describe('sales Dialog preview contract', () => {
  it('passes the selected conversation and role/content history, preserving the old call', async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ message: 'test', context: { variables: {} } }), { status: 200 }));
    const api = new BlueAiApi({ accessToken: 'test-access', fetchImpl });
    await api.previewStudioBot('bot /', 'xác nhận', { variables: {} }, {
      conversationId: 'own-conversation', history: [{ role: 'user', content: 'Khách đi Nhật' }],
    });
    expect(fetchImpl.mock.calls[0][0]).toContain('/api/v1/studio/bots/bot%20%2F/preview');
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      message: 'xác nhận', context: { variables: {} }, conversationId: 'own-conversation',
      history: [{ role: 'user', content: 'Khách đi Nhật' }],
    });
    await api.previewStudioBot('bot', 'xin chào');
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({ message: 'xin chào' });
  });
});
