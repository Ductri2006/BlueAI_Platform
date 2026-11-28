// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const source = readFileSync(resolve('public/widget.js'), 'utf8');
let mount: HTMLElement;
let shadow: ShadowRoot;
let input: HTMLInputElement;
let fetchMock: ReturnType<typeof vi.fn>;
const submitted = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/messages'));
const enter = (options: KeyboardEventInit = {}) => {
  const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...options });
  input.dispatchEvent(event);
  return event;
};

beforeEach(async () => {
  const script = document.createElement('script');
  script.dataset.botKey = 'ime-widget';
  script.dataset.apiBase = 'https://example.test';
  vi.spyOn(document, 'currentScript', 'get').mockReturnValue(script);
  fetchMock = vi.fn().mockImplementation(async (url: string) => new Response(JSON.stringify(url.endsWith('/sessions')
    ? { sessionId: 'ime-session', accessToken: 'test-token' }
    : { result: { message: '收到' } }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  new Function(source)();
  mount = document.querySelector<HTMLElement>('[data-blueai-widget="ime-widget"]')!;
  shadow = mount.shadowRoot!;
  input = shadow.querySelector('input')!;
  shadow.querySelector<HTMLButtonElement>('.launcher')!.click();
  await vi.waitFor(() => expect(shadow.querySelector('.status')!.textContent).toBe('Sẵn sàng hỗ trợ'));
});
afterEach(() => {
  mount?.remove();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('standalone widget IME submission', () => {
  it.each([
    ['Chinese', '你', '你好，请问价格？'],
    ['Vietnamese', 'Toi muon hoi gia', 'Tôi muốn hỏi giá.'],
  ])('confirms %s composition without sending, then sends on the next Enter', async (_language, partial, complete) => {
    input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    input.value = partial;
    expect(enter().defaultPrevented).toBe(true);
    expect(submitted()).toHaveLength(0);
    expect(input.value).toBe(partial);
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: complete }));
    input.value = complete;
    expect(submitted()).toHaveLength(0);
    expect(enter().defaultPrevented).toBe(true);
    await vi.waitFor(() => expect(submitted()).toHaveLength(1));
    expect(JSON.parse(submitted()[0][1].body).message).toBe(complete);
    expect(input.value).toBe('');
    enter();
    expect(submitted()).toHaveLength(1);
  });

  it.each([{ isComposing: true }, { keyCode: 229 }])('guards native IME confirmation %j', flags => {
    input.value = '你';
    expect(enter(flags).defaultPrevented).toBe(true);
    expect(submitted()).toHaveLength(0);
    expect(input.value).toBe('你');
  });

  it('does not submit Shift+Enter or repeated Enter', () => {
    input.value = '你好';
    expect(enter({ shiftKey: true }).defaultPrevented).toBe(true);
    expect(enter({ repeat: true }).defaultPrevented).toBe(true);
    expect(submitted()).toHaveLength(0);
  });

  it('keeps the explicit send button working after composition', async () => {
    input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    input.value = '你好 — Tôi muốn hỏi giá.';
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: input.value }));
    shadow.querySelector<HTMLButtonElement>('.send')!.click();
    await vi.waitFor(() => expect(submitted()).toHaveLength(1));
    expect(JSON.parse(submitted()[0][1].body).message).toBe('你好 — Tôi muốn hỏi giá.');
  });
});
