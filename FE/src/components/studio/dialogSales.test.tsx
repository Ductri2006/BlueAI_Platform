import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { StudioDialogNode } from '../../api/blueAiApi';
import { analyzeDialogNodes } from './dialogValidation';
import { deleteDialogNode, planDialogInsertion, renameDialogNode, summarizeDialogStep, uniqueDialogName } from './dialogNodeOperations';
import { DialogContextPolicy, DialogValidationNotice } from './DialogContextPolicy';

const node = (name: string, priority: number, patch: Partial<StudioDialogNode> = {}): StudioDialogNode => ({
  name, priority, type: 'ROUTE', matchIntent: 'hoi_gia', matchEntityName: '', matchEntityValue: '', requiredEntityName: '',
  responseText: 'Bạn muốn hỏi giá sản phẩm nào?', missingEntityPrompt: '', nextStep: 'WAIT_FOR_REPLY', ...patch,
});
const bookends = [node('Chào', 0, { type: 'WELCOME', matchIntent: '' }), node('Chưa hiểu', 999, { type: 'FALLBACK', matchIntent: '' })];
const entities = [{ name: 'chi_nhanh', values: [{ value: 'Hà Nội' }, { value: 'HCM' }] }];
const check = (routes: StudioDialogNode[]) => analyzeDialogNodes([...bookends, ...routes], ['hoi_gia', 'dat_hang'], ['chi_nhanh'], entities);

describe('sales dialog validation', () => {
  it('allows the common same-intent route → answer → WAIT flow without shadow warnings', () => {
    const result = check([
      node('Khách hỏi giá', 10, { responseText: '', nextStep: 'JUMP_TO', jumpToNode: 'Báo giá' }),
      node('Báo giá', 20, { responseText: 'Giá sản phẩm là 100.000đ.', requiredEntityName: 'chi_nhanh', contextReusePolicy: 'CONFIRMED_ONLY' }),
    ]);
    expect(result).toEqual({ errors: [], warnings: [] });
  });

  it('allows sequential same-intent SKIP → answer and retains WAIT as a legal boundary', () => {
    expect(check([
      node('Thông tin chung', 10, { nextStep: 'SKIP_USER_INPUT' }),
      node('Báo giá', 20, { jumpToNode: 'Thông tin chung', nextStep: 'WAIT_FOR_REPLY' }),
    ])).toEqual({ errors: [], warnings: [] });
  });

  it('rejects duplicate priorities and missing/duplicate welcome and fallback steps', () => {
    const duplicate = check([node('Giá 1', 10), node('Giá 2', 10)]);
    expect(duplicate.errors.join(' ')).toContain('Ưu tiên 10 bị trùng');
    expect(analyzeDialogNodes([], [], []).errors).toHaveLength(2);
    expect(check([node('Chào nữa', 50, { type: 'WELCOME' }), node('Dự phòng nữa', 60, { type: 'FALLBACK' })]).errors.join(' ')).toContain('đúng một bước');
  });

  it('validates intent/entity names, canonical values (not synonyms) and integer priorities', () => {
    expect(check([node('Giá', 1.5, { matchIntent: 'đã_xoá', requiredEntityName: 'đã_xoá', matchEntityName: 'chi_nhanh', matchEntityValue: 'Sài Gòn' })]).errors.join(' '))
      .toMatch(/số nguyên hợp lệ.*intent không tồn tại.*entity không tồn tại.*giá trị chưa có/s);
    expect(check([node('Giá', 10, { matchEntityName: 'CHI_NHANH', matchEntityValue: ' hcm ' })]).errors).toEqual([]);
    expect(check([node('Giá', 10, { matchEntityValue: 'HCM' })]).errors.join(' ')).toContain('chưa chọn loại dữ kiện');
  });

  it('rejects unknown links, self-parent/self-jump and parent cycles', () => {
    expect(check([node('Giá', 10, { parentNode: 'đã xoá', nextStep: 'JUMP_TO', jumpToNode: 'đã xoá' })]).errors.join(' ')).toMatch(/bước cha không tồn tại.*bước đích hợp lệ/s);
    expect(check([node('Giá', 10, { parentNode: 'giá', nextStep: 'JUMP_TO', jumpToNode: 'GIÁ' })]).errors.join(' ')).toMatch(/cha của chính nó.*tự chuyển đến chính nó/s);
    expect(check([node('A', 10, { parentNode: 'B' }), node('B', 20, { parentNode: 'A' })]).errors.join(' ')).toContain('vòng lặp bước cha');
  });

  it('blocks only automatic jump/skip loops and accepts a conversation loop with WAIT', () => {
    const routes = [node('Giá', 10, { nextStep: 'JUMP_TO', jumpToNode: 'Xác nhận' }), node('Xác nhận', 20, { nextStep: 'JUMP_TO', jumpToNode: 'Giá' })];
    expect(check(routes).errors.join(' ')).toContain('Vòng lặp tự động');
    routes[1].nextStep = 'WAIT_FOR_REPLY';
    expect(check(routes).errors).toEqual([]);
    expect(check([node('A', 10, { nextStep: 'SKIP_USER_INPUT' }), node('B', 20, { nextStep: 'JUMP_TO', jumpToNode: 'A' })]).errors.join(' ')).toContain('Vòng lặp tự động');
  });

  it('honors first matching branch instead of inventing a loop through a shadowed child', () => {
    const result = check([
      node('Giá', 10, { nextStep: 'SKIP_USER_INPUT' }),
      node('Lời đáp chung', 20, { parentNode: 'Giá' }),
      node('Quay lại', 30, { parentNode: 'Giá', matchEntityName: 'chi_nhanh', matchEntityValue: 'HCM', nextStep: 'JUMP_TO', jumpToNode: 'Giá' }),
    ]);
    expect(result.errors).toEqual([]);
    expect(result.warnings.join(' ')).toContain('khớp trước');
  });

  it('does not block cycles whose automatic entity conditions cannot coexist', () => {
    const result = check([
      node('Nhóm A', 10, { type: 'FOLDER', matchIntent: '' }),
      node('Nhóm B', 20, { type: 'FOLDER', matchIntent: '' }),
      node('A', 30, { parentNode: 'Nhóm A', nextStep: 'SKIP_USER_INPUT' }),
      node('HN', 40, { parentNode: 'A', matchEntityName: 'chi_nhanh', matchEntityValue: 'Hà Nội', nextStep: 'JUMP_TO', jumpToNode: 'B' }),
      node('B', 50, { parentNode: 'Nhóm B', nextStep: 'SKIP_USER_INPUT' }),
      node('HCM', 60, { parentNode: 'B', matchEntityName: 'chi_nhanh', matchEntityValue: 'HCM', nextStep: 'JUMP_TO', jumpToNode: 'A' }),
    ]);
    expect(result.errors).toEqual([]);
  });

  it('warns about covered and unreachable paths without preventing a valid draft save', () => {
    const result = check([
      node('Giá chung', 10), node('Giá HCM', 20, { matchEntityName: 'chi_nhanh', matchEntityValue: 'HCM' }),
      node('Nhóm chưa nối', 30, { type: 'FOLDER', matchIntent: '' }), node('Nhánh chưa nối', 40, { parentNode: 'Nhóm chưa nối' }),
    ]);
    expect(result.errors).toEqual([]);
    expect(result.warnings.join(' ')).toContain('khớp trước');
    expect(result.warnings.join(' ')).toContain('chưa có đường đi tới');
  });

  it('requires a response for WAIT but allows a routing-only jump step', () => {
    expect(check([node('Giá', 10, { responseText: '' })]).errors.join(' ')).toContain('cần lời đáp');
    expect(check([node('Giá', 10, { responseText: '', nextStep: 'JUMP_TO', jumpToNode: 'Chưa hiểu' })]).errors).toEqual([]);
  });

  it('allows exactly 20 executed steps including WAIT and rejects a 21st step', () => {
    const chain = (length: number) => Array.from({ length }, (_, index) => node(`Bước ${index + 1}`, index + 1, {
      nextStep: index === length - 1 ? 'WAIT_FOR_REPLY' : 'JUMP_TO', jumpToNode: index === length - 1 ? '' : `Bước ${index + 2}`,
    }));
    expect(check(chain(20)).errors).toEqual([]);
    expect(check(chain(21)).errors.join(' ')).toContain('vượt 20 bước');
  });

  it('does not invent values absent from the catalog when detecting automatic cycles', () => {
    const routes = [node('Giá', 10, { nextStep: 'SKIP_USER_INPUT' }), node('HN', 20, { parentNode: 'Giá', matchEntityName: 'chi_nhanh', matchEntityValue: 'Hà Nội' }), node('Mọi nơi khác', 30, { parentNode: 'Giá', matchEntityName: 'chi_nhanh', nextStep: 'JUMP_TO', jumpToNode: 'Giá' })];
    expect(analyzeDialogNodes([...bookends, ...routes], ['hoi_gia'], ['chi_nhanh'], [{ name: 'chi_nhanh', values: [{ value: 'Hà Nội' }] }]).errors).toEqual([]);
    expect(check(routes).errors.join(' ')).toContain('Vòng lặp tự động');
  });

  it('handles many independent conditions without a cartesian entity enumeration', () => {
    const catalog = Array.from({ length: 40 }, (_, index) => ({ name: `e${index}`, values: [{ value: 'v' }] }));
    const routes = [node('Giá', 10, { nextStep: 'SKIP_USER_INPUT' }), ...catalog.map((entity, index) => node(`Đáp ${index}`, index + 20, { parentNode: 'Giá', matchEntityName: entity.name, matchEntityValue: 'v' }))];
    expect(analyzeDialogNodes([...bookends, ...routes], ['hoi_gia'], catalog.map(entity => entity.name), catalog).errors).toEqual([]);
  });
});

describe('stable dialog priorities', () => {
  it('adds a branch before catch-all without changing any existing custom priority', () => {
    const existing = [node('Chưa hiểu', 999, { type: 'FALLBACK' }), node('Giá', 47, { nextStep: 'SKIP_USER_INPUT' }), node('Hỏi chi nhánh', 300, { parentNode: 'Giá' }), node('HN', 102, { parentNode: 'Giá', matchEntityName: 'chi_nhanh', matchEntityValue: 'Hà Nội' })];
    const plan = planDialogInsertion(existing, [node('HCM', 0, { parentNode: 'Giá', matchEntityName: 'chi_nhanh', matchEntityValue: 'HCM' })], 'Hỏi chi nhánh');
    expect(plan.requiresRenumber).toBe(false);
    existing.forEach(before => expect(plan.nodes.find(after => after.name === before.name)?.priority).toBe(before.priority));
    const inserted = plan.nodes.find(item => item.name === 'HCM')!;
    expect(inserted.priority).toBeGreaterThan(102);
    expect(inserted.priority).toBeLessThan(300);
    expect(new Set(plan.nodes.map(item => item.priority)).size).toBe(plan.nodes.length);
  });

  it('plans two linked steps and a duplicate without rewriting runtime order', () => {
    const existing = [node('B', 200), node('A', 17), node('Dự phòng', 999, { type: 'FALLBACK' })];
    const plan = planDialogInsertion(existing, [node('Nhánh', 0), node('Đích', 0)], 'B');
    expect(plan.requiresRenumber).toBe(false);
    expect(plan.nodes.filter(item => existing.some(old => old.name === item.name))).toEqual(existing);
    const duplicate = planDialogInsertion(plan.nodes, [node('A bản sao', 17)], 'Nhánh');
    expect(duplicate.requiresRenumber).toBe(false);
    expect(duplicate.nodes.find(item => item.name === 'A')?.priority).toBe(17);
  });

  it('requires conscious renumbering only when the integer gap is exhausted and retains relative order', () => {
    const existing = [node('B', 11), node('A', 10), node('C', 500)];
    const plan = planDialogInsertion(existing, [node('Mới', 0)], 'B');
    expect(plan.requiresRenumber).toBe(true);
    expect([...plan.nodes].sort((a, b) => a.priority - b.priority).map(item => item.name)).toEqual(['A', 'Mới', 'B', 'C']);
    expect(existing.map(item => item.priority)).toEqual([11, 10, 500]);
  });

  it('handles integer extremes without generating invalid server priorities', () => {
    const plan = planDialogInsertion([node('Cuối', 2147483647)], [node('Mới', 0)]);
    expect(plan.requiresRenumber).toBe(true);
    const before = planDialogInsertion([node('Đầu', -2147483648)], [node('Mới', 0)], 'Đầu');
    expect(before.requiresRenumber).toBe(true);
  });

  it('deletes a branch, repairs inbound links and preserves every remaining priority', () => {
    const existing = [node('Cha', 42), node('Nhánh', 79, { parentNode: 'Cha' }), node('Con', 165, { parentNode: 'NHÁNH' }), node('Nguồn', 388, { nextStep: 'JUMP_TO', jumpToNode: 'nhánh' })];
    const result = deleteDialogNode(existing, 'Nhánh');
    expect(result.map(item => item.priority)).toEqual([42, 165, 388]);
    expect(result.find(item => item.name === 'Con')?.parentNode).toBe('Cha');
    expect(result.find(item => item.name === 'Nguồn')).toMatchObject({ nextStep: 'WAIT_FOR_REPLY', jumpToNode: '' });
    expect(deleteDialogNode(bookends, 'Chào')).toBe(bookends);
  });

  it('renames parent and jump references together without editing priorities', () => {
    const result = renameDialogNode([node('Giá', 42), node('Con', 200, { parentNode: 'GIÁ', jumpToNode: 'giá' })], 0, ' Báo giá ');
    expect(result[0]).toMatchObject({ name: 'Báo giá', priority: 42 });
    expect(result[1]).toMatchObject({ parentNode: 'Báo giá', jumpToNode: 'Báo giá', priority: 200 });
  });

  it('keeps generated names unique and within the server limit, including duplicates of long names', () => {
    const long = 'a'.repeat(120);
    const result = uniqueDialogName([long], `${long} bản sao`);
    expect(result).not.toBe(long);
    expect(result.length).toBeLessThanOrEqual(120);
    expect(uniqueDialogName([' Nhánh ', 'nhánh 2'], 'NHÁNH')).toBe('NHÁNH 3');
  });
});

describe('business route guidance', () => {
  it('summarizes direct jumps and the target WAIT boundary, including bypassed conditions', () => {
    const routes = [node('Hỏi giá', 10, { nextStep: 'JUMP_TO', jumpToNode: 'Báo giá' }), node('Báo giá', 20)];
    expect(summarizeDialogStep(routes[0], routes)).toContain('Không xét lại điều kiện');
    expect(summarizeDialogStep(routes[0], routes)).toContain('chờ người dùng');
    expect(summarizeDialogStep({ ...routes[0], jumpToNode: 'missing' }, routes)).toContain('chưa chọn bước đích hợp lệ');
  });

  it('defaults context reuse to NONE and makes confirmation explicit', () => {
    const markup = renderToStaticMarkup(<DialogContextPolicy onChange={() => {}} className="" />);
    expect(markup).toContain('value="NONE" selected=""');
    expect(markup).toContain('Đề xuất từ ghi nhớ/phiên liên kết, luôn xác nhận');
    expect(markup).toContain('phiên bạn chủ động liên kết');
    expect(markup).toContain('trước khi dùng');
  });

  it('renders warnings as advisory and errors as required before saving', () => {
    const markup = renderToStaticMarkup(<DialogValidationNotice errors={['Lỗi đích']} warnings={['Khớp trước']} />);
    expect(markup).toContain('Cần sửa trước khi lưu');
    expect(markup).toContain('vẫn có thể lưu');
  });
});
