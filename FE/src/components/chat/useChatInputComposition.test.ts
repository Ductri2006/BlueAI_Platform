import { describe, expect, it, vi } from 'vitest';
import { shouldSubmitChatOnEnter } from './useChatInputComposition';

describe('chat Enter semantics', () => {
  it.each([
    { isComposing: true },
    { nativeEvent: { isComposing: true } },
    { keyCode: 229 },
    { nativeEvent: { keyCode: 229 } },
    { repeat: true },
  ])('cancels handled Enter even when blocked by %j', flags => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ key: 'Enter', preventDefault, ...flags })).toBe(false);
    expect(preventDefault).toHaveBeenCalledOnce();
  });

  it('prevents the default action before reading IME flags', () => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ key: 'Enter', preventDefault, get isComposing() {
      expect(preventDefault).toHaveBeenCalledOnce();
      return true;
    } })).toBe(false);
  });

  it('honors tracked composition when the keyboard event omits IME flags', () => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ key: 'Enter', preventDefault }, true)).toBe(false);
    expect(preventDefault).toHaveBeenCalledOnce();
  });

  it.each([{ key: 'Enter', shiftKey: true }, { key: 'a' }])('leaves non-submit keys untouched: %j', key => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ ...key, preventDefault })).toBe(false);
    expect(preventDefault).not.toHaveBeenCalled();
  });

  it('allows an ordinary Enter after preventing the default form action', () => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ key: 'Enter', preventDefault })).toBe(true);
    expect(preventDefault).toHaveBeenCalledOnce();
  });

  it('blocks implicit Shift+Enter submission for a single-line input', () => {
    const preventDefault = vi.fn();
    expect(shouldSubmitChatOnEnter({ key: 'Enter', shiftKey: true, currentTarget: { tagName: 'INPUT' }, preventDefault })).toBe(false);
    expect(preventDefault).toHaveBeenCalledOnce();
  });
});
