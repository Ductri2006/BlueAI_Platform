import React, { useState } from 'react';
import { useAppStore } from '../../store/useAppStore';
import { Palette, Type, Layers, Box, Copy, Check, Download, FileCode, Sparkles } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';

export const DesignSpecsStudio: React.FC = () => {
  const { showToast } = useAppStore();
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const copyToClipboard = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(label);
    showToast(`Đã sao chép: ${text} (${label})`, 'info');
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const figmaTokensJSON = JSON.stringify({
    color: {
      brand: {
        cobalt: { value: '#1E4FD8', type: 'color' },
        cobaltLight: { value: '#3B82F6', type: 'color' },
        cobaltSoft: { value: '#EBF2FF', type: 'color' },
        iceCyan: { value: '#0EA5E9', type: 'color' },
      },
      neutral: {
        canvasLight: { value: '#F4F7FB', type: 'color' },
        canvasDark: { value: '#080E1A', type: 'color' },
        surfaceLight: { value: '#FFFFFF', type: 'color' },
        surfaceDark: { value: '#0F172A', type: 'color' },
        borderBase: { value: '#D8E2EE', type: 'color' },
        textPrimaryLight: { value: '#0B132B', type: 'color' },
        textPrimaryDark: { value: '#F1F5F9', type: 'color' },
      },
      semantic: {
        confirmed: { value: '#10B981', type: 'color' },
        inferred: { value: '#F59E0B', type: 'color' },
        proposal: { value: '#6366F1', type: 'color' },
        danger: { value: '#EF4444', type: 'color' },
      }
    },
    typography: {
      primary: { value: 'Be Vietnam Pro', type: 'fontFamilies' },
      mono: { value: 'IBM Plex Mono', type: 'fontFamilies' },
      scale: {
        pageTitle: { fontSize: '24px', lineHeight: '1.3', fontWeight: '700' },
        sectionHeading: { fontSize: '18px', lineHeight: '1.4', fontWeight: '600' },
        body: { fontSize: '15px', lineHeight: '1.6', fontWeight: '400' },
        subtext: { fontSize: '13px', lineHeight: '1.5', fontWeight: '500' },
        metadata: { fontSize: '12px', lineHeight: '1.4', fontWeight: '500' }
      }
    },
    borderRadius: {
      input: { value: '8px', type: 'borderRadius' },
      button: { value: '10px', type: 'borderRadius' },
      card: { value: '12px', type: 'borderRadius' },
      modal: { value: '16px', type: 'borderRadius' },
    }
  }, null, 2);

  const colors = [
    { name: 'Cobalt Blue (Brand Primary)', hex: '#1E4FD8', role: 'Primary CTA, Active States, Key Branding' },
    { name: 'Cobalt Light (Dark Theme Accent)', hex: '#3B82F6', role: 'Active borders, dark theme highlights' },
    { name: 'Cobalt Soft (Light Surface Tint)', hex: '#EBF2FF', role: 'Selected badges, chip backgrounds' },
    { name: 'Ice Cyan (Connection & 3D Core)', hex: '#0EA5E9', role: 'Streaming pulse, particles, status dot' },
    { name: 'Canvas Light (Soft Slate Neutral)', hex: '#F4F7FB', role: 'Light theme background (reduces eye strain)' },
    { name: 'Canvas Dark (Deep Space Navy)', hex: '#080E1A', role: 'Dark theme background' },
    { name: 'Surface Dark (Navy Slate 900)', hex: '#0F172A', role: 'Card & modal backgrounds in dark theme' },
    { name: 'Border Base (Crisp Gray)', hex: '#D8E2EE', role: 'Structural 1px borders & dividers' },
    { name: 'Semantic Confirmed (Emerald)', hex: '#10B981', role: 'Grounded & verified RAG claims' },
    { name: 'Semantic Inferred (Amber)', hex: '#F59E0B', role: 'Inferred / approximate calculations' },
    { name: 'Semantic Danger / HITL (Rose)', hex: '#EF4444', role: 'Refunds & human approval triggers' },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Palette className="w-6 h-6 text-cobalt-600 dark:text-cobalt-400" />
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
              Design Specs & Figma / Canva Token Studio
            </h1>
          </div>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Bảng quy chuẩn thiết kế, mã màu Hex, tỷ lệ font chữ và Design Tokens xuất khẩu sang Figma / Canva.
          </p>
        </div>

        <Button 
          variant="primary" 
          size="sm"
          onClick={() => copyToClipboard(figmaTokensJSON, 'Figma Tokens JSON')}
        >
          <Download className="w-4 h-4 mr-1.5" />
          Sao chép Tokens JSON cho Figma
        </Button>
      </div>

      {/* Color Palette Swatches */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>Bảng mã màu Tone Lạnh (Cool Tone Enterprise Palette)</span>
            <Badge variant="subtle" size="sm">Click vào ô để copy mã Hex</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {colors.map((c, idx) => (
              <button
                key={idx}
                onClick={() => copyToClipboard(c.hex, c.name)}
                className="p-3 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50/60 dark:bg-slate-900/60 hover:border-cobalt-500 text-left transition-all flex items-center gap-3 group"
              >
                <div 
                  className="w-10 h-10 rounded-lg shadow-inner border border-black/10 shrink-0" 
                  style={{ backgroundColor: c.hex }} 
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between">
                    <span className="font-mono font-bold text-xs text-slate-900 dark:text-slate-100">{c.hex}</span>
                    {copiedKey === c.name ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3 h-3 text-slate-300 group-hover:text-cobalt-600" />}
                  </div>
                  <p className="text-[11px] font-semibold text-slate-700 dark:text-slate-300 truncate mt-0.5">{c.name}</p>
                  <p className="text-[10px] text-slate-400 truncate">{c.role}</p>
                </div>
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Typography & Spacing Matrix */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Quy chuẩn Typography (Be Vietnam Pro & IBM Plex Mono)</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4 text-xs">
            <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10.5px] font-mono text-slate-400">Page Title (24px / Bold / Line-height 1.3)</span>
              <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100">
                BlueAI Platform — Trí tuệ Vận hành Doanh nghiệp
              </h2>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10.5px] font-mono text-slate-400">Section Title (16px / SemiBold / Line-height 1.4)</span>
              <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                Chính sách Hoàn hủy & Đổi gói cước eSIM Quốc tế
              </h3>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10.5px] font-mono text-slate-400">Body Text (14.5px / Regular / Line-height 1.6)</span>
              <p className="text-[14px] text-slate-700 dark:text-slate-300 leading-relaxed">
                Nội dung câu trả lời được tối ưu hóa cho mắt khi đọc liên tục, không gây lóa hoặc mỏi mắt.
              </p>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10.5px] font-mono text-slate-400">System Monospace (12px / Medium / IBM Plex Mono)</span>
              <p className="font-mono text-xs text-cobalt-600 dark:text-cobalt-400 font-semibold">
                ORD-JP-98214 • ICCID: 8984040000008819234 • Latency: 412ms
              </p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Bo góc (Radius) & Độ nổi 3D (Elevation)</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3.5 text-xs">
            <div className="flex items-center justify-between p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <div>
                <span className="font-semibold text-slate-800 dark:text-slate-200">Input Fields & Small Badges</span>
                <p className="text-[11px] text-slate-400">Border Radius: 8px (rounded-lg)</p>
              </div>
              <div className="w-12 h-7 rounded-[8px] bg-cobalt-100 dark:bg-cobalt-950 border border-cobalt-300 dark:border-cobalt-700" />
            </div>

            <div className="flex items-center justify-between p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <div>
                <span className="font-semibold text-slate-800 dark:text-slate-200">Action Buttons</span>
                <p className="text-[11px] text-slate-400">Border Radius: 10px (rounded-button)</p>
              </div>
              <div className="w-12 h-7 rounded-[10px] bg-cobalt-600 text-white flex items-center justify-center font-bold text-[10px]">Btn</div>
            </div>

            <div className="flex items-center justify-between p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <div>
                <span className="font-semibold text-slate-800 dark:text-slate-200">Cards & Dashboard Panels</span>
                <p className="text-[11px] text-slate-400">Border Radius: 12px (rounded-card) + Subtle Shadow</p>
              </div>
              <div className="w-12 h-8 rounded-[12px] bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-subtle" />
            </div>

            <div className="flex items-center justify-between p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <div>
                <span className="font-semibold text-slate-800 dark:text-slate-200">Composer & Modals</span>
                <p className="text-[11px] text-slate-400">Border Radius: 16px (rounded-modal) + Float Shadow</p>
              </div>
              <div className="w-12 h-8 rounded-[16px] bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-float" />
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};
