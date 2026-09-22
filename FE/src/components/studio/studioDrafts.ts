export function splitMultilineDraft(value: string): string[] {
  return value.split(/\r?\n/);
}

export function splitCommaDraft(value: string): string[] {
  return value
    .split(',')
    .map((item, index) => index === 0 ? item : item.replace(/^\s+/, ''));
}

export function compactDraftEntries(values: string[]): string[] {
  return values
    .map(value => value.trim())
    .filter(Boolean);
}

export interface ParsedIntentImport {
  items: { name: string; description: string; examples: string[] }[];
  errors: string[];
}

export interface ParsedEntityImport {
  items: { name: string; description: string; values: { value: string; synonyms: string[] }[] }[];
  errors: string[];
}

export interface IntentImportMergeResult<T> {
  items: T[];
  added: number;
  merged: number;
}

interface DelimitedDraftLineResult {
  cells: string[];
  error?: string;
}

function detectDraftDelimiter(line: string): string {
  const counts: Record<string, number> = { ',': 0, '|': 0, '\t': 0 };
  let quoted = false;
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (character === '"') {
      if (quoted && line[index + 1] === '"') index += 1;
      else quoted = !quoted;
    } else if (!quoted && character in counts) {
      counts[character] += 1;
    }
  }
  if (counts['\t'] > 0) return '\t';
  if (counts['|'] > 0) return '|';
  return ',';
}

/** Parse one CSV/TSV/pipe-delimited row and keep quote errors visible to import validation. */
function parseDelimitedDraftLineResult(line: string): DelimitedDraftLineResult {
  const delimiter = detectDraftDelimiter(line);
  const cells: string[] = [];
  let cell = '';
  let quoted = false;
  let closedQuote = false;
  let error: string | undefined;

  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (character === '"') {
      if (quoted && line[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (quoted) {
        quoted = !quoted;
        closedQuote = true;
      } else if (!cell.trim() && !closedQuote) {
        quoted = true;
      } else {
        error = 'dấu ngoặc kép không hợp lệ; hãy bọc toàn bộ ô có dấu phân cách bằng dấu "';
      }
    } else if (character === delimiter && !quoted) {
      cells.push(cell.trim());
      cell = '';
      closedQuote = false;
    } else if (closedQuote && character.trim()) {
      error = 'có ký tự nằm sau dấu ngoặc kép đóng trước dấu phân cách';
    } else {
      cell += character;
    }
  }
  if (quoted) error = 'thiếu dấu ngoặc kép đóng';
  cells.push(cell.trim());
  return { cells, error };
}

/**
 * Parse one CSV/TSV/pipe-delimited row without pulling a CSV dependency into the
 * studio bundle. Quoted commas and escaped quotes are supported for examples
 * such as "Tôi muốn hỏi, ...".
 */
export function parseDelimitedDraftLine(line: string): string[] {
  return parseDelimitedDraftLineResult(line).cells;
}

function normalizeImportName(value: string): string {
  return value.trim().replace(/\s+/g, '_');
}

function isHeader(value: string, candidates: string[]): boolean {
  return candidates.includes(value.trim().toLocaleLowerCase());
}

function uniqueCaseInsensitive(values: string[]): string[] {
  const seen = new Set<string>();
  return values.filter(value => {
    const key = value.trim().toLocaleLowerCase();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function parseIntentImport(value: string): ParsedIntentImport {
  const grouped = new Map<string, { name: string; description: string; examples: string[] }>();
  const errors: string[] = [];
  const lines = value.split(/\r?\n/);
  let logicalRow = 0;

  lines.forEach((line, index) => {
    const normalizedLine = logicalRow === 0 ? line.replace(/^\uFEFF/, '') : line;
    if (!normalizedLine.trim()) return;
    const parsed = parseDelimitedDraftLineResult(normalizedLine);
    if (parsed.error) {
      errors.push(`Dòng ${index + 1}: ${parsed.error}.`);
      logicalRow += 1;
      return;
    }
    if (logicalRow === 0 && isHeader(parsed.cells[0], ['intent', 'intent_name', 'name', 'mục đích'])) {
      logicalRow += 1;
      return;
    }
    logicalRow += 1;
    const cells = parsed.cells;
    const name = normalizeImportName(cells[0] || '');
    const examples = uniqueCaseInsensitive(cells.slice(1));
    if (!name) {
      errors.push(`Dòng ${index + 1}: thiếu tên intent.`);
      return;
    }
    if (examples.length === 0) {
      errors.push(`Dòng ${index + 1}: cần ít nhất một câu mẫu sau tên intent.`);
      return;
    }
    const key = name.toLocaleLowerCase();
    const existing = grouped.get(key);
    if (existing) existing.examples = uniqueCaseInsensitive([...existing.examples, ...examples]);
    else grouped.set(key, { name, description: '', examples });
  });

  return { items: [...grouped.values()], errors };
}

export function parseEntityImport(value: string): ParsedEntityImport {
  const grouped = new Map<string, { name: string; description: string; values: { value: string; synonyms: string[] }[] }>();
  const errors: string[] = [];
  const lines = value.split(/\r?\n/);
  let logicalRow = 0;

  lines.forEach((line, index) => {
    const normalizedLine = logicalRow === 0 ? line.replace(/^\uFEFF/, '') : line;
    if (!normalizedLine.trim()) return;
    const parsed = parseDelimitedDraftLineResult(normalizedLine);
    if (parsed.error) {
      errors.push(`Dòng ${index + 1}: ${parsed.error}.`);
      logicalRow += 1;
      return;
    }
    if (logicalRow === 0 && isHeader(parsed.cells[0], ['entity', 'entity_name', 'name', 'entity name']) && isHeader(parsed.cells[1] || '', ['value', 'canonical', 'giá trị'])) {
      logicalRow += 1;
      return;
    }
    logicalRow += 1;
    const cells = parsed.cells;
    const name = normalizeImportName(cells[0] || '');
    const canonicalValue = cells[1]?.trim() || '';
    const synonyms = uniqueCaseInsensitive(cells.slice(2)).filter(item => item.toLocaleLowerCase() !== canonicalValue.toLocaleLowerCase());
    if (!name) {
      errors.push(`Dòng ${index + 1}: thiếu tên entity.`);
      return;
    }
    if (!canonicalValue) {
      errors.push(`Dòng ${index + 1}: cần giá trị chính tắc sau tên entity.`);
      return;
    }
    const key = name.toLocaleLowerCase();
    const entity = grouped.get(key) || { name, description: '', values: [] };
    const existingValue = entity.values.find(item => item.value.toLocaleLowerCase() === canonicalValue.toLocaleLowerCase());
    if (existingValue) existingValue.synonyms = uniqueCaseInsensitive([...existingValue.synonyms, ...synonyms]);
    else entity.values.push({ value: canonicalValue, synonyms });
    grouped.set(key, entity);
  });

  return { items: [...grouped.values()], errors };
}

function mergeUniqueStrings(existing: string[], incoming: string[]): string[] {
  return uniqueCaseInsensitive([...existing, ...incoming]);
}

export function mergeIntentImports<T extends { name: string; description: string; examples: string[] }>(
  existing: T[],
  incoming: T[],
): IntentImportMergeResult<T> {
  const items = existing.map(item => ({ ...item, examples: [...item.examples] })) as T[];
  let added = 0;
  let merged = 0;
  incoming.forEach(imported => {
    const match = items.find(item => item.name.toLocaleLowerCase() === imported.name.toLocaleLowerCase());
    if (!match) {
      items.push({ ...imported, examples: [...imported.examples] });
      added += 1;
    } else {
      match.examples = mergeUniqueStrings(match.examples, imported.examples);
      merged += 1;
    }
  });
  return { items, added, merged };
}

export function mergeEntityImports<T extends { name: string; description: string; values: { value: string; synonyms: string[] }[] }>(
  existing: T[],
  incoming: T[],
): IntentImportMergeResult<T> {
  const items = existing.map(item => ({
    ...item,
    values: item.values.map(value => ({ ...value, synonyms: [...value.synonyms] })),
  })) as T[];
  let added = 0;
  let merged = 0;
  incoming.forEach(imported => {
    const match = items.find(item => item.name.toLocaleLowerCase() === imported.name.toLocaleLowerCase());
    if (!match) {
      items.push({ ...imported, values: imported.values.map(value => ({ ...value, synonyms: [...value.synonyms] })) });
      added += 1;
      return;
    }
    imported.values.forEach(importedValue => {
      const value = match.values.find(item => item.value.toLocaleLowerCase() === importedValue.value.toLocaleLowerCase());
      if (value) value.synonyms = mergeUniqueStrings(value.synonyms, importedValue.synonyms);
      else match.values.push({ ...importedValue, synonyms: [...importedValue.synonyms] });
    });
    merged += 1;
  });
  return { items, added, merged };
}

export function validateDialogNodes(
  nodes: Array<{ name: string; type?: string; matchIntent?: string; matchEntityName?: string; requiredEntityName?: string; parentNode?: string; nextStep?: string; jumpToNode?: string }>,
  intentNames: string[] = [],
  entityNames: string[] = [],
): string[] {
  const errors: string[] = [];
  const names = new Set<string>();
  const knownIntents = new Set(intentNames.map(name => name.trim().toLocaleLowerCase()));
  const knownEntities = new Set(entityNames.map(name => name.trim().toLocaleLowerCase()));
  nodes.forEach(node => {
    const key = node.name.trim().toLocaleLowerCase();
    if (!key) errors.push('Có dialog node chưa đặt tên.');
    else if (names.has(key)) errors.push(`Tên dialog node bị trùng: “${node.name}”.`);
    else names.add(key);
  });
  nodes.forEach(node => {
    if (node.type === 'ROUTE' && !node.matchIntent?.trim()) {
      errors.push(`Route node “${node.name}” chưa chọn intent.`);
    }
    if (node.matchIntent?.trim() && !knownIntents.has(node.matchIntent.trim().toLocaleLowerCase())) {
      errors.push(`Node “${node.name}” đang tham chiếu intent không tồn tại: “${node.matchIntent}”.`);
    }
    for (const entityReference of [node.matchEntityName, node.requiredEntityName]) {
      if (entityReference?.trim() && !knownEntities.has(entityReference.trim().toLocaleLowerCase())) {
        errors.push(`Node “${node.name}” đang tham chiếu entity không tồn tại: “${entityReference}”.`);
      }
    }
    if (node.parentNode && !names.has(node.parentNode.trim().toLocaleLowerCase())) {
      errors.push(`Node “${node.name}” đang trỏ đến node cha không tồn tại: “${node.parentNode}”.`);
    }
    if (node.nextStep === 'JUMP_TO' && (!node.jumpToNode || !names.has(node.jumpToNode.trim().toLocaleLowerCase()))) {
      errors.push(`Node “${node.name}” cần một node đích hợp lệ.`);
    }
  });
  return errors;
}
