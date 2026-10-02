import { describe, expect, it } from 'vitest';
import {
  compactDraftEntries,
  mergeEntityImports,
  mergeIntentImports,
  parseDelimitedDraftLine,
  parseEntityImport,
  parseIntentImport,
  splitCommaDraft,
  splitMultilineDraft,
  validateDialogNodes,
} from './studioDrafts';

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

  it('parses quoted CSV intent examples and groups repeated intent rows', () => {
    expect(parseDelimitedDraftLine('greeting,"Xin chào, BlueAI",Hello')).toEqual(['greeting', 'Xin chào, BlueAI', 'Hello']);
    expect(parseIntentImport('intent,example\ngreeting,"Xin chào, BlueAI"\ngreeting,Hello')).toEqual({
      items: [{ name: 'greeting', description: '', examples: ['Xin chào, BlueAI', 'Hello'] }],
      errors: [],
    });
  });

  it('detects delimiters outside quotes and skips a BOM/header after leading blanks', () => {
    expect(parseDelimitedDraftLine('greeting,"A | B",Hello')).toEqual(['greeting', 'A | B', 'Hello']);
    expect(parseIntentImport('\n\uFEFFintent,example\ngreeting,"A | B"')).toEqual({
      items: [{ name: 'greeting', description: '', examples: ['A | B'] }],
      errors: [],
    });
    expect(parseEntityImport('\n\uFEFFentity,value,synonym\ncity,Hồ Chí Minh,TP HCM')).toEqual({
      items: [{ name: 'city', description: '', values: [{ value: 'Hồ Chí Minh', synonyms: ['TP HCM'] }] }],
      errors: [],
    });
  });

  it('reports an unclosed quote instead of silently importing malformed data', () => {
    const result = parseIntentImport('intent,example\ngreeting,Hello\ngreeting,"unclosed');
    expect(result.items).toEqual([{ name: 'greeting', description: '', examples: ['Hello'] }]);
    expect(result.errors).toEqual(['Dòng 3: thiếu dấu ngoặc kép đóng.']);
  });

  it('reports actionable line errors while keeping valid import preview rows', () => {
    const result = parseIntentImport('intent,example\ngreeting,Hello\nmissing-example');
    expect(result.items).toHaveLength(1);
    expect(result.errors).toEqual(['Dòng 3: cần ít nhất một câu mẫu sau tên intent.']);
  });

  it('parses entity values and merges synonyms without removing existing data', () => {
    const parsed = parseEntityImport('entity,value,synonym\ncity,Hồ Chí Minh,TP HCM\ncity,Hồ Chí Minh,Sài Gòn');
    const merged = mergeEntityImports([
      { name: 'city', description: '', values: [{ value: 'Hồ Chí Minh', synonyms: ['HCM'] }] },
    ], parsed.items);
    expect(merged.added).toBe(0);
    expect(merged.merged).toBe(1);
    expect(merged.items[0].values[0].synonyms).toEqual(['HCM', 'TP HCM', 'Sài Gòn']);
  });

  it('appends new intents and de-duplicates examples when importing', () => {
    const merged = mergeIntentImports([
      { name: 'greeting', description: '', examples: ['Hello'] },
    ], [
      { name: 'greeting', description: '', examples: ['hello', 'Xin chào'] },
      { name: 'hours', description: '', examples: ['Mở cửa lúc mấy giờ?'] },
    ]);
    expect(merged.added).toBe(1);
    expect(merged.merged).toBe(1);
    expect(merged.items[0].examples).toEqual(['Hello', 'Xin chào']);
  });

  it('catches broken dialog links before a draft can be saved', () => {
    expect(validateDialogNodes([
      { name: 'Route giá', nextStep: 'JUMP_TO', jumpToNode: 'missing' },
      { name: 'Child', parentNode: 'missing' },
      { name: 'Child' },
    ])).toEqual(expect.arrayContaining([
      'Tên bước bị trùng: “Child”.',
      'Bước “Route giá” cần một bước đích hợp lệ.',
      'Bước “Child” có bước cha không tồn tại: “missing”.',
    ]));
  });

  it('catches routes that reference deleted intents or entities', () => {
    expect(validateDialogNodes([
      { name: 'Route', type: 'ROUTE', matchIntent: 'deleted_intent', matchEntityName: 'deleted_entity' },
    ], ['greeting'], ['location'])).toEqual(expect.arrayContaining([
      'Bước “Route” tham chiếu intent không tồn tại: “deleted_intent”.',
      'Bước “Route” tham chiếu entity không tồn tại: “deleted_entity”.',
    ]));
  });
});
