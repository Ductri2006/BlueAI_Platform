import { describe, expect, it } from 'vitest';
import type { StudioDialogNode } from '../../api/blueAiApi';
import { dialogReply, patchDialogReply } from './dialogTranslations';

const node: StudioDialogNode = {
  name: 'Welcome', type: 'WELCOME', matchIntent: '', matchEntityName: '', matchEntityValue: '',
  requiredEntityName: '', responseText: 'Xin chào', missingEntityPrompt: '', priority: 1,
  englishResponseText: 'Hello', englishMissingEntityPrompt: 'Your device?',
};

describe('Dialog language dictionaries', () => {
  it('reads old English fields only when the language dictionary was omitted', () => {
    expect(dialogReply(node, 'en').responseText).toBe('Hello');
    expect(dialogReply({ ...node, translations: {} }, 'en')).toEqual({});
  });
  it('preserves other languages and prompt fields when editing an English response', () => {
    const original = { ...node, translations: { en: { responseText: 'Hello', missingEntityPrompt: 'Device?' }, ja: { responseText: 'こんにちは' } } };
    const patch = patchDialogReply(original, 'en', { responseText: 'Welcome' });
    expect(patch.translations?.en).toEqual({ responseText: 'Welcome', missingEntityPrompt: 'Device?' });
    expect(patch.translations?.ja.responseText).toBe('こんにちは');
    expect(original.translations.en.responseText).toBe('Hello');
    expect(patch.englishResponseText).toBe('Welcome');
  });
  it('upgrades a legacy draft and retains an explicit empty translation', () => {
    const patched = { ...node, ...patchDialogReply(node, 'en', { responseText: '' }) };
    expect(dialogReply(patched, 'en').responseText).toBe('');
    expect(dialogReply(patched, 'en').missingEntityPrompt).toBe('Your device?');
  });

  it.each([
    ['responseText', '欢迎来到 {{location}}'],
    ['missingEntityPrompt', '请选择设备'],
    ['irrelevantResponseText', '请再说一次'],
  ] as const)('edits and clears Chinese %s without changing other translations', (field, text) => {
    const original = {
      ...node,
      translations: {
        en: { responseText: 'Hello', missingEntityPrompt: 'Device?', irrelevantResponseText: 'Please repeat' },
        'zh-CN': { responseText: '你好', missingEntityPrompt: '什么设备？', irrelevantResponseText: '我没听懂' },
        ja: { responseText: 'こんにちは' },
        vi: { responseText: 'Xin chào' },
      },
    };
    const snapshot = structuredClone(original);
    const edit = patchDialogReply(original, 'zh-CN', { [field]: text });
    const edited = { ...original, ...edit };
    expect(edit).toEqual({ translations: { ...original.translations, 'zh-CN': { ...original.translations['zh-CN'], [field]: text } } });
    expect(dialogReply(edited, 'zh-CN')[field]).toBe(text);
    const clear = patchDialogReply(edited, 'zh-CN', { [field]: '' });
    const cleared = { ...edited, ...clear };
    expect(clear).toEqual({ translations: { ...original.translations, 'zh-CN': { ...original.translations['zh-CN'], [field]: '' } } });
    expect(dialogReply(cleared, 'zh-CN')[field]).toBe('');
    expect(cleared.englishResponseText).toBe(original.englishResponseText);
    expect(cleared.englishMissingEntityPrompt).toBe(original.englishMissingEntityPrompt);
    expect(original).toEqual(snapshot);
  });

  it.each([undefined, null])('retains legacy English fields when first adding Chinese to a %s dictionary', translations => {
    const original = { ...node, translations, englishIrrelevantResponseText: 'Please repeat' };
    const edit = patchDialogReply(original, 'zh-CN', { responseText: '你好 {{location}}' });
    const edited = { ...original, ...edit };
    expect(edit).toEqual({ translations: {
      en: { responseText: 'Hello', missingEntityPrompt: 'Your device?', irrelevantResponseText: 'Please repeat' },
      'zh-CN': { responseText: '你好 {{location}}' },
    } });
    expect(dialogReply(edited, 'en')).toEqual(dialogReply(original, 'en'));
    expect(edited.englishResponseText).toBe('Hello');
    expect(edited.englishMissingEntityPrompt).toBe('Your device?');
    expect(edited.englishIrrelevantResponseText).toBe('Please repeat');
    expect(dialogReply(original, 'zh-CN')).toEqual({});
  });

  it('keeps Chinese translations while synchronizing an edited English legacy field', () => {
    const original = { ...node, translations: { 'zh-CN': { responseText: '你好' }, ja: { responseText: 'こんにちは' } } };
    const edit = patchDialogReply(original, 'en', { responseText: 'Welcome' });
    expect(edit.translations?.['zh-CN']).toEqual(original.translations['zh-CN']);
    expect(edit.translations?.ja).toEqual(original.translations.ja);
    expect(edit.translations?.en.responseText).toBe('Welcome');
    expect(edit.englishResponseText).toBe('Welcome');
  });
});
