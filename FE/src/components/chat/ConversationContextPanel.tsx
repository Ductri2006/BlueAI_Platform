import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Brain, Link2, LoaderCircle, Plus, RefreshCw, Trash2, X } from 'lucide-react';
import {
  blueAiApi,
  BlueAiApiError,
  type ConversationContextMode,
  type ConversationContextSettingsResponse,
  type ConversationLinkResponse,
  type MemoryScope,
  type UserMemoryResponse,
} from '../../api/blueAiApi';

interface ConversationContextPanelProps {
  conversationId?: string;
  isOpen: boolean;
  onClose: () => void;
  onModeChange?: (mode: ConversationContextMode) => void;
}

const MODE_OPTIONS: Array<{ value: ConversationContextMode; label: string; description: string }> = [
  {
    value: 'CURRENT_CONVERSATION',
    label: 'Chỉ phiên hiện tại',
    description: 'Dùng lịch sử, tóm tắt và ghi nhớ phù hợp của phiên đang mở.',
  },
  {
    value: 'LINKED_CONVERSATIONS',
    label: 'Các phiên đã liên kết',
    description: 'Tham khảo thêm các snapshot mà bạn chủ động liên kết bên dưới.',
  },
  {
    value: 'RELEVANT_HISTORY',
    label: 'Tự tìm trong lịch sử',
    description: 'Ưu tiên phiên đã liên kết, sau đó tìm các phiên gần đây cùng từ khóa của bạn.',
  },
];

function errorMessage(error: unknown): string {
  return error instanceof BlueAiApiError ? error.message : 'Không thể cập nhật context trên máy chủ.';
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
}

export const ConversationContextPanel: React.FC<ConversationContextPanelProps> = ({
  conversationId,
  isOpen,
  onClose,
  onModeChange,
}) => {
  const [settings, setSettings] = useState<ConversationContextSettingsResponse>();
  const [conversations, setConversations] = useState<Array<{ id: string; title: string }>>([]);
  const [memories, setMemories] = useState<UserMemoryResponse[]>([]);
  const [sourceConversationId, setSourceConversationId] = useState('');
  const [memoryKey, setMemoryKey] = useState('');
  const [memoryContent, setMemoryContent] = useState('');
  const [memoryScope, setMemoryScope] = useState<MemoryScope>('USER');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!conversationId) return;
    setLoading(true);
    setError('');
    try {
      const [nextSettings, allConversations, nextMemories] = await Promise.all([
        blueAiApi.getConversationContext(conversationId),
        blueAiApi.listConversations(),
        blueAiApi.listMemories(),
      ]);
      setSettings(nextSettings);
      setConversations(allConversations.map(item => ({ id: item.id, title: item.title })));
      setMemories(nextMemories);
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [conversationId]);

  useEffect(() => {
    if (isOpen) void load();
  }, [isOpen, load]);

  const availableSources = useMemo(() => {
    const linkedSourceIds = new Set(settings?.links.map(link => link.sourceConversationId) || []);
    return conversations.filter(item => item.id !== conversationId && !linkedSourceIds.has(item.id));
  }, [conversationId, conversations, settings?.links]);

  const confirmedMemories = useMemo(
    () => memories.filter(memory => memory.status === 'CONFIRMED'),
    [memories],
  );

  if (!isOpen) return null;

  const updateMode = async (mode: ConversationContextMode) => {
    if (!conversationId || !settings || settings.mode === mode) return;
    setSaving(true);
    setError('');
    try {
      const updated = await blueAiApi.updateConversationContext(conversationId, mode);
      setSettings(updated);
      onModeChange?.(updated.mode);
    } catch (updateError) {
      setError(errorMessage(updateError));
    } finally {
      setSaving(false);
    }
  };

  const addLink = async () => {
    if (!conversationId || !sourceConversationId) return;
    setSaving(true);
    setError('');
    try {
      const link = await blueAiApi.createConversationLink(conversationId, sourceConversationId);
      setSettings(current => current && { ...current, links: [link, ...current.links] });
      setSourceConversationId('');
    } catch (linkError) {
      setError(errorMessage(linkError));
    } finally {
      setSaving(false);
    }
  };

  const refreshLink = async (link: ConversationLinkResponse) => {
    if (!conversationId) return;
    setSaving(true);
    setError('');
    try {
      const refreshed = await blueAiApi.refreshConversationLink(conversationId, link.id);
      setSettings(current => current && {
        ...current,
        links: current.links.map(item => item.id === refreshed.id ? refreshed : item),
      });
    } catch (refreshError) {
      setError(errorMessage(refreshError));
    } finally {
      setSaving(false);
    }
  };

  const removeLink = async (linkId: string) => {
    if (!conversationId) return;
    setSaving(true);
    setError('');
    try {
      await blueAiApi.deleteConversationLink(conversationId, linkId);
      setSettings(current => current && { ...current, links: current.links.filter(link => link.id !== linkId) });
    } catch (removeError) {
      setError(errorMessage(removeError));
    } finally {
      setSaving(false);
    }
  };

  const saveMemory = async () => {
    const key = memoryKey.trim();
    const content = memoryContent.trim();
    if (!key || !content || !conversationId) return;
    setSaving(true);
    setError('');
    try {
      const memory = await blueAiApi.upsertMemory(key, {
        content,
        scope: memoryScope,
        conversationId: memoryScope === 'CONVERSATION' ? conversationId : undefined,
      });
      setMemories(current => [memory, ...current.filter(item => item.key !== memory.key)]);
      setMemoryKey('');
      setMemoryContent('');
      setMemoryScope('USER');
    } catch (memoryError) {
      setError(errorMessage(memoryError));
    } finally {
      setSaving(false);
    }
  };

  const removeMemory = async (key: string) => {
    setSaving(true);
    setError('');
    try {
      await blueAiApi.deleteMemory(key);
      setMemories(current => current.filter(memory => memory.key !== key));
    } catch (removeError) {
      setError(errorMessage(removeError));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="absolute inset-0 z-30 flex justify-end bg-slate-950/25 backdrop-blur-[1px]" role="dialog" aria-modal="true" aria-label="Quản lý context hội thoại">
      <div className="h-full w-full max-w-xl overflow-y-auto border-l border-slate-200 bg-white shadow-2xl dark:border-slate-800 dark:bg-[#091224]">
        <header className="sticky top-0 z-10 flex items-start justify-between border-b border-slate-200 bg-white/95 px-5 py-4 backdrop-blur dark:border-slate-800 dark:bg-[#091224]/95">
          <div className="flex gap-3">
            <div className="mt-0.5 rounded-xl bg-cobalt-50 p-2 text-cobalt-600 dark:bg-cobalt-950/70 dark:text-cyan-300"><Brain className="h-4 w-4" /></div>
            <div>
              <h3 className="text-sm font-extrabold text-slate-900 dark:text-slate-100">Context &amp; ghi nhớ</h3>
              <p className="mt-1 max-w-sm text-xs leading-relaxed text-slate-500 dark:text-slate-400">Chỉ dữ liệu thuộc tài khoản của bạn được dùng làm ngữ cảnh.</p>
            </div>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200" aria-label="Đóng bảng context"><X className="h-4 w-4" /></button>
        </header>

        <div className="space-y-6 p-5">
          {loading && <div className="flex items-center gap-2 text-sm text-slate-500"><LoaderCircle className="h-4 w-4 animate-spin" />Đang tải context…</div>}
          {error && <p className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300">{error}</p>}

          {settings && <>
            <section className="space-y-2.5">
              <div><h4 className="text-xs font-bold uppercase tracking-wider text-slate-500">Cách lấy context</h4><p className="mt-1 text-xs text-slate-500">Chọn phạm vi AI được phép tham khảo cho phiên này.</p></div>
              <div className="space-y-2">
                {MODE_OPTIONS.map(option => (
                  <label key={option.value} className={`block cursor-pointer rounded-xl border p-3 transition-colors ${settings.mode === option.value ? 'border-cobalt-400 bg-cobalt-50/70 dark:border-cyan-500 dark:bg-cyan-950/20' : 'border-slate-200 hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700'}`}>
                    <div className="flex gap-2.5"><input type="radio" name="context-mode" checked={settings.mode === option.value} disabled={saving} onChange={() => void updateMode(option.value)} className="mt-0.5 accent-cobalt-600" /><span><span className="block text-sm font-bold text-slate-800 dark:text-slate-100">{option.label}</span><span className="mt-0.5 block text-xs leading-relaxed text-slate-500 dark:text-slate-400">{option.description}</span></span></div>
                  </label>
                ))}
              </div>
            </section>

            <section className="space-y-3 border-t border-slate-100 pt-5 dark:border-slate-800">
              <div><h4 className="text-xs font-bold uppercase tracking-wider text-slate-500">Liên kết hội thoại</h4><p className="mt-1 text-xs text-slate-500">Mỗi link giữ snapshot. Bấm làm mới khi bạn muốn lấy thay đổi mới từ phiên nguồn.</p></div>
              <div className="flex gap-2">
                <select value={sourceConversationId} onChange={event => setSourceConversationId(event.target.value)} disabled={saving || availableSources.length === 0} className="min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
                  <option value="">{availableSources.length ? 'Chọn hội thoại nguồn…' : 'Không còn hội thoại để liên kết'}</option>
                  {availableSources.map(source => <option key={source.id} value={source.id}>{source.title}</option>)}
                </select>
                <button onClick={() => void addLink()} disabled={saving || !sourceConversationId} className="inline-flex items-center gap-1 rounded-lg bg-cobalt-600 px-3 py-2 text-xs font-bold text-white disabled:cursor-not-allowed disabled:opacity-50"><Link2 className="h-3.5 w-3.5" />Liên kết</button>
              </div>
              <div className="space-y-2">
                {settings.links.length === 0 && <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500 dark:bg-slate-900/50">Chưa có hội thoại liên kết.</p>}
                {settings.links.map(link => <div key={link.id} className="flex items-center gap-2 rounded-xl border border-slate-200 p-3 dark:border-slate-800"><Link2 className="h-4 w-4 shrink-0 text-cobalt-500" /><div className="min-w-0 flex-1"><p className="truncate text-xs font-bold text-slate-800 dark:text-slate-100">{link.sourceConversationTitle}</p><p className="mt-0.5 text-[11px] text-slate-500">Snapshot: {formatDate(link.sourceSnapshotAt)}</p></div><button onClick={() => void refreshLink(link)} disabled={saving} title="Làm mới snapshot" className="rounded-md p-1.5 text-slate-400 hover:bg-slate-100 hover:text-cobalt-600 disabled:opacity-50 dark:hover:bg-slate-800"><RefreshCw className="h-3.5 w-3.5" /></button><button onClick={() => void removeLink(link.id)} disabled={saving} title="Bỏ liên kết" className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:opacity-50 dark:hover:bg-rose-950/30"><Trash2 className="h-3.5 w-3.5" /></button></div>)}
              </div>
            </section>

            <section className="space-y-3 border-t border-slate-100 pt-5 dark:border-slate-800">
              <div><h4 className="text-xs font-bold uppercase tracking-wider text-slate-500">Ghi nhớ được xác nhận</h4><p className="mt-1 text-xs text-slate-500">Lưu nhu cầu, quyết định hoặc cách làm việc để AI dùng khi phù hợp.</p></div>
              <div className="space-y-2 rounded-xl border border-slate-200 p-3 dark:border-slate-800">
                <input value={memoryKey} onChange={event => setMemoryKey(event.target.value.toLowerCase())} maxLength={100} placeholder="key, ví dụ: preferred-language" className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs dark:border-slate-700 dark:bg-slate-900" />
                <textarea value={memoryContent} onChange={event => setMemoryContent(event.target.value)} maxLength={4000} rows={3} placeholder="Thông tin cần ghi nhớ…" className="w-full resize-y rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs leading-relaxed dark:border-slate-700 dark:bg-slate-900" />
                <div className="flex items-center justify-between gap-2"><select value={memoryScope} onChange={event => setMemoryScope(event.target.value as MemoryScope)} className="rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs dark:border-slate-700 dark:bg-slate-900"><option value="USER">Dùng cho các phiên của tôi</option><option value="CONVERSATION">Chỉ phiên hiện tại</option></select><button onClick={() => void saveMemory()} disabled={saving || !memoryKey.trim() || !memoryContent.trim()} className="inline-flex items-center gap-1 rounded-lg border border-cobalt-200 bg-cobalt-50 px-3 py-1.5 text-xs font-bold text-cobalt-700 disabled:opacity-50 dark:border-cyan-800 dark:bg-cyan-950/30 dark:text-cyan-300"><Plus className="h-3.5 w-3.5" />Lưu ghi nhớ</button></div>
              </div>
              <div className="space-y-2">
                {confirmedMemories.map(memory => <div key={memory.key} className="flex items-start gap-2 rounded-xl bg-slate-50 p-3 dark:bg-slate-900/60"><Brain className="mt-0.5 h-3.5 w-3.5 shrink-0 text-cobalt-500" /><div className="min-w-0 flex-1"><div className="flex flex-wrap gap-x-2"><span className="text-xs font-bold text-slate-800 dark:text-slate-100">{memory.key}</span><span className="text-[11px] text-slate-500">{memory.scope === 'CONVERSATION' ? 'Phiên hiện tại' : 'Tất cả phiên'}</span></div><p className="mt-1 whitespace-pre-wrap text-xs leading-relaxed text-slate-600 dark:text-slate-300">{memory.content}</p></div><button onClick={() => void removeMemory(memory.key)} disabled={saving} title="Xóa ghi nhớ" className="rounded-md p-1.5 text-slate-400 hover:bg-rose-100 hover:text-rose-600 disabled:opacity-50 dark:hover:bg-rose-950/40"><Trash2 className="h-3.5 w-3.5" /></button></div>)}
                {confirmedMemories.length === 0 && <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500 dark:bg-slate-900/50">Chưa có ghi nhớ được xác nhận.</p>}
              </div>
            </section>
          </>}
        </div>
      </div>
    </div>
  );
};
