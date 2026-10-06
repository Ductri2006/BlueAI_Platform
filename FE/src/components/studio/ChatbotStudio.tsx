import React from 'react';
import {
  AlertTriangle, ArrowDown, ArrowUp, Bot, Boxes, Braces, BrainCircuit, CheckCircle2,
  ChevronDown, ChevronRight, CircleDot, ClipboardPaste, Code2, Copy, CopyPlus, FlaskConical, FolderPlus,
  GitBranch, LoaderCircle, MessageSquareText, MoreVertical, MoveRight, Plus,
  Rocket, Save, Search, ShieldCheck, Sparkles, Trash2, Upload, X,
} from 'lucide-react';
import {
  blueAiApi,
  type StudioBotConfiguration,
  type StudioCustomization,
  type StudioDialogNode,
  type StudioEntity,
  type StudioIntent,
} from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { TryItOutPanel } from './TryItOutPanel';
import { analyzeDialogNodes } from './dialogValidation';
import { deleteDialogNode, planDialogInsertion, renameDialogNode, summarizeDialogStep, uniqueDialogName } from './dialogNodeOperations';
import { beginStudioOperation, confirmStudioExit, installStudioExitProtection } from './studioDraftProtection';
import { DialogNodeNameField } from './DialogNodeNameField';
import { DialogContextPolicy, DialogValidationNotice } from './DialogContextPolicy';
import { StudioDiscardDialog, useStudioConfirmation } from './StudioDiscardDialog';
import { StudioTemplateSelector, type StudioCreationTemplate } from './StudioTemplateSelector';
import {
  compactDraftEntries,
  mergeEntityImports,
  mergeIntentImports,
  parseEntityImport,
  parseIntentImport,
  splitCommaDraft,
  splitMultilineDraft,
  validateDialogNodes,
} from './studioDrafts';

type StudioTab = 'blueai' | 'intents' | 'entities' | 'dialog' | 'integrate';

const inputClass = 'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 shadow-sm placeholder:text-slate-400 focus:border-cobalt-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100';
const labelClass = 'mb-1.5 block text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400';

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Đã xảy ra lỗi khi kết nối BlueAI Custom.';
}

function copyConfiguration(configuration: StudioBotConfiguration): StudioBotConfiguration {
  return JSON.parse(JSON.stringify(configuration)) as StudioBotConfiguration;
}

export const ChatbotStudio: React.FC = () => {
  const { showToast } = useAppStore();
  const [bots, setBots] = React.useState<StudioBotConfiguration['bot'][]>([]);
  const [configuration, setConfiguration] = React.useState<StudioBotConfiguration>();
  const [activeTab, setActiveTab] = React.useState<StudioTab>('blueai');
  const [isLoading, setIsLoading] = React.useState(true);
  const [isSaving, setIsSaving] = React.useState(false);
  const [isPublishing, setIsPublishing] = React.useState(false);
  const [isActivating, setIsActivating] = React.useState(false);
  const [isDirty, setIsDirty] = React.useState(false);
  const [isPreviewOpen, setIsPreviewOpen] = React.useState(false);
  const [isCreateOpen, setIsCreateOpen] = React.useState(false);
  const [newBotName, setNewBotName] = React.useState('Trợ lý BlueAI');
  const [newBotTemplate, setNewBotTemplate] = React.useState<StudioCreationTemplate>('STARTER');
  const [error, setError] = React.useState('');
  const studioRef = React.useRef<HTMLElement>(null);
  const draftGuard = React.useRef({ dirty: false, busy: false });
  const discard = useStudioConfirmation();
  const studioLifecycle = React.useRef({ active: true, generation: 0 });
  React.useEffect(() => {
    studioLifecycle.current.active = true;
    return () => { studioLifecycle.current.active = false; studioLifecycle.current.generation += 1; };
  }, []);
  const isBusy = isLoading || isSaving || isPublishing || isActivating;
  const notifyBusy = () => showToast('Đang xử lý cấu hình. Vui lòng chờ hoàn tất trước khi rời hoặc đổi cấu hình.', 'warning');
  React.useEffect(() => installStudioExitProtection(() => studioRef.current, draftGuard.current, notifyBusy, discard.confirm), [showToast, discard.confirm]);

  const loadBot = React.useCallback(async (botId: string) => {
    if (!beginStudioOperation(draftGuard.current)) return;
    const requestGeneration = ++studioLifecycle.current.generation;
    const isCurrentRequest = () => studioLifecycle.current.active && studioLifecycle.current.generation === requestGeneration;
    setIsLoading(true);
    setError('');
    try {
      const result = await blueAiApi.getStudioBot(botId);
      if (!isCurrentRequest()) return;
      setConfiguration(copyConfiguration(result));
      setIsPreviewOpen(false);
      draftGuard.current.dirty = false;
      setIsDirty(false);
    } catch (requestError) {
      if (isCurrentRequest()) setError(errorMessage(requestError));
    } finally {
      if (isCurrentRequest()) {
        draftGuard.current.busy = false;
        setIsLoading(false);
      }
    }
  }, []);

  React.useEffect(() => {
    let active = true;
    void blueAiApi.listStudioBots()
      .then(result => {
        if (!active) return;
        setBots(result);
        if (result[0]) return loadBot(result[0].id);
        setIsLoading(false);
      })
      .catch(requestError => {
        if (!active) return;
        setError(errorMessage(requestError));
        setIsLoading(false);
      });
    return () => { active = false; };
  }, [loadBot]);

  const updateConfiguration = (updater: (current: StudioBotConfiguration) => StudioBotConfiguration) => {
    if (draftGuard.current.busy) return;
    setConfiguration(current => current ? updater(copyConfiguration(current)) : current);
    draftGuard.current.dirty = true;
    setIsDirty(true);
  };

  const switchBot = async (botId: string) => {
    if (botId === configuration?.bot.id) return;
    if (await confirmStudioExit(draftGuard.current, discard.confirm, notifyBusy)) void loadBot(botId);
  };

  const createBot = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!newBotName.trim()) return;
    if (!await confirmStudioExit(draftGuard.current, discard.confirm, notifyBusy) || !beginStudioOperation(draftGuard.current)) return;
    setIsLoading(true);
    setError('');
    try {
      const result = await blueAiApi.createStudioBot({
        name: newBotName.trim(),
        description: newBotTemplate === 'SALES_DEMO'
          ? 'DEMO: 6 kịch bản sales, dữ liệu minh họa; không phải báo giá hay đơn hàng thật.'
          : 'Trải nghiệm BlueAI tùy chỉnh cho nghiệp vụ của doanh nghiệp.',
        locale: 'vi-VN',
        timezone: 'Asia/Ho_Chi_Minh',
        includeStarterTemplate: true,
        template: newBotTemplate,
      });
      setBots(current => [result.bot, ...current]);
      setConfiguration(copyConfiguration(result));
      setIsPreviewOpen(false);
      setIsCreateOpen(false);
      if (newBotTemplate === 'SALES_DEMO') setActiveTab('dialog');
      draftGuard.current.dirty = false;
      setIsDirty(false);
      showToast(newBotTemplate === 'SALES_DEMO'
        ? 'Đã tạo bản nháp demo 6 kịch bản. Core/LLM đã tắt; bot đang dùng không thay đổi.'
        : 'Đã tạo cấu hình BlueAI với bộ intent và luồng mẫu.', 'success');
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      draftGuard.current.busy = false;
      setIsLoading(false);
    }
  };

  const save = async (announce = true): Promise<StudioBotConfiguration | undefined> => {
    if (!configuration || draftGuard.current.busy) return undefined;
    const intents = configuration.intents.map(intent => ({
      ...intent,
      examples: compactDraftEntries(intent.examples),
    }));
    const entities = configuration.entities.map(entity => ({
      ...entity,
      values: entity.values.map(value => ({
        ...value,
        synonyms: compactDraftEntries(value.synonyms),
      })),
    }));
    const invalidIntent = intents.find(intent => !intent.name.trim() || intent.examples.length < 2);
    if (invalidIntent) {
      setActiveTab('intents');
      setError(`Intent #${invalidIntent.name || '(chưa đặt tên)'} cần có tên và ít nhất 2 câu người dùng mẫu.`);
      return undefined;
    }
    const invalidEntity = entities.find(entity => (
      !entity.name.trim()
      || entity.values.length === 0
      || entity.values.some(value => !value.value.trim())
    ));
    if (invalidEntity) {
      setActiveTab('entities');
      setError(`Entity @${invalidEntity.name || '(chưa đặt tên)'} cần có tên và ít nhất một giá trị không để trống.`);
      return undefined;
    }
    const dialogErrors = validateDialogNodes(
      configuration.dialogNodes,
      intents.map(intent => intent.name),
      entities.map(entity => entity.name),
      entities,
    );
    if (dialogErrors.length > 0) {
      setActiveTab('dialog');
      setError(`Kiểm tra luồng hội thoại trước khi lưu (${dialogErrors.length} lỗi): ${dialogErrors.join('\n')}`);
      return undefined;
    }
    if (!beginStudioOperation(draftGuard.current)) return undefined;
    setIsSaving(true);
    setError('');
    try {
      const saved = await blueAiApi.saveStudioBotConfiguration(configuration.bot.id, {
        expectedRevision: configuration.bot.draftRevision,
        name: configuration.bot.name,
        description: configuration.bot.description,
        locale: configuration.bot.locale,
        timezone: configuration.bot.timezone,
        blueAiCoreEnabled: configuration.bot.blueAiCoreEnabled !== false,
        customInstructions: configuration.bot.customInstructions || '',
        customization: configuration.customization || configuration.bot.customization || {},
        intents,
        entities,
        dialogNodes: configuration.dialogNodes,
      });
      setConfiguration(copyConfiguration(saved));
      setBots(current => current.map(bot => bot.id === saved.bot.id ? saved.bot : bot));
      draftGuard.current.dirty = false;
      setIsDirty(false);
      if (announce) showToast(`Đã lưu bản nháp r${saved.bot.draftRevision}.`, 'success');
      return saved;
    } catch (requestError) {
      setError(errorMessage(requestError));
      return undefined;
    } finally {
      draftGuard.current.busy = false;
      setIsSaving(false);
    }
  };

  const openPreview = async () => {
    if (draftGuard.current.busy) return;
    const saved = isDirty ? await save(false) : configuration;
    if (saved) setIsPreviewOpen(true);
  };

  const publish = async () => {
    if (draftGuard.current.busy) return;
    const saved = isDirty ? await save(false) : configuration;
    if (!saved || !beginStudioOperation(draftGuard.current)) return;
    setIsPublishing(true);
    setError('');
    try {
      const release = await blueAiApi.publishStudioBot(saved.bot.id);
      const refreshed = await blueAiApi.getStudioBot(saved.bot.id);
      setConfiguration(copyConfiguration(refreshed));
      setBots(current => current.map(bot => bot.id === refreshed.bot.id ? refreshed.bot : bot));
      setActiveTab('integrate');
      showToast(`Đã phát hành release r${release.revision}.`, 'success');
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      draftGuard.current.busy = false;
      setIsPublishing(false);
    }
  };

  const activate = async () => {
    if (!configuration || configuration.bot.status !== 'PUBLISHED' || draftGuard.current.busy) return;
    const saved = isDirty ? await save(false) : configuration;
    if (!saved || !beginStudioOperation(draftGuard.current)) return;
    setIsActivating(true);
    setError('');
    try {
      const activated = await blueAiApi.activateStudioBot(saved.bot.id);
      setConfiguration(copyConfiguration(activated));
      setBots(current => current.map(bot => ({ ...bot, active: bot.id === activated.bot.id })));
      showToast('Đã áp dụng cấu hình cho màn Hỏi BlueAI.', 'success');
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      draftGuard.current.busy = false;
      setIsActivating(false);
    }
  };

  if (isLoading && !configuration) {
    return <div className="flex flex-1 items-center justify-center"><LoaderCircle className="h-7 w-7 animate-spin text-cobalt-600" aria-label="Đang tải BlueAI Custom" /></div>;
  }

  if (!configuration) {
    return (
      <div className="flex flex-1 items-center justify-center overflow-y-auto bg-ambient-grid p-6">
        <div className="w-full max-w-lg rounded-3xl border border-slate-200 bg-white p-8 text-center shadow-xl dark:border-slate-800 dark:bg-slate-950">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-cobalt-50 text-cobalt-600 dark:bg-cobalt-950 dark:text-cobalt-300"><Bot className="h-7 w-7" /></div>
          <h1 className="mt-5 text-2xl font-black text-slate-900 dark:text-white">Tạo cấu hình BlueAI đầu tiên</h1>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-500">BlueAI sẽ tạo sẵn intent chào hỏi, giờ làm việc, entity chi nhánh và luồng fallback để bạn có thể thử ngay.</p>
          {error && <p className="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">{error}</p>}
          <form onSubmit={createBot} className="mt-6 space-y-3">
            <StudioTemplateSelector value={newBotTemplate} onChange={setNewBotTemplate} />
            <div className="flex gap-2">
              <input value={newBotName} onChange={event => setNewBotName(event.target.value)} className={inputClass} aria-label="Tên cấu hình" />
              <button type="submit" className="shrink-0 rounded-xl bg-cobalt-600 px-5 py-2 text-sm font-bold text-white hover:bg-cobalt-700">Tạo cấu hình</button>
            </div>
          </form>
        </div>
      </div>
    );
  }

  const tabs: { id: StudioTab; label: string; icon: React.ComponentType<{ className?: string }>; count?: number }[] = [
    { id: 'blueai', label: 'BlueAI chính', icon: BrainCircuit },
    { id: 'intents', label: 'Mục đích (intent)', icon: MessageSquareText, count: configuration.intents.length },
    { id: 'entities', label: 'Dữ kiện (entity)', icon: Braces, count: configuration.entities.length },
    { id: 'dialog', label: 'Luồng hội thoại', icon: GitBranch, count: configuration.dialogNodes.length },
    { id: 'integrate', label: 'Tích hợp', icon: Code2 },
  ];

  return (
    <section ref={studioRef} aria-busy={isBusy} className="relative flex min-w-0 flex-1 flex-col overflow-hidden bg-slate-50 dark:bg-[#060B14]">
      <header className="border-b border-slate-200 bg-white px-4 py-3 dark:border-slate-800 dark:bg-[#0B1324] sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-cobalt-600 to-cyan-500 text-white shadow-lg shadow-cobalt-500/20"><Bot className="h-5 w-5" /></div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <select
                  value={configuration.bot.id}
                  onChange={event => void switchBot(event.target.value)}
                  disabled={isBusy}
                  className="max-w-64 truncate bg-transparent text-base font-black text-slate-900 outline-none dark:text-white"
                  aria-label="Chọn cấu hình BlueAI"
                >
                  {bots.map(botItem => <option key={botItem.id} value={botItem.id}>{botItem.name}</option>)}
                </select>
                <span className={`rounded-full px-2 py-0.5 text-[10px] font-black uppercase ${configuration.bot.status === 'PUBLISHED' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'}`}>{configuration.bot.status}</span>
                {configuration.bot.active && <span className="rounded-full bg-cyan-100 px-2 py-0.5 text-[10px] font-black text-cyan-700 dark:bg-cyan-950 dark:text-cyan-300">Đang dùng cho Hỏi BlueAI</span>}
              </div>
              <p className="text-[11px] text-slate-500" role="status">Bản nháp r{configuration.bot.draftRevision}{isBusy ? ' • Đang xử lý, vui lòng chờ' : isDirty ? ' • Có thay đổi chưa lưu' : ' • Đã đồng bộ'}</p>
            </div>
            <button type="button" disabled={isBusy} onClick={() => setIsCreateOpen(true)} className="rounded-xl border border-slate-200 p-2 text-slate-500 hover:border-cobalt-300 hover:text-cobalt-600 disabled:opacity-40 dark:border-slate-700" title="Tạo cấu hình BlueAI khác" aria-label="Tạo cấu hình BlueAI khác"><Plus className="h-4 w-4" /></button>
          </div>
          <fieldset disabled={isBusy} className="flex flex-wrap items-center gap-2">
            <button type="button" onClick={() => void openPreview()} disabled={isSaving} className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-bold text-slate-700 hover:border-cobalt-300 hover:text-cobalt-700 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"><FlaskConical className="h-4 w-4" />Thử BlueAI</button>
            <button type="button" onClick={() => void save()} disabled={!isDirty || isSaving} className="inline-flex items-center gap-2 rounded-xl border border-cobalt-200 bg-cobalt-50 px-3.5 py-2 text-xs font-bold text-cobalt-700 hover:bg-cobalt-100 disabled:cursor-not-allowed disabled:opacity-45 dark:border-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200">{isSaving ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}Lưu nháp</button>
            <button type="button" onClick={() => void publish()} disabled={isPublishing || isSaving} className="inline-flex items-center gap-2 rounded-xl bg-cobalt-600 px-3.5 py-2 text-xs font-bold text-white shadow-md shadow-cobalt-500/20 hover:bg-cobalt-700 disabled:opacity-50">{isPublishing ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Rocket className="h-4 w-4" />}Phát hành</button>
            {configuration.bot.status === 'PUBLISHED' && !configuration.bot.active && <button type="button" onClick={() => void activate()} disabled={isActivating || isSaving} className="inline-flex items-center gap-2 rounded-xl border border-cyan-200 bg-cyan-50 px-3.5 py-2 text-xs font-bold text-cyan-800 hover:bg-cyan-100 disabled:opacity-50 dark:border-cyan-800 dark:bg-cyan-950 dark:text-cyan-200">{isActivating ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}Áp dụng cho Hỏi BlueAI</button>}
          </fieldset>
        </div>
      </header>

      <nav className="flex shrink-0 gap-1 overflow-x-auto border-b border-slate-200 bg-white px-4 dark:border-slate-800 dark:bg-[#0B1324] sm:px-6" aria-label="Các khu vực BlueAI Custom">
        {tabs.map(tab => {
          const Icon = tab.icon;
          return <button key={tab.id} type="button" disabled={isBusy} onClick={() => setActiveTab(tab.id)} className={`flex items-center gap-2 border-b-2 px-3 py-3 text-xs font-bold transition-colors ${activeTab === tab.id ? 'border-cobalt-600 text-cobalt-700 dark:text-cobalt-300' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'}`}><Icon className="h-4 w-4" />{tab.label}{tab.count !== undefined && <span className="rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] dark:bg-slate-800">{tab.count}</span>}</button>;
        })}
      </nav>

      {error && <div className="mx-4 mt-4 flex items-start justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300 sm:mx-6"><span className="whitespace-pre-line">{error}</span><button type="button" onClick={() => setError('')} aria-label="Đóng lỗi"><X className="h-4 w-4" /></button></div>}

      <div className="flex-1 overflow-y-auto p-4 sm:p-6">
        <fieldset disabled={isBusy} className="min-w-0">
        {activeTab === 'blueai' && <BlueAiCorePanel configuration={configuration} onChange={bot => updateConfiguration(current => ({ ...current, bot }))} onCustomizationChange={customization => updateConfiguration(current => ({ ...current, customization }))} />}
        {activeTab === 'intents' && <IntentsEditor key={configuration.bot.id} intents={configuration.intents} onChange={intents => updateConfiguration(current => ({ ...current, intents }))} />}
        {activeTab === 'entities' && <EntitiesEditor key={configuration.bot.id} entities={configuration.entities} onChange={entities => updateConfiguration(current => ({ ...current, entities }))} />}
        {activeTab === 'dialog' && <DialogEditor key={configuration.bot.id} nodes={configuration.dialogNodes} intents={configuration.intents} entities={configuration.entities} onChange={dialogNodes => updateConfiguration(current => ({ ...current, dialogNodes }))} onPreview={() => void openPreview()} />}
        {activeTab === 'integrate' && <IntegrationPanel configuration={configuration} />}
        </fieldset>
      </div>

      {discard.message && <StudioDiscardDialog message={discard.message} onDecision={discard.decide} />}

      {isPreviewOpen && <TryItOutPanel
        key={configuration.bot.id}
        botId={configuration.bot.id}
        botName={configuration.bot.name}
        welcomeMessage={configuration.dialogNodes.find(node => node.type === 'WELCOME')?.responseText}
        blueAiCoreEnabled={configuration.bot.blueAiCoreEnabled !== false}
        customization={configuration.customization || configuration.bot.customization}
        onClose={() => setIsPreviewOpen(false)}
      />}

      {isCreateOpen && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-sm">
          <form onSubmit={createBot} className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
            <fieldset disabled={isBusy}>
            <div className="flex items-center justify-between"><h2 className="text-lg font-black">Tạo cấu hình BlueAI mới</h2><button type="button" onClick={() => setIsCreateOpen(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><X className="h-4 w-4" /></button></div>
            <p className="mt-1 text-sm text-slate-500">Khởi tạo bản cấu hình dùng chung cho BlueAI với dữ liệu mẫu tiếng Việt.</p>
            <label className="mt-5 block"><span className={labelClass}>Tên cấu hình</span><input value={newBotName} onChange={event => setNewBotName(event.target.value)} className={inputClass} autoFocus /></label>
            <StudioTemplateSelector value={newBotTemplate} onChange={setNewBotTemplate} />
            <div className="mt-5 flex justify-end gap-2"><button type="button" onClick={() => setIsCreateOpen(false)} className="rounded-xl px-4 py-2 text-sm font-bold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">Huỷ</button><button type="submit" className="rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white hover:bg-cobalt-700">Tạo với dữ liệu mẫu</button></div>
            </fieldset>
          </form>
        </div>
      )}
    </section>
  );
};

function IntentsEditor({ intents, onChange }: { intents: StudioIntent[]; onChange: (value: StudioIntent[]) => void }) {
  const [selected, setSelected] = React.useState(0);
  const [isImportOpen, setIsImportOpen] = React.useState(false);
  const [importText, setImportText] = React.useState('');
  React.useEffect(() => { if (selected >= intents.length) setSelected(Math.max(0, intents.length - 1)); }, [intents.length, selected]);
  const current = intents[selected];
  const update = (patch: Partial<StudioIntent>) => onChange(intents.map((intent, index) => index === selected ? { ...intent, ...patch } : intent));
  const add = () => { onChange([...intents, { name: `intent_${intents.length + 1}`, description: '', examples: ['Ví dụ câu hỏi'] }]); setSelected(intents.length); };
  const remove = () => {
    if (!current || !window.confirm(`Xoá intent “${current.name}”? Các câu mẫu trong intent này sẽ bị xoá khỏi bản nháp.`)) return;
    onChange(intents.filter((_, index) => index !== selected));
  };
  const parsedImport = React.useMemo(() => parseIntentImport(importText), [importText]);
  const importPreview = React.useMemo(() => mergeIntentImports(intents, parsedImport.items), [intents, parsedImport.items]);
  const applyImport = () => {
    if (parsedImport.items.length === 0) return;
    const merged = mergeIntentImports(intents, parsedImport.items);
    onChange(merged.items);
    const selectedName = parsedImport.items[0]?.name.toLocaleLowerCase();
    const importedIndex = merged.items.findIndex(item => item.name.toLocaleLowerCase() === selectedName);
    if (importedIndex >= 0) setSelected(importedIndex);
    setImportText('');
    setIsImportOpen(false);
  };

  return <EditorLayout
    title="Intents" description="Nhóm các cách diễn đạt có cùng mục đích của người dùng. Nên có ít nhất 5 ví dụ đa dạng cho mỗi intent."
    items={intents.map(intent => ({ title: `#${intent.name}`, subtitle: `${compactDraftEntries(intent.examples).length} câu mẫu` }))}
    selected={selected} onSelect={setSelected} onAdd={add} addLabel="Thêm intent" onImport={() => setIsImportOpen(true)} importLabel="Nhập mẫu câu"
  >
    {current ? <div className="space-y-5">
      <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cobalt-600">Intent đang chọn</p><h2 className="mt-1 text-xl font-black">#{current.name}</h2></div><button type="button" onClick={remove} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40" aria-label="Xoá intent"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 md:grid-cols-2"><label><span className={labelClass}>Tên intent</span><input value={current.name} onChange={event => update({ name: event.target.value.replace(/\s+/g, '_') })} className={inputClass} /></label><label><span className={labelClass}>Mô tả</span><input value={current.description || ''} onChange={event => update({ description: event.target.value })} className={inputClass} /></label></div>
      <label className="block"><span className={labelClass}>Câu người dùng mẫu — mỗi dòng một câu</span><textarea value={current.examples.join('\n')} onChange={event => update({ examples: splitMultilineDraft(event.target.value) })} rows={12} className={`${inputClass} resize-y font-medium leading-7`} placeholder={'Xin chào\nChào BlueAI\nBạn có thể giúp gì?'} /></label>
    </div> : <EmptySelection label="intent" />}
    {isImportOpen && <ImportDialog
      title="Nhập mẫu câu cho intents"
      description="Mỗi dòng là một intent và một hoặc nhiều câu mẫu. Hệ thống sẽ gộp theo tên, không xoá dữ liệu đang có."
      value={importText}
      onChange={setImportText}
      placeholder={'intent,example\ngreeting,"Xin chào, BlueAI"\ngreeting,Hello\n\nHoặc dùng dấu | / tab để tách cột.'}
      errors={parsedImport.errors}
      preview={parsedImport.items.map(item => `#${item.name} · ${item.examples.length} câu mẫu`)}
      mergeSummary={`Sẽ thêm ${importPreview.added} intent mới, gộp vào ${importPreview.merged} intent hiện có.`}
      onClose={() => { setIsImportOpen(false); setImportText(''); }}
      onApply={applyImport}
      canApply={parsedImport.items.length > 0}
    />}
  </EditorLayout>;
}

function EntitiesEditor({ entities, onChange }: { entities: StudioEntity[]; onChange: (value: StudioEntity[]) => void }) {
  const [selected, setSelected] = React.useState(0);
  const [isImportOpen, setIsImportOpen] = React.useState(false);
  const [importText, setImportText] = React.useState('');
  const [valueSearch, setValueSearch] = React.useState('');
  const [valuePage, setValuePage] = React.useState(0);
  React.useEffect(() => { if (selected >= entities.length) setSelected(Math.max(0, entities.length - 1)); }, [entities.length, selected]);
  const current = entities[selected];
  const matchingValues = (current?.values || []).map((value, index) => ({ value, index })).filter(({ value }) =>
    `${value.value} ${value.synonyms.join(' ')}`.toLocaleLowerCase().includes(valueSearch.trim().toLocaleLowerCase()));
  const pageSize = 20;
  const pageCount = Math.max(1, Math.ceil(matchingValues.length / pageSize));
  const visibleValues = matchingValues.slice(Math.min(valuePage, pageCount - 1) * pageSize, (Math.min(valuePage, pageCount - 1) + 1) * pageSize);
  const update = (patch: Partial<StudioEntity>) => onChange(entities.map((entity, index) => index === selected ? { ...entity, ...patch } : entity));
  const add = () => { onChange([...entities, { name: `entity_${entities.length + 1}`, description: '', values: [{ value: 'Giá trị', synonyms: [] }] }]); setSelected(entities.length); setValueSearch(''); setValuePage(0); };
  const remove = () => {
    if (!current || !window.confirm(`Xoá entity “${current.name}”? Từ điển giá trị của entity này sẽ bị xoá khỏi bản nháp.`)) return;
    onChange(entities.filter((_, index) => index !== selected));
  };
  const parsedImport = React.useMemo(() => parseEntityImport(importText), [importText]);
  const importPreview = React.useMemo(() => mergeEntityImports(entities, parsedImport.items), [entities, parsedImport.items]);
  const applyImport = () => {
    if (parsedImport.items.length === 0) return;
    const merged = mergeEntityImports(entities, parsedImport.items);
    onChange(merged.items);
    const selectedName = parsedImport.items[0]?.name.toLocaleLowerCase();
    const importedIndex = merged.items.findIndex(item => item.name.toLocaleLowerCase() === selectedName);
    if (importedIndex >= 0) setSelected(importedIndex);
    setImportText('');
    setIsImportOpen(false);
  };

  return <EditorLayout
    title="Entities" description="Chuẩn hoá từ khoá nghiệp vụ thành giá trị chính tắc; synonym giúp nhận diện cách gọi địa phương hoặc tên viết tắt."
    items={entities.map(entity => ({ title: `@${entity.name}`, subtitle: `${entity.values.length} giá trị` }))}
    selected={selected} onSelect={index => { setSelected(index); setValueSearch(''); setValuePage(0); }} onAdd={add} addLabel="Thêm entity" onImport={() => setIsImportOpen(true)} importLabel="Nhập từ điển"
  >
    {current ? <div className="space-y-5">
      <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cyan-600">Entity đang chọn</p><h2 className="mt-1 text-xl font-black">@{current.name}</h2></div><button type="button" onClick={remove} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40" aria-label="Xoá entity"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 md:grid-cols-2"><label><span className={labelClass}>Tên entity</span><input value={current.name} onChange={event => update({ name: event.target.value.replace(/\s+/g, '_') })} className={inputClass} /></label><label><span className={labelClass}>Mô tả</span><input value={current.description || ''} onChange={event => update({ description: event.target.value })} className={inputClass} /></label></div>
      <div><div className="mb-2 flex items-center justify-between"><span className={labelClass}>Từ điển giá trị và từ đồng nghĩa · {current.values.length}</span><button type="button" onClick={() => { update({ values: [...current.values, { value: 'Giá trị mới', synonyms: [] }] }); setValueSearch(''); setValuePage(Math.floor(current.values.length / pageSize)); }} className="inline-flex items-center gap-1 text-xs font-bold text-cobalt-600"><Plus className="h-3.5 w-3.5" />Thêm giá trị</button></div>
        <label className="relative mb-3 block"><Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" /><input value={valueSearch} onChange={event => { setValueSearch(event.target.value); setValuePage(0); }} className={`${inputClass} pl-9`} placeholder="Tìm giá trị hoặc từ đồng nghĩa" aria-label="Tìm giá trị entity" /></label>
        <p className="mb-2 text-xs text-slate-500" aria-live="polite">Hiển thị {matchingValues.length} / {current.values.length} giá trị</p>
        <div className="space-y-2">{visibleValues.map(({ value: entityValue, index: valueIndex }) => <div key={valueIndex} className="grid gap-2 rounded-xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950 md:grid-cols-[0.8fr_1.5fr_auto]">
          <input value={entityValue.value} onChange={event => update({ values: current.values.map((value, index) => index === valueIndex ? { ...value, value: event.target.value } : value) })} className={inputClass} aria-label={`Giá trị entity ${valueIndex + 1}`} />
          <input value={entityValue.synonyms.join(', ')} onChange={event => update({ values: current.values.map((value, index) => index === valueIndex ? { ...value, synonyms: splitCommaDraft(event.target.value) } : value) })} className={inputClass} placeholder="Từ đồng nghĩa, cách viết tắt" aria-label={`Từ đồng nghĩa ${valueIndex + 1}`} />
          <button type="button" disabled={current.values.length === 1} onClick={() => update({ values: current.values.filter((_, index) => index !== valueIndex) })} className="rounded-lg p-2 text-slate-400 hover:text-rose-600 disabled:opacity-30" aria-label={`Xoá giá trị ${valueIndex + 1}`}><Trash2 className="h-4 w-4" /></button>
        </div>)}</div>
        {matchingValues.length === 0 && <p className="rounded-xl border border-dashed border-slate-200 px-3 py-6 text-center text-sm text-slate-500">Không tìm thấy giá trị phù hợp.</p>}
        {pageCount > 1 && <div className="mt-3 flex items-center justify-between gap-3 text-xs text-slate-500"><span>Trang {Math.min(valuePage, pageCount - 1) + 1} / {pageCount}</span><div className="flex gap-2"><button type="button" disabled={valuePage === 0} onClick={() => setValuePage(page => page - 1)} className="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">Trước</button><button type="button" disabled={valuePage >= pageCount - 1} onClick={() => setValuePage(page => page + 1)} className="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">Sau</button></div></div>}
      </div>
    </div> : <EmptySelection label="entity" />}
    {isImportOpen && <ImportDialog
      title="Nhập từ điển cho entities"
      description="Mỗi dòng gồm entity, giá trị chính tắc và các từ đồng nghĩa. Các dòng trùng entity/value sẽ được gộp, dữ liệu cũ vẫn giữ nguyên."
      value={importText}
      onChange={setImportText}
      placeholder={'entity,value,synonym1,synonym2\ncity,Hồ Chí Minh,TP HCM,Sài Gòn\ncity,Hà Nội,Thủ đô\n\nHoặc dùng dấu | / tab để tách cột.'}
      errors={parsedImport.errors}
      preview={parsedImport.items.map(item => `@${item.name} · ${item.values.length} giá trị`)}
      mergeSummary={`Sẽ thêm ${importPreview.added} entity mới, gộp vào ${importPreview.merged} entity hiện có.`}
      onClose={() => { setIsImportOpen(false); setImportText(''); }}
      onApply={applyImport}
      canApply={parsedImport.items.length > 0}
    />}
  </EditorLayout>;
}

interface RouteComposerDraft {
  routeName: string;
  matchIntent: string;
  parentNode: string;
  destinationMode: 'new' | 'existing';
  destinationName: string;
  destinationNode: string;
  destinationResponse: string;
}

function RouteComposer({ nodes, intents, defaultParent, onClose, onCreate }: { nodes: StudioDialogNode[]; intents: StudioIntent[]; defaultParent: string; onClose: () => void; onCreate: (draft: RouteComposerDraft) => void }) {
  const [draft, setDraft] = React.useState<RouteComposerDraft>({
    routeName: 'Nhánh mới',
    matchIntent: intents[0]?.name || '',
    parentNode: defaultParent,
    destinationMode: 'new',
    destinationName: 'Bước tiếp theo',
    destinationNode: nodes.find(node => node.type !== 'WELCOME')?.name || '',
    destinationResponse: 'Nội dung phản hồi',
  });
  const [error, setError] = React.useState('');
  const availableParents = nodes.filter(node => node.type === 'FOLDER');
  const availableTargets = nodes.filter(node => node.type !== 'WELCOME');
  const update = (patch: Partial<RouteComposerDraft>) => setDraft(current => ({ ...current, ...patch }));
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!draft.routeName.trim()) { setError('Route cần có tên để dễ tìm trong cây Dialog.'); return; }
    if (!draft.matchIntent) { setError('Hãy chọn intent để route biết khi nào được kích hoạt.'); return; }
    if (draft.destinationMode === 'new' && !draft.destinationName.trim()) { setError('Hãy đặt tên cho bước đích mới.'); return; }
    if (draft.destinationMode === 'new' && !draft.destinationResponse.trim()) { setError('Bước đích cần có lời đáp trước khi chờ người dùng.'); return; }
    if (draft.destinationMode === 'existing' && !availableTargets.some(node => node.name === draft.destinationNode)) { setError('Hãy chọn một bước đích có sẵn.'); return; }
    onCreate(draft);
  };

  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-label="Tạo nhánh và bước đích">
    <form onSubmit={submit} className="max-h-[min(760px,calc(100vh-2rem))] w-full max-w-2xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
      <div className="flex items-start justify-between gap-4"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cobalt-600">Liên kết hội thoại</p><h2 className="mt-1 text-xl font-black">Tạo nhánh và bước trả lời cùng lúc</h2><p className="mt-1 text-sm leading-5 text-slate-500">Khi khách có mục đích đã chọn, nhánh sẽ chuyển ngay đến bước đích. Chọn bước có sẵn hoặc tạo bước trả lời mới.</p></div><button type="button" onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="Đóng trình liên kết hội thoại"><X className="h-4 w-4" /></button></div>
      <div className="mt-5 grid gap-4 md:grid-cols-2">
        <label><span className={labelClass}>Tên nhánh</span><input maxLength={120} value={draft.routeName} onChange={event => update({ routeName: event.target.value })} className={inputClass} autoFocus placeholder="Ví dụ: Hỏi giá cước" /></label>
        <SearchableSelect label="Khách muốn làm gì (intent)" value={draft.matchIntent} onChange={matchIntent => update({ matchIntent })} emptyLabel="Chọn mục đích" options={intents.map(intent => ({ value: intent.name, label: `#${intent.name}` }))} />
        <SearchableSelect label="Bước cha" value={draft.parentNode} onChange={parentNode => update({ parentNode })} emptyLabel="Cấp gốc" options={availableParents.map(node => ({ value: node.name, label: node.name }))} />
      </div>
      <div className="mt-5 rounded-2xl border border-cobalt-100 bg-cobalt-50/60 p-4 dark:border-cobalt-900 dark:bg-cobalt-950/30"><p className="text-xs font-black text-cobalt-800 dark:text-cobalt-200">Bước tiếp theo khi mục đích khớp</p><div className="mt-3 grid grid-cols-2 gap-2"><button type="button" onClick={() => update({ destinationMode: 'new' })} className={`rounded-xl border px-3 py-2 text-xs font-bold ${draft.destinationMode === 'new' ? 'border-cobalt-500 bg-white text-cobalt-700 shadow-sm dark:bg-slate-900 dark:text-cobalt-200' : 'border-transparent text-slate-500 hover:bg-white/60'}`}>Tạo bước mới</button><button type="button" onClick={() => update({ destinationMode: 'existing' })} className={`rounded-xl border px-3 py-2 text-xs font-bold ${draft.destinationMode === 'existing' ? 'border-cobalt-500 bg-white text-cobalt-700 shadow-sm dark:bg-slate-900 dark:text-cobalt-200' : 'border-transparent text-slate-500 hover:bg-white/60'}`}>Chọn bước có sẵn</button></div>
        {draft.destinationMode === 'new' ? <div className="mt-3 grid gap-3 md:grid-cols-2"><label><span className={labelClass}>Tên bước đích</span><input maxLength={120} value={draft.destinationName} onChange={event => update({ destinationName: event.target.value })} className={inputClass} placeholder="Ví dụ: Trả lời giá cước" /></label><label><span className={labelClass}>Nội dung trả lời</span><textarea maxLength={4000} value={draft.destinationResponse} onChange={event => update({ destinationResponse: event.target.value })} rows={2} className={`${inputClass} resize-y`} placeholder="Nội dung BlueAI trả lời" /></label></div> : <div className="mt-3"><SearchableSelect label="Bước đích có sẵn" value={draft.destinationNode} onChange={destinationNode => update({ destinationNode })} emptyLabel="Chọn bước đích" options={availableTargets.map(node => ({ value: node.name, label: `${node.name} · ${node.type}` }))} /></div>}
        <p className="mt-3 text-[11px] leading-5 text-cobalt-800 dark:text-cobalt-200"><GitBranch className="mr-1 inline h-3.5 w-3.5" />Kết quả: <strong>{draft.routeName || 'Route mới'}</strong> sẽ chuyển thẳng đến <strong>{draft.destinationMode === 'new' ? draft.destinationName || 'bước mới' : draft.destinationNode || 'bước đã chọn'}</strong>.</p>
      </div>
      {error && <p className="mt-4 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-bold text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">{error}</p>}
      <div className="mt-6 flex justify-end gap-2"><button type="button" onClick={onClose} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-bold text-slate-600 dark:border-slate-700 dark:text-slate-300">Huỷ</button><button type="submit" className="inline-flex items-center gap-2 rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white hover:bg-cobalt-700"><CheckCircle2 className="h-4 w-4" />Tạo và liên kết nhánh</button></div>
    </form>
  </div>;
}

function DialogEditor({ nodes, intents, entities, onChange, onPreview }: { nodes: StudioDialogNode[]; intents: StudioIntent[]; entities: StudioEntity[]; onChange: (value: StudioDialogNode[]) => void; onPreview: () => void }) {
  const [selected, setSelected] = React.useState(0);
  const [nodeSearch, setNodeSearch] = React.useState('');
  const [collapsed, setCollapsed] = React.useState<Set<string>>(() => new Set());
  const [isBranchFormOpen, setIsBranchFormOpen] = React.useState(false);
  const [branchEntity, setBranchEntity] = React.useState('');
  const [branchValue, setBranchValue] = React.useState('');
  const [branchResponse, setBranchResponse] = React.useState('');
  const [branchError, setBranchError] = React.useState('');
  const [branchSearch, setBranchSearch] = React.useState('');
  const [branchPage, setBranchPage] = React.useState(0);
  const [openMenu, setOpenMenu] = React.useState<number | null>(null);
  const [isMoveOpen, setIsMoveOpen] = React.useState(false);
  const [isRouteComposerOpen, setIsRouteComposerOpen] = React.useState(false);
  const [moveParent, setMoveParent] = React.useState('');
  const [inspectorTab, setInspectorTab] = React.useState<'condition' | 'response' | 'flow'>('condition');
  React.useEffect(() => { if (selected >= nodes.length) setSelected(Math.max(0, nodes.length - 1)); }, [nodes.length, selected]);
  React.useEffect(() => { setBranchSearch(''); setBranchPage(0); setIsBranchFormOpen(false); setInspectorTab(nodes[selected]?.type === 'ROUTE' ? 'condition' : 'response'); }, [selected]);
  const current = nodes[selected];
  const update = (patch: Partial<StudioDialogNode>) => onChange(nodes.map((node, index) => index === selected ? { ...node, ...patch } : node));
  const updateAt = (index: number, patch: Partial<StudioDialogNode>) => onChange(nodes.map((node, nodeIndex) => nodeIndex === index ? { ...node, ...patch } : node));
  const makeName = (base: string) => uniqueDialogName(nodes.map(node => node.name), base);
  const makeNode = (type: StudioDialogNode['type'], parentNode = ''): StudioDialogNode => ({
    name: makeName(type === 'FOLDER' ? 'Nhóm hội thoại' : 'Nhánh mới'),
    type,
    parentNode,
    matchIntent: type === 'ROUTE' ? nodes.find(node => node.name === parentNode)?.matchIntent || intents[0]?.name || '' : '',
    matchEntityName: '',
    matchEntityValue: '',
    requiredEntityName: '',
    responseText: type === 'FOLDER' ? '' : 'Nội dung phản hồi',
    missingEntityPrompt: '',
    nextStep: type === 'FOLDER' ? 'SKIP_USER_INPUT' : 'WAIT_FOR_REPLY',
    jumpToNode: '',
    irrelevantResponseText: '',
    contextReusePolicy: 'NONE',
    priority: Math.max(0, ...nodes.map(node => node.priority)) + 10,
  });
  const acceptInsertion = (value: StudioDialogNode[], additions: StudioDialogNode[], beforeName?: string) => {
    const plan = planDialogInsertion(value, additions, beforeName);
    if (plan.requiresRenumber && !window.confirm('Không còn khoảng số ưu tiên ở vị trí này. Đánh lại ưu tiên để chèn bước, giữ nguyên thứ tự chạy tương đối của các bước hiện có?')) return undefined;
    return plan.nodes;
  };
  const insertNode = (node: StudioDialogNode, index = nodes.length) => {
    const next = acceptInsertion(nodes, [node], nodes[index]?.name);
    if (!next) return;
    onChange(next);
    setSelected(next.findIndex(item => item.name === node.name));
    setNodeSearch('');
    setOpenMenu(null);
  };
  const add = (type: StudioDialogNode['type'] = 'ROUTE', parentNode = '') => {
    const fallbackIndex = nodes.findIndex(node => node.type === 'FALLBACK');
    const insertAt = !parentNode && fallbackIndex >= 0 ? fallbackIndex : nodes.length;
    insertNode(makeNode(type, parentNode), insertAt);
  };
  const rename = (name: string) => {
    if (!current) return;
    onChange(renameDialogNode(nodes, selected, name));
  };
  const remove = () => {
    if (!current || current.type === 'WELCOME' || current.type === 'FALLBACK') return;
    const children = nodes.filter(node => node.parentNode?.trim().toLocaleLowerCase() === current.name.trim().toLocaleLowerCase()).length;
    const incoming = nodes.filter(node => node.jumpToNode?.trim().toLocaleLowerCase() === current.name.trim().toLocaleLowerCase()).length;
    if (!window.confirm(`Xoá bước “${current.name}”? ${children} bước con được chuyển lên cấp cha; ${incoming} liên kết đến bước này sẽ chuyển sang chờ người dùng. Hãy kiểm tra lại lời đáp.`)) return;
    onChange(deleteDialogNode(nodes, current.name));
    setSelected(Math.max(0, selected - 1));
    setOpenMenu(null);
  };
  const addRelative = (offset: 0 | 1) => {
    if (!current) return;
    const anchor = offset === 0 ? current : [...nodes].sort((a, b) => a.priority - b.priority).find(node => node.priority > current.priority);
    insertNode(makeNode('ROUTE', current.parentNode || ''), anchor ? nodes.indexOf(anchor) : nodes.length);
  };
  const createRouteFlow = (draft: RouteComposerDraft) => {
    const routeName = makeName(draft.routeName.trim() || 'Nhánh mới');
    let destinationName = draft.destinationMode === 'new'
      ? draft.destinationName.trim() || 'Bước tiếp theo'
      : draft.destinationNode;
    if (draft.destinationMode === 'new') {
      destinationName = uniqueDialogName([...nodes.map(node => node.name), routeName], destinationName);
    }
    if (!destinationName) return;
    const route: StudioDialogNode = {
      ...makeNode('ROUTE', draft.parentNode),
      name: routeName,
      matchIntent: draft.matchIntent,
      nextStep: 'JUMP_TO',
      jumpToNode: destinationName,
      responseText: '',
    };
    const additions = [route];
    const fallbackIndex = nodes.findIndex(node => node.type === 'FALLBACK');
    const insertAt = !draft.parentNode && fallbackIndex >= 0 ? fallbackIndex : nodes.length;
    if (draft.destinationMode === 'new') {
      additions.push({
        ...makeNode('ROUTE', draft.parentNode),
        name: destinationName,
        // A ROUTE node must always reference an intent for backend validation;
        // the jump still reaches it directly, so this does not add a second match.
        matchIntent: draft.matchIntent,
        responseText: draft.destinationResponse.trim() || 'Nội dung phản hồi',
        nextStep: 'WAIT_FOR_REPLY',
      });
    }
    const next = acceptInsertion(nodes, additions, nodes[insertAt]?.name);
    if (!next) return;
    onChange(next);
    setSelected(next.findIndex(item => item.name === routeName));
    setNodeSearch('');
    setIsRouteComposerOpen(false);
  };
  const duplicate = () => {
    if (!current || current.type === 'WELCOME' || current.type === 'FALLBACK') return;
    const anchor = [...nodes].sort((a, b) => a.priority - b.priority).find(node => node.priority > current.priority);
    insertNode({ ...current, name: makeName(`${current.name} bản sao`) }, anchor ? nodes.indexOf(anchor) : nodes.length);
  };
  const responseBranches = current?.type === 'ROUTE' ? nodes
    .map((node, index) => ({ node, index }))
    .filter(({ node }) => current.nextStep === 'SKIP_USER_INPUT' && node.parentNode === current.name && node.type === 'ROUTE' && node.matchIntent === current.matchIntent && Boolean(node.matchEntityName && node.matchEntityValue))
    .sort((left, right) => left.node.priority - right.node.priority) : [];
  const filteredResponseBranches = responseBranches.filter(({ node }) =>
    `${node.matchEntityName} ${node.matchEntityValue} ${node.responseText}`.toLocaleLowerCase().includes(branchSearch.trim().toLocaleLowerCase()));
  const branchPageCount = Math.max(1, Math.ceil(filteredResponseBranches.length / 8));
  const visibleResponseBranches = filteredResponseBranches.slice(Math.min(branchPage, branchPageCount - 1) * 8, (Math.min(branchPage, branchPageCount - 1) + 1) * 8);
  const addResponseBranch = () => {
    if (!current || current.type !== 'ROUTE' || !current.matchIntent) {
      setBranchError('Chọn intent cho node này trước khi thêm phản hồi theo entity.');
      return;
    }
    if (!branchEntity || !branchValue || !branchResponse.trim()) {
      setBranchError('Chọn entity, giá trị và nhập nội dung trả lời.');
      return;
    }
    if (current.matchEntityName && branchEntity !== current.matchEntityName) {
      setBranchError(`Node này chỉ nhận diện @${current.matchEntityName}. Hãy chọn cùng entity cho phản hồi.`);
      return;
    }
    if (current.matchEntityValue && branchValue !== current.matchEntityValue) {
      setBranchError('Node đang lọc một giá trị entity cụ thể. Xoá điều kiện giá trị ở node cha để thêm nhiều phản hồi.');
      return;
    }
    if (nodes.some(node => node.parentNode === current.name && node.matchIntent === current.matchIntent && node.matchEntityName === branchEntity && node.matchEntityValue === branchValue)) {
      setBranchError('Giá trị entity này đã có phản hồi trong node.');
      return;
    }
    const branch: StudioDialogNode = {
      ...makeNode('ROUTE', current.name),
      name: makeName(`${current.name} · ${branchValue}`),
      matchIntent: current.matchIntent,
      matchEntityName: branchEntity,
      matchEntityValue: branchValue,
      responseText: branchResponse.trim(),
    };
    const next = [...nodes];
    const catchAll = [...next].sort((a, b) => a.priority - b.priority).find(node => node.parentNode === current.name && node.type === 'ROUTE' && !node.matchEntityName);
    const parentIndex = next.findIndex(node => node === current);
    next[parentIndex] = {
      ...next[parentIndex],
      nextStep: 'SKIP_USER_INPUT',
      jumpToNode: '',
      responseText: current.responseText === 'Nội dung phản hồi' ? '' : current.responseText,
    };
    const inserted = acceptInsertion(next, [branch], catchAll?.name);
    if (!inserted) return;
    onChange(inserted);
    setSelected(inserted.findIndex(node => node.name === current.name));
    setCollapsed(previous => { const result = new Set(previous); result.delete(current.name); return result; });
    setIsBranchFormOpen(false);
    setBranchSearch('');
    setBranchPage(0);
    setBranchResponse('');
    setBranchError('');
  };
  const removeResponseBranch = (index: number) => {
    const branch = nodes[index];
    if (!window.confirm(`Xoá phản hồi cho @${branch.matchEntityName}:${branch.matchEntityValue}?`)) return;
    onChange(deleteDialogNode(nodes, branch.name));
    if (index < selected) setSelected(selected - 1);
  };
  const changeIntent = (matchIntent: string) => {
    if (!current) return;
    onChange(nodes.map((node, index) => index === selected || (node.parentNode === current.name && node.matchIntent === current.matchIntent)
      ? { ...node, matchIntent } : node));
  };
  const descendants = current ? collectDialogDescendants(nodes, current.name) : new Set<string>();
  const allowedParents = nodes.filter(node => node !== current && !descendants.has(node.name));
  const move = () => {
    update({ parentNode: moveParent });
    setIsMoveOpen(false);
    setOpenMenu(null);
  };
  const treeRows = buildDialogTree(nodes);
  const normalizedSearch = nodeSearch.trim().toLocaleLowerCase();
  const isHiddenByCollapsedParent = (node: StudioDialogNode) => {
    let parentName = node.parentNode || '';
    const visited = new Set<string>();
    while (parentName && !visited.has(parentName)) {
      if (collapsed.has(parentName)) return true;
      visited.add(parentName);
      parentName = nodes.find(parent => parent.name === parentName)?.parentNode || '';
    }
    return false;
  };
  const visibleTreeRows = normalizedSearch ? treeRows.filter(({ node }) =>
    `${node.name} ${node.matchIntent} ${node.matchEntityName} ${node.matchEntityValue} ${node.parentNode || ''}`.toLocaleLowerCase().includes(normalizedSearch)) : treeRows.filter(({ node }) => !isHiddenByCollapsedParent(node));
  const nextStep = current?.nextStep || 'WAIT_FOR_REPLY';
  const validation = React.useMemo(() => analyzeDialogNodes(nodes, intents.map(intent => intent.name), entities.map(entity => entity.name), entities), [nodes, intents, entities]);

  return <div className="mx-auto max-w-[1700px] space-y-4">
    <p className="text-xs leading-5 text-slate-500">Mục đích (intent) là việc khách muốn làm, như hỏi giá hoặc đặt hàng. Dữ kiện (entity) là thông tin để trả lời đúng, như sản phẩm, số lượng hoặc chi nhánh. Mỗi bước xác định khi nào chạy, lời đáp và bước tiếp theo.</p>
    <DialogValidationNotice {...validation} />
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div><h1 className="text-lg font-black text-slate-900 dark:text-white">Luồng hội thoại</h1><p className="mt-0.5 text-xs text-slate-500">Thiết lập nhánh bán hàng, dữ kiện cần hỏi và bước trả lời tiếp theo.</p></div>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => add('ROUTE')} className="inline-flex items-center gap-1.5 rounded-xl bg-cobalt-600 px-3 py-2 text-xs font-bold text-white hover:bg-cobalt-700"><Plus className="h-4 w-4" />Thêm bước</button>
        <button type="button" disabled={!current || current.type === 'FALLBACK'} onClick={() => current && add('ROUTE', current.name)} className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-2 text-xs font-bold text-slate-700 hover:border-cobalt-300 disabled:opacity-40 dark:border-slate-700 dark:text-slate-200"><GitBranch className="h-4 w-4" />Bước con</button>
        <button type="button" onClick={() => add('FOLDER')} className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-2 text-xs font-bold text-slate-700 hover:border-cobalt-300 dark:border-slate-700 dark:text-slate-200"><FolderPlus className="h-4 w-4" />Nhóm bước</button>
        <button type="button" onClick={() => setIsRouteComposerOpen(true)} className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-2 text-xs font-bold text-slate-700 hover:border-cobalt-300 dark:border-slate-700 dark:text-slate-200"><MoveRight className="h-4 w-4" />Nhánh + bước đích</button>
        <button type="button" onClick={onPreview} className="inline-flex items-center gap-1.5 rounded-xl border border-cyan-200 bg-cyan-50 px-3 py-2 text-xs font-bold text-cyan-800 hover:bg-cyan-100 dark:border-cyan-800 dark:bg-cyan-950 dark:text-cyan-200"><FlaskConical className="h-4 w-4" />Thử hội thoại</button>
      </div>
    </div>
    <div className="grid items-start gap-4 xl:grid-cols-[minmax(390px,0.9fr)_minmax(460px,1.1fr)]">
    <aside className="overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div className="border-b border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-[#0B1324]">
        <div className="flex items-center justify-between"><h2 className="text-sm font-black">Cây hội thoại</h2><span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-bold text-slate-500 dark:bg-slate-800">{nodes.length} bước</span></div>
        <label className="relative mt-3 block"><Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" /><input value={nodeSearch} onChange={event => setNodeSearch(event.target.value)} className={`${inputClass} pl-9`} placeholder="Tìm bước, mục đích, dữ kiện" aria-label="Tìm bước hội thoại" /></label>
        <p className="mt-1.5 text-[11px] text-slate-500" aria-live="polite">{visibleTreeRows.length} / {treeRows.length} bước{normalizedSearch ? ' · Đang xem kết quả tìm kiếm' : ''}</p>
      </div>
      <div className="max-h-[75vh] min-h-80 overflow-y-auto p-3 sm:p-4">
        {visibleTreeRows.length === 0 && <p className="px-3 py-8 text-center text-xs text-slate-400">Không tìm thấy bước phù hợp.</p>}
        {visibleTreeRows.map(row => {
          const childrenCount = nodes.filter(node => node.parentNode === row.node.name).length;
          const responseCount = row.node.nextStep === 'SKIP_USER_INPUT' ? nodes.filter(node => node.parentNode === row.node.name && node.matchIntent === row.node.matchIntent && node.matchEntityName && node.matchEntityValue).length : 0;
          return <div key={`${row.node.name}-${row.index}`} style={{ marginLeft: `${(normalizedSearch ? 0 : row.depth) * 20}px` }} className="relative mb-3 border-l-2 border-slate-200 pl-4 dark:border-slate-700">
          <span className="absolute left-0 top-6 w-4 border-t-2 border-slate-200 dark:border-slate-700" />
          <div className={`relative rounded-xl border bg-white shadow-sm transition ${selected === row.index ? 'border-cobalt-500 ring-2 ring-cobalt-100 dark:ring-cobalt-950' : 'border-slate-200 hover:border-cobalt-300 dark:border-slate-700'} dark:bg-slate-900`}>
          <button type="button" onClick={() => { setSelected(row.index); setOpenMenu(null); setIsBranchFormOpen(false); }} aria-current={selected === row.index ? 'true' : undefined} className="block w-full px-4 py-3 pr-20 text-left">
            <span className="flex items-center gap-2"><span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-lg ${row.node.type === 'FOLDER' ? 'bg-amber-100 text-amber-700' : row.node.type === 'FALLBACK' ? 'bg-slate-100 text-slate-600' : 'bg-cobalt-50 text-cobalt-700'}`}>{row.node.type === 'FOLDER' ? <FolderPlus className="h-4 w-4" /> : <GitBranch className="h-4 w-4" />}</span><span className="truncate text-sm font-bold text-slate-900 dark:text-white">{row.node.name}</span></span>
            <span className="mt-2 flex flex-wrap gap-1.5 pl-9 text-[11px] text-slate-500">{row.node.matchIntent && <span className="rounded bg-cobalt-50 px-1.5 py-0.5 text-cobalt-700 dark:bg-cobalt-950 dark:text-cobalt-200">#{row.node.matchIntent}</span>}{row.node.matchEntityName && <span className="rounded bg-cyan-50 px-1.5 py-0.5 text-cyan-700 dark:bg-cyan-950 dark:text-cyan-200">@{row.node.matchEntityName}{row.node.matchEntityValue ? `:${row.node.matchEntityValue}` : ''}</span>}{!row.node.matchIntent && !row.node.matchEntityName && <span>{row.node.type === 'WELCOME' ? 'Bắt đầu hội thoại' : row.node.type === 'FALLBACK' ? 'Khi không khớp nhánh nào' : 'Nhóm bước'}</span>}</span>
            <span className="mt-3 block border-t border-slate-100 pt-2 pl-9 text-[11px] text-slate-500 dark:border-slate-800">{responseCount ? `${responseCount} phản hồi theo dữ kiện` : row.node.responseText ? '1 phản hồi' : '0 phản hồi'} · {childrenCount} bước con · {dialogNodeSubtitle(row.node, nodes)}</span>
          </button>
          {childrenCount > 0 && <button type="button" onClick={() => setCollapsed(previous => { const result = new Set(previous); if (result.has(row.node.name)) result.delete(row.node.name); else result.add(row.node.name); return result; })} className="absolute right-10 top-3 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label={`${collapsed.has(row.node.name) ? 'Mở' : 'Thu gọn'} bước con của ${row.node.name}`} aria-expanded={!collapsed.has(row.node.name)}><ChevronDown className={`h-4 w-4 transition-transform ${collapsed.has(row.node.name) ? '-rotate-90' : ''}`} /></button>}
          <button type="button" onClick={event => { event.stopPropagation(); setSelected(row.index); setOpenMenu(openMenu === row.index ? null : row.index); }} className="absolute right-2 top-3 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label={`Mở menu ${row.node.name}`}><MoreVertical className="h-4 w-4" /></button>
          {openMenu === row.index && <div className="grid grid-cols-2 gap-1 border-t border-slate-100 p-1.5 dark:border-slate-700">
            <DialogMenuButton icon={ChevronRight} label="Thêm bước con" onClick={() => add('ROUTE', row.node.name)} />
            <DialogMenuButton icon={ArrowUp} label="Thêm bước trước" onClick={() => addRelative(0)} />
            <DialogMenuButton icon={ArrowDown} label="Thêm bước sau" onClick={() => addRelative(1)} />
            <DialogMenuButton icon={FolderPlus} label="Thêm nhóm bước" onClick={() => add('FOLDER', row.node.parentNode || '')} />
            <DialogMenuButton icon={MoveRight} label="Di chuyển" disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={() => { setMoveParent(row.node.parentNode || ''); setIsMoveOpen(true); }} />
            <DialogMenuButton icon={CopyPlus} label="Nhân bản" disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={duplicate} />
            <DialogMenuButton icon={GitBranch} label="Chuyển đến" disabled={row.node.type === 'FOLDER' || responseBranches.length > 0} onClick={() => { update({ nextStep: 'JUMP_TO' }); setOpenMenu(null); }} />
            <DialogMenuButton icon={Trash2} label="Xoá" danger disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={remove} />
          </div>}
          </div>
        </div>;
        })}
      </div>
    </aside>
    <main className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-6">
    {current ? <div className="space-y-5">
      <p className="rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-600 dark:bg-slate-900 dark:text-slate-300">{summarizeDialogStep(current, nodes)}</p>
      <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-4 dark:border-slate-800"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cobalt-600">Đang chỉnh sửa bước</p><h2 className="mt-1 text-xl font-black">{current.name}</h2><p className="mt-1 text-xs text-slate-500">{current.parentNode ? `Bước con của ${current.parentNode}` : 'Bước cấp gốc'} · Ưu tiên {current.priority}</p></div><button type="button" onClick={remove} disabled={current.type === 'WELCOME' || current.type === 'FALLBACK'} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-30 dark:hover:bg-rose-950/40" aria-label="Xoá bước hội thoại"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 sm:grid-cols-2"><DialogNodeNameField key={selected} name={current.name} otherNames={nodes.filter((_, index) => index !== selected).map(node => node.name)} onRename={rename} className={inputClass} /><label><span className={labelClass}>Vai trò của bước</span><select value={current.type} disabled={current.type === 'WELCOME' || current.type === 'FALLBACK'} onChange={event => update({ type: event.target.value as StudioDialogNode['type'], parentNode: ['WELCOME', 'FALLBACK'].includes(event.target.value) ? '' : current.parentNode })} className={inputClass}><option value="WELCOME" disabled={nodes.some(node => node !== current && node.type === 'WELCOME')}>Chào mở đầu</option><option value="ROUTE">Nhánh theo mục đích</option><option value="FALLBACK" disabled={nodes.some(node => node !== current && node.type === 'FALLBACK')}>Dự phòng khi chưa hiểu</option><option value="FOLDER">Nhóm bước</option></select></label><SearchableSelect label="Bước cha" value={current.parentNode || ''} onChange={parentNode => update({ parentNode })} emptyLabel="Cấp gốc" options={allowedParents.map(node => ({ value: node.name, label: node.name }))} disabled={current.type === 'WELCOME' || current.type === 'FALLBACK'} /><label><span className={labelClass}>Ưu tiên xét (số nhỏ chạy trước)</span><input type="number" step={1} min={-2147483648} max={2147483647} value={current.priority} onChange={event => update({ priority: Number(event.target.value) })} className={inputClass} /></label></div>
      {current.type === 'FOLDER' ? <div className="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm leading-6 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-100"><div className="flex items-center gap-2 font-black"><FolderPlus className="h-4 w-4" />Nhóm các nhánh hội thoại</div><p className="mt-2">Nhóm không gửi lời đáp. Khi luồng đi vào nhóm, BlueAI xét bước con theo điều kiện và ưu tiên. Liên kết nhóm vào luồng để các bước này được chạy.</p></div> : <>
      <div role="tablist" aria-label="Cấu hình bước" className="grid grid-cols-3 rounded-xl bg-slate-100 p-1 dark:bg-slate-900">
        {(['condition', 'response', 'flow'] as const).map(tab => <button key={tab} type="button" role="tab" aria-selected={inspectorTab === tab} onClick={() => setInspectorTab(tab)} className={`rounded-lg px-2 py-2 text-xs font-bold transition ${inspectorTab === tab ? 'bg-white text-cobalt-700 shadow-sm dark:bg-slate-800 dark:text-cobalt-200' : 'text-slate-500 hover:text-slate-800 dark:hover:text-white'}`}>{tab === 'condition' ? 'Điều kiện' : tab === 'response' ? `Phản hồi${responseBranches.length ? ` (${responseBranches.length})` : ''}` : 'Chuyển bước'}</button>)}
      </div>
      {inspectorTab === 'condition' && (current.type === 'ROUTE' ? <div className="rounded-2xl border border-cobalt-100 bg-cobalt-50/60 p-4 dark:border-cobalt-900 dark:bg-cobalt-950/30"><p className="mb-1 text-sm font-black text-cobalt-800 dark:text-cobalt-200">Nếu BlueAI nhận diện</p><p className="mb-3 text-xs text-slate-500">Chọn mục đích của khách, rồi thêm điều kiện dữ kiện nếu cần. Dữ kiện cần thu thập sẽ được hỏi khi còn thiếu.</p><div className="grid gap-3 md:grid-cols-2">
        <SearchableSelect label="Mục đích của khách (intent)" value={current.matchIntent || ''} onChange={changeIntent} emptyLabel="Chọn mục đích" options={intents.map(intent => ({ value: intent.name, label: `#${intent.name}` }))} />
        <SearchableSelect label="Dữ kiện cần hỏi khi thiếu" value={current.requiredEntityName || ''} onChange={requiredEntityName => update({ requiredEntityName })} emptyLabel="Không cần hỏi thêm" options={entities.map(entity => ({ value: entity.name, label: `@${entity.name}` }))} />
        <SearchableSelect label="Điều kiện dữ kiện (entity)" value={current.matchEntityName || ''} onChange={matchEntityName => update({ matchEntityName, matchEntityValue: '' })} emptyLabel="Không lọc theo dữ kiện" options={entities.map(entity => ({ value: entity.name, label: `@${entity.name}` }))} disabled={responseBranches.length > 0} />
        <SearchableSelect label="Giá trị chính để khớp" value={current.matchEntityValue || ''} onChange={matchEntityValue => update({ matchEntityValue })} emptyLabel="Mọi giá trị" options={(entities.find(entity => entity.name === current.matchEntityName)?.values || []).map(value => ({ value: value.value, label: value.value }))} disabled={!current.matchEntityName || responseBranches.length > 0} />
      </div><p className="mt-3 text-xs leading-5 text-slate-500">Thêm sản phẩm, chi nhánh hoặc giá trị mới ở tab Dữ kiện. Giá trị chính được dùng để khớp; các tên gọi khác giúp nhận diện câu khách nói. Không tự lấy dữ kiện từ câu trước làm sự thật.</p><DialogContextPolicy value={current.contextReusePolicy} onChange={contextReusePolicy => update({ contextReusePolicy })} className={inputClass} /></div> : <div className="rounded-2xl border border-slate-200 p-4 text-xs text-slate-500 dark:border-slate-700">Bước chào mở đầu hoặc dự phòng không cần điều kiện nhận diện.</div>)}
      {inspectorTab === 'response' && <section className="rounded-2xl border border-slate-200 p-4 dark:border-slate-700">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><div><h3 className="text-sm font-black">BlueAI phản hồi</h3><p className="mt-1 text-xs text-slate-500">Viết lời đáp chung hoặc lời đáp riêng cho từng sản phẩm / chi nhánh.</p></div>{current.type === 'ROUTE' && <button type="button" disabled={entities.length === 0} onClick={() => { const entityName = current.matchEntityName || entities[0]?.name || ''; setBranchEntity(entityName); setBranchValue(entities.find(entity => entity.name === entityName)?.values[0]?.value || ''); setBranchResponse(''); setBranchError(''); setIsBranchFormOpen(true); }} className="inline-flex items-center gap-1.5 rounded-lg border border-cobalt-200 px-2.5 py-2 text-xs font-bold text-cobalt-700 hover:bg-cobalt-50 disabled:opacity-40 dark:border-cobalt-800 dark:text-cobalt-200"><Plus className="h-3.5 w-3.5" />Phản hồi theo dữ kiện</button>}</div>
        <label className="block"><span className={labelClass}>{responseBranches.length ? 'Lời đáp trước khi xét các nhánh (tuỳ chọn)' : 'Nội dung BlueAI trả lời'}</span><textarea value={current.responseText} onChange={event => update({ responseText: event.target.value })} maxLength={4000} rows={responseBranches.length ? 2 : 4} className={`${inputClass} resize-y leading-6`} placeholder="Bạn có thể dùng {{location}} để chèn giá trị entity." /></label>
        {responseBranches.length > 0 && <div className="mt-4 space-y-3">
          <p className={labelClass}>{responseBranches.length} phản hồi theo dữ kiện · xét theo thứ tự</p>
          {responseBranches.length > 8 && <label className="relative block"><Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" /><input value={branchSearch} onChange={event => { setBranchSearch(event.target.value); setBranchPage(0); }} className={`${inputClass} pl-9`} placeholder="Tìm giá trị entity hoặc nội dung phản hồi" aria-label="Tìm phản hồi theo entity" /></label>}
          {visibleResponseBranches.length === 0 && <p className="rounded-xl bg-slate-50 px-3 py-5 text-center text-xs text-slate-500 dark:bg-slate-900">Không tìm thấy phản hồi phù hợp.</p>}
          {visibleResponseBranches.map(({ node, index }) => <div key={`${node.name}-${index}`} className="rounded-xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950">
            <div className="mb-2 flex items-center justify-between gap-2"><span className="text-xs font-bold text-cobalt-700 dark:text-cobalt-200">@{node.matchEntityName}:{node.matchEntityValue}</span><div className="flex gap-1"><button type="button" onClick={() => { setSelected(index); setIsBranchFormOpen(false); }} className="rounded-lg px-2 py-1 text-[11px] font-bold text-slate-500 hover:bg-white hover:text-cobalt-700 dark:hover:bg-slate-800">Mở node</button><button type="button" onClick={() => removeResponseBranch(index)} className="rounded-lg p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600" aria-label={`Xoá phản hồi @${node.matchEntityName}:${node.matchEntityValue}`}><Trash2 className="h-3.5 w-3.5" /></button></div></div>
            <textarea maxLength={4000} value={node.responseText} onChange={event => updateAt(index, { responseText: event.target.value })} rows={2} className={`${inputClass} resize-y`} aria-label={`Nội dung phản hồi @${node.matchEntityName}:${node.matchEntityValue}`} />
          </div>)}
          {filteredResponseBranches.length > 8 && <div className="flex items-center justify-between text-xs text-slate-500"><span>{filteredResponseBranches.length} kết quả · Trang {Math.min(branchPage, branchPageCount - 1) + 1}/{branchPageCount}</span><div className="flex gap-2"><button type="button" disabled={branchPage <= 0} onClick={() => setBranchPage(page => Math.max(0, page - 1))} className="rounded-lg border border-slate-200 px-2.5 py-1.5 font-bold disabled:opacity-40 dark:border-slate-700">Trước</button><button type="button" disabled={branchPage >= branchPageCount - 1} onClick={() => setBranchPage(page => Math.min(branchPageCount - 1, page + 1))} className="rounded-lg border border-slate-200 px-2.5 py-1.5 font-bold disabled:opacity-40 dark:border-slate-700">Sau</button></div></div>}
        </div>}
        {isBranchFormOpen && current.type === 'ROUTE' && <div className="mt-4 space-y-3 rounded-xl border border-cobalt-200 bg-cobalt-50/60 p-3 dark:border-cobalt-800 dark:bg-cobalt-950/30"><p className="text-xs font-black text-cobalt-800 dark:text-cobalt-200">Thêm phản hồi có điều kiện</p><div className="grid gap-3 sm:grid-cols-2"><SearchableSelect label="Loại dữ kiện" value={branchEntity} onChange={value => { setBranchEntity(value); setBranchValue(entities.find(entity => entity.name === value)?.values[0]?.value || ''); }} emptyLabel="Chọn dữ kiện" options={entities.map(entity => ({ value: entity.name, label: `@${entity.name}` }))} disabled={Boolean(current.matchEntityName)} /><SearchableSelect label="Giá trị" value={branchValue} onChange={setBranchValue} emptyLabel="Chọn giá trị" options={(entities.find(entity => entity.name === branchEntity)?.values || []).map(value => ({ value: value.value, label: value.value }))} /></div><label className="block"><span className={labelClass}>Lời đáp</span><textarea maxLength={4000} value={branchResponse} onChange={event => setBranchResponse(event.target.value)} rows={2} className={`${inputClass} resize-y`} placeholder="Ví dụ: Chi nhánh {{location}} mở cửa lúc 8 giờ." /></label>{branchError && <p className="text-xs font-bold text-rose-600" role="alert">{branchError}</p>}<div className="flex justify-end gap-2"><button type="button" onClick={() => setIsBranchFormOpen(false)} className="rounded-lg px-3 py-2 text-xs font-bold text-slate-600">Huỷ</button><button type="button" onClick={addResponseBranch} className="rounded-lg bg-cobalt-600 px-3 py-2 text-xs font-bold text-white">Thêm phản hồi</button></div></div>}
        {current.type === 'ROUTE' && current.requiredEntityName && <label className="mt-4 block"><span className={labelClass}>Câu hỏi khi thiếu dữ kiện</span><textarea maxLength={1000} value={current.missingEntityPrompt || ''} onChange={event => update({ missingEntityPrompt: event.target.value })} rows={2} className={`${inputClass} resize-y`} placeholder={`Bạn muốn hỏi cho ${current.requiredEntityName} nào?`} /></label>}
      </section>}
      {inspectorTab === 'flow' && <div className="space-y-4 rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950">
        <div className="flex items-center gap-2"><CircleDot className="h-4 w-4 text-emerald-500" /><p className="text-xs font-black text-slate-800 dark:text-slate-100">Sau đó BlueAI sẽ</p></div>
        <div className="grid gap-3 md:grid-cols-2">
          <label><span className={labelClass}>Hành động tiếp theo</span><select value={nextStep} onChange={event => update({ nextStep: event.target.value as StudioDialogNode['nextStep'], jumpToNode: event.target.value === 'JUMP_TO' ? current.jumpToNode || '' : '' })} className={inputClass}><option value="WAIT_FOR_REPLY" disabled={responseBranches.length > 0}>Chờ người dùng trả lời</option><option value="SKIP_USER_INPUT">Tự động tiếp tục</option><option value="JUMP_TO" disabled={responseBranches.length > 0}>Chuyển ngay đến bước đích</option></select></label>
          {nextStep === 'JUMP_TO' && <SearchableSelect label="Bước đích" value={current.jumpToNode || ''} onChange={jumpToNode => update({ jumpToNode })} emptyLabel="Chọn bước đích" options={nodes.filter(node => node !== current).map(node => ({ value: node.name, label: node.name }))} />}
        </div>
        <p className="text-xs leading-5 text-slate-500">{responseBranches.length > 0 ? 'Tự động tiếp tục để chọn phản hồi đúng theo giá trị entity. Xoá các phản hồi có điều kiện nếu muốn đổi hành động.' : nextStep === 'WAIT_FOR_REPLY' ? 'Dừng tại bước này và chờ người dùng gửi câu tiếp theo.' : nextStep === 'SKIP_USER_INPUT' ? 'Không chờ người dùng; xét bước con trước, rồi nhánh cùng cấp có số ưu tiên lớn hơn.' : 'Không chờ người dùng; chuyển thẳng đến bước đích, không xét lại điều kiện của đích.'}</p>
        {nextStep === 'WAIT_FOR_REPLY' && <label className="block"><span className={labelClass}>Lời đáp khi câu tiếp theo chưa rõ</span><textarea maxLength={1000} value={current.irrelevantResponseText || ''} onChange={event => update({ irrelevantResponseText: event.target.value })} rows={3} className={`${inputClass} resize-y`} placeholder="Mình chưa hiểu câu trả lời này. Bạn vui lòng thử lại nhé." /><span className="mt-1.5 block text-[11px] leading-5 text-slate-500">Khi đang chờ ở bước này nhưng chưa hiểu mục đích hoặc dữ kiện của câu mới. Để trống sẽ dùng bước dự phòng.</span></label>}
      </div>}</>}
    </div> : <EmptySelection label="dialog node" />}
    </main>
    </div>
    {isRouteComposerOpen && <RouteComposer
      nodes={nodes}
      intents={intents}
      defaultParent={current?.type === 'FOLDER' ? current.name : current?.parentNode || ''}
      onClose={() => setIsRouteComposerOpen(false)}
      onCreate={createRouteFlow}
    />}
    {isMoveOpen && current && <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm"><div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-slate-700 dark:bg-slate-900"><h2 className="text-lg font-black">Di chuyển “{current.name}”</h2><p className="mt-1 text-sm text-slate-500">Chọn bước cha mới; các bước con vẫn đi cùng bước này.</p><div className="mt-5"><SearchableSelect label="Bước cha mới" value={moveParent} onChange={setMoveParent} emptyLabel="Cấp gốc" options={allowedParents.map(node => ({ value: node.name, label: node.name }))} /></div><div className="mt-6 flex justify-end gap-2"><button type="button" onClick={() => setIsMoveOpen(false)} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-bold dark:border-slate-700">Huỷ</button><button type="button" onClick={move} className="rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white">Di chuyển bước</button></div></div></div>}
  </div>;
}

function DialogMenuButton({ icon: Icon, label, onClick, disabled = false, danger = false }: { icon: React.ComponentType<{ className?: string }>; label: string; onClick: () => void; disabled?: boolean; danger?: boolean }) {
  return <button type="button" onClick={onClick} disabled={disabled} className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-xs font-bold disabled:cursor-not-allowed disabled:opacity-35 ${danger ? 'text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40' : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800'}`}><Icon className="h-3.5 w-3.5" />{label}</button>;
}

function buildDialogTree(nodes: StudioDialogNode[]): { node: StudioDialogNode; index: number; depth: number }[] {
  const rows: { node: StudioDialogNode; index: number; depth: number }[] = [];
  const visited = new Set<number>();
  const visit = (parentName: string, depth: number) => nodes
    .map((node, index) => ({ node, index }))
    .filter(item => (item.node.parentNode || '') === parentName)
    .sort((left, right) => left.node.priority - right.node.priority)
    .forEach(item => {
      if (visited.has(item.index)) return;
      visited.add(item.index);
      rows.push({ ...item, depth });
      visit(item.node.name, depth + 1);
    });
  visit('', 0);
  nodes.forEach((node, index) => { if (!visited.has(index)) rows.push({ node, index, depth: 0 }); });
  return rows;
}

function collectDialogDescendants(nodes: StudioDialogNode[], parentName: string): Set<string> {
  const result = new Set<string>();
  const visit = (name: string) => nodes.filter(node => node.parentNode === name).forEach(node => {
    if (result.has(node.name)) return;
    result.add(node.name);
    visit(node.name);
  });
  visit(parentName);
  return result;
}

function dialogNodeSubtitle(node: StudioDialogNode, nodes: StudioDialogNode[]): string {
  if (node.type === 'FOLDER') return `${nodes.filter(item => item.parentNode === node.name).length} bước con`;
  const action = node.nextStep === 'SKIP_USER_INPUT'
    ? 'Tự động tiếp tục'
    : node.nextStep === 'JUMP_TO'
      ? `Chuyển đến ${node.jumpToNode || '…'}`
      : 'Chờ người dùng trả lời';
  return `${node.type === 'ROUTE' ? `#${node.matchIntent || 'chưa_chọn'}` : node.type === 'WELCOME' ? 'Chào mở đầu' : 'Dự phòng khi chưa hiểu'} · ${action}`;
}

function EditorLayout({ title, description, items, selected, onSelect, onAdd, addLabel, onImport, importLabel, children }: { title: string; description: string; items: { title: string; subtitle: string }[]; selected: number; onSelect: (index: number) => void; onAdd: () => void; addLabel: string; onImport?: () => void; importLabel?: string; children: React.ReactNode }) {
  const [search, setSearch] = React.useState('');
  const normalizedSearch = search.trim().toLocaleLowerCase();
  const visibleItems = items.map((item, index) => ({ item, index })).filter(({ item }) =>
    `${item.title} ${item.subtitle}`.toLocaleLowerCase().includes(normalizedSearch));
  return <div className="mx-auto grid max-w-7xl gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
    <aside className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div className="border-b border-slate-200 p-4 dark:border-slate-800"><h1 className="text-lg font-black">{title} <span className="text-sm font-medium text-slate-400">({items.length})</span></h1><p className="mt-1 text-xs leading-5 text-slate-500">{description}</p><div className={`mt-4 grid gap-2 ${onImport ? 'grid-cols-[1fr_auto]' : 'grid-cols-1'}`}><button type="button" onClick={() => { setSearch(''); onAdd(); }} className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-cobalt-600 px-3 py-2.5 text-xs font-bold text-white hover:bg-cobalt-700"><Plus className="h-4 w-4" />{addLabel}</button>{onImport && <button type="button" onClick={onImport} className="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-3 py-2.5 text-xs font-bold text-slate-700 hover:border-cobalt-300 hover:text-cobalt-700 dark:border-slate-700 dark:text-slate-200" title={importLabel}><Upload className="h-4 w-4" /><span>{importLabel?.replace(' mẫu câu', '').replace(' từ điển', '') || 'Nhập'}</span></button>}</div><label className="relative mt-3 block"><Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" /><input value={search} onChange={event => setSearch(event.target.value)} className={`${inputClass} pl-9`} placeholder={`Tìm ${title.toLocaleLowerCase()}`} aria-label={`Tìm ${title}`} /></label><p className="mt-1.5 text-[11px] text-slate-500" aria-live="polite">{visibleItems.length} / {items.length} mục</p></div>
      <div className="max-h-[58vh] overflow-y-auto p-2">{items.length === 0 ? <p className="px-3 py-8 text-center text-xs text-slate-400">Chưa có dữ liệu.</p> : visibleItems.length === 0 ? <p className="px-3 py-8 text-center text-xs text-slate-400">Không tìm thấy mục phù hợp.</p> : visibleItems.map(({ item, index }) => <button key={`${item.title}-${index}`} type="button" onClick={() => onSelect(index)} className={`mb-1 flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left ${selected === index ? 'bg-cobalt-50 text-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}`}><div className="min-w-0 flex-1"><p className="truncate text-sm font-bold">{item.title}</p><p className="mt-0.5 truncate text-[11px] text-slate-500">{item.subtitle}</p></div><ChevronRight className="h-4 w-4 shrink-0 text-slate-400" /></button>)}</div>
    </aside>
    <main className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-6">{children}</main>
  </div>;
}

function SearchableSelect({ label, value, options, onChange, emptyLabel, disabled = false }: { label: string; value: string; options: { value: string; label: string }[]; onChange: (value: string) => void; emptyLabel: string; disabled?: boolean }) {
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const [active, setActive] = React.useState(0);
  const searchRef = React.useRef<HTMLInputElement>(null);
  const allOptions = [{ value: '', label: emptyLabel }, ...options];
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const matches = allOptions.filter(option => option.label.toLocaleLowerCase().includes(normalizedQuery));
  const visibleOptions = matches.slice(0, 60);
  const selectedLabel = allOptions.find(option => option.value === value)?.label || value || emptyLabel;
  const choose = (nextValue: string) => { onChange(nextValue); setOpen(false); setQuery(''); setActive(0); };
  return <div className="min-w-0" onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <span className={labelClass}>{label}</span>
    <div className="relative">
      <button type="button" disabled={disabled} aria-label={label} aria-expanded={open} aria-haspopup="listbox" onClick={() => { setOpen(current => !current); setQuery(''); setActive(0); window.setTimeout(() => searchRef.current?.focus(), 0); }} className={`${inputClass} flex items-center justify-between gap-2 text-left disabled:cursor-not-allowed disabled:opacity-50`}><span className="truncate">{selectedLabel}</span><ChevronDown className="h-4 w-4 shrink-0 text-slate-400" /></button>
      {open && !disabled && <div className="absolute left-0 right-0 top-full z-50 mt-1 rounded-xl border border-slate-200 bg-white p-2 shadow-xl dark:border-slate-700 dark:bg-slate-900">
        <div className="relative"><Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" /><input ref={searchRef} value={query} onChange={event => { setQuery(event.target.value); setActive(0); }} onKeyDown={event => { if (event.key === 'Escape') { setOpen(false); event.preventDefault(); } else if (event.key === 'ArrowDown') { setActive(index => Math.min(index + 1, Math.max(visibleOptions.length - 1, 0))); event.preventDefault(); } else if (event.key === 'ArrowUp') { setActive(index => Math.max(index - 1, 0)); event.preventDefault(); } else if (event.key === 'Enter') { if (visibleOptions[active]) choose(visibleOptions[active].value); event.preventDefault(); } }} className={`${inputClass} pl-8`} placeholder={`Tìm ${label.toLocaleLowerCase()}`} aria-label={`Tìm ${label}`} /></div>
        <div role="listbox" aria-label={label} className="mt-2 max-h-56 overflow-y-auto">{visibleOptions.map((option, index) => <button key={`${option.value}-${index}`} type="button" role="option" aria-selected={option.value === value} onMouseDown={event => event.preventDefault()} onClick={() => choose(option.value)} className={`block w-full truncate rounded-lg px-3 py-2 text-left text-sm ${index === active || option.value === value ? 'bg-cobalt-50 text-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200' : 'text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-800'}`}>{option.label}</button>)}</div>
        {matches.length === 0 && <p className="px-3 py-3 text-xs text-slate-500">Không tìm thấy lựa chọn.</p>}
        {matches.length > visibleOptions.length && <p className="px-3 pt-2 text-[11px] text-slate-500">Còn {matches.length - visibleOptions.length} mục. Gõ thêm để tìm chính xác.</p>}
      </div>}
    </div>
  </div>;
}

function BlueAiCorePanel({ configuration, onChange, onCustomizationChange }: { configuration: StudioBotConfiguration; onChange: (bot: StudioBotConfiguration['bot']) => void; onCustomizationChange: (customization: StudioCustomization) => void }) {
  const enabled = configuration.bot.blueAiCoreEnabled !== false;
  const update = (patch: Partial<StudioBotConfiguration['bot']>) => onChange({ ...configuration.bot, ...patch });
  const customization = configuration.customization || configuration.bot.customization || {};
  const updateCustomization = (patch: Partial<StudioCustomization>) => onCustomizationChange({ ...customization, ...patch });

  return <div className="mx-auto max-w-4xl space-y-4">
    <div className="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div><h1 className="text-base font-black">Cấu hình BlueAI chính</h1><p className="mt-0.5 text-xs text-slate-500">Dialog được xử lý trước, sau đó dùng RAG/LLM chung của BlueAI.</p></div>
      <button type="button" role="switch" aria-checked={enabled} onClick={() => update({ blueAiCoreEnabled: !enabled })} className={`inline-flex shrink-0 items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-black ${enabled ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-300 bg-slate-100 text-slate-500'}`}><span className={`h-2 w-2 rounded-full ${enabled ? 'bg-emerald-500' : 'bg-slate-400'}`} />{enabled ? 'RAG/LLM bật' : 'Chỉ Dialog'}</button>
    </div>

    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <h2 className="text-sm font-black">Danh tính và phong cách</h2>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <label><span className={labelClass}>Vai trò / cách xưng hô</span><input value={customization.identityRole || ''} onChange={event => updateCustomization({ identityRole: event.target.value })} className={inputClass} placeholder="Trợ lý CSKH, xưng mình – bạn" /></label>
        <label><span className={labelClass}>Preset văn phong</span><select value={customization.tonePreset || 'professional'} onChange={event => updateCustomization({ tonePreset: event.target.value })} className={inputClass}><option value="professional">Chuyên nghiệp</option><option value="friendly">Thân thiện</option><option value="concise">Ngắn gọn</option><option value="formal">Trang trọng</option></select></label>
        <label><span className={labelClass}>Độ dài</span><select value={customization.answerLength || 'balanced'} onChange={event => updateCustomization({ answerLength: event.target.value })} className={inputClass}><option value="short">Ngắn</option><option value="balanced">Cân bằng</option><option value="detailed">Chi tiết</option></select></label>
        <label><span className={labelClass}>Cấu trúc trả lời</span><select value={customization.responseStructure || 'direct'} onChange={event => updateCustomization({ responseStructure: event.target.value })} className={inputClass}><option value="direct">Trả lời trực tiếp</option><option value="steps">Theo bước</option><option value="summary_then_detail">Tóm tắt rồi chi tiết</option></select></label>
      </div>
    </section>

    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <h2 className="text-sm font-black">Chính sách trả lời</h2>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <label><span className={labelClass}>Mức chủ động / hỏi lại</span><select value={customization.proactivity || 'ask_when_needed'} onChange={event => updateCustomization({ proactivity: event.target.value })} className={inputClass}><option value="low">Ít chủ động</option><option value="ask_when_needed">Hỏi lại khi cần</option><option value="proactive">Chủ động gợi ý</option></select></label>
        <label><span className={labelClass}>Dẫn nguồn</span><select value={customization.citationPolicy || 'always_when_available'} onChange={event => updateCustomization({ citationPolicy: event.target.value })} className={inputClass}><option value="always_when_available">Luôn dẫn nguồn khi có</option><option value="when_factual">Dẫn nguồn cho thông tin thực tế</option></select></label>
        <label><span className={labelClass}>Khi không có nguồn</span><select value={customization.noSourcePolicy || 'say_not_found'} onChange={event => updateCustomization({ noSourcePolicy: event.target.value })} className={inputClass}><option value="say_not_found">Nói rõ chưa tìm thấy</option><option value="ask_clarification">Hỏi lại để làm rõ</option><option value="escalate">Chuyển hỗ trợ</option></select></label>
        <label><span className={labelClass}>Hồ sơ phản hồi / node Dialog</span><input value={customization.responseProfile || ''} onChange={event => updateCustomization({ responseProfile: event.target.value })} className={inputClass} placeholder="Tên profile hoặc node tuỳ chọn" /></label>
      </div>
    </section>

    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <h2 className="text-sm font-black">Thuật ngữ và xử lý ngoại lệ</h2>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <label><span className={labelClass}>Thuật ngữ ưu tiên</span><textarea value={customization.preferredTerms || ''} onChange={event => updateCustomization({ preferredTerms: event.target.value })} rows={2} className={`${inputClass} resize-y`} placeholder="eSIM, mã QR, ..." /></label>
        <label><span className={labelClass}>Từ / cụm từ cấm</span><textarea value={customization.forbiddenTerms || ''} onChange={event => updateCustomization({ forbiddenTerms: event.target.value })} rows={2} className={`${inputClass} resize-y`} placeholder="Mỗi cụm từ cách nhau bằng dấu phẩy" /></label>
        <label><span className={labelClass}>Fallback</span><textarea value={customization.fallbackMessage || ''} onChange={event => updateCustomization({ fallbackMessage: event.target.value })} rows={2} className={`${inputClass} resize-y`} /></label>
        <label><span className={labelClass}>Escalation</span><textarea value={customization.escalationMessage || ''} onChange={event => updateCustomization({ escalationMessage: event.target.value })} rows={2} className={`${inputClass} resize-y`} /></label>
      </div>
      <label className="mt-3 block"><span className={labelClass}>Chỉ dẫn bổ sung</span><textarea value={configuration.bot.customInstructions || ''} onChange={event => update({ customInstructions: event.target.value })} rows={3} maxLength={2000} disabled={!enabled} className={`${inputClass} resize-y leading-6 disabled:cursor-not-allowed disabled:opacity-50`} placeholder="Quy tắc nghiệp vụ ngắn gọn, áp dụng sau guardrails hệ thống" /></label>
    </section>
  </div>;
}

function CoreFeature({ icon: Icon, title, description }: { icon: React.ComponentType<{ className?: string }>; title: string; description: string }) {
  return <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324]"><Icon className="h-5 w-5 text-cobalt-600" /><h2 className="mt-3 text-sm font-black">{title}</h2><p className="mt-1 text-xs leading-5 text-slate-500">{description}</p></div>;
}

function IntegrationPanel({ configuration }: { configuration: StudioBotConfiguration }) {
  const isPublished = configuration.bot.status === 'PUBLISHED' && Boolean(configuration.bot.publishedReleaseId);
  const origin = typeof window === 'undefined' ? 'https://your-blueai-host.example' : window.location.origin;
  const snippet = `<script src="${origin}/widget.js" data-bot-key="${configuration.bot.publicKey}" data-primary-color="#1e4fd8" defer></script>`;
  const copy = async () => { await navigator.clipboard.writeText(snippet); };

  return <div className="mx-auto max-w-4xl space-y-5">
    <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-8">
      <div className="flex items-start gap-4"><div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl ${isPublished ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'}`}>{isPublished ? <CheckCircle2 className="h-6 w-6" /> : <Rocket className="h-6 w-6" />}</div><div><h1 className="text-xl font-black">{isPublished ? 'Cấu hình BlueAI đã sẵn sàng để nhúng' : 'Phát hành cấu hình trước khi tích hợp'}</h1><p className="mt-1 text-sm leading-6 text-slate-500">Mỗi phiên widget được ghim vào đúng release đã phát hành. Các thay đổi bản nháp sẽ không làm đổi cuộc hội thoại đang diễn ra.</p></div></div>
      <div className="mt-6 grid gap-3 sm:grid-cols-3"><InfoCard label="Public key" value={configuration.bot.publicKey} /><InfoCard label="Release" value={configuration.bot.publishedReleaseId || 'Chưa phát hành'} /><InfoCard label="Locale" value={configuration.bot.locale} /></div>
    </div>
    <div className={`rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-8 ${!isPublished ? 'opacity-60' : ''}`}>
      <div className="flex items-center gap-2"><Code2 className="h-5 w-5 text-cobalt-600" /><h2 className="text-base font-black">Mã nhúng website</h2></div><p className="mt-2 text-sm text-slate-500">Dán một dòng này trước thẻ đóng <code>&lt;/body&gt;</code>. Widget dùng Shadow DOM nên không xung đột CSS của trang chủ.</p>
      <div className="relative mt-4 rounded-2xl bg-slate-950 p-4 pr-12 font-mono text-xs leading-6 text-cyan-200"><code className="break-all">{snippet}</code><button type="button" onClick={() => void copy()} disabled={!isPublished} className="absolute right-3 top-3 rounded-lg bg-white/10 p-2 text-slate-300 hover:bg-white/20 hover:text-white disabled:opacity-40" title="Sao chép mã nhúng"><Copy className="h-4 w-4" /></button></div>
      <div className="mt-5 flex items-start gap-3 rounded-2xl bg-cobalt-50 p-4 text-sm text-cobalt-900 dark:bg-cobalt-950/50 dark:text-cobalt-100"><Sparkles className="mt-0.5 h-4 w-4 shrink-0" /><p>Có thể tuỳ chỉnh màu bằng <code>data-primary-color</code>. Token phiên được cấp ở runtime và không xuất hiện trong mã nguồn trang.</p></div>
    </div>
  </div>;
}

function InfoCard({ label, value }: { label: string; value: string }) {
  return <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950"><p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p><p className="mt-1 truncate text-sm font-black" title={value}>{value}</p></div>;
}

function EmptySelection({ label }: { label: string }) {
  return <div className="flex min-h-72 flex-col items-center justify-center text-center"><Boxes className="h-9 w-9 text-slate-300" /><p className="mt-3 text-sm font-bold text-slate-500">Hãy thêm {label} để bắt đầu.</p></div>;
}

function ImportDialog({ title, description, value, onChange, placeholder, errors, preview, mergeSummary, onClose, onApply, canApply }: {
  title: string;
  description: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  errors: string[];
  preview: string[];
  mergeSummary: string;
  onClose: () => void;
  onApply: () => void;
  canApply: boolean;
}) {
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-label={title}>
    <div className="flex max-h-[min(760px,calc(100vh-2rem))] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900">
      <div className="flex items-start justify-between gap-4 border-b border-slate-200 p-5 dark:border-slate-800">
        <div><div className="flex items-center gap-2"><ClipboardPaste className="h-5 w-5 text-cobalt-600" /><h2 className="text-lg font-black">{title}</h2></div><p className="mt-1 max-w-xl text-sm leading-5 text-slate-500">{description}</p></div>
        <button type="button" onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="Đóng cửa sổ nhập"><X className="h-4 w-4" /></button>
      </div>
      <div className="min-h-0 space-y-4 overflow-y-auto p-5">
        <label className="block"><span className={labelClass}>Dán nội dung CSV / TSV / dấu |</span><textarea value={value} onChange={event => onChange(event.target.value)} rows={9} className={`${inputClass} resize-y font-mono text-xs leading-6`} placeholder={placeholder} autoFocus /><span className="mt-1.5 block text-[11px] leading-5 text-slate-500">Dòng lỗi sẽ được bỏ qua; các dòng hợp lệ sẽ được gộp theo tên và không xoá dữ liệu hiện tại.</span></label>
        <div className="rounded-xl border border-cobalt-100 bg-cobalt-50/70 p-3 text-xs text-cobalt-900 dark:border-cobalt-900 dark:bg-cobalt-950/40 dark:text-cobalt-100"><p className="font-black">{mergeSummary}</p><p className="mt-1">Kiểm tra preview trước khi thêm vào bản nháp.</p></div>
        {errors.length > 0 && <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 dark:border-amber-900 dark:bg-amber-950/30"><div className="flex items-center gap-2 text-xs font-black text-amber-800 dark:text-amber-200"><AlertTriangle className="h-4 w-4" />{errors.length} dòng cần kiểm tra (sẽ không được nhập)</div><ul className="mt-2 max-h-24 space-y-1 overflow-y-auto pl-5 text-[11px] leading-5 text-amber-800 dark:text-amber-200">{errors.map(error => <li key={error}>{error}</li>)}</ul></div>}
        <div><p className={labelClass}>Preview {preview.length > 0 ? `(${preview.length})` : ''}</p>{preview.length === 0 ? <p className="rounded-xl border border-dashed border-slate-200 px-3 py-5 text-center text-xs text-slate-400 dark:border-slate-700">Chưa có dòng hợp lệ để preview.</p> : <div className="grid gap-2 sm:grid-cols-2">{preview.slice(0, 12).map(item => <div key={item} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold text-slate-700 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200"><CheckCircle2 className="mr-1.5 inline h-3.5 w-3.5 text-emerald-500" />{item}</div>)}</div>}{preview.length > 12 && <p className="mt-1 text-[11px] text-slate-500">+ {preview.length - 12} mục khác</p>}</div>
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 p-4 dark:border-slate-800"><button type="button" onClick={onClose} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-bold text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">Huỷ</button><button type="button" onClick={onApply} disabled={!canApply} className="inline-flex items-center gap-2 rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white hover:bg-cobalt-700 disabled:cursor-not-allowed disabled:opacity-40"><Upload className="h-4 w-4" />Nhập phần hợp lệ</button></div>
    </div>
  </div>;
}
