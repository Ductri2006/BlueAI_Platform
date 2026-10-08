import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { BlueAiApi } from '../../api/blueAiApi';
import { StudioTemplateSelector } from './StudioTemplateSelector';
import { TryItOutPanel } from './TryItOutPanel';

describe('RAG and Dialog modes', () => {
  it('creates the combined website demo through the API', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('{}', { status: 201 }));
    const api = new BlueAiApi({ accessToken: 'test-access', fetchImpl });
    await api.createStudioBot({ name: 'Website support', template: 'RAG_DIALOG_DEMO' });
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ name: 'Website support', template: 'RAG_DIALOG_DEMO' });
    const html = renderToStaticMarkup(<StudioTemplateSelector value="RAG_DIALOG_DEMO" onChange={() => {}} />);
    expect(html).toContain('value="RAG_DIALOG_DEMO" selected=""');
    expect(html).toContain('tài liệu công khai đã chọn');
  });

  it.each([[true, true], [false, true], [true, false]])('persists Dialog=%s and RAG=%s with public document scope', async (dialogEnabled, blueAiCoreEnabled) => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    const api = new BlueAiApi({ accessToken: 'test-access', fetchImpl });
    await api.saveStudioBotConfiguration('bot', { expectedRevision: 4, name: 'Bot', description: '', locale: 'vi-VN',
      timezone: 'Asia/Ho_Chi_Minh', dialogEnabled, blueAiCoreEnabled, widgetDocumentIds: ['approved-document'],
      intents: [], entities: [], dialogNodes: [] });
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toMatchObject({ dialogEnabled, blueAiCoreEnabled, widgetDocumentIds: ['approved-document'] });
  });

  it('shows RAG-only preview without claiming that Dialog is running', () => {
    const html = renderToStaticMarkup(<TryItOutPanel botId="bot" botName="Bot" blueAiCoreEnabled dialogEnabled={false} onClose={() => {}} />);
    expect(html).toContain('Chỉ RAG');
    expect(html).toContain('không chạy kịch bản Dialog');
    expect(html).not.toContain('Intent/entity/Dialog chạy trước');
  });
});
