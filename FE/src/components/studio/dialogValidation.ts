export interface DialogValidationNode {
  name: string;
  type?: string;
  priority?: number;
  matchIntent?: string;
  matchEntityName?: string;
  matchEntityValue?: string;
  requiredEntityName?: string;
  parentNode?: string;
  nextStep?: string;
  jumpToNode?: string;
  responseText?: string;
  contextReusePolicy?: string;
}
export interface DialogValidationEntity { name: string; values: { value: string }[] }
const key = (value?: string) => (value || '').trim().toLocaleLowerCase();
const MAX_AUTOMATIC_STEPS = 20;

/** Error checks block saving; routing advice remains non-blocking. WAIT stops automatic traversal. */
export function analyzeDialogNodes(
  nodes: DialogValidationNode[], intentNames: string[] = [], entityNames: string[] = [],
  entities?: DialogValidationEntity[],
): { errors: string[]; warnings: string[] } {
  const errors: string[] = [];
  const warnings: string[] = [];
  const named = new Map<string, DialogValidationNode>();
  const priorities = new Set<number>();
  const knownIntents = new Set(intentNames.map(key));
  const knownEntities = new Set(entityNames.map(key));
  for (const type of ['WELCOME', 'FALLBACK']) {
    if (nodes.filter(node => node.type === type).length !== 1) errors.push(`Luồng cần đúng một bước ${type === 'WELCOME' ? 'chào mở đầu' : 'dự phòng khi chưa hiểu'}.`);
  }
  nodes.forEach(node => {
    if (!key(node.name)) errors.push('Có bước hội thoại chưa đặt tên.');
    else if (named.has(key(node.name))) errors.push(`Tên bước bị trùng: “${node.name}”.`);
    else named.set(key(node.name), node);
    if (!Number.isInteger(node.priority) || node.priority! < -2147483648 || node.priority! > 2147483647) errors.push(`Bước “${node.name}” cần ưu tiên là số nguyên hợp lệ.`);
    else if (priorities.has(node.priority!)) errors.push(`Ưu tiên ${node.priority} bị trùng tại “${node.name}”; mỗi bước cần một số khác nhau.`);
    else priorities.add(node.priority!);
  });
  nodes.forEach(node => {
    if (node.name.trim().length > 120) errors.push(`Tên bước “${node.name}” tối đa 120 ký tự.`);
    if (!['WELCOME', 'FALLBACK', 'ROUTE', 'FOLDER'].includes(node.type || '')) errors.push(`Bước “${node.name}” cần loại bước hợp lệ.`);
    if (node.nextStep && !['WAIT_FOR_REPLY', 'SKIP_USER_INPUT', 'JUMP_TO'].includes(node.nextStep)) errors.push(`Bước “${node.name}” cần hành động tiếp theo hợp lệ.`);
    if (node.contextReusePolicy && !['NONE', 'CONFIRMED_ONLY'].includes(node.contextReusePolicy)) errors.push(`Bước “${node.name}” cần chính sách dữ kiện hợp lệ.`);
    if (node.type === 'ROUTE' && !key(node.matchIntent)) errors.push(`Nhánh “${node.name}” chưa chọn mục đích của người dùng (intent).`);
    if (key(node.matchIntent) && !knownIntents.has(key(node.matchIntent))) errors.push(`Bước “${node.name}” tham chiếu intent không tồn tại: “${node.matchIntent}”.`);
    for (const reference of [node.matchEntityName, node.requiredEntityName]) {
      if (key(reference) && !knownEntities.has(key(reference))) errors.push(`Bước “${node.name}” tham chiếu entity không tồn tại: “${reference}”.`);
    }
    if (key(node.matchEntityValue)) {
      if (!key(node.matchEntityName)) errors.push(`Bước “${node.name}” có giá trị nhưng chưa chọn loại dữ kiện (entity).`);
      else if (entities && knownEntities.has(key(node.matchEntityName)) && !entities.find(entity => key(entity.name) === key(node.matchEntityName))?.values.some(value => key(value.value) === key(node.matchEntityValue))) {
        errors.push(`Bước “${node.name}” dùng giá trị chưa có trong danh sách chính của @${node.matchEntityName}: “${node.matchEntityValue}”. Chọn giá trị chính, không dùng tên gọi khác.`);
      }
    }
    if (key(node.parentNode) && !named.has(key(node.parentNode))) errors.push(`Bước “${node.name}” có bước cha không tồn tại: “${node.parentNode}”.`);
    if (key(node.parentNode) === key(node.name) && key(node.name)) errors.push(`Bước “${node.name}” không thể là bước cha của chính nó.`);
    if (['WELCOME', 'FALLBACK'].includes(node.type || '') && key(node.parentNode)) errors.push(`Bước “${node.name}” phải ở cấp gốc.`);
    if ((node.nextStep === 'JUMP_TO' || key(node.jumpToNode)) && !named.has(key(node.jumpToNode))) errors.push(`Bước “${node.name}” cần một bước đích hợp lệ.`);
    if (node.nextStep === 'JUMP_TO' && key(node.jumpToNode) === key(node.name)) errors.push(`Bước “${node.name}” không thể tự chuyển đến chính nó.`);
    if (node.type !== 'FOLDER' && (!node.nextStep || node.nextStep === 'WAIT_FOR_REPLY') && !node.responseText?.trim()) errors.push(`Bước “${node.name}” cần lời đáp trước khi chờ người dùng.`);
  });

  const reportedParentCycles = new Set<string>();
  nodes.forEach(node => {
    const path = new Set<string>();
    let current: DialogValidationNode | undefined = node;
    while (current && key(current.parentNode)) {
      const name = key(current.name);
      if (path.has(name)) {
        const cycleKey = [...path].sort().join('|');
        if (!reportedParentCycles.has(cycleKey)) errors.push(`Cây hội thoại có vòng lặp bước cha tại “${current.name}”.`);
        reportedParentCycles.add(cycleKey);
        break;
      }
      path.add(name);
      current = named.get(key(current.parentNode));
    }
  });

  // References must be valid before interpreting execution paths.
  if (errors.length) return { errors: [...new Set(errors)], warnings: [] };

  // Narrow possible entity values along each path instead of enumerating a cartesian
  // product. This models first-match priority, including folders; JUMP bypasses conditions.
  const ordered = [...nodes].sort((a, b) => (a.priority || 0) - (b.priority || 0));
  const OTHER = Symbol('any other canonical value');
  type EntityValue = string | null | typeof OTHER;
  type Domains = Map<string, Set<EntityValue>>;
  type Condition = { entity?: string; value?: string };
  const universe: Domains = new Map();
  entities?.forEach(entity => universe.set(key(entity.name), new Set<EntityValue>([null, ...entity.values.map(value => key(value.value))])));
  nodes.forEach(node => {
    for (const name of [node.matchEntityName, node.requiredEntityName].filter(Boolean)) {
      const values = universe.get(key(name)) || new Set<EntityValue>(entities ? [null] : [null, OTHER]);
      if (!entities && key(node.matchEntityValue) && key(name) === key(node.matchEntityName)) values.add(key(node.matchEntityValue));
      universe.set(key(name), values);
    }
  });
  const conditions = (node: DialogValidationNode, intent: string, seen = new Set<string>()): Condition[] => {
    if (node.type === 'ROUTE') return key(node.matchIntent) === intent ? [{ entity: key(node.matchEntityName) || undefined, value: key(node.matchEntityValue) || undefined }] : [];
    if (node.type !== 'FOLDER' || seen.has(key(node.name))) return [];
    seen.add(key(node.name));
    return ordered.filter(child => key(child.parentNode) === key(node.name)).flatMap(child => conditions(child, intent, new Set(seen)));
  };
  const narrow = (domains: Domains, condition: Condition, positive: boolean): Domains | undefined => {
    if (!condition.entity) return positive ? domains : undefined;
    const values = new Set([...(domains.get(condition.entity) || universe.get(condition.entity)!)].filter(value => {
      const matches = condition.value ? value === condition.value : value !== null;
      return positive ? matches : !matches;
    }));
    return values.size ? new Map([...domains, [condition.entity, values]]) : undefined;
  };
  const successors = (node: DialogValidationNode, intent: string, domains: Domains): { node: DialogValidationNode; domains: Domains }[] => {
    if (node.type !== 'FOLDER' && (!node.nextStep || node.nextStep === 'WAIT_FOR_REPLY')) return [];
    if (node.type !== 'FOLDER' && node.nextStep === 'JUMP_TO') {
      const target = named.get(key(node.jumpToNode));
      return target ? [{ node: target, domains }] : [];
    }
    const candidates = [
      ...ordered.filter(child => key(child.parentNode) === key(node.name) && ['ROUTE', 'FOLDER'].includes(child.type || '')),
      ...ordered.filter(sibling => key(sibling.parentNode) === key(node.parentNode) && sibling.priority! > node.priority! && sibling.type === 'ROUTE'),
    ];
    const result: { node: DialogValidationNode; domains: Domains }[] = [];
    let remaining: Domains | undefined = domains;
    for (const candidate of candidates) {
      if (!remaining) break;
      const alternatives = conditions(candidate, intent);
      for (const condition of alternatives) {
        if (!remaining) break;
        const match = narrow(remaining, condition, true);
        if (match) result.push({ node: candidate, domains: match });
        remaining = narrow(remaining, condition, false);
      }
    }
    const fallback = ordered.find(candidate => candidate.type === 'FALLBACK' && !key(candidate.parentNode));
    if (remaining && fallback) result.push({ node: fallback, domains: remaining });
    return result;
  };
  const automaticCycles = new Set<string>();
  const automaticReach = new Map<string, Set<string>>();
  const jumpTargets = new Set(nodes.filter(node => node.type !== 'FOLDER' && node.nextStep === 'JUMP_TO').map(node => key(node.jumpToNode)));
  const emptyVariables: Domains = new Map([...universe.keys()].map(entity => [entity, new Set<EntityValue>([null])]));
  const depthReported = new Set<string>();
  for (const intent of new Set(['', ...nodes.map(node => key(node.matchIntent)).filter(Boolean)])) {
    for (const start of nodes) {
      const walk = (current: DialogValidationNode, domains: Domains, path: DialogValidationNode[]) => {
          const withRequired = narrow(domains, { entity: key(current.requiredEntityName) || undefined }, true);
          if (!withRequired) return; // A missing required entity pauses to ask the user.
          const position = path.findIndex(item => key(item.name) === key(current.name));
          if (position >= 0) {
            const cycle = path.slice(position);
            const cycleKey = cycle.map(item => key(item.name)).sort().join('|');
            if (!automaticCycles.has(cycleKey)) errors.push(`Vòng lặp tự động: ${cycle.map(item => item.name).join(' → ')} → ${current.name}. Thêm bước chờ người dùng để ngắt vòng lặp.`);
            automaticCycles.add(cycleKey);
            return;
          }
          const reachable = automaticReach.get(key(start.name)) || new Set<string>();
          if (current !== start) reachable.add(key(current.name));
          automaticReach.set(key(start.name), reachable);
          const nextSteps = successors(current, intent, withRequired);
          // The current node counts as executed, including a terminal WAIT node.
          if (path.length + 1 >= MAX_AUTOMATIC_STEPS && nextSteps.length) {
            if (!depthReported.has(key(start.name))) errors.push(`Chuỗi tự chạy từ “${start.name}” vượt 20 bước. Thêm bước chờ người dùng trước khi vượt giới hạn.`);
            depthReported.add(key(start.name));
            return;
          }
          nextSteps.forEach(next => walk(next.node, next.domains, [...path, current]));
      };
      if (jumpTargets.has(key(start.name)) || start.type === 'FALLBACK') walk(start, new Map(), []);
      else if (start.type === 'WELCOME') { if (!intent) walk(start, emptyVariables, []); }
      else conditions(start, intent).forEach(condition => {
        const domain = narrow(new Map(), condition, true);
        if (domain) walk(start, domain, []);
      });
    }
  }
  ordered.filter(node => node.type === 'ROUTE').forEach(node => {
    const covering = ordered.find(earlier => earlier.type === 'ROUTE' && earlier.priority! < node.priority! && key(earlier.parentNode) === key(node.parentNode) && key(earlier.matchIntent) === key(node.matchIntent)
      && (!key(earlier.matchEntityName) || (key(earlier.matchEntityName) === key(node.matchEntityName) && (!key(earlier.matchEntityValue) || key(earlier.matchEntityValue) === key(node.matchEntityValue)))));
    if (covering && !automaticReach.get(key(covering.name))?.has(key(node.name)) && !nodes.some(source => source.nextStep === 'JUMP_TO' && key(source.jumpToNode) === key(node.name))) warnings.push(`Nhánh “${node.name}” có thể bị “${covering.name}” khớp trước. Đặt nhánh cụ thể trước nhánh chung hoặc liên kết bước tiếp theo.`);
  });
  const reachable = new Set(nodes.filter(node => !key(node.parentNode) && node.type !== 'FOLDER').map(node => key(node.name)));
  let changed = true;
  while (changed) {
    changed = false;
    nodes.forEach(node => {
      if (reachable.has(key(node.name))) return;
      const parent = named.get(key(node.parentNode));
      const viaParent = parent && reachable.has(key(parent.name)) && (parent.type === 'FOLDER' || parent.nextStep !== 'JUMP_TO');
      const viaJump = nodes.some(source => reachable.has(key(source.name)) && source.nextStep === 'JUMP_TO' && key(source.jumpToNode) === key(node.name));
      if (viaParent || viaJump) { reachable.add(key(node.name)); changed = true; }
    });
  }
  nodes.filter(node => !reachable.has(key(node.name))).forEach(node => warnings.push(`Bước “${node.name}” chưa có đường đi tới từ luồng cấp gốc. Kiểm tra bước cha hoặc liên kết chuyển đến.`));
  return { errors: [...new Set(errors)], warnings: [...new Set(warnings)] };
}
