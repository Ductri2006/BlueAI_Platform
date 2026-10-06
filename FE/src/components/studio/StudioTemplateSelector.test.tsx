import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { BlueAiApi } from '../../api/blueAiApi';
import { StudioTemplateSelector } from './StudioTemplateSelector';

describe('sales demo creation', () => {
  it('explains the six scenarios, bounded quantities and safe draft defaults', () => {
    const html = renderToStaticMarkup(<StudioTemplateSelector value="SALES_DEMO" onChange={() => {}} />);
    expect(html).toContain('Demo sales — 6 kịch bản Dialog');
    expect(html).toContain('Mẫu khởi tạo');
    expect(html).toContain('10, 20, 30, 50, 100');
    expect(html).toContain('Tắt Core/LLM');
    expect(html).toContain('không tự phát hành');
    expect(html).toContain('value="SALES_DEMO" selected=""');
  });

  it('keeps the basic starter choice and old API contract available', async () => {
    const html = renderToStaticMarkup(<StudioTemplateSelector value="STARTER" onChange={() => {}} />);
    expect(html).toContain('value="STARTER" selected=""');
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('{}', { status: 201 }));
    const api = new BlueAiApi({ accessToken: 'test-access', fetchImpl });
    await api.createStudioBot({ name: 'Old caller', includeStarterTemplate: false });
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ name: 'Old caller', includeStarterTemplate: false });
    await api.createStudioBot({ name: 'Demo', template: 'SALES_DEMO' });
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({ name: 'Demo', template: 'SALES_DEMO' });
  });
});
