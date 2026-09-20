import { describe, expect, it } from 'vitest';
import { compactDraftEntries, splitCommaDraft, splitMultilineDraft } from './studioDrafts';

describe('studio draft list editing', () => {
  it('keeps a trailing line while the user is composing another intent example', () => {
    expect(splitMultilineDraft('Xin chào\n')).toEqual(['Xin chào', '']);
  });

  it('keeps a trailing comma while the user is composing another synonym', () => {
    expect(splitCommaDraft('Hồ Chí Minh,')).toEqual(['Hồ Chí Minh', '']);
  });

  it('removes unfinished blank entries before saving', () => {
    expect(compactDraftEntries([' Xin chào ', '', '  Chào BlueAI  ']))
      .toEqual(['Xin chào', 'Chào BlueAI']);
  });
});
