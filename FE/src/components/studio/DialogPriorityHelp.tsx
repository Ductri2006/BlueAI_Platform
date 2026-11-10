import React from 'react';

export function DialogPriorityHelp() {
  return <details className="rounded-xl border border-cobalt-200 bg-cobalt-50/60 p-4 text-sm dark:border-cobalt-900 dark:bg-cobalt-950/30">
    <summary className="cursor-pointer font-bold text-cobalt-700 dark:text-cobalt-300">Ưu tiên xét hoạt động thế nào?</summary>
    <div className="mt-3 space-y-2 leading-6 text-slate-600 dark:text-slate-300">
      <p>Trong nhóm nhánh đang được xét, số nhỏ được xét trước nếu điều kiện khớp. Ví dụ nhánh 10 dành riêng cho eSIM Nhật Bản, nhánh 20 cho báo giá chung. Khi cùng khớp, nhánh 10 được chọn trước.</p>
      <p>Ưu tiên còn phụ thuộc bước cha–con và trạng thái hội thoại. Tự tiếp tục xét bước con trước, rồi nhánh cùng cấp có số lớn hơn. Chuyển thẳng đến bước khác đi đến đích đã chọn.</p>
      <p>Mỗi bước cần một số nguyên riêng. Nên dùng 10, 20, 30 để dễ chèn thêm bước. Thử BlueAI trước khi phát hành.</p>
      <a href="/guides/blueai-custom.html" target="_blank" rel="noopener noreferrer" className="inline-block font-semibold text-cobalt-700 underline dark:text-cobalt-300">Mở hướng dẫn BlueAI Custom và ví dụ eSIM</a>
    </div>
  </details>;
}
