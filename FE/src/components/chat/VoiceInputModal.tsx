import React, { useEffect, useRef, useState } from 'react';
import { Check, Mic, X } from 'lucide-react';
import { Button } from '../ui/Button';

interface VoiceInputModalProps { isOpen: boolean; onClose: () => void; onTranscript: (text: string) => void; }
interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start: () => void;
  stop: () => void;
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null;
  onerror: (() => void) | null;
  onend: (() => void) | null;
}

export const VoiceInputModal: React.FC<VoiceInputModalProps> = ({ isOpen, onClose, onTranscript }) => {
  const [transcript, setTranscript] = useState('');
  const [status, setStatus] = useState('Đang khởi tạo microphone…');
  const recognition = useRef<SpeechRecognitionLike | undefined>(undefined);

  useEffect(() => {
    if (!isOpen) return;
    setTranscript('');
    const browserWindow = window as typeof window & {
      SpeechRecognition?: new () => SpeechRecognitionLike;
      webkitSpeechRecognition?: new () => SpeechRecognitionLike;
    };
    const Constructor = browserWindow.SpeechRecognition || browserWindow.webkitSpeechRecognition;
    if (!Constructor) {
      setStatus('Trình duyệt này không hỗ trợ Web Speech API.');
      return;
    }
    const instance = new Constructor();
    recognition.current = instance;
    instance.lang = 'vi-VN';
    instance.interimResults = true;
    instance.continuous = false;
    instance.onresult = event => {
      const text = Array.from(event.results).map(result => result[0].transcript).join(' ');
      setTranscript(text);
      setStatus('Đã nhận giọng nói.');
    };
    instance.onerror = () => setStatus('Không thể nhận giọng nói. Hãy kiểm tra quyền microphone.');
    instance.onend = () => setStatus(current => current.startsWith('Đang') ? 'Đã dừng lắng nghe.' : current);
    setStatus('Đang lắng nghe…');
    instance.start();
    return () => { instance.stop(); recognition.current = undefined; };
  }, [isOpen]);

  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/70" role="dialog" aria-modal="true">
      <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-2xl p-6 space-y-5">
        <div className="flex justify-between"><h3 className="font-bold flex items-center gap-2"><Mic className="w-4 h-4 text-cobalt-600" />Nhập bằng giọng nói</h3><button onClick={onClose}><X className="w-4 h-4" /></button></div>
        <p className="text-xs text-slate-500">{status}</p>
        <div className="min-h-24 p-4 rounded-xl bg-slate-100 dark:bg-slate-800 text-sm">{transcript || 'Chưa nhận được nội dung.'}</div>
        <div className="flex gap-2"><Button variant="outline" className="flex-1" onClick={onClose}>Huỷ</Button><Button variant="primary" className="flex-1" disabled={!transcript.trim()} onClick={() => { onTranscript(transcript); onClose(); }}><Check className="w-4 h-4 mr-1" />Chèn</Button></div>
      </div>
    </div>
  );
};
