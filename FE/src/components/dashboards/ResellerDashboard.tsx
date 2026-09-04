import React from 'react';
import { PackageSearch, Store } from 'lucide-react';
import { RagMiniChat } from '../chat/RagMiniChat';
import { Card } from '../ui/Card';

export const ResellerDashboard: React.FC = () => (
  <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
    <div><h1 className="text-2xl font-bold flex items-center gap-2"><Store className="w-6 h-6 text-cobalt-600" />Cổng Đại lý Phân phối</h1><p className="text-sm text-slate-500 mt-1">Giá, tồn kho và công nợ cần connector ERP/OMS; giao diện không còn dùng bảng giá mẫu.</p></div>
    <div className="grid lg:grid-cols-[320px,1fr] gap-6">
      <Card className="p-5 min-h-[360px] flex flex-col items-center justify-center text-center">
        <PackageSearch className="w-12 h-12 text-slate-300 mb-3" />
        <h2 className="font-bold">Chưa có dữ liệu catalog</h2>
        <p className="text-sm text-slate-500 mt-2">Kết nối Product/Inventory API để hiển thị SKU, giá đại lý và tồn kho theo thời gian thực.</p>
      </Card>
      <RagMiniChat title="Trợ lý tri thức đối tác" placeholder="Hỏi điều kiện SKU, quy trình tích hợp, chính sách đại lý…" conversationTitle="Reseller RAG Assistant" />
    </div>
  </div>
);
