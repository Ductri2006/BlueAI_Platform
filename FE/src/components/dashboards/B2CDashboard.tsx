import React from 'react';
import { QrCode, Smartphone } from 'lucide-react';
import { RagMiniChat } from '../chat/RagMiniChat';
import { Card } from '../ui/Card';

export const B2CDashboard: React.FC = () => (
  <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
    <div><h1 className="text-2xl font-bold flex items-center gap-2"><Smartphone className="w-6 h-6 text-cobalt-600" />Trung tâm eSIM & Hỗ trợ</h1><p className="text-sm text-slate-500 mt-1">Thông tin đơn hàng, QR và dung lượng chỉ xuất hiện khi backend có dữ liệu thuê bao thật.</p></div>
    <div className="grid lg:grid-cols-[320px,1fr] gap-6">
      <Card className="p-5 min-h-[360px] flex flex-col items-center justify-center text-center">
        <QrCode className="w-14 h-14 text-slate-300 mb-3" />
        <h2 className="font-bold">Chưa có eSIM đang hoạt động</h2>
        <p className="text-sm text-slate-500 mt-2">Kết nối Order/Subscription API để tải QR, trạng thái kích hoạt và dung lượng còn lại.</p>
      </Card>
      <RagMiniChat title="Hỏi đáp BlueAI" placeholder="Hỏi cách cài đặt eSIM, roaming hoặc xử lý lỗi…" conversationTitle="B2C RAG Support" />
    </div>
  </div>
);
