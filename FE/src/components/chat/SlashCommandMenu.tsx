import React from 'react';
import { DollarSign, Scale, Wifi, Shield, Smartphone } from 'lucide-react';

export interface SlashCommand {
  key: string;
  label: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  promptText: string;
}

export const SLASH_COMMANDS: SlashCommand[] = [
  {
    key: '/hoan-tien',
    label: '/hoan-tien',
    description: 'Tra cứu chính sách & điều kiện hoàn tiền eSIM',
    icon: DollarSign,
    promptText: 'Khách hàng muốn hoàn tiền gói eSIM vì chưa quét mã QR, quy định xử lý như thế nào?'
  },
  {
    key: '/so-sanh',
    label: '/so-sanh',
    description: 'So sánh độ phủ sóng và tốc độ Docomo vs SoftBank',
    icon: Scale,
    promptText: 'So sánh ưu nhược điểm giữa gói mạng SoftBank 5G và Docomo Nhật Bản?'
  },
  {
    key: '/apn',
    label: '/apn',
    description: 'Hướng dẫn cấu hình APN vmobile.jp và bật Roaming',
    icon: Wifi,
    promptText: 'Hướng dẫn chi tiết cách cấu hình APN và xử lý lỗi không lên sóng khi hạ cánh?'
  },
  {
    key: '/chiet-khau',
    label: '/chiet-khau',
    description: 'Chính sách chiết khấu & hạn mức tín dụng Đại lý Cấp 1',
    icon: Shield,
    promptText: 'Chính sách chiết khấu và thưởng doanh số cho Đại lý Cấp 1 là bao nhiêu?'
  },
  {
    key: '/iccid',
    label: '/iccid',
    description: 'Kiểm tra trạng thái quét mã ICCID của đơn hàng',
    icon: Smartphone,
    promptText: 'Tôi cần kiểm tra trạng thái kích hoạt một đơn hàng. Hãy cho biết dữ liệu hoặc công cụ cần cung cấp, không tự suy đoán trạng thái.'
  }
];

interface SlashCommandMenuProps {
  filter: string;
  onSelect: (command: SlashCommand) => void;
  isOpen: boolean;
}

export const SlashCommandMenu: React.FC<SlashCommandMenuProps> = ({ filter, onSelect, isOpen }) => {
  if (!isOpen) return null;

  const filteredCommands = SLASH_COMMANDS.filter(cmd => 
    cmd.key.toLowerCase().includes(filter.toLowerCase()) || 
    cmd.description.toLowerCase().includes(filter.toLowerCase())
  );

  if (filteredCommands.length === 0) return null;

  return (
    <div className="absolute bottom-full left-0 mb-2 w-80 max-h-64 overflow-y-auto rounded-xl bg-white dark:bg-[#0F172A] border border-slate-200 dark:border-slate-800 shadow-xl z-30 p-1.5 space-y-1 animate-in fade-in slide-in-from-bottom-2">
      <div className="px-2.5 py-1 text-[10.5px] font-bold uppercase tracking-wider text-slate-400">
        Lệnh tắt nghiệp vụ (Slash Commands)
      </div>
      {filteredCommands.map((cmd) => {
        const Icon = cmd.icon;
        return (
          <button
            key={cmd.key}
            onClick={() => onSelect(cmd)}
            className="w-full text-left p-2 rounded-lg hover:bg-cobalt-50 dark:hover:bg-cobalt-950/60 hover:text-cobalt-700 dark:hover:text-cobalt-300 transition-colors flex items-center gap-2.5 group"
          >
            <div className="w-6 h-6 rounded-md bg-slate-100 dark:bg-slate-800 flex items-center justify-center text-slate-600 dark:text-slate-300 group-hover:bg-cobalt-100 dark:group-hover:bg-cobalt-900 group-hover:text-cobalt-600">
              <Icon className="w-3.5 h-3.5" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-xs font-bold text-slate-800 dark:text-slate-200 group-hover:text-cobalt-600 font-mono">
                {cmd.label}
              </div>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                {cmd.description}
              </p>
            </div>
          </button>
        );
      })}
    </div>
  );
};
