// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiQuotaMenu, aiQuotaStatus } from './AiQuotaMenu';
import type { AiTokenQuota } from '../../api/blueAiApi';

const quota: AiTokenQuota = { enabled: true, serverTime: '',
  fiveHour: {limit:100000,used:2200,reserved:0,uncertain:0,remaining:97800,resetsAt:null,resetAfterSeconds:0},
  weekly: {limit:1000000,used:2200,reserved:0,uncertain:0,remaining:997800,resetsAt:null,resetAfterSeconds:0} };
let root: Root;
let host: HTMLDivElement;
const refresh = vi.fn<() => Promise<void>>().mockResolvedValue();
const trigger = () => host.querySelector<HTMLButtonElement>('[aria-haspopup="dialog"]')!;
const click = async (element: Element) => { await act(async () => (element as HTMLElement).click()); };
const show = async (value?: AiTokenQuota, error = '') => {
  await act(async () => root.render(<AiQuotaMenu quota={value} error={error} refresh={refresh} />));
};
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host); refresh.mockClear();
});
afterEach(async () => { await act(async () => root.unmount()); document.body.replaceChildren(); vi.restoreAllMocks(); });

describe('compact quota menu', () => {
  it('keeps all token bars outside the chat until explicitly opened', async () => {
    await show(quota);
    expect(trigger().textContent).toContain('Hạn mức AI');
    expect(trigger().getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('[role="progressbar"]')).toBeNull();
    await click(trigger());
    expect(document.querySelectorAll('[role="progressbar"]')).toHaveLength(2);
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('2.200 / 100.000');
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Đóng hạn mức AI');
  });
  it('closes with Escape and returns focus without closing an underlying workspace', async () => {
    await show(quota); await click(trigger());
    const underlying = vi.fn(); document.addEventListener('keydown', underlying);
    try {
      await act(async () => document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true})));
      expect(document.querySelector('[role="dialog"]')).toBeNull();
      expect(document.activeElement).toBe(trigger()); expect(underlying).not.toHaveBeenCalled();
    } finally { document.removeEventListener('keydown', underlying); }
  });
  it('returns focus when explicitly closed', async () => {
    await show(quota); await click(trigger());
    await click(document.querySelector('[aria-label="Đóng hạn mức AI"]')!);
    expect(document.querySelector('[role="dialog"]')).toBeNull(); expect(document.activeElement).toBe(trigger());
  });
  it('dismisses on outside press without stealing focus from the composer', async () => {
    await show(quota); await click(trigger());
    const input = document.createElement('textarea'); input.value='Câu hỏi đang soạn'; document.body.append(input);
    await act(async () => { input.dispatchEvent(new Event('pointerdown',{bubbles:true})); input.focus(); });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(input); expect(input.value).toBe('Câu hỏi đang soạn');
  });
  it('dismisses when keyboard focus leaves, keeping navigation accessible', async () => {
    await show(quota); await click(trigger());
    const input = document.createElement('textarea'); document.body.append(input);
    await act(async () => input.focus());
    expect(document.querySelector('[role="dialog"]')).toBeNull(); expect(document.activeElement).toBe(input);
  });
  it('keeps clicks inside the details open and allows refresh', async () => {
    await show(quota); await click(trigger());
    const panel = document.querySelector('[role="dialog"]')!;
    await act(async () => panel.dispatchEvent(new Event('pointerdown',{bubbles:true})));
    await click(document.querySelector('[aria-label="Làm mới hạn mức AI"]')!);
    expect(refresh).toHaveBeenCalledTimes(1); expect(document.querySelector('[role="dialog"]')).not.toBeNull();
  });
  it('shows loading or a failed read honestly and supports retry', async () => {
    await show(); await click(trigger());
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('Đang đọc hạn mức AI');
    await show(undefined, 'Chưa đọc được hạn mức AI');
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('Chưa đọc được');
    expect(document.querySelector('[role="progressbar"]')).toBeNull();
    await click(document.querySelector('[aria-label="Làm mới hạn mức AI"]')!);
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it('keeps a refreshed snapshot visible while the panel is open', async () => {
    await show(quota); await click(trigger());
    await show({...quota,fiveHour:{...quota.fiveHour!,used:4474,remaining:95526}});
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('4.474 / 100.000');
    expect(document.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('4.5');
  });
  it.each([320,390,768,1440])('bounds the popup inside a %i px viewport', async width => {
    Object.defineProperty(window,'innerWidth',{value:width, configurable:true});
    vi.spyOn(HTMLElement.prototype,'getBoundingClientRect').mockReturnValue({left:width-60,right:width-20,top:10,bottom:46,width:40,height:36,x:width-60,y:10,toJSON:()=>({})});
    await show(quota); await click(trigger());
    const panel=document.querySelector<HTMLElement>('[role="dialog"]')!;
    expect(parseFloat(panel.style.left)).toBeGreaterThanOrEqual(12);
    expect(parseFloat(panel.style.left)+parseFloat(panel.style.width)).toBeLessThanOrEqual(width-12);
  });
  it('does not announce exhaustion from reservations alone', async () => {
    const held={...quota, fiveHour:{...quota.fiveHour!,reserved:97800,remaining:0}};
    expect(aiQuotaStatus(held).label).toBe('Đang chờ xác nhận token');
    await show(held); await click(trigger());
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain('Hạn mức AI đã hết');
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('97.800 token');
  });
  it('warns for either budget, including a weekly cap', async () => {
    const full={...quota,weekly:{...quota.weekly!,used:1000000,remaining:0}};
    expect(aiQuotaStatus(full).tone).toBe('exhausted');
    await show(full); await click(trigger());
    expect(trigger().disabled).toBe(false);
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('Dialog vẫn sử dụng được');
    expect(aiQuotaStatus({...quota,weekly:{...quota.weekly!,used:850000,remaining:150000}}).tone).toBe('warning');
  });
  it('omits the menu when the server disables the policy', async () => {
    await show({...quota,enabled:false}); expect(host.innerHTML).toBe('');
  });
});
