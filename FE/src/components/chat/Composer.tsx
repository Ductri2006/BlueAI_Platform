import React, { useState, useRef, useEffect } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { 
  ArrowUp, Sparkles, X, Square,
  Shield, Mic
} from 'lucide-react';
import { Button } from '../ui/Button';
import { SlashCommandMenu, SlashCommand } from './SlashCommandMenu';
import { VoiceInputModal } from './VoiceInputModal';

interface ComposerProps {
  placeholder?: string;
  contextChip?: string;
  onClearContext?: () => void;
}

export const Composer: React.FC<ComposerProps> = ({
  placeholder = 'Hỏi quy chế, chính sách hoàn hủy hoặc gõ "/" để dùng lệnh tắt...',
  contextChip,
  onClearContext
}) => {
  const { sendMessage, streamStatus } = useAppStore();
  const [text, setText] = useState('');
  const [slashFilter, setSlashFilter] = useState('');
  const [isSlashOpen, setIsSlashOpen] = useState(false);
  const [isVoiceOpen, setIsVoiceOpen] = useState(false);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const isStreaming = streamStatus === 'searching' || streamStatus === 'tool_calling' || streamStatus === 'streaming';

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 180)}px`;
    }
  }, [text]);

  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    setText(val);

    if (val.startsWith('/')) {
      setIsSlashOpen(true);
      setSlashFilter(val);
    } else {
      setIsSlashOpen(false);
    }
  };

  const handleSelectSlashCommand = (cmd: SlashCommand) => {
    setIsSlashOpen(false);
    setText(cmd.promptText);
  };

  const handleSend = () => {
    if (!text.trim() || isStreaming) return;
    sendMessage(text);
    setText('');
    setIsSlashOpen(false);
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="w-full max-w-4xl mx-auto space-y-2 relative">
      <VoiceInputModal
        isOpen={isVoiceOpen}
        onClose={() => setIsVoiceOpen(false)}
        onTranscript={(val) => setText(val)}
      />

      {/* Active Context */}
      <div className="flex flex-wrap items-center gap-2">
        {contextChip && (
          <div className="flex items-center gap-1.5 px-3 py-1 bg-cobalt-50 dark:bg-cobalt-950/60 border border-cobalt-200/80 dark:border-cobalt-800 text-cobalt-700 dark:text-cobalt-300 text-xs rounded-lg w-fit animate-in fade-in">
            <Shield className="w-3.5 h-3.5" />
            <span className="font-medium">{contextChip}</span>
            {onClearContext && (
              <button onClick={onClearContext} className="ml-1 hover:text-rose-500" aria-label="Xóa ngữ cảnh đang chọn">
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        )}
      </div>

      {/* Slash Command Autocomplete Popover */}
      <SlashCommandMenu
        isOpen={isSlashOpen}
        filter={slashFilter}
        onSelect={handleSelectSlashCommand}
      />

      {/* Main Composer Container */}
      <div className="relative rounded-card bg-white dark:bg-[#0F172A] border border-slate-300/80 dark:border-slate-800 shadow-float dark:shadow-float-dark focus-within:border-cobalt-500 dark:focus-within:border-cobalt-400 focus-within:ring-2 focus-within:ring-cobalt-500/10 transition-all p-3">
        <textarea
          ref={textareaRef}
          value={text}
          onChange={handleTextChange}
          onKeyDown={handleKeyDown}
          rows={1}
          placeholder={placeholder}
          aria-label="Nhập câu hỏi cho BlueAI"
          className="w-full bg-transparent resize-none border-0 text-[14.5px] text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:outline-none focus:ring-0 leading-relaxed px-1 py-1 max-h-[180px]"
        />

        {/* Bottom Toolbar */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-100 dark:border-slate-800/80 mt-1">
          <div className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
            {/* Voice Input Button */}
            <button 
              type="button" 
              onClick={() => setIsVoiceOpen(true)}
              className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-700 dark:hover:text-slate-200 transition-colors"
              title="Nhập giọng nói tiếng Việt"
            >
              <Mic className="w-4 h-4" />
            </button>

          </div>

          <div className="flex items-center gap-2">
            <span className="text-[11px] text-slate-400 hidden sm:inline">
              Gõ <strong className="font-mono text-cobalt-600">/</strong> cho lệnh tắt • <strong>Enter ↵</strong> gửi
            </span>
            <Button
              size="icon"
              variant="primary"
              onClick={handleSend}
              disabled={!text.trim() || isStreaming}
              className="h-8 w-8 rounded-xl shadow-sm"
              aria-label={isStreaming ? 'Đang xử lý câu hỏi' : 'Gửi câu hỏi'}
            >
              {isStreaming ? (
                <Square className="w-3.5 h-3.5 fill-current animate-pulse" />
              ) : (
                <ArrowUp className="w-4 h-4" />
              )}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
