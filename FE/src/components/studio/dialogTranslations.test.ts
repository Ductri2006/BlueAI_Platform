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
});
