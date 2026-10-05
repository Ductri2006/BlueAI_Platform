import React from 'react';

export type StudioCreationTemplate = 'STARTER' | 'SALES_DEMO';

export function StudioTemplateSelector({ value, onChange }: {
  value: StudioCreationTemplate;
  onChange: (template: StudioCreationTemplate) => void;
}) {
  return <label className="mt-4 block text-left">
    <span className="mb-1.5 block text-xs font-bold text-slate-600 dark:text-slate-300">Mẫu khởi tạo</span>
    <select value={value} onChange={event => onChange(event.target.value as StudioCreationTemplate)}
      className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 focus:border-cobalt-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
      <option value="STARTER">Cơ bản — chào hỏi và giờ hoạt động</option>
      <option value="SALES_DEMO">Demo sales — 6 kịch bản Dialog</option>
    </select>
    <span className="mt-2 block text-xs leading-5 text-slate-500">
      {value === 'SALES_DEMO'
        ? 'Dữ liệu minh họa: chào hỏi, chi nhánh, thu thập yêu cầu báo giá, xác nhận, sửa dữ kiện và dự phòng. Số lượng mẫu: 10, 20, 30, 50, 100. Tắt Core/LLM; không tạo đơn, không tự phát hành hoặc thay bot đang dùng.'
        : 'Tạo một bản nháp mới với intent, entity và luồng mẫu tiếng Việt. Không thay cấu hình đang dùng.'}
    </span>
  </label>;
}
