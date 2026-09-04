import React, { lazy, Suspense, useRef, useEffect, useState } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { MessageItem } from './MessageItem';
import { Composer } from './Composer';
import { 
  Sparkles, Shield, Wifi, Network, Download, 
  Trash2, ArrowRight, Layers, FileText, Pin, 
  DollarSign, Scale, RefreshCw, Smartphone, Zap, 
  Terminal, Bot, CheckCircle2 
} from 'lucide-react';
import { Button } from '../ui/Button';

const IntelligenceOrb = lazy(() => import('../3d/IntelligenceOrb').then((module) => ({ default: module.IntelligenceOrb })));
const KnowledgeGraph3DModal = lazy(() => import('./KnowledgeGraph3DModal').then((module) => ({ default: module.KnowledgeGraph3DModal })));

export const ChatWorkspace: React.FC = () => {
  const { 
    sessions, 
    activeSessionId, 
    streamStatus, 
    currentStreamActivity, 
    sendMessage,
    deleteSession,
    togglePinSession,
    showToast
  } = useAppStore();

  const [is3DGraphModalOpen, setIs3DGraphModalOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const currentSession = sessions.find(s => s.id === activeSessionId) || sessions[0];
  const messages = currentSession?.messages || [];

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamStatus]);

  const handleExportMarkdown = () => {
    if (!currentSession || messages.length === 0) {
      showToast('Chưa có tin nhắn để xuất tệp', 'warning');
      return;
    }
    const mdContent = `# ${currentSession.title}\n\n` +
      messages.map(m => `### ${m.sender === 'user' ? 'Người dùng' : 'BlueAI'} (${m.timestamp})\n\n${m.content}\n\n---\n`).join('\n');
    
    const blob = new Blob([mdContent], { type: 'text/markdown;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${currentSession.title.replace(/[^a-zA-Z0-9_-]/g, '_')}.md`;
    link.click();
    showToast('Đã xuất hội thoại dạng Markdown', 'success');
  };

  const quickPrompts = [
    {
      title: 'Hoàn tiền eSIM chưa kích hoạt',
      tag: 'Chính sách CSKH',
      subtitle: 'Quy định hủy đơn & điều kiện ICCID Unused theo quy chế DOC-POL-042',
      prompt: 'Khách hàng muốn hoàn tiền gói eSIM Nhật Bản 7 ngày vì chuyến đi bị hoãn, mã QR chưa quét thì chính sách xử lý như thế nào?',
      icon: DollarSign,
      gradient: 'from-emerald-500/10 to-transparent border-emerald-300 dark:border-emerald-800/80',
      iconBox: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/80 dark:text-emerald-400 border-emerald-200 dark:border-emerald-800'
    },
    {
      title: 'So sánh gói mạng Nhật Bản',
      tag: 'Kỹ thuật Telco',
      subtitle: 'Docomo phủ sóng 99% đồi núi vs SoftBank 5G tốc độ 450Mbps nội đô',
      prompt: 'So sánh ưu nhược điểm giữa gói mạng SoftBank 5G và Docomo Nhật Bản?',
      icon: Scale,
      gradient: 'from-cobalt-500/10 to-transparent border-cobalt-300 dark:border-cobalt-800/80',
      iconBox: 'bg-cobalt-50 text-cobalt-600 dark:bg-cobalt-950/80 dark:text-cobalt-400 border-cobalt-200 dark:border-cobalt-800'
    },
    {
      title: 'Cấu hình APN vmobile.jp',
      tag: 'Cài đặt mạng',
      subtitle: 'Xử lý sự cố không lên sóng 5G khi vừa hạ cánh tại sân bay quốc tế',
      prompt: 'Hướng dẫn chi tiết cách cấu hình APN và xử lý lỗi không lên sóng khi hạ cánh?',
      icon: Wifi,
      gradient: 'from-sky-500/10 to-transparent border-sky-300 dark:border-sky-800/80',
      iconBox: 'bg-sky-50 text-sky-600 dark:bg-sky-950/80 dark:text-sky-400 border-sky-200 dark:border-sky-800'
    },
    {
      title: 'Hạn mức chiết khấu Đại lý',
      tag: 'Kênh Phân phối',
      subtitle: 'Chiết khấu cố định 24% hạng Kim Cương, thưởng quý và credit 50tr',
      prompt: 'Chính sách chiết khấu và thưởng doanh số cho Đại lý Cấp 1 là bao nhiêu?',
      icon: Smartphone,
      gradient: 'from-amber-500/10 to-transparent border-amber-300 dark:border-amber-800/80',
      iconBox: 'bg-amber-50 text-amber-600 dark:bg-amber-950/80 dark:text-amber-400 border-amber-200 dark:border-amber-800'
    }
  ];

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-canvas-light dark:bg-canvas-dark bg-ambient-grid relative">
      {is3DGraphModalOpen && (
        <Suspense fallback={null}>
          <KnowledgeGraph3DModal
            isOpen
            onClose={() => setIs3DGraphModalOpen(false)}
          />
        </Suspense>
      )}

      {/* Top Workspace Bar */}
      <header className="h-12 border-b border-slate-200/80 dark:border-slate-800/80 bg-white/75 dark:bg-[#070E1C]/75 backdrop-blur-md px-4 sm:px-6 flex items-center justify-between shrink-0 z-10">
        <div className="flex items-center gap-2.5 min-w-0">
          <button
            onClick={() => currentSession && togglePinSession(currentSession.id)}
            className={`p-1.5 rounded-lg transition-colors ${currentSession?.isPinned ? 'text-cobalt-600 dark:text-cobalt-400 bg-cobalt-50 dark:bg-cobalt-950/80' : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-200'}`}
            title={currentSession?.isPinned ? 'Bỏ ghim cuộc trò chuyện' : 'Ghim cuộc trò chuyện này'}
            aria-label={currentSession?.isPinned ? 'Bỏ ghim cuộc trò chuyện' : 'Ghim cuộc trò chuyện này'}
          >
            <Pin className="w-3.5 h-3.5" />
          </button>
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse" />
            <h2 className="text-xs sm:text-sm font-extrabold text-slate-800 dark:text-slate-100 truncate tracking-tight">
              {currentSession?.title || 'Hỏi BlueAI'}
            </h2>
          </div>
        </div>

        {/* Action Tools */}
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => setIs3DGraphModalOpen(true)}
            className="text-xs h-8 px-3 gap-1.5 font-bold text-cobalt-600 dark:text-cyan-400 border-cobalt-200 dark:border-cyan-900/60 bg-cobalt-50/60 dark:bg-cyan-950/30 hover:bg-cobalt-100 rounded-xl"
          >
            <Network className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Mạng 3D Tri thức</span>
          </Button>

          <button
            onClick={handleExportMarkdown}
            className="p-1.5 rounded-xl border border-slate-200 dark:border-slate-800 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-300 transition-colors shadow-2xs"
            title="Xuất hội thoại dạng Markdown (.md)"
            aria-label="Xuất hội thoại dạng Markdown"
          >
            <Download className="w-3.5 h-3.5" />
          </button>

          {currentSession && messages.length > 0 && (
            <button
              onClick={() => deleteSession(currentSession.id)}
              className="p-1.5 rounded-xl border border-slate-200 dark:border-slate-800 hover:bg-rose-50 dark:hover:bg-rose-950 hover:text-rose-600 text-slate-400 transition-colors shadow-2xs"
              title="Xóa cuộc trò chuyện này"
              aria-label="Xóa cuộc trò chuyện này"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </header>

      {/* Main Conversation Canvas */}
      <div className="flex-1 overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="max-w-4xl mx-auto min-h-full flex flex-col justify-between">
          {messages.length === 0 ? (
            /* Futuristic 3D Holographic AI Stage */
            <div className="my-auto py-5 sm:py-6 space-y-6 sm:space-y-7 animate-in fade-in duration-500">
              <div className="text-center space-y-4">
                {/* 3D Holographic Quantum Core */}
                <div className="mx-auto relative w-36 h-36 flex items-center justify-center">
                  <Suspense fallback={<div className="h-28 w-28 rounded-full bg-cobalt-500/20 shadow-cobalt-glow animate-pulse" aria-hidden="true" />}>
                    <IntelligenceOrb size={135} status={streamStatus} />
                  </Suspense>
                </div>

                {/* Status Pill */}
                <div className="inline-flex items-center gap-2 px-3.5 py-1 rounded-full bg-white/80 dark:bg-[#0C1527]/90 border border-slate-200/80 dark:border-slate-700/80 text-cobalt-700 dark:text-cyan-300 text-xs font-bold shadow-subtle backdrop-blur-md">
                  <Sparkles className="w-3.5 h-3.5 text-cyan-500 animate-pulse" />
                  <span>BlueAI Operational Intelligence v5.2</span>
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                </div>

                <h1 className="text-[1.65rem] sm:text-3xl font-extrabold text-slate-900 dark:text-slate-100 tracking-[-0.025em] leading-tight text-balance">
                  Chào bạn, tôi có thể hỗ trợ gì cho ca làm việc hôm nay?
                </h1>
                <p className="text-xs sm:text-sm text-slate-500 dark:text-slate-400 max-w-xl mx-auto leading-relaxed">
                  BlueAI tự động kết nối và đối chiếu với kho quy chế, chính sách hoàn hủy, bảng giá cước viễn thông và hệ thống OMS Core theo thời gian thực.
                </p>
              </div>

              {/* Bento Prompt Cards with 3D Depth */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 max-w-3xl mx-auto pt-2">
                {quickPrompts.map((item, idx) => {
                  const Icon = item.icon;
                  return (
                    <button
                      key={idx}
                      onClick={() => sendMessage(item.prompt)}
                      className="p-[18px] rounded-card text-left bg-gradient-to-br from-white to-slate-50 dark:from-[#0E1A33] dark:to-[#091224] border border-slate-200/80 dark:border-slate-800/90 hover:border-cobalt-500/80 dark:hover:border-cyan-500/80 shadow-subtle hover:shadow-float hover:-translate-y-0.5 transition-all duration-200 group relative flex flex-col justify-between space-y-3.5 glow-blue-card"
                    >
                      <div className="flex items-start justify-between w-full">
                        <div className="flex items-center gap-2.5">
                          <div className={`w-9 h-9 rounded-2xl border flex items-center justify-center shrink-0 shadow-2xs ${item.iconBox}`}>
                            <Icon className="w-[18px] h-[18px]" />
                          </div>
                          <span className="text-[10.5px] font-extrabold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                            {item.tag}
                          </span>
                        </div>
                        <div className="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-800 flex items-center justify-center text-slate-400 group-hover:bg-cobalt-600 dark:group-hover:bg-cyan-500 group-hover:text-white transition-colors">
                          <ArrowRight className="w-3.5 h-3.5 group-hover:translate-x-0.5 transition-transform" />
                        </div>
                      </div>

                      <div>
                        <h4 className="text-sm font-extrabold text-slate-900 dark:text-slate-100 group-hover:text-cobalt-600 dark:group-hover:text-cyan-400 transition-colors leading-snug">
                          {item.title}
                        </h4>
                        <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 leading-relaxed line-clamp-2">
                          {item.subtitle}
                        </p>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : (
            /* Active Message List */
            <div className="space-y-2 pb-6">
              {messages.map((msg) => (
                <MessageItem key={msg.id} message={msg} />
              ))}

              {/* Streaming State Bar */}
              {(streamStatus === 'searching' || streamStatus === 'tool_calling' || streamStatus === 'streaming') && (
                <div className="flex items-start gap-3.5 my-4 animate-in fade-in duration-200">
                  <div className="w-8 h-8 rounded-xl bg-cobalt-50 dark:bg-cobalt-950 flex items-center justify-center text-cobalt-600 dark:text-cyan-400 shrink-0 shadow-2xs">
                    <Sparkles className="w-4 h-4 animate-spin" />
                  </div>
                  <div className="p-4 rounded-2xl bg-white/90 dark:bg-[#0E1B33]/90 border border-cobalt-200/80 dark:border-cyan-800/60 shadow-subtle flex items-center gap-3 text-xs text-cobalt-900 dark:text-cyan-200">
                    <div className="flex space-x-1">
                      <div className="w-2 h-2 bg-cobalt-600 dark:bg-cyan-400 rounded-full animate-stream-dot" />
                      <div className="w-2 h-2 bg-cobalt-600 dark:bg-cyan-400 rounded-full animate-stream-dot [animation-delay:0.2s]" />
                      <div className="w-2 h-2 bg-cobalt-600 dark:bg-cyan-400 rounded-full animate-stream-dot [animation-delay:0.4s]" />
                    </div>
                    <span className="font-bold">{currentStreamActivity || 'Đang xử lý dữ liệu...'}</span>
                  </div>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          )}

          {/* Sticky Bottom Composer */}
          <div className="sticky bottom-0 pt-3 pb-4 bg-gradient-to-t from-canvas-light via-canvas-light/95 to-transparent dark:from-canvas-dark dark:via-canvas-dark/95">
            <Composer />
          </div>
        </div>
      </div>
    </div>
  );
};
