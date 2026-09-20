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
