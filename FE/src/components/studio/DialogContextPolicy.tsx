import type { StudioDialogNode } from '../../api/blueAiApi';

export function DialogContextPolicy({ value = 'NONE', onChange, className }: { value?: StudioDialogNode['contextReusePolicy']; onChange: (value: NonNullable<StudioDialogNode['contextReusePolicy']>) => void; className: string }) {
  return <label className="mt-4 block">
    <span className="mb-1.5 block text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">Dữ kiện từ context</span>
    <select value={value} onChange={event => onChange(event.target.value as NonNullable<StudioDialogNode['contextReusePolicy']>)} className={className}>
      <option value="NONE">Hỏi lại khi thiếu dữ kiện (mặc định)</option>
      <option value="CONFIRMED_ONLY">Đề xuất từ ghi nhớ/phiên liên kết, luôn xác nhận</option>
    </select>
    <span className="mt-2 block text-xs leading-5 text-slate-500">Chỉ đề xuất giá trị chính từ ghi nhớ đã xác nhận hoặc phiên bạn chủ động liên kết. Người dùng cần trả lời “đúng” / “xác nhận” trước khi dùng. Dữ kiện mới được ưu tiên; từ chối hoặc đổi chủ đề sẽ bỏ gợi ý.</span>
  </label>;
}

export function DialogValidationNotice({ errors, warnings }: { errors: string[]; warnings: string[] }) {
  if (!errors.length && !warnings.length) return null;
  return <div className="space-y-2 text-xs leading-5">
    {errors.length > 0 && <details open className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200"><summary className="cursor-pointer font-bold">Cần sửa trước khi lưu: {errors.length} lỗi</summary><ul className="mt-2 list-disc space-y-1 pl-5">{errors.map(error => <li key={error}>{error}</li>)}</ul></details>}
    {warnings.length > 0 && <details className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"><summary className="cursor-pointer font-bold">Nên kiểm tra: {warnings.length} cảnh báo (vẫn có thể lưu)</summary><ul className="mt-2 list-disc space-y-1 pl-5">{warnings.map(warning => <li key={warning}>{warning}</li>)}</ul></details>}
  </div>;
}
