// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StudioDialogNode } from '../../api/blueAiApi';
import { DialogTranslationsEditor } from './DialogTranslationsEditor';

const node: StudioDialogNode = {
  name: 'Welcome', type: 'WELCOME', matchIntent: '', matchEntityName: '', matchEntityValue: '',
  requiredEntityName: 'device', responseText: 'Xin chào', missingEntityPrompt: 'Thiết bị nào?', priority: 1,
  englishResponseText: 'Hello', englishMissingEntityPrompt: 'Your device?', englishIrrelevantResponseText: 'Please repeat',
};
let root: Root;
let host: HTMLDivElement;
let current: StudioDialogNode;
const onChange = vi.fn<(patch: Partial<StudioDialogNode>) => void>();
const renderEditor = async () => {
  await act(async () => root.render(<DialogTranslationsEditor node={current} onChange={onChange} inputClass="shared-input" labelClass="shared-label" />));
  host.querySelector('details')!.open = true;
};
const selectLocale = async (locale: string) => {
  const select = host.querySelector('select')!;
  await act(async () => {
    select.value = locale;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
};
const editText = async (textarea: HTMLTextAreaElement, value: string) => {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(textarea, value);
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await renderEditor();
};

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  current = structuredClone(node);
  onChange.mockReset().mockImplementation(patch => { current = { ...current, ...patch }; });
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});

describe('Dialog translation language selection', () => {
  it('offers Simplified Chinese on a legacy draft and labels all three reply fields', async () => {
    await renderEditor();
    const select = host.querySelector('select')!;
    expect(select.labels?.[0].textContent).toContain('Ngôn ngữ bản dịch');
    expect(Array.from(select.options, option => [option.value, option.textContent])).toEqual([
      ['en', 'Tiếng Anh'], ['zh-CN', 'Tiếng Trung giản thể'],
    ]);
    expect(select.value).toBe('en');
    await selectLocale('zh-CN');
    expect(select.value).toBe('zh-CN');
    const fields = Array.from(host.querySelectorAll('textarea'));
    expect(fields.map(field => field.labels?.[0].textContent)).toEqual([
      'Nội dung trả lời — Tiếng Trung giản thể',
      'Câu hỏi bổ sung — Tiếng Trung giản thể',
      'Lời đáp khi chưa rõ — Tiếng Trung giản thể',
    ]);
    expect(fields.every(field => field.value === '')).toBe(true);
    expect(host.textContent).toContain('{{location}}');
    expect(host.textContent).not.toContain('Bản dịch này được lưu để mở rộng');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('keeps existing locales selectable without duplicating Chinese or offering the Vietnamese source', async () => {
    current = { ...node, translations: { 'zh-CN': { responseText: '你好' }, ja: { responseText: 'こんにちは' }, vi: { responseText: 'Xin chào' } } };
    await renderEditor();
    expect(Array.from(host.querySelector('select')!.options, option => option.value)).toEqual(['en', 'zh-CN', 'ja']);
    await selectLocale('ja');
    expect(host.querySelector('textarea')!.value).toBe('こんにちは');
    expect(host.textContent).toContain('hỗ trợ trả lời bằng tiếng Việt, tiếng Anh và tiếng Trung giản thể');
    await selectLocale('zh-CN');
    expect(host.querySelector('textarea')!.value).toBe('你好');
    expect(host.textContent).not.toContain('Bản dịch này được lưu để mở rộng');
  });

  it.each([
    [0, 'responseText', '欢迎来到 {{location}}'],
    [1, 'missingEntityPrompt', '请选择设备'],
    [2, 'irrelevantResponseText', '请再说一次'],
  ] as const)('edits and clears Chinese field %i while keeping English and other locales', async (index, field, text) => {
    const translations = {
      en: { responseText: 'Hello', missingEntityPrompt: 'Device?', irrelevantResponseText: 'Please repeat' },
      'zh-CN': { responseText: '你好', missingEntityPrompt: '什么设备？', irrelevantResponseText: '我没听懂' },
      ja: { responseText: 'こんにちは' },
      vi: { responseText: 'Xin chào' },
    };
    current = { ...node, translations: structuredClone(translations) };
    await renderEditor();
    await selectLocale('zh-CN');
    await editText(host.querySelectorAll('textarea')[index], text);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(current.translations).toEqual({ ...translations, 'zh-CN': { ...translations['zh-CN'], [field]: text } });
    expect(host.querySelectorAll('textarea')[index].value).toBe(text);
    await editText(host.querySelectorAll('textarea')[index], '');
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(current.translations).toEqual({ ...translations, 'zh-CN': { ...translations['zh-CN'], [field]: '' } });
    expect(host.querySelectorAll('textarea')[index].value).toBe('');
    expect(current.englishResponseText).toBe(node.englishResponseText);
    expect(current.englishMissingEntityPrompt).toBe(node.englishMissingEntityPrompt);
    expect(current.englishIrrelevantResponseText).toBe(node.englishIrrelevantResponseText);
    await selectLocale('en');
    expect(host.querySelector('textarea')!.value).toBe('Hello');
    await selectLocale('ja');
    expect(host.querySelector('textarea')!.value).toBe('こんにちは');
    expect(translations['zh-CN'][field]).not.toBe('');
  });

  it('adds Chinese to a legacy draft without losing its English text or prompts', async () => {
    await renderEditor();
    await selectLocale('zh-CN');
    await editText(host.querySelector('textarea')!, '你好 {{location}}');
    expect(current.translations?.['zh-CN'].responseText).toBe('你好 {{location}}');
    await selectLocale('en');
    expect(Array.from(host.querySelectorAll('textarea'), field => field.value)).toEqual(['Hello', 'Your device?', 'Please repeat']);
    expect(current.englishResponseText).toBe('Hello');
    expect(current.englishMissingEntityPrompt).toBe('Your device?');
    expect(current.englishIrrelevantResponseText).toBe('Please repeat');
  });
});
