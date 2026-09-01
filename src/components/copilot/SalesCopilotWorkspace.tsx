import React, { useState } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { 
  Users, MessageSquare, Sparkles, Send, ShieldCheck, 
  HelpCircle, Copy, ArrowRight, CornerDownLeft, RefreshCw, 
  Smartphone, CreditCard, Activity, CheckCircle2, Clock, 
  AlertTriangle, ArrowUpRight, Scale, ChevronRight, Zap 
} from 'lucide-react';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { Card } from '../ui/Card';
import { HITLReviewDialog } from './HITLReviewDialog';
import { INITIAL_CITATIONS } from '../../data/mockData';

export const SalesCopilotWorkspace: React.FC = () => {
  const { 
    customerQueue, 
    activeQueueItem, 
    setActiveQueueItem, 
    salesComposerText, 
    setSalesComposerText, 
    insertToSalesComposer, 
    openEvidence,
    openHITLModal,
    showToast
  } = useAppStore();

  const [activeCopilotTab, setActiveCopilotTab] = useState<'suggestions' | 'customer360' | 'compare'>('suggestions');
  const [messages, setMessages] = useState([
    {
      id: 'c-1',
      sender: 'customer',
      text: 'Chào shop, mình vừa hạ cánh sân bay Narita nhưng bật mạng chưa thấy có sóng 5G, shop hỗ trợ gấp với!',
      time: '14:32'
    }
  ]);

  const handleSendCustomerMessage = () => {
    if (!salesComposerText.trim()) return;
    setMessages(prev => [
      ...prev,
      {
        id: `c-${Date.now()}`,
        sender: 'agent',
        text: salesComposerText,
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      }
    ]);
    setSalesComposerText('');
    showToast('Đã gửi phản hồi cho khách hàng', 'success');
  };

  const activeCustomer = activeQueueItem?.customerData;

  const sampleCopilotResponse = `Dạ em chào anh Nam, Bluecom đã kiểm tra thấy mã eSIM ${activeCustomer?.activeOrder?.orderId || '#ORD-JP-98214'} của anh đã kích hoạt thành công trên hệ thống. 

Để lên sóng mạng SoftBank tại Narita, anh thao tác giúp em 2 bước nhanh sau nhé:
1. Vào Cài đặt → Di động → chọn eSIM Bluecom → Bật **Chuyển vùng dữ liệu (Data Roaming)**.
2. Khởi động lại máy khoảng 30 giây để thiết bị bắt băng tần APN tự động \`vmobile.jp\`.

Em đang giữ kết nối hỗ trợ anh ngay ạ!`;

  return (
    <div className="flex-1 flex h-full overflow-hidden bg-canvas-light dark:bg-canvas-dark relative">
      <HITLReviewDialog />

      {/* CỘT 1: Hàng chờ xử lý (Queue List) */}
      <div className="hidden xl:flex w-72 2xl:w-80 border-r border-slate-200/80 dark:border-slate-800 bg-white/60 dark:bg-[#08101E]/60 backdrop-blur-md flex-col shrink-0">
        {/* Header */}
        <div className="p-4 border-b border-slate-200/80 dark:border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Users className="w-4 h-4 text-cobalt-600 dark:text-cyan-400" />
            <span className="text-xs sm:text-sm font-extrabold text-slate-800 dark:text-slate-200 tracking-tight">
              Hàng chờ xử lý
            </span>
          </div>
          <span className="text-[11px] px-2.5 py-0.5 rounded-full bg-cobalt-50 dark:bg-cyan-950/70 text-cobalt-600 dark:text-cyan-300 font-bold border border-cobalt-200/60 dark:border-cyan-800/60">
            {customerQueue.length} khách
          </span>
        </div>

        {/* Queue Items */}
        <div className="flex-1 overflow-y-auto p-2.5 space-y-2">
          {customerQueue.map((item) => {
            const isSelected = activeQueueItem?.id === item.id;
            return (
              <button
                key={item.id}
                onClick={() => setActiveQueueItem(item)}
                className={`w-full text-left p-3.5 rounded-2xl border transition-all ${
                  isSelected
                    ? 'bg-cobalt-50/90 dark:bg-cobalt-950/60 border-cobalt-400 dark:border-cyan-500 shadow-sm'
                    : 'bg-white dark:bg-[#0B152A]/70 border-slate-200/80 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700'
                }`}
              >
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center gap-2 min-w-0">
                    <div className="w-6 h-6 rounded-lg bg-gradient-to-tr from-cobalt-600 to-cyan-500 text-white font-extrabold text-[10px] flex items-center justify-center shrink-0 shadow-2xs">
                      {item.customerName ? item.customerName.charAt(0) : 'K'}
                    </div>
                    <span className="text-xs font-extrabold text-slate-900 dark:text-slate-100 truncate">
                      {item.customerName}
                    </span>
                  </div>
                  <Badge 
                    variant={item.slaStatus === 'warning' ? 'danger' : 'subtle'} 
                    size="sm"
                  >
                    {item.waitTime}
                  </Badge>
                </div>
                <p className="text-[11.5px] text-slate-500 dark:text-slate-400 line-clamp-2 leading-relaxed">
                  {item.lastMessage}
                </p>
                <div className="flex items-center justify-between mt-2 pt-2 border-t border-slate-100 dark:border-slate-800/60 text-[10.5px] text-slate-400">
                  <span>Kênh: <strong>{item.channel}</strong></span>
                  <span className="font-bold text-cobalt-600 dark:text-cyan-400">{item.customerData.tier}</span>
                </div>
              </button>
            );
          })}
        </div>
      </div>

      {/* CỘT 2: Hội thoại khách hàng (Live Conversation Thread) */}
      <div className="flex-1 flex flex-col min-w-0 border-r border-slate-200/80 dark:border-slate-800 bg-white dark:bg-[#070D18]">
        {/* Customer Header */}
        <div className="h-14 border-b border-slate-200/80 dark:border-slate-800 px-5 flex items-center justify-between shrink-0 bg-white/70 dark:bg-[#070D18]/70 backdrop-blur-md">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-cobalt-600 to-cyan-500 text-white font-extrabold text-xs flex items-center justify-center shrink-0 shadow-sm">
              {activeCustomer?.name ? activeCustomer.name.charAt(0) : 'K'}
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-extrabold text-slate-900 dark:text-slate-100 truncate">{activeCustomer?.name}</h3>
                <span className="hidden sm:inline-flex text-[10.5px] px-2 py-0.5 rounded-full bg-cobalt-100 dark:bg-cobalt-950 text-cobalt-700 dark:text-cyan-300 font-bold border border-cobalt-200 dark:border-cyan-800">
                  {activeCustomer?.tier}
                </span>
              </div>
              <p className="text-[11px] text-slate-400 font-mono">{activeCustomer?.phone} • {activeCustomer?.activeOrder?.country}</p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button 
              size="sm" 
              variant="outline" 
              className="text-xs font-bold rounded-xl border-amber-300 dark:border-amber-800 text-amber-700 dark:text-amber-400 bg-amber-50/50 dark:bg-amber-950/30 hover:bg-amber-100"
              onClick={() => {
                openHITLModal({
                  title: 'Hoàn tiền gói eSIM sự cố sóng',
                  customerName: activeCustomer?.name || 'Khách hàng',
                  orderId: activeCustomer?.activeOrder?.orderId || 'ORD-JP-98214',
                  amount: '350.000 VNĐ',
                  policyRef: 'DOC-POL-042 (Mục 3.2)',
                  reason: 'Sự cố lỗi mạng viễn thông tại trạm Narita không kết nối được sau 2 giờ'
                });
              }}
            >
              <AlertTriangle className="w-3.5 h-3.5 mr-1 text-amber-500" />
              <span className="hidden sm:inline">Yêu cầu hoàn tiền (HITL)</span>
              <span className="sm:hidden">HITL</span>
            </Button>
          </div>
        </div>

        {/* Message Thread */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {messages.map((msg) => {
            const isAgent = msg.sender === 'agent';
            return (
              <div key={msg.id} className={`flex ${isAgent ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[78%] p-4 rounded-3xl text-[14px] leading-relaxed shadow-2xs ${
                  isAgent 
                    ? 'bg-gradient-to-r from-cobalt-600 to-cobalt-500 text-white rounded-tr-xs' 
                    : 'bg-slate-100 dark:bg-[#111C33] text-slate-800 dark:text-slate-100 rounded-tl-xs border border-slate-200/60 dark:border-slate-800'
                }`}>
                  <div className="whitespace-pre-line">{msg.text}</div>
                  <div className={`text-[10.5px] mt-1 text-right ${isAgent ? 'text-white/70' : 'text-slate-400'}`}>
                    {msg.time}
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Agent Composer */}
        <div className="p-4 border-t border-slate-200/80 dark:border-slate-800 bg-slate-50/70 dark:bg-[#070D18]/70 backdrop-blur-md space-y-2">
          <div className="relative rounded-2xl bg-white dark:bg-[#0E1A33] border border-slate-300 dark:border-slate-700/80 p-3 focus-within:border-cobalt-500 shadow-sm">
            <textarea
              value={salesComposerText}
              onChange={(e) => setSalesComposerText(e.target.value)}
              placeholder="Nhập nội dung phản hồi hoặc chèn từ gợi ý BlueAI Copilot..."
              rows={3}
              className="w-full bg-transparent resize-none border-0 text-sm text-slate-800 dark:text-slate-100 placeholder:text-slate-400 focus:outline-none leading-relaxed"
            />
            <div className="flex items-center justify-between pt-2 border-t border-slate-100 dark:border-slate-800">
              <span className="text-[11px] text-slate-400">Kênh gửi: <strong>{activeQueueItem?.channel}</strong></span>
              <Button size="sm" variant="primary" className="rounded-xl px-4 text-xs font-bold" onClick={handleSendCustomerMessage} disabled={!salesComposerText.trim()}>
                <Send className="w-3.5 h-3.5 mr-1" />
                Gửi phản hồi
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* CỘT 3: BlueAI Copilot & Customer 360 (Right Panel 380px) */}
      <div className="hidden lg:flex w-80 2xl:w-96 bg-white/80 dark:bg-[#0A1324]/80 backdrop-blur-md flex-col shrink-0">
        {/* Tab Navigation */}
        <div className="p-2.5 border-b border-slate-200/80 dark:border-slate-800 flex gap-1.5 bg-slate-50/50 dark:bg-slate-900/50">
          <button
            onClick={() => setActiveCopilotTab('suggestions')}
            className={`flex-1 py-2 text-xs font-bold rounded-xl flex items-center justify-center gap-1.5 transition-all ${
              activeCopilotTab === 'suggestions'
                ? 'bg-cobalt-600 text-white shadow-sm'
                : 'text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            <Sparkles className="w-3.5 h-3.5" />
            Gợi ý BlueAI
          </button>
          <button
            onClick={() => setActiveCopilotTab('customer360')}
            className={`flex-1 py-2 text-xs font-bold rounded-xl flex items-center justify-center gap-1.5 transition-all ${
              activeCopilotTab === 'customer360'
                ? 'bg-cobalt-600 text-white shadow-sm'
                : 'text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            Customer 360
          </button>
          <button
            onClick={() => setActiveCopilotTab('compare')}
            className={`flex-1 py-2 text-xs font-bold rounded-xl flex items-center justify-center gap-1.5 transition-all ${
              activeCopilotTab === 'compare'
                ? 'bg-cobalt-600 text-white shadow-sm'
                : 'text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            <Scale className="w-3.5 h-3.5" />
            So sánh
          </button>
        </div>

        {/* Tab Content */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {activeCopilotTab === 'suggestions' && (
            <div className="space-y-4 animate-in fade-in">
              {/* Context Banner */}
              <div className="p-3.5 rounded-2xl bg-cobalt-50/80 dark:bg-cobalt-950/60 border border-cobalt-200/80 dark:border-cyan-900/60 text-xs">
                <div className="flex items-center gap-1.5 font-bold text-cobalt-800 dark:text-cyan-300 mb-1">
                  <Activity className="w-3.5 h-3.5" />
                  Ngữ cảnh: Sự cố sóng sân bay Narita (Nhật Bản)
                </div>
                <p className="text-slate-600 dark:text-slate-400 text-[11.5px] leading-relaxed">
                  Tự động đối chiếu quy chuẩn kỹ thuật mạng SoftBank DOC-TECH-108 và thông tin đơn hàng ORD-JP-98214.
                </p>
              </div>

              {/* Recommended Response Card */}
              <div className="p-4 rounded-2xl bg-white dark:bg-[#0E1B33] border border-slate-200 dark:border-slate-800 shadow-subtle space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-extrabold text-slate-900 dark:text-slate-100">
                    Câu trả lời đề xuất (Grounded)
                  </span>
                  <span className="text-[11px] text-emerald-600 dark:text-emerald-400 font-bold flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" /> Đã xác thực
                  </span>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-950/80 border border-slate-100 dark:border-slate-800 text-xs text-slate-700 dark:text-slate-300 leading-relaxed font-sans whitespace-pre-line">
                  {sampleCopilotResponse}
                </div>

                {/* Actions */}
                <Button
                  size="sm"
                  variant="primary"
                  className="w-full text-xs font-bold rounded-xl shadow-sm"
                  onClick={() => insertToSalesComposer(sampleCopilotResponse)}
                >
                  <CornerDownLeft className="w-3.5 h-3.5 mr-1" />
                  Chèn vào ô trả lời
                </Button>

                <div className="flex items-center justify-between pt-2 border-t border-slate-100 dark:border-slate-800 text-[11px] text-slate-400">
                  <button
                    onClick={() => openEvidence(INITIAL_CITATIONS['cit-2'])}
                    className="text-cobalt-600 dark:text-cyan-400 font-semibold hover:underline flex items-center gap-1"
                  >
                    Xem căn cứ [DOC-TECH-108]
                  </button>
                  <span>Chỉnh sửa trước khi gửi</span>
                </div>
              </div>

              {/* Quick Objection Prompts */}
              <div className="space-y-2">
                <span className="text-[10.5px] font-extrabold text-slate-400 uppercase tracking-wider">
                  Mẫu xử lý thắc mắc nhanh:
                </span>
                <div className="space-y-1.5">
                  {[
                    'Hướng dẫn bật Data Roaming trên iOS & Android',
                    'Giải thích chính sách hoàn tiền 100% nếu lỗi trạm',
                    'Mẫu xin lỗi và gửi mã voucher bồi thường 50k'
                  ].map((tip, idx) => (
                    <button
                      key={idx}
                      onClick={() => insertToSalesComposer(tip)}
                      className="w-full text-left p-3 rounded-xl border border-slate-200 dark:border-slate-800 hover:border-cobalt-400 dark:hover:border-cyan-600 bg-slate-50/60 dark:bg-[#0B152A] text-xs text-slate-700 dark:text-slate-300 transition-colors flex items-center justify-between"
                    >
                      <span>{tip}</span>
                      <ChevronRight className="w-3.5 h-3.5 text-slate-400" />
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {activeCopilotTab === 'customer360' && (
            <div className="space-y-3.5 animate-in fade-in text-xs">
              {/* Order Status Card */}
              <div className="p-4 rounded-2xl bg-gradient-to-br from-slate-50 to-white dark:from-[#0E1B33] dark:to-[#081224] border border-slate-200 dark:border-slate-800 space-y-2.5 shadow-subtle">
                <div className="flex justify-between items-center">
                  <span className="font-bold text-slate-800 dark:text-slate-200">Gói cước đang sử dụng</span>
                  <Badge variant="confirmed" size="sm">Đang kích hoạt</Badge>
                </div>
                <h4 className="text-sm font-extrabold text-cobalt-600 dark:text-cyan-400">
                  {activeCustomer?.activeOrder?.plan}
                </h4>
                <div className="grid grid-cols-2 gap-2 pt-1">
                  <div>
                    <span className="text-slate-400 text-[11px]">Dung lượng đã dùng:</span>
                    <p className="font-bold text-slate-900 dark:text-slate-100">{activeCustomer?.activeOrder?.dataUsed} / {activeCustomer?.activeOrder?.totalData}</p>
                  </div>
                  <div>
                    <span className="text-slate-400 text-[11px]">Hạn sử dụng:</span>
                    <p className="font-bold text-slate-900 dark:text-slate-100">{activeCustomer?.activeOrder?.expiry}</p>
                  </div>
                </div>
                <div className="pt-1 text-[11px] font-mono text-slate-400 truncate">
                  ICCID: {activeCustomer?.activeOrder?.iccid}
                </div>
              </div>

              {/* Customer Profile Summary */}
              <div className="p-4 rounded-2xl bg-white dark:bg-[#0B152A] border border-slate-200 dark:border-slate-800 space-y-2 shadow-subtle">
                <span className="font-bold text-slate-800 dark:text-slate-200">Lịch sử tương tác</span>
                <div className="flex justify-between text-slate-600 dark:text-slate-400">
                  <span>Số đơn hàng đã mua:</span>
                  <span className="font-bold text-slate-900 dark:text-slate-100">{activeCustomer?.pastOrdersCount} đơn</span>
                </div>
                <div className="flex justify-between text-slate-600 dark:text-slate-400">
                  <span>Mức độ hài lòng (CSAT):</span>
                  <span className="font-bold text-emerald-600">4.9 / 5.0 ⭐</span>
                </div>
                <div className="flex justify-between text-slate-600 dark:text-slate-400">
                  <span>Trạng thái tâm lý:</span>
                  <span className="font-semibold text-amber-600">Hơi lo lắng (Cần phản hồi gấp)</span>
                </div>
              </div>
            </div>
          )}

          {activeCopilotTab === 'compare' && (
            <div className="space-y-3 animate-in fade-in text-xs">
              <div className="p-3 rounded-xl bg-cobalt-50 dark:bg-cobalt-950/60 border border-cobalt-200 text-cobalt-800 dark:text-cyan-300 font-bold">
                Bảng so sánh 2 gói Nhật Bản phổ biến:
              </div>
              <div className="border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden shadow-subtle">
                <table className="w-full text-left">
                  <thead className="bg-slate-100 dark:bg-slate-900 text-slate-700 dark:text-slate-300 text-[11px]">
                    <tr>
                      <th className="p-2.5">Tiêu chí</th>
                      <th className="p-2.5 text-cobalt-600 dark:text-cyan-400">SoftBank 5G</th>
                      <th className="p-2.5 text-indigo-600 dark:text-indigo-400">Docomo Pro</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 dark:divide-slate-800 text-[11px]">
                    <tr>
                      <td className="p-2.5 font-medium text-slate-500">Tốc độ nội đô</td>
                      <td className="p-2.5 font-bold text-emerald-600">Rất nhanh (450 Mbps)</td>
                      <td className="p-2.5 text-slate-700 dark:text-slate-300">Ổn định (320 Mbps)</td>
                    </tr>
                    <tr>
                      <td className="p-2.5 font-medium text-slate-500">Vùng đồi núi/Phú Sĩ</td>
                      <td className="p-2.5 text-slate-700 dark:text-slate-300">Khá (82%)</td>
                      <td className="p-2.5 font-bold text-emerald-600">Tốt nhất (99%)</td>
                    </tr>
                    <tr>
                      <td className="p-2.5 font-medium text-slate-500">Giá niêm yết</td>
                      <td className="p-2.5 font-bold text-slate-900 dark:text-slate-100">220.000đ</td>
                      <td className="p-2.5 font-bold text-slate-900 dark:text-slate-100">260.000đ</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
