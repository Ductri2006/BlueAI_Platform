// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blueAiApi, BlueAiApiError, type StudioRuntimeResponse } from '../../api/blueAiApi';
import { StudioPreviewFallbackBadge } from './StudioPreviewFallbackBadge';
import { TryItOutPanel } from './TryItOutPanel';

vi.mock('../chat/ChatQuotaContext', () => ({
  useSharedChatQuota: () => ({ blocked: false, seconds: 0, aiError: '', refresh: vi.fn() }),
}));

let host: HTMLDivElement;
let root: Root;
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');

function preview(answerStatus?: StudioRuntimeResponse['answerStatus']): StudioRuntimeResponse {
  return { message: '请稍后重试。', answerStatus, recognizedEntities: {}, context: { variables: {} }, fallback: true };
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  vi.spyOn(blueAiApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(blueAiApi, 'previewStudioBot').mockResolvedValue(preview('ANSWERED'));
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (originalScroll) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
  else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

async function showAndSubmit() {
  await act(async () => root.render(<TryItOutPanel botId="test-bot" botName="Test" blueAiCoreEnabled onClose={() => {}} />));
  const input = host.querySelector<HTMLInputElement>('form input')!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, '你好 — câu hỏi tiếng Việt');
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  input.focus();
  await act(async () => { input.form!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  return input;
}

describe('preview answer statuses', () => {
  it.each([
    ['SAFE_FALLBACK', 'Lỗi dịch vụ'],
    ['NEEDS_DETAILS', 'Cần thêm thông tin'],
    ['INSUFFICIENT_EVIDENCE', 'Chưa đủ căn cứ xác thực'],
  ] as const)('labels %s explicitly without the legacy fallback badge', (answerStatus, label) => {
    const html = renderToStaticMarkup(<StudioPreviewFallbackBadge answerStatus={answerStatus} fallback />);
    expect(html).toContain(label);
    expect(html).not.toContain('Dự phòng');
  });

  it('preserves legacy badges and lets ANSWERED override the old fallback flag', () => {
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge fallback />)).toContain('Dự phòng');
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge answerStatus="ANSWERED" fallback />)).toBe('');
  });

  it('preserves draft and keyboard focus after a safe fallback', async () => {
    vi.mocked(blueAiApi.previewStudioBot).mockResolvedValueOnce(preview('SAFE_FALLBACK'));
    const input = await showAndSubmit();
    expect(input.value).toBe('你好 — câu hỏi tiếng Việt');
    expect(document.activeElement).toBe(input);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Lỗi dịch vụ');
    expect(host.textContent).not.toContain('Chưa đủ căn cứ xác thực');
  });

  it('uses a generic recoverable message for technical API errors', async () => {
    vi.mocked(blueAiApi.previewStudioBot).mockRejectedValueOnce(new BlueAiApiError('Provider API_KEY=secret https://internal.example', 502, 'PRODUCT_UPSTREAM_FAILED'));
    const input = await showAndSubmit();
    expect(input.value).toBe('你好 — câu hỏi tiếng Việt');
    expect(document.activeElement).toBe(input);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Vui lòng thử lại');
    expect(host.textContent).not.toMatch(/API_KEY|secret|internal\.example|PRODUCT_UPSTREAM_FAILED/);
  });
});
