import React, { useState } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { 
  QrCode, Smartphone, Wifi, Headphones, CheckCircle2, 
  AlertCircle, ArrowRight, HelpCircle, Sparkles, Shield, 
  Send, Compass, Zap, Check 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';

export const B2CDashboard: React.FC = () => {
  const { showToast } = useAppStore();
  const [b2cChat, setB2cChat] = useState('');
  const [activeStep, setActiveStep] = useState(1);
  const [b2cMessages, setB2cMessages] = useState([
    { sender: 'bot', text: 'Xin chào bạn! Mình là Trợ lý hỗ trợ khách hàng Bluecom. Bạn cần hướng dẫn cài đặt eSIM hay kiểm tra dung lượng gói cước?' }
  ]);

  const handleSendB2c = () => {
    if (!b2cChat.trim()) return;
    const userText = b2cChat;
    setB2cMessages(prev => [...prev, { sender: 'user', text: userText }]);
    setB2cChat('');

    setTimeout(() => {
      setB2cMessages(prev => [
        ...prev,
        {
          sender: 'bot',
          text: 'Dạ mình đã kiểm tra đơn hàng #ORD-JP-98214 của bạn. Gói cước đang hoạt động bình thường với 3GB tốc độ cao mỗi ngày. Khi đến Nhật, bạn nhớ Bật "Chuyển vùng dữ liệu" (Data Roaming) trong phần Cài đặt nhé!'
        }
      ]);
    }, 600);
  };

  return (
    <div className="flex-1 overflow-y-auto p-5 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark relative">
      {/* Ambient background glow */}
      <div className="absolute top-0 right-1/4 w-96 h-96 bg-cobalt-600/10 dark:bg-cobalt-500/10 rounded-full blur-3xl pointer-events-none -z-10" />

      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2.5">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-cobalt-600 to-cyan-500 text-white flex items-center justify-center shadow-md">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <h1 className="text-xl sm:text-2xl font-extrabold text-slate-900 dark:text-slate-100 tracking-tight">
                Trung tâm eSIM & Trợ lý Khách hàng
              </h1>
              <p className="text-xs sm:text-sm text-slate-500 dark:text-slate-400 mt-0.5">
                Quét mã QR kích hoạt tức thì • Hỗ trợ kỹ thuật mạng 24/7
              </p>
            </div>
          </div>
        </div>

        <Button 
          variant="outline" 
          size="sm"
          className="rounded-xl border-cobalt-200 dark:border-cobalt-800 bg-white/80 dark:bg-slate-900/80 backdrop-blur-sm hover:border-cobalt-400 text-xs font-semibold text-cobalt-700 dark:text-cobalt-300"
          onClick={() => showToast('Đang kết nối bạn với chuyên viên CSKH Bluecom trực tuyến...', 'info')}
        >
          <Headphones className="w-4 h-4 mr-1.5 text-cobalt-600 dark:text-cobalt-400" />
          Gặp nhân viên hỗ trợ trực tiếp
        </Button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Holographic 3D eSIM Card & QR Scanner */}
        <div className="relative rounded-3xl bg-gradient-to-b from-white to-slate-50 dark:from-[#0E1B33] dark:to-[#091324] border border-slate-200/80 dark:border-slate-800/80 p-6 shadow-float dark:shadow-float-dark flex flex-col items-center text-center space-y-5">
          {/* Card Badge */}
          <div className="flex items-center justify-between w-full">
            <div className="flex items-center gap-1.5 text-xs font-bold text-slate-900 dark:text-slate-100">
              <Sparkles className="w-4 h-4 text-cobalt-500" />
              <span>Bluecom eSIM Travel</span>
            </div>
            <Badge variant="confirmed" size="sm">
              <CheckCircle2 className="w-3 h-3 mr-1" />
              Sẵn sàng cài đặt
            </Badge>
          </div>
          
          {/* QR Code Container with Holographic Scan Corner */}
          <div className="relative group">
            <div className="absolute -inset-1 rounded-2xl bg-gradient-to-r from-cobalt-500 to-cyan-400 opacity-30 blur group-hover:opacity-60 transition duration-300" />
            <div className="relative w-48 h-48 bg-white p-3.5 rounded-2xl border border-slate-200 shadow-md flex flex-col items-center justify-center">
              <QrCode className="w-40 h-40 text-slate-950" />
              {/* Glowing Scan Bar */}
              <div className="absolute top-4 left-4 right-4 h-0.5 bg-gradient-to-r from-transparent via-cyan-400 to-transparent animate-pulse" />
            </div>
          </div>

          <div className="space-y-1">
            <h3 className="font-extrabold text-slate-900 dark:text-slate-100 text-lg tracking-tight">
              eSIM Nhật Bản 7 Ngày 5G
            </h3>
            <p className="text-xs font-mono text-cobalt-600 dark:text-cobalt-400 font-semibold">
              Mã đơn: #ORD-JP-98214
            </p>
          </div>

          {/* Quick Specs Matrix */}
          <div className="w-full p-4 rounded-2xl bg-slate-100/70 dark:bg-slate-900/90 border border-slate-200/80 dark:border-slate-800 text-xs text-left space-y-2">
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Nhà mạng đối tác:</span>
              <span className="font-bold text-slate-800 dark:text-slate-200">SoftBank / Docomo (Auto 5G)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Dung lượng Data:</span>
              <span className="font-bold text-emerald-600 dark:text-emerald-400">3GB/Ngày (FUP tốc độ cao)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500 dark:text-slate-400">Hạn kích hoạt:</span>
              <span className="font-semibold text-slate-800 dark:text-slate-200">30/06/2026</span>
            </div>
          </div>

          <Button 
            variant="primary" 
            size="md" 
            className="w-full rounded-xl shadow-sm text-xs font-bold"
            onClick={() => showToast('Đang tải ảnh mã QR độ nét cao về thiết bị', 'success')}
          >
            Lưu ảnh mã QR về điện thoại
          </Button>
        </div>

        {/* 3 Step Interactive Activation Guide & AI Assistant */}
        <div className="lg:col-span-2 space-y-6">
          {/* Step-by-Step Activation Guide (Fixed LaTeX arrows to clean arrows) */}
          <div className="rounded-3xl bg-white dark:bg-[#0F172A] border border-slate-200/80 dark:border-slate-800 p-6 shadow-subtle space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-bold text-slate-900 dark:text-slate-100 text-sm tracking-tight flex items-center gap-2">
                <Zap className="w-4 h-4 text-amber-500" />
                Hướng dẫn 3 bước kích hoạt nhanh trước chuyến bay:
              </h3>
              <span className="text-[11px] text-slate-400">Đơn giản • Không tháo SIM gốc</span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3.5 text-xs">
              <div 
                onClick={() => setActiveStep(1)}
                className={`p-4 rounded-2xl border transition-all cursor-pointer ${
                  activeStep === 1 
                    ? 'bg-cobalt-50/80 dark:bg-cobalt-950/60 border-cobalt-300 dark:border-cobalt-700 shadow-2xs' 
                    : 'bg-slate-50/60 dark:bg-slate-900/60 border-slate-200/80 dark:border-slate-800 hover:border-slate-300'
                }`}
              >
                <div className="w-7 h-7 rounded-xl bg-cobalt-600 text-white flex items-center justify-center font-extrabold text-xs shadow-sm">
                  1
                </div>
                <h4 className="font-bold text-slate-900 dark:text-slate-100 mt-2.5 text-[13px]">Quét mã QR</h4>
                <p className="text-slate-500 dark:text-slate-400 text-[11.5px] mt-1 leading-relaxed">
                  Vào <strong>Cài đặt → Di động → Thêm eSIM</strong> và hướng camera vào mã QR bên cạnh.
                </p>
              </div>

              <div 
                onClick={() => setActiveStep(2)}
                className={`p-4 rounded-2xl border transition-all cursor-pointer ${
                  activeStep === 2 
                    ? 'bg-cobalt-50/80 dark:bg-cobalt-950/60 border-cobalt-300 dark:border-cobalt-700 shadow-2xs' 
                    : 'bg-slate-50/60 dark:bg-slate-900/60 border-slate-200/80 dark:border-slate-800 hover:border-slate-300'
                }`}
              >
                <div className="w-7 h-7 rounded-xl bg-cobalt-600 text-white flex items-center justify-center font-extrabold text-xs shadow-sm">
                  2
                </div>
                <h4 className="font-bold text-slate-900 dark:text-slate-100 mt-2.5 text-[13px]">Đặt tên thẻ SIM</h4>
                <p className="text-slate-500 dark:text-slate-400 text-[11.5px] mt-1 leading-relaxed">
                  Đặt nhãn <strong>"Bluecom Du lịch"</strong> để phân biệt với SIM chính Việt Nam của bạn.
                </p>
              </div>

              <div 
                onClick={() => setActiveStep(3)}
                className={`p-4 rounded-2xl border transition-all cursor-pointer ${
                  activeStep === 3 
                    ? 'bg-cobalt-50/80 dark:bg-cobalt-950/60 border-cobalt-300 dark:border-cobalt-700 shadow-2xs' 
                    : 'bg-slate-50/60 dark:bg-slate-900/60 border-slate-200/80 dark:border-slate-800 hover:border-slate-300'
                }`}
              >
                <div className="w-7 h-7 rounded-xl bg-cobalt-600 text-white flex items-center justify-center font-extrabold text-xs shadow-sm">
                  3
                </div>
                <h4 className="font-bold text-slate-900 dark:text-slate-100 mt-2.5 text-[13px]">Bật Roaming khi hạ cánh</h4>
                <p className="text-slate-500 dark:text-slate-400 text-[11.5px] mt-1 leading-relaxed">
                  Khi đến sân bay Narita/Kansai, chỉ cần bật <strong>Data Roaming (Chuyển vùng dữ liệu)</strong> để có sóng 5G.
                </p>
              </div>
            </div>
          </div>

          {/* Customer Chat Assistant with Sleek UI */}
          <div className="rounded-3xl bg-white dark:bg-[#0F172A] border border-slate-200/80 dark:border-slate-800 shadow-subtle flex flex-col h-84 overflow-hidden">
            <div className="py-3 px-5 border-b border-slate-200/80 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-900/70 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span className="text-xs font-bold text-slate-800 dark:text-slate-200">
                  Hỏi đáp nhanh cùng Trợ lý BlueAI
                </span>
              </div>
              <span className="text-[11px] text-slate-400">Trực tuyến 24/7</span>
            </div>

            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              {b2cMessages.map((m, idx) => (
                <div key={idx} className={`flex ${m.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
                  <div className={`max-w-[85%] p-3.5 rounded-2xl text-xs leading-relaxed ${
                    m.sender === 'user'
                      ? 'bg-cobalt-600 text-white rounded-tr-xs'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100 rounded-tl-xs'
                  }`}>
                    {m.text}
                  </div>
                </div>
              ))}
            </div>

            <div className="p-3 border-t border-slate-200/80 dark:border-slate-800 bg-slate-50 dark:bg-slate-900 flex gap-2">
              <input
                type="text"
                value={b2cChat}
                onChange={(e) => setB2cChat(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSendB2c()}
                placeholder="Nhập câu hỏi (Ví dụ: Máy iPhone 13 có hỗ trợ eSIM không?)..."
                className="flex-1 px-3.5 py-2 text-xs rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:border-cobalt-500"
              />
              <Button size="sm" variant="primary" className="rounded-xl px-4 text-xs font-bold" onClick={handleSendB2c}>
                <Send className="w-3.5 h-3.5 mr-1" />
                Gửi
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
