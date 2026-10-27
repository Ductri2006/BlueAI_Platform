/** Hide internal attribution in legacy replies as well as newly generated answers. */
export function presentAssistantAnswer(content: string, filenames: string[] = []): string {
  let answer = content
    .replace(/\[(\[S\d+(?:\s*[,;]\s*S?\d+)*\]|S\d+)\]\([^\r\n)]*\)/gi, '')
    .replace(/\[S\d+(?:\s*[,;]\s*S?\d+)*\]/gi, '');
  for (const filename of filenames) {
    if (filename.trim()) answer = answer.split(filename).join('tài liệu nội bộ');
  }
  return answer.replace(/[ \t]+([,.;:!?])/g, '$1').replace(/[ \t]+$/gm, '').trim();
}
