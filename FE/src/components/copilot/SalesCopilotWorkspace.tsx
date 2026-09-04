import React from 'react';
import { Inbox, Sparkles, Users } from 'lucide-react';
import { useAppStore } from '../../store/useAppStore';
import { RagMiniChat } from '../chat/RagMiniChat';
import { Card } from '../ui/Card';

export const SalesCopilotWorkspace: React.FC = () => {
  const { customerQueue } = useAppStore();

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 bg-canvas-light dark:bg-canvas-dark space-y-6">
      <div>
        <h1 className="text-2xl font-bold flex items-center gap-2"><Users className="w-6 h-6 text-cobalt-600" />Sales / CSKH Copilot</h1>
        <p className="text-sm text-slate-500 mt-1">Copilot truy vấn trực tiếp RAG API. Queue khách hàng chỉ hiển thị khi có connector CRM/omnichannel cung cấp dữ liệu.</p>
      </div>
      <div className="grid lg:grid-cols-[320px,1fr] gap-6">
        <Card className="p-5 min-h-[360px]">
          <h2 className="font-bold flex items-center gap-2"><Inbox className="w-4 h-4" />Hàng chờ ({customerQueue.length})</h2>
          {customerQueue.length === 0 ? <div className="h-64 flex flex-col items-center justify-center text-center text-slate-500"><Inbox className="w-10 h-10 mb-3 opacity-40" /><p className="text-sm">Chưa có dữ liệu ticket từ backend.</p><p className="text-xs mt-1">Không hiển thị khách hàng hoặc đơn hàng giả.</p></div> : null}
        </Card>
        <RagMiniChat
          title="Soạn câu trả lời có căn cứ"
          placeholder="Hỏi chính sách bán hàng, hoàn huỷ, xử lý sự cố…"
          conversationTitle="Sales Copilot"
        />
      </div>
      <div className="text-xs text-slate-500 flex items-center gap-2"><Sparkles className="w-4 h-4" />Dữ liệu giao dịch động phải đến từ CRM/OMS; RAG chỉ dùng cho tri thức và chính sách.</div>
    </div>
  );
};
