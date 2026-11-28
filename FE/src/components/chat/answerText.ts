import type { ChatMessage } from '../../types';

// A source identifier is not customer-facing content. Apply this at every
// presentation boundary too, so persisted replies from older releases are safe.
const SOURCE_ID = String.raw`S\d+(?:[ \t]*[,;][ \t]*S?\d+)*`;
const SOURCE_MARKER = String.raw`\[[ \t]*${SOURCE_ID}[ \t]*\]`;
const REFERENCE = `${SOURCE_MARKER}(?:\\([^\\r\\n)]*\\))?`;
// Remove paired emphasis only around the marker itself. Never consume the
// closing bold delimiter of real content such as **eSIM [S1]**.
const SOURCE_REFERENCE = new RegExp(`(?:\\*\\*${REFERENCE}\\*\\*|__${REFERENCE}__|${REFERENCE})`, 'gi');
const SOURCE_HEADING = /^(?:#{1,6}[ \t]+)?(?:\*\*|__)?(?:nguồn(?: tham khảo| tham chiếu)?|tài liệu tham khảo|sources?|references?|参考资料|引用来源|参考来源)(?:\*\*|__)?[ \t]*[:：]?(?:\*\*|__)?[ \t]*(.*)$/iu;
const SOURCE_ENTRY = new RegExp(`^(?:[-*]|\\d+[.)])?[ \\t]*(?:\\*\\*|__)?${SOURCE_MARKER}`, 'i');

export function withoutDocumentSources(content: string): string {
  const lines = content.split('\n');
  const visible: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const heading = lines[i].trim().match(SOURCE_HEADING);
    if (heading) {
      let end = i + 1;
      let hasEntry = SOURCE_ENTRY.test(heading[1]);
      // Only consume source entries, never the next business paragraph/heading.
      if (!heading[1] || hasEntry) {
        while (end < lines.length && (!lines[end].trim() || SOURCE_ENTRY.test(lines[end].trim()))) {
          hasEntry ||= SOURCE_ENTRY.test(lines[end].trim());
          end++;
        }
        if (hasEntry) { i = end - 1; continue; }
      }
    }
    visible.push(lines[i]);
  }
  return visible.join('\n').replace(SOURCE_REFERENCE, '')
    .replace(/[ \t]+([,.;:!?，。；：！？])/g, '$1')
    .replace(/[ \t]+$/gm, '').trim();
}

export function visibleMessageText(message: Pick<ChatMessage, 'sender' | 'content'>): string {
  return message.sender === 'user' ? message.content : withoutDocumentSources(message.content);
}
