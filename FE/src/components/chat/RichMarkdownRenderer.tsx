import React, { useState } from 'react';
import { Citation } from '../../types';
import { useAppStore } from '../../store/useAppStore';
import { Copy, Check, BookOpen, AlertCircle, Info, CheckCircle } from 'lucide-react';

interface RichMarkdownRendererProps {
  content: string;
  citations?: Citation[];
}

export const RichMarkdownRenderer: React.FC<RichMarkdownRendererProps> = ({ content, citations = [] }) => {
  const { openEvidence, showToast } = useAppStore();
  const [copiedCodeIndex, setCopiedCodeIndex] = useState<number | null>(null);

  const handleCopyCode = (code: string, index: number) => {
    navigator.clipboard.writeText(code);
    setCopiedCodeIndex(index);
    showToast('Đã sao chép đoạn mã/cấu hình', 'info');
    setTimeout(() => setCopiedCodeIndex(null), 2000);
  };

  // Process text into structured blocks (Paragraphs, Headings, Lists, Code, Tables, Callouts)
  const lines = content.split('\n');
  const renderedElements: React.ReactNode[] = [];
  let inCodeBlock = false;
  let codeBuffer: string[] = [];
  let codeLang = '';
  let inTable = false;
  let tableBuffer: string[] = [];
  let codeBlockCounter = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Handle Code Block
    if (line.startsWith('```')) {
      if (inCodeBlock) {
        // End of code block
        const codeText = codeBuffer.join('\n');
        const currentIndex = codeBlockCounter++;
        renderedElements.push(
          <div key={`code-${i}`} className="my-3 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-800 bg-slate-900 text-slate-100 shadow-md">
            <div className="flex items-center justify-between px-3.5 py-2 bg-slate-950/80 border-b border-slate-800 text-xs text-slate-400 font-mono">
              <span>{codeLang || 'cấu hình / mã'}</span>
              <button
                onClick={() => handleCopyCode(codeText, currentIndex)}
                className="flex items-center gap-1 hover:text-white transition-colors"
              >
                {copiedCodeIndex === currentIndex ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copiedCodeIndex === currentIndex ? 'Đã chép' : 'Sao chép'}</span>
              </button>
            </div>
            <pre className="p-3.5 text-xs font-mono overflow-x-auto text-emerald-300 leading-relaxed">
              <code>{codeText}</code>
            </pre>
          </div>
        );
        codeBuffer = [];
        inCodeBlock = false;
      } else {
        // Start of code block
        inCodeBlock = true;
        codeLang = line.replace('```', '').trim();
      }
      continue;
    }

    if (inCodeBlock) {
      codeBuffer.push(line);
      continue;
    }

    // Handle Table
    if (line.includes('|') && line.trim().startsWith('|')) {
      inTable = true;
      tableBuffer.push(line);
      continue;
    } else if (inTable) {
      // End of table, render it
      renderedElements.push(renderTable(tableBuffer, `table-${i}`));
      tableBuffer = [];
      inTable = false;
    }

    // Handle Headings
    if (line.startsWith('### ')) {
      renderedElements.push(
        <h4 key={`h3-${i}`} className="text-sm font-bold text-slate-900 dark:text-slate-100 mt-3 mb-1 tracking-tight">
          {renderInlineText(line.replace('### ', ''), citations, openEvidence)}
        </h4>
      );
      continue;
    }

    if (line.startsWith('## ')) {
      renderedElements.push(
        <h3 key={`h2-${i}`} className="text-base font-bold text-slate-900 dark:text-slate-100 mt-4 mb-2 tracking-tight">
          {renderInlineText(line.replace('## ', ''), citations, openEvidence)}
        </h3>
      );
      continue;
    }

    // Handle Lists
    if (line.trim().startsWith('- ') || line.trim().startsWith('* ')) {
      renderedElements.push(
        <div key={`li-${i}`} className="flex items-start gap-2 my-1 text-slate-700 dark:text-slate-300">
          <span className="w-1.5 h-1.5 rounded-full bg-cobalt-500 mt-2 shrink-0" />
          <div className="flex-1 leading-relaxed">
            {renderInlineText(line.trim().substring(2), citations, openEvidence)}
          </div>
        </div>
      );
      continue;
    }

    if (/^\d+\.\s/.test(line.trim())) {
      const match = line.trim().match(/^(\d+)\.\s(.*)/);
      if (match) {
        renderedElements.push(
          <div key={`oli-${i}`} className="flex items-start gap-2 my-1.5 text-slate-700 dark:text-slate-300">
            <span className="w-5 h-5 rounded-full bg-cobalt-100 dark:bg-cobalt-950 text-cobalt-700 dark:text-cobalt-300 font-bold text-[11px] flex items-center justify-center shrink-0 mt-0.5">
              {match[1]}
            </span>
            <div className="flex-1 leading-relaxed">
              {renderInlineText(match[2], citations, openEvidence)}
            </div>
          </div>
        );
        continue;
      }
    }

    // Handle Callouts / Important notes
    if (line.startsWith('> ') || line.startsWith('*Lưu ý:') || line.startsWith('Lưu ý:')) {
      renderedElements.push(
        <div key={`callout-${i}`} className="my-2.5 p-3 rounded-xl bg-amber-50/60 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-900/40 flex items-start gap-2.5 text-xs text-amber-900 dark:text-amber-300 leading-relaxed">
          <Info className="w-4 h-4 shrink-0 text-amber-600 mt-0.5" />
          <div>{renderInlineText(line.replace(/^>\s*/, ''), citations, openEvidence)}</div>
        </div>
      );
      continue;
    }

    // Regular paragraph
    if (line.trim()) {
      renderedElements.push(
        <p key={`p-${i}`} className="my-1.5 leading-relaxed text-slate-800 dark:text-slate-200">
          {renderInlineText(line, citations, openEvidence)}
        </p>
      );
    }
  }

  // If table remains at the end
  if (inTable && tableBuffer.length > 0) {
    renderedElements.push(renderTable(tableBuffer, `table-end`));
  }

  return <div className="space-y-1 text-[14.5px]">{renderedElements}</div>;
};

// Helper to render Markdown Table
function renderTable(tableLines: string[], key: string) {
  const rows = tableLines.map(l => 
    l.split('|')
      .map(c => c.trim())
      .filter((_, idx, arr) => idx > 0 && idx < arr.length - 1)
  ).filter(row => row.length > 0 && !row.every(c => c.startsWith('---') || c.startsWith(':---') || c.startsWith('---:')));

  if (rows.length === 0) return null;

  const header = rows[0];
  const body = rows.slice(1);

  return (
    <div key={key} className="my-3 overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-2xs">
      <table className="w-full text-left text-xs">
        <thead className="bg-slate-50 dark:bg-slate-950 border-b border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300">
          <tr>
            {header.map((col, idx) => (
              <th key={idx} className="p-2.5 font-bold tracking-tight">{col}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
          {body.map((row, rIdx) => (
            <tr key={rIdx} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/40">
              {row.map((cell, cIdx) => (
                <td key={cIdx} className="p-2.5 text-slate-700 dark:text-slate-300 font-sans">{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// Helper to render inline formatting (bold, italic, code pills, citation chips `[1]`)
function renderInlineText(text: string, citations: Citation[], onOpenCitation: (c: Citation) => void): React.ReactNode {
  // Regex to match bold `**text**`, inline code `\`code\``, citation `[1]` or `[2]`
  const parts = text.split(/(\*\*.*?\*\*|`.*?`|\[\d+\]|\$\$.*?\$\$|\$.*?\$)/g);

  return parts.map((part, idx) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={idx} className="font-semibold text-slate-900 dark:text-slate-100">{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return (
        <code key={idx} className="px-1.5 py-0.5 mx-0.5 rounded-md bg-slate-100 dark:bg-slate-800 text-cobalt-600 dark:text-cobalt-400 font-mono text-[12px] font-medium border border-slate-200 dark:border-slate-700">
          {part.slice(1, -1)}
        </code>
      );
    }
    if (/^\[\d+\]$/.test(part)) {
      const citNum = parseInt(part.slice(1, -1), 10);
      const targetCitation = citations[citNum - 1] || citations[0];
      return (
        <button
          key={idx}
          onClick={() => targetCitation && onOpenCitation(targetCitation)}
          className="inline-flex items-center gap-0.5 px-1.5 py-0.5 mx-0.5 rounded bg-cobalt-50 hover:bg-cobalt-100 text-cobalt-700 dark:bg-cobalt-950 dark:hover:bg-cobalt-900 dark:text-cobalt-300 text-[11px] font-bold border border-cobalt-200 dark:border-cobalt-800 cursor-pointer transition-colors align-baseline"
          title={`Xem nguồn tham chiếu ${part}`}
        >
          <BookOpen className="w-2.5 h-2.5" />
          {part}
        </button>
      );
    }
    return part;
  });
}
