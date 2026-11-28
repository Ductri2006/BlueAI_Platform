// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blueAiApi } from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { useSharedChatQuota } from './ChatQuotaContext';
import { Composer } from './Composer';
import { RagMiniChat } from './RagMiniChat';
import { TryItOutPanel } from '../studio/TryItOutPanel';

vi.mock('../../store/useAppStore', () => ({ useAppStore: vi.fn() }));
vi.mock('./ChatQuotaContext', () => ({ useSharedChatQuota: vi.fn() }));

const sent = vi.fn<(text: string) => void>();
let host: HTMLDivElement;
let root: Root;
const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
type ChatInput = HTMLInputElement | HTMLTextAreaElement;
const typeText = async (input: ChatInput, value: string) => {
  await act(async () => {
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};
const composition = async (input: ChatInput, type: 'compositionstart' | 'compositionend', data = '') => {
  await act(async () => { input.dispatchEvent(new CompositionEvent(type, { bubbles: true, data })); });
};
const enter = async (input: ChatInput, options: KeyboardEventInit = {}) => {
  const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...options });
  await act(async () => { input.dispatchEvent(event); });
  return event;
};

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  sent.mockReset();
  vi.mocked(useAppStore).mockReturnValue({
    streamStatus: 'idle', sendMessage: async (text: string) => { sent(text); return true; },
  } as ReturnType<typeof useAppStore>);
  vi.mocked(useSharedChatQuota).mockReturnValue({
    blocked: false, seconds: 0, aiError: '', refresh: vi.fn().mockResolvedValue(undefined),
  } as unknown as ReturnType<typeof useSharedChatQuota>);
  vi.spyOn(blueAiApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(blueAiApi, 'createConversation').mockResolvedValue({ id: 'ime-conversation', contextMode: 'CURRENT_CONVERSATION' } as Awaited<ReturnType<typeof blueAiApi.createConversation>>);
  vi.spyOn(blueAiApi, 'askQuestion').mockImplementation(async (_id, text) => {
    sent(text);
    return { assistantMessage: { id: 'answer', content: '收到', createdAt: '2026-11-28T04:00:00Z' }, sources: [] } as unknown as Awaited<ReturnType<typeof blueAiApi.askQuestion>>;
  });
  vi.spyOn(blueAiApi, 'previewStudioBot').mockImplementation(async (_id, text) => {
    sent(text);
    return { message: '收到', context: { variables: {} } } as Awaited<ReturnType<typeof blueAiApi.previewStudioBot>>;
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView);
  else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

const surfaces = [
  { name: 'Composer', render: () => <Composer />, input: 'textarea', send: '[aria-label="Gửi câu hỏi"]' },
  { name: 'RagMiniChat', render: () => <RagMiniChat title="IME" placeholder="IME test" conversationTitle="IME" />, input: 'input[placeholder="IME test"]', send: 'input + button' },
  { name: 'TryItOutPanel', render: () => <TryItOutPanel botId="ime-bot" botName="IME" blueAiCoreEnabled onClose={() => {}} />, input: 'form input', send: '[aria-label="Gửi câu thử nghiệm"]' },
];

describe.each(surfaces)('$name IME submission', surface => {
  const show = async () => {
    await act(async () => root.render(surface.render()));
    return host.querySelector<ChatInput>(surface.input)!;
  };

  it.each([
    ['Chinese', '你', '你好，请问价格？'],
    ['Vietnamese', 'Toi muon hoi gia', 'Tôi muốn hỏi giá.'],
  ])('keeps partial %s during composition and sends once on the next Enter', async (_language, partial, complete) => {
    const input = await show();
    await composition(input, 'compositionstart');
    await typeText(input, partial);
    // Some IMEs omit isComposing on their confirmation keydown.
    expect((await enter(input)).defaultPrevented).toBe(true);
    expect(sent).not.toHaveBeenCalled();
    expect(input.value).toBe(partial);
    await composition(input, 'compositionend', complete);
    await typeText(input, complete);
    expect(sent).not.toHaveBeenCalled();
    expect((await enter(input)).defaultPrevented).toBe(true);
    expect(sent.mock.calls).toEqual([[complete]]);
    expect(input.value).toBe('');
    await enter(input);
    expect(sent).toHaveBeenCalledTimes(1);
  });

  it.each([
    { isComposing: true },
    { keyCode: 229 },
  ])('cancels Enter before guarding native IME flags %j', async flags => {
    const input = await show();
    await typeText(input, '你');
    expect((await enter(input, flags)).defaultPrevented).toBe(true);
    expect(sent).not.toHaveBeenCalled();
    expect(input.value).toBe('你');
  });

  it('preserves Shift+Enter and ignores repeated Enter without sending', async () => {
    const input = await show();
    await typeText(input, '第一行');
    expect((await enter(input, { shiftKey: true })).defaultPrevented).toBe(input instanceof HTMLInputElement);
    expect(input.value).toBe('第一行');
    expect((await enter(input, { repeat: true })).defaultPrevented).toBe(true);
    expect(sent).not.toHaveBeenCalled();
    if (input instanceof HTMLTextAreaElement) {
      // jsdom does not insert the browser's default newline after keydown.
      await typeText(input, '第一行\nDòng thứ hai');
      await enter(input);
      expect(sent.mock.calls).toEqual([['第一行\nDòng thứ hai']]);
    }
  });

  it('still sends completed text with the explicit send button', async () => {
    const input = await show();
    await composition(input, 'compositionstart');
    await typeText(input, '你好 — Tôi muốn hỏi giá.');
    await composition(input, 'compositionend', input.value);
    expect(sent).not.toHaveBeenCalled();
    await act(async () => host.querySelector<HTMLButtonElement>(surface.send)!.click());
    expect(sent.mock.calls).toEqual([['你好 — Tôi muốn hỏi giá.']]);
    expect(input.value).toBe('');
  });
});
