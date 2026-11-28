import React from 'react';
import type { StudioDialogNode } from '../../api/blueAiApi';
import { dialogReply, patchDialogReply } from './dialogTranslations';

const languageNames = new Map([
  ['en', 'Tiếng Anh'],
  ['zh-CN', 'Tiếng Trung giản thể'],
]);

export function DialogTranslationsEditor({ node, onChange, inputClass, labelClass }: {
  node: StudioDialogNode;
  onChange: (patch: Partial<StudioDialogNode>) => void;
  inputClass: string;
  labelClass: string;
}) {
  const [locale, setLocale] = React.useState('en');
  const locales = [...new Set([...languageNames.keys(), ...Object.keys(node.translations ?? {}).filter(code => code !== 'vi')])];
  const selected = locales.includes(locale) ? locale : 'en';
  const reply = dialogReply(node, selected);
  const language = languageNames.get(selected) ?? selected;
  return <details className="rounded-xl border border-slate-200 p-3 dark:border-slate-700">
    <summary className="cursor-pointer py-1.5 text-sm font-bold focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cobalt-500">Bản dịch câu trả lời</summary>
    <label className="mt-3 block"><span className={labelClass}>Ngôn ngữ bản dịch</span><select value={selected} onChange={event => setLocale(event.target.value)} className={inputClass}>{locales.map(code => <option key={code} value={code}>{languageNames.get(code) ?? code}</option>)}</select></label>
    <p className="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">Nội dung gốc dùng tiếng Việt. Giữ các biến như {'{{location}}'} trong bản dịch. Khi để trống và AI được bật, hệ thống có thể dịch câu trả lời bằng AI và tính token.</p>
    {!languageNames.has(selected) && <p className="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">Bản dịch này được lưu để mở rộng. Phiên bản hiện tại hỗ trợ trả lời bằng tiếng Việt, tiếng Anh và tiếng Trung giản thể.</p>}
    <label className="mt-3 block"><span className={labelClass}>Nội dung trả lời — {language}</span><textarea value={reply.responseText ?? ''} onChange={event => onChange(patchDialogReply(node, selected, { responseText: event.target.value }))} maxLength={4000} rows={3} className={`${inputClass} resize-y leading-6`} /></label>
    {node.requiredEntityName && <label className="mt-3 block"><span className={labelClass}>Câu hỏi bổ sung — {language}</span><textarea value={reply.missingEntityPrompt ?? ''} onChange={event => onChange(patchDialogReply(node, selected, { missingEntityPrompt: event.target.value }))} maxLength={1000} rows={2} className={`${inputClass} resize-y`} /></label>}
    {(node.nextStep ?? 'WAIT_FOR_REPLY') === 'WAIT_FOR_REPLY' && <label className="mt-3 block"><span className={labelClass}>Lời đáp khi chưa rõ — {language}</span><textarea value={reply.irrelevantResponseText ?? ''} onChange={event => onChange(patchDialogReply(node, selected, { irrelevantResponseText: event.target.value }))} maxLength={1000} rows={2} className={`${inputClass} resize-y`} /></label>}
  </details>;
}
