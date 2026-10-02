import React from 'react';

export function DialogNodeNameField({ name, otherNames, onRename, className }: { name: string; otherNames: string[]; onRename: (name: string) => void; className: string }) {
  const [draft, setDraft] = React.useState(name);
  const [error, setError] = React.useState('');
  React.useEffect(() => { setDraft(name); setError(''); }, [name]);
  const commit = () => {
    const value = draft.trim();
    if (!value) { setError('Tên bước không được để trống.'); return; }
    if (otherNames.some(other => other.trim().toLocaleLowerCase() === value.toLocaleLowerCase())) { setError('Tên này đã được dùng. Chọn tên khác để giữ liên kết rõ ràng.'); return; }
    setError('');
    if (value !== name) onRename(value);
  };
  return <label><span className="mb-1.5 block text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">Tên bước</span><input value={draft} onChange={event => { setDraft(event.target.value); setError(''); }} onBlur={commit} onKeyDown={event => { if (event.key === 'Enter') { commit(); event.preventDefault(); } if (event.key === 'Escape') { setDraft(name); setError(''); } }} maxLength={120} aria-invalid={Boolean(error)} className={className} /><span className={`mt-1 block text-[11px] ${error ? 'text-rose-600' : 'text-slate-500'}`} role={error ? 'alert' : undefined}>{error || 'Đổi tên khi rời ô; các liên kết được cập nhật cùng lúc.'}</span></label>;
}
