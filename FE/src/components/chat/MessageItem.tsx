import React from 'react';
import { ChatMessage } from '../../types';
import { useAppStore } from '../../store/useAppStore';
import { GroundedStatusBadge } from './GroundedStatusBadge';
import { RichMarkdownRenderer } from './RichMarkdownRenderer';
import { Copy, ThumbsUp, ThumbsDown, Check, User, Sparkles, Layers } from 'lucide-react';
import { presentAssistantAnswer } from '../../lib/answerPresentation';

interface MessageItemProps {
  message: ChatMessage;
}

export const MessageItem: React.FC<MessageItemProps> = ({ message }) => {
  const { showToast, sendMessage } = useAppStore();
  const [copied, setCopied] = React.useState(false);
  const [feedback, setFeedback] = React.useState<'up' | 'down' | null>(null);

  const isUser = message.sender === 'user';
  const visibleContent = isUser ? message.content
    : presentAssistantAnswer(message.content, (message.citations || []).map(source => source.title));

  const handleCopy = () => {
    navigator.clipboard.writeText(visibleContent);
    setCopied(true);
    showToast('Đã sao chép nội dung tin nhắn', 'info');
    setTimeout(() => setCopied(false), 2000);
  };

  const handleFeedback = (type: 'up' | 'down') => {
    setFeedback(type);
    showToast(type === 'up' ? 'Cảm ơn bạn đã phản hồi câu trả lời hữu ích' : 'Đã ghi nhận phản hồi để cải thiện', 'info');
  };

  if (isUser) {
    return (
      <div className="flex justify-end my-4 animate-in fade-in slide-in-from-bottom-2 duration-200">
        <div className="flex items-start gap-2.5 max-w-[82%] sm:max-w-[72%]">
          <div className="bg-cobalt-600 dark:bg-cobalt-600 text-white p-3.5 px-4 rounded-2xl rounded-tr-xs shadow-sm text-[14.5px] leading-relaxed whitespace-pre-line">
            {message.content}
          </div>
          <div className="w-8 h-8 rounded-full bg-slate-200 dark:bg-slate-700 flex items-center justify-center text-slate-600 dark:text-slate-200 shrink-0 text-xs font-semibold">
            <User className="w-4 h-4" />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start gap-3.5 my-6 animate-in fade-in duration-300">
      {/* Bot Avatar */}
      <div className="w-8 h-8 rounded-lg bg-cobalt-50 dark:bg-cobalt-950/80 border border-cobalt-200 dark:border-cobalt-800/80 flex items-center justify-center text-cobalt-600 dark:text-cobalt-400 shrink-0 shadow-2xs mt-1">
        <Sparkles className="w-4 h-4 text-cobalt-600 dark:text-cobalt-400" />
      </div>

      {/* Open Canvas Layout for Assistant */}
      <div className="flex-1 min-w-0 space-y-3">
        {/* Header line with Brand tag & Grounded Status */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-black text-slate-800 dark:text-slate-200 tracking-tight">
            blueAI
          </span>
          <span className="text-[11px] text-slate-400 dark:text-slate-500">• {message.timestamp}</span>
          
          {(message.confidence === 'context_confirmation' || message.confidence === 'danger') && (
            <GroundedStatusBadge 
              confidence={message.confidence} 
            />
          )}
        </div>

        {/* Content Body with Rich Markdown & Tables */}
        <div className="text-slate-800 dark:text-slate-200">
          <RichMarkdownRenderer content={visibleContent} />
        </div>

        {message.contextSources && message.contextSources.length > 0 && (
          <div className="pt-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                <Layers className="h-3 w-3" />
                {message.confidence === 'context_confirmation' ? 'Context đề xuất (chưa áp dụng):' : 'Context đã dùng:'}
              </span>
              {message.contextSources.map((source, index) => (
                <span
                  key={`${source.type}:${source.conversationId || source.memoryKey || source.title}:${index}`}
                  className="inline-flex max-w-[240px] items-center rounded-md border border-violet-200 bg-violet-50 px-2.5 py-1 text-xs font-medium text-violet-800 dark:border-violet-900/70 dark:bg-violet-950/30 dark:text-violet-200"
                  title={source.title}
                >
                  <span className="truncate">{source.title}</span>
                </span>
              ))}
            </div>
          </div>
        )}

        {/* Suggested Next Questions */}
        {message.suggestedFollowUps && message.suggestedFollowUps.length > 0 && (
          <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200/80 dark:border-slate-800/80 space-y-1.5 mt-3">
            <div className="text-[11.5px] font-semibold text-slate-500 dark:text-slate-400">Gợi ý tác vụ tiếp theo:</div>
            <div className="flex flex-wrap gap-2">
              {message.suggestedFollowUps.map((prompt, i) => (
                <button
                  key={i}
                  onClick={() => {
                    sendMessage(prompt);
                  }}
                  className="text-xs px-3 py-1 rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:border-cobalt-400 hover:text-cobalt-600 dark:hover:text-cobalt-400 transition-colors text-left"
                >
                  → {prompt}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Footer Actions */}
        <div className="flex items-center gap-1 pt-1 text-slate-400 dark:text-slate-500">
          <button
            onClick={handleCopy}
            title="Sao chép câu trả lời"
            className="p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-700 dark:hover:text-slate-300 transition-colors text-xs flex items-center gap-1"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Đã chép' : 'Sao chép'}</span>
          </button>

          <div className="h-3 w-px bg-slate-200 dark:bg-slate-800 mx-1" />

          <button
            onClick={() => handleFeedback('up')}
            title="Hữu ích"
            className={`p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors ${feedback === 'up' ? 'text-cobalt-600 font-bold' : 'hover:text-slate-700 dark:hover:text-slate-300'}`}
          >
            <ThumbsUp className="w-3.5 h-3.5" />
          </button>

          <button
            onClick={() => handleFeedback('down')}
            title="Chưa đúng"
            className={`p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors ${feedback === 'down' ? 'text-rose-600 font-bold' : 'hover:text-slate-700 dark:hover:text-slate-300'}`}
          >
            <ThumbsDown className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
};
