import type { StudioDialogNode } from '../../api/blueAiApi';

const key = (value?: string) => (value || '').trim().toLocaleLowerCase();
const MIN_PRIORITY = -2147483648;
const MAX_PRIORITY = 2147483647;

export function uniqueDialogName(names: string[], base: string): string {
  const used = new Set(names.map(key));
  const trimmed = base.trim() || 'Bước mới';
  if (!used.has(key(trimmed.slice(0, 120)))) return trimmed.slice(0, 120);
  let suffix = 2;
  while (true) {
    const ending = ` ${suffix}`;
    const name = `${trimmed.slice(0, 120 - ending.length)}${ending}`;
    if (!used.has(key(name))) return name;
    suffix += 1;
  }
}

/** Plan first: exhausted integer gaps require the editor's explicit confirmation. */
export function planDialogInsertion<T extends { name: string; priority: number }>(
  nodes: T[], additions: T[], beforeName?: string,
): { nodes: T[]; requiresRenumber: boolean } {
  const ordered = [...nodes].sort((a, b) => a.priority - b.priority);
  const anchor = beforeName ? ordered.findIndex(node => key(node.name) === key(beforeName)) : -1;
  const position = anchor < 0 ? ordered.length : anchor;
  const lower = position > 0 ? ordered[position - 1].priority : Math.max(MIN_PRIORITY, (ordered[0]?.priority || 0) - 10 * (additions.length + 1));
  const upper = position < ordered.length ? ordered[position].priority : Math.min(MAX_PRIORITY, lower + 10 * (additions.length + 1));
  const step = Math.floor((upper - lower) / (additions.length + 1));
  const canFit = step >= 1 && Number.isInteger(lower) && Number.isInteger(upper);
  const placed = additions.map((node, index) => ({ ...node, priority: lower + step * (index + 1) }));
  const storagePosition = beforeName ? nodes.findIndex(node => key(node.name) === key(beforeName)) : -1;
  const result = [...nodes];
  result.splice(storagePosition < 0 ? nodes.length : storagePosition, 0, ...placed);
  if (canFit) return { nodes: result, requiresRenumber: false };
  const runtimeOrder = [...ordered];
  runtimeOrder.splice(position, 0, ...additions);
  const priorities = new Map(runtimeOrder.map((node, index) => [key(node.name), index * 10]));
  return { nodes: result.map(node => ({ ...node, priority: priorities.get(key(node.name))! })), requiresRenumber: true };
}

export function deleteDialogNode(nodes: StudioDialogNode[], name: string): StudioDialogNode[] {
  const removed = nodes.find(node => key(node.name) === key(name));
  if (!removed || removed.type === 'WELCOME' || removed.type === 'FALLBACK') return nodes;
  return nodes.filter(node => node !== removed).map(node => ({
    ...node,
    parentNode: key(node.parentNode) === key(name) ? removed.parentNode || '' : node.parentNode,
    jumpToNode: key(node.jumpToNode) === key(name) ? '' : node.jumpToNode,
    nextStep: key(node.jumpToNode) === key(name) ? 'WAIT_FOR_REPLY' : node.nextStep,
  }));
}

export function renameDialogNode(nodes: StudioDialogNode[], index: number, name: string): StudioDialogNode[] {
  const previous = nodes[index]?.name;
  if (previous === undefined) return nodes;
  return nodes.map((node, nodeIndex) => ({
    ...node,
    name: nodeIndex === index ? name.trim() : node.name,
    parentNode: key(previous) && key(node.parentNode) === key(previous) ? name.trim() : node.parentNode,
    jumpToNode: key(previous) && key(node.jumpToNode) === key(previous) ? name.trim() : node.jumpToNode,
  }));
}

export function summarizeDialogStep(node: StudioDialogNode, nodes: StudioDialogNode[]): string {
  if (node.type === 'FOLDER') return 'Nhóm các bước: xét điều kiện của bước con theo ưu tiên.';
  if (node.nextStep === 'JUMP_TO') {
    const target = nodes.find(item => key(item.name) === key(node.jumpToNode));
    if (!target) return 'Chuyển ngay → chưa chọn bước đích hợp lệ.';
    return `Chuyển ngay → ${target.name}. Không xét lại điều kiện của bước đích. ${target.type === 'FOLDER' ? 'Sau đó tự xét bước con theo điều kiện.' : target.nextStep === 'JUMP_TO' ? `Sau đó chuyển → ${target.jumpToNode || '(chưa chọn)'}.` : target.nextStep === 'SKIP_USER_INPUT' ? 'Sau đó tự xét bước con / bước tiếp theo.' : 'Sau đó chờ người dùng trả lời.'}`;
  }
  return node.nextStep === 'SKIP_USER_INPUT'
    ? 'Tự tiếp tục: xét bước con trước, rồi nhánh cùng cấp có ưu tiên lớn hơn; không khớp thì dùng bước dự phòng.'
    : 'Gửi lời đáp và chờ câu tiếp theo; khi có câu mới, xét bước con trước rồi nhánh cấp gốc.';
}
