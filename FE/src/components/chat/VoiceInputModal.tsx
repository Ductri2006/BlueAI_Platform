import React, { useState, useEffect } from 'react';
import { Mic, Square, Check, X, Sparkles, Volume2 } from 'lucide-react';
import { Button } from '../ui/Button';

interface VoiceInputModalProps {
  isOpen: boolean;
  onClose: () => void;
  onTranscript: (text: string) => void;
}

export const VoiceInputModal: React.FC<VoiceInputModalProps> = ({ isOpen, onClose, onTranscript }) => {
  const [isRecording, setIsRecording] = useState(true);
  const [transcript, setTranscript] = useState('');
  const [bars, setBars] = useState<number[]>([30, 60, 45, 80, 50, 90, 40, 70, 55, 85, 60, 40]);

  useEffect(() => {
    if (!isOpen) return;

    setIsRecording(true);
    setTranscript('');

    // Simulate audio wave animation
    const waveInterval = setInterval(() => {
      setBars(Array.from({ length: 14 }, () => Math.floor(Math.random() * 65) + 20));
    }, 120);

    // Simulate speech-to-text progress
    const timeout1 = setTimeout(() => {
      setTranscript('Khách hàng hỏi điều kiện hoàn tiền eSIM');
    }, 1200);

    const timeout2 = setTimeout(() => {
      setTranscript('Khách hàng hỏi điều kiện hoàn tiền eSIM chưa quét mã QR đi Nhật Bản 7 ngày');
    }, 2400);

    return () => {
      clearInterval(waveInterval);
      clearTimeout(timeout1);
      clearTimeout(timeout2);
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const handleDone = () => {
    onTranscript(transcript || 'Khách hàng muốn hoàn tiền gói eSIM Nhật Bản 7 ngày vì chưa quét mã QR.');
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/70 backdrop-blur-sm animate-in fade-in" role="dialog" aria-modal="true" aria-label="Nhập câu hỏi bằng giọng nói">
      <div className="w-full max-w-md rounded-2xl bg-white dark:bg-[#0F172A] border border-slate-200 dark:border-slate-800 shadow-2xl p-6 flex flex-col items-center text-center space-y-5 animate-in zoom-in-95">
        <div className="flex items-center justify-between w-full">
          <span className="text-xs font-bold uppercase tracking-wider text-cobalt-600 dark:text-cobalt-400 flex items-center gap-1.5">
            <Mic className="w-3.5 h-3.5 animate-pulse" />
            Nhận diện Giọng nói Tiếng Việt
          </span>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Dynamic Waveform Visualizer */}
        <div className="w-full h-20 bg-slate-50 dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 flex items-center justify-center gap-1.5 px-6">
          {bars.map((height, i) => (
            <div
              key={i}
              className="w-1.5 bg-gradient-to-t from-cobalt-600 to-cyan-400 rounded-full transition-all duration-100"
              style={{ height: `${height}%` }}
            />
          ))}
        </div>

        {/* Live Transcribed Text */}
        <div className="min-h-12 w-full p-3 rounded-xl bg-slate-100/70 dark:bg-slate-900 border border-slate-200/80 dark:border-slate-800 text-xs text-slate-800 dark:text-slate-200 leading-relaxed text-left font-sans">
          {transcript ? (
            <span className="font-medium">{transcript}</span>
          ) : (
            <span className="text-slate-400 italic">Đang lắng nghe giọng nói của bạn...</span>
          )}
        </div>

        {/* Action Buttons */}
        <div className="flex items-center gap-2.5 w-full">
          <Button variant="outline" size="sm" className="flex-1 text-xs" onClick={onClose}>
            Hủy
          </Button>
          <Button variant="primary" size="sm" className="flex-1 text-xs font-semibold" onClick={handleDone}>
            <Check className="w-3.5 h-3.5 mr-1" />
            Chèn vào câu hỏi
          </Button>
        </div>
      </div>
    </div>
  );
};
