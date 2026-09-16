import React from 'react';
import {
  ArrowDown, ArrowUp, Bot, Boxes, Braces, BrainCircuit, CheckCircle2,
  ChevronRight, CircleDot, Code2, Copy, CopyPlus, FlaskConical, FolderPlus,
  GitBranch, LoaderCircle, MessageSquareText, MoreVertical, MoveRight, Plus,
  Rocket, Save, ShieldCheck, Sparkles, Trash2, X,
} from 'lucide-react';
import {
  blueAiApi,
  type StudioBotConfiguration,
  type StudioDialogNode,
  type StudioEntity,
  type StudioIntent,
} from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { TryItOutPanel } from './TryItOutPanel';

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
  const [activeTab, setActiveTab] = React.useState<StudioTab>('intents');
  const [isLoading, setIsLoading] = React.useState(true);
  const [isSaving, setIsSaving] = React.useState(false);
  const [isPublishing, setIsPublishing] = React.useState(false);
  const [isDirty, setIsDirty] = React.useState(false);
  const [isPreviewOpen, setIsPreviewOpen] = React.useState(false);
  const [isCreateOpen, setIsCreateOpen] = React.useState(false);
  const [newBotName, setNewBotName] = React.useState('Trợ lý BlueAI');
  const [error, setError] = React.useState('');

  const loadBot = React.useCallback(async (botId: string) => {
    setIsLoading(true);
    setError('');
    try {
      const result = await blueAiApi.getStudioBot(botId);
      setConfiguration(copyConfiguration(result));
      setIsDirty(false);
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setIsLoading(false);
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
    setConfiguration(current => current ? updater(copyConfiguration(current)) : current);
    setIsDirty(true);
  };

  const createBot = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!newBotName.trim()) return;
    setIsLoading(true);
    setError('');
    try {
      const result = await blueAiApi.createStudioBot({
        name: newBotName.trim(),
        description: 'Trải nghiệm BlueAI tùy chỉnh cho nghiệp vụ của doanh nghiệp.',
        locale: 'vi-VN',
        timezone: 'Asia/Ho_Chi_Minh',
        includeStarterTemplate: true,
      });
      setBots(current => [result.bot, ...current]);
      setConfiguration(copyConfiguration(result));
      setIsCreateOpen(false);
      setIsDirty(false);
      showToast('Đã tạo một cấu hình BlueAI Custom với bộ intent và luồng mẫu.', 'success');
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setIsLoading(false);
    }
  };

  const save = async (announce = true): Promise<StudioBotConfiguration | undefined> => {
    if (!configuration) return undefined;
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
        intents: configuration.intents,
        entities: configuration.entities,
        dialogNodes: configuration.dialogNodes,
      });
      setConfiguration(copyConfiguration(saved));
      setBots(current => current.map(bot => bot.id === saved.bot.id ? saved.bot : bot));
      setIsDirty(false);
      if (announce) showToast(`Đã lưu bản nháp r${saved.bot.draftRevision}.`, 'success');
      return saved;
    } catch (requestError) {
      setError(errorMessage(requestError));
      return undefined;
    } finally {
      setIsSaving(false);
    }
  };

  const openPreview = async () => {
    const saved = isDirty ? await save(false) : configuration;
    if (saved) setIsPreviewOpen(true);
  };

  const publish = async () => {
    const saved = isDirty ? await save(false) : configuration;
    if (!saved) return;
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
      setIsPublishing(false);
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
          <h1 className="mt-5 text-2xl font-black text-slate-900 dark:text-white">Tạo cấu hình BlueAI Custom đầu tiên</h1>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-500">BlueAI sẽ tạo sẵn intent chào hỏi, giờ làm việc, entity chi nhánh và luồng fallback để bạn có thể thử ngay.</p>
          {error && <p className="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">{error}</p>}
          <form onSubmit={createBot} className="mt-6 flex gap-2">
            <input value={newBotName} onChange={event => setNewBotName(event.target.value)} className={inputClass} aria-label="Tên cấu hình" />
            <button type="submit" className="shrink-0 rounded-xl bg-cobalt-600 px-5 py-2 text-sm font-bold text-white hover:bg-cobalt-700">Tạo cấu hình</button>
          </form>
        </div>
      </div>
    );
  }

  const tabs: { id: StudioTab; label: string; icon: React.ComponentType<{ className?: string }>; count?: number }[] = [
    { id: 'blueai', label: 'BlueAI Core', icon: BrainCircuit },
    { id: 'intents', label: 'Intents', icon: MessageSquareText, count: configuration.intents.length },
    { id: 'entities', label: 'Entities', icon: Braces, count: configuration.entities.length },
    { id: 'dialog', label: 'Dialog', icon: GitBranch, count: configuration.dialogNodes.length },
    { id: 'integrate', label: 'Tích hợp', icon: Code2 },
  ];

  return (
    <section className="relative flex min-w-0 flex-1 flex-col overflow-hidden bg-slate-50 dark:bg-[#060B14]">
      <header className="border-b border-slate-200 bg-white px-4 py-3 dark:border-slate-800 dark:bg-[#0B1324] sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-cobalt-600 to-cyan-500 text-white shadow-lg shadow-cobalt-500/20"><Bot className="h-5 w-5" /></div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <select
                  value={configuration.bot.id}
                  onChange={event => void loadBot(event.target.value)}
                  className="max-w-64 truncate bg-transparent text-base font-black text-slate-900 outline-none dark:text-white"
                  aria-label="Chọn chatbot"
                >
                  {bots.map(botItem => <option key={botItem.id} value={botItem.id}>{botItem.name}</option>)}
                </select>
                <span className={`rounded-full px-2 py-0.5 text-[10px] font-black uppercase ${configuration.bot.status === 'PUBLISHED' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'}`}>{configuration.bot.status}</span>
              </div>
              <p className="text-[11px] text-slate-500">Bản nháp r{configuration.bot.draftRevision}{isDirty ? ' • Có thay đổi chưa lưu' : ' • Đã đồng bộ'}</p>
            </div>
            <button type="button" onClick={() => setIsCreateOpen(true)} className="rounded-xl border border-slate-200 p-2 text-slate-500 hover:border-cobalt-300 hover:text-cobalt-600 dark:border-slate-700" title="Tạo cấu hình BlueAI Custom khác" aria-label="Tạo cấu hình BlueAI Custom khác"><Plus className="h-4 w-4" /></button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" onClick={() => void openPreview()} disabled={isSaving} className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-bold text-slate-700 hover:border-cobalt-300 hover:text-cobalt-700 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"><FlaskConical className="h-4 w-4" />Thử BlueAI</button>
            <button type="button" onClick={() => void save()} disabled={!isDirty || isSaving} className="inline-flex items-center gap-2 rounded-xl border border-cobalt-200 bg-cobalt-50 px-3.5 py-2 text-xs font-bold text-cobalt-700 hover:bg-cobalt-100 disabled:cursor-not-allowed disabled:opacity-45 dark:border-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200">{isSaving ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}Lưu nháp</button>
            <button type="button" onClick={() => void publish()} disabled={isPublishing || isSaving} className="inline-flex items-center gap-2 rounded-xl bg-cobalt-600 px-3.5 py-2 text-xs font-bold text-white shadow-md shadow-cobalt-500/20 hover:bg-cobalt-700 disabled:opacity-50">{isPublishing ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Rocket className="h-4 w-4" />}Phát hành</button>
          </div>
        </div>
      </header>

      <nav className="flex shrink-0 gap-1 overflow-x-auto border-b border-slate-200 bg-white px-4 dark:border-slate-800 dark:bg-[#0B1324] sm:px-6" aria-label="Các khu vực BlueAI Custom">
        {tabs.map(tab => {
          const Icon = tab.icon;
          return <button key={tab.id} type="button" onClick={() => setActiveTab(tab.id)} className={`flex items-center gap-2 border-b-2 px-3 py-3 text-xs font-bold transition-colors ${activeTab === tab.id ? 'border-cobalt-600 text-cobalt-700 dark:text-cobalt-300' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'}`}><Icon className="h-4 w-4" />{tab.label}{tab.count !== undefined && <span className="rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] dark:bg-slate-800">{tab.count}</span>}</button>;
        })}
      </nav>

      {error && <div className="mx-4 mt-4 flex items-start justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300 sm:mx-6"><span>{error}</span><button type="button" onClick={() => setError('')} aria-label="Đóng lỗi"><X className="h-4 w-4" /></button></div>}

      <div className="flex-1 overflow-y-auto p-4 sm:p-6">
        {activeTab === 'blueai' && <BlueAiCorePanel configuration={configuration} onChange={bot => updateConfiguration(current => ({ ...current, bot }))} />}
        {activeTab === 'intents' && <IntentsEditor intents={configuration.intents} onChange={intents => updateConfiguration(current => ({ ...current, intents }))} />}
        {activeTab === 'entities' && <EntitiesEditor entities={configuration.entities} onChange={entities => updateConfiguration(current => ({ ...current, entities }))} />}
        {activeTab === 'dialog' && <DialogEditor nodes={configuration.dialogNodes} intents={configuration.intents} entities={configuration.entities} onChange={dialogNodes => updateConfiguration(current => ({ ...current, dialogNodes }))} />}
        {activeTab === 'integrate' && <IntegrationPanel configuration={configuration} />}
      </div>

      {isPreviewOpen && <TryItOutPanel botId={configuration.bot.id} botName={configuration.bot.name} onClose={() => setIsPreviewOpen(false)} />}

      {isCreateOpen && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-sm">
          <form onSubmit={createBot} className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
            <div className="flex items-center justify-between"><h2 className="text-lg font-black">Tạo cấu hình BlueAI Custom mới</h2><button type="button" onClick={() => setIsCreateOpen(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><X className="h-4 w-4" /></button></div>
            <p className="mt-1 text-sm text-slate-500">Khởi tạo một workspace độc lập với dữ liệu mẫu tiếng Việt.</p>
            <label className="mt-5 block"><span className={labelClass}>Tên cấu hình</span><input value={newBotName} onChange={event => setNewBotName(event.target.value)} className={inputClass} autoFocus /></label>
            <div className="mt-5 flex justify-end gap-2"><button type="button" onClick={() => setIsCreateOpen(false)} className="rounded-xl px-4 py-2 text-sm font-bold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">Huỷ</button><button type="submit" className="rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white hover:bg-cobalt-700">Tạo với dữ liệu mẫu</button></div>
          </form>
        </div>
      )}
    </section>
  );
};

function IntentsEditor({ intents, onChange }: { intents: StudioIntent[]; onChange: (value: StudioIntent[]) => void }) {
  const [selected, setSelected] = React.useState(0);
  React.useEffect(() => { if (selected >= intents.length) setSelected(Math.max(0, intents.length - 1)); }, [intents.length, selected]);
  const current = intents[selected];
  const update = (patch: Partial<StudioIntent>) => onChange(intents.map((intent, index) => index === selected ? { ...intent, ...patch } : intent));
  const add = () => { onChange([...intents, { name: `intent_${intents.length + 1}`, description: '', examples: ['Ví dụ câu hỏi'] }]); setSelected(intents.length); };
  const remove = () => current && onChange(intents.filter((_, index) => index !== selected));

  return <EditorLayout
    title="Intents" description="Nhóm các cách diễn đạt có cùng mục đích của người dùng. Nên có ít nhất 5 ví dụ đa dạng cho mỗi intent."
    items={intents.map(intent => ({ title: `#${intent.name}`, subtitle: `${intent.examples.length} câu mẫu` }))}
    selected={selected} onSelect={setSelected} onAdd={add} addLabel="Thêm intent"
  >
    {current ? <div className="space-y-5">
      <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cobalt-600">Intent đang chọn</p><h2 className="mt-1 text-xl font-black">#{current.name}</h2></div><button type="button" onClick={remove} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40" aria-label="Xoá intent"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 md:grid-cols-2"><label><span className={labelClass}>Tên intent</span><input value={current.name} onChange={event => update({ name: event.target.value.replace(/\s+/g, '_') })} className={inputClass} /></label><label><span className={labelClass}>Mô tả</span><input value={current.description || ''} onChange={event => update({ description: event.target.value })} className={inputClass} /></label></div>
      <label className="block"><span className={labelClass}>Câu người dùng mẫu — mỗi dòng một câu</span><textarea value={current.examples.join('\n')} onChange={event => update({ examples: event.target.value.split('\n').map(item => item.trim()).filter(Boolean) })} rows={12} className={`${inputClass} resize-y font-medium leading-7`} placeholder={'Xin chào\nChào BlueAI\nBạn có thể giúp gì?'} /></label>
    </div> : <EmptySelection label="intent" />}
  </EditorLayout>;
}

function EntitiesEditor({ entities, onChange }: { entities: StudioEntity[]; onChange: (value: StudioEntity[]) => void }) {
  const [selected, setSelected] = React.useState(0);
  React.useEffect(() => { if (selected >= entities.length) setSelected(Math.max(0, entities.length - 1)); }, [entities.length, selected]);
  const current = entities[selected];
  const update = (patch: Partial<StudioEntity>) => onChange(entities.map((entity, index) => index === selected ? { ...entity, ...patch } : entity));
  const add = () => { onChange([...entities, { name: `entity_${entities.length + 1}`, description: '', values: [{ value: 'Giá trị', synonyms: [] }] }]); setSelected(entities.length); };
  const remove = () => current && onChange(entities.filter((_, index) => index !== selected));

  return <EditorLayout
    title="Entities" description="Chuẩn hoá từ khoá nghiệp vụ thành giá trị chính tắc; synonym giúp nhận diện cách gọi địa phương hoặc tên viết tắt."
    items={entities.map(entity => ({ title: `@${entity.name}`, subtitle: `${entity.values.length} giá trị` }))}
    selected={selected} onSelect={setSelected} onAdd={add} addLabel="Thêm entity"
  >
    {current ? <div className="space-y-5">
      <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-cyan-600">Entity đang chọn</p><h2 className="mt-1 text-xl font-black">@{current.name}</h2></div><button type="button" onClick={remove} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40" aria-label="Xoá entity"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 md:grid-cols-2"><label><span className={labelClass}>Tên entity</span><input value={current.name} onChange={event => update({ name: event.target.value.replace(/\s+/g, '_') })} className={inputClass} /></label><label><span className={labelClass}>Mô tả</span><input value={current.description || ''} onChange={event => update({ description: event.target.value })} className={inputClass} /></label></div>
      <div><div className="mb-2 flex items-center justify-between"><span className={labelClass}>Từ điển giá trị và từ đồng nghĩa</span><button type="button" onClick={() => update({ values: [...current.values, { value: 'Giá trị mới', synonyms: [] }] })} className="inline-flex items-center gap-1 text-xs font-bold text-cobalt-600"><Plus className="h-3.5 w-3.5" />Thêm giá trị</button></div>
        <div className="space-y-2">{current.values.map((entityValue, valueIndex) => <div key={valueIndex} className="grid gap-2 rounded-xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950 md:grid-cols-[0.8fr_1.5fr_auto]">
          <input value={entityValue.value} onChange={event => update({ values: current.values.map((value, index) => index === valueIndex ? { ...value, value: event.target.value } : value) })} className={inputClass} aria-label={`Giá trị entity ${valueIndex + 1}`} />
          <input value={entityValue.synonyms.join(', ')} onChange={event => update({ values: current.values.map((value, index) => index === valueIndex ? { ...value, synonyms: event.target.value.split(',').map(item => item.trim()).filter(Boolean) } : value) })} className={inputClass} placeholder="Từ đồng nghĩa, cách viết tắt" aria-label={`Từ đồng nghĩa ${valueIndex + 1}`} />
          <button type="button" disabled={current.values.length === 1} onClick={() => update({ values: current.values.filter((_, index) => index !== valueIndex) })} className="rounded-lg p-2 text-slate-400 hover:text-rose-600 disabled:opacity-30" aria-label={`Xoá giá trị ${valueIndex + 1}`}><Trash2 className="h-4 w-4" /></button>
        </div>)}</div>
      </div>
    </div> : <EmptySelection label="entity" />}
  </EditorLayout>;
}

function DialogEditor({ nodes, intents, entities, onChange }: { nodes: StudioDialogNode[]; intents: StudioIntent[]; entities: StudioEntity[]; onChange: (value: StudioDialogNode[]) => void }) {
  const [selected, setSelected] = React.useState(0);
  const [openMenu, setOpenMenu] = React.useState<number | null>(null);
  const [isMoveOpen, setIsMoveOpen] = React.useState(false);
  const [moveParent, setMoveParent] = React.useState('');
  React.useEffect(() => { if (selected >= nodes.length) setSelected(Math.max(0, nodes.length - 1)); }, [nodes.length, selected]);
  const current = nodes[selected];
  const update = (patch: Partial<StudioDialogNode>) => onChange(nodes.map((node, index) => index === selected ? { ...node, ...patch } : node));
  const makeName = (base: string) => {
    const used = new Set(nodes.map(node => node.name.toLocaleLowerCase()));
    if (!used.has(base.toLocaleLowerCase())) return base;
    let suffix = 2;
    while (used.has(`${base} ${suffix}`.toLocaleLowerCase())) suffix += 1;
    return `${base} ${suffix}`;
  };
  const makeNode = (type: StudioDialogNode['type'], parentNode = ''): StudioDialogNode => ({
    name: makeName(type === 'FOLDER' ? 'Nhóm hội thoại' : 'Nhánh mới'),
    type,
    parentNode,
    matchIntent: type === 'ROUTE' ? intents[0]?.name || '' : '',
    matchEntityName: '',
    matchEntityValue: '',
    requiredEntityName: '',
    responseText: type === 'FOLDER' ? '' : 'Nội dung phản hồi',
    missingEntityPrompt: '',
    nextStep: type === 'FOLDER' ? 'SKIP_USER_INPUT' : 'WAIT_FOR_REPLY',
    jumpToNode: '',
    irrelevantResponseText: '',
    priority: Math.max(0, ...nodes.map(node => node.priority)) + 10,
  });
  const resequence = (value: StudioDialogNode[]) => value.map((node, index) => ({ ...node, priority: index * 10 }));
  const insertNode = (node: StudioDialogNode, index = nodes.length) => {
    const next = [...nodes];
    next.splice(index, 0, node);
    const sequenced = resequence(next);
    onChange(sequenced);
    setSelected(sequenced.findIndex(item => item === sequenced[index]));
    setOpenMenu(null);
  };
  const add = (type: StudioDialogNode['type'] = 'ROUTE', parentNode = '') => {
    const fallbackIndex = nodes.findIndex(node => node.type === 'FALLBACK');
    const insertAt = !parentNode && fallbackIndex >= 0 ? fallbackIndex : nodes.length;
    insertNode(makeNode(type, parentNode), insertAt);
  };
  const rename = (name: string) => {
    if (!current) return;
    const previousName = current.name;
    onChange(nodes.map((node, index) => ({
      ...node,
      name: index === selected ? name : node.name,
      parentNode: node.parentNode === previousName ? name : node.parentNode,
      jumpToNode: node.jumpToNode === previousName ? name : node.jumpToNode,
    })));
  };
  const remove = () => {
    if (!current || current.type === 'WELCOME' || current.type === 'FALLBACK') return;
    const next = nodes
      .filter((_, index) => index !== selected)
      .map(node => ({
        ...node,
        parentNode: node.parentNode === current.name ? current.parentNode || '' : node.parentNode,
        jumpToNode: node.jumpToNode === current.name ? '' : node.jumpToNode,
        nextStep: node.jumpToNode === current.name ? 'WAIT_FOR_REPLY' as const : node.nextStep,
      }));
    onChange(resequence(next));
    setSelected(Math.max(0, selected - 1));
    setOpenMenu(null);
  };
  const addRelative = (offset: 0 | 1) => current && insertNode(makeNode('ROUTE', current.parentNode || ''), selected + offset);
  const duplicate = () => {
    if (!current || current.type === 'WELCOME' || current.type === 'FALLBACK') return;
    insertNode({ ...current, name: makeName(`${current.name} copy`), priority: current.priority + 1 }, selected + 1);
  };
  const descendants = current ? collectDialogDescendants(nodes, current.name) : new Set<string>();
  const allowedParents = nodes.filter(node => node !== current && !descendants.has(node.name));
  const move = () => {
    update({ parentNode: moveParent });
    setIsMoveOpen(false);
    setOpenMenu(null);
  };
  const treeRows = buildDialogTree(nodes);
  const nextStep = current?.nextStep || 'WAIT_FOR_REPLY';

  return <div className="mx-auto grid max-w-7xl gap-5 lg:grid-cols-[390px_minmax(0,1fr)]">
    <aside className="overflow-visible rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div className="border-b border-slate-200 p-4 dark:border-slate-800">
        <h1 className="text-lg font-black">Dialog</h1>
        <p className="mt-1 text-xs leading-5 text-slate-500">Xây cây hội thoại bằng node cha/con. Priority quyết định thứ tự xét giữa các node cùng cấp.</p>
        <div className="mt-4 grid grid-cols-2 gap-2">
          <button type="button" onClick={() => add('ROUTE')} className="inline-flex items-center justify-center gap-2 rounded-xl bg-cobalt-600 px-3 py-2.5 text-xs font-bold text-white hover:bg-cobalt-700"><Plus className="h-4 w-4" />Thêm node</button>
          <button type="button" onClick={() => add('FOLDER')} className="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-3 py-2.5 text-xs font-bold text-slate-700 hover:border-cobalt-300 hover:text-cobalt-700 dark:border-slate-700 dark:text-slate-200"><FolderPlus className="h-4 w-4" />Thêm folder</button>
        </div>
      </div>
      <div className="max-h-[62vh] overflow-y-auto p-2">
        {treeRows.map(row => <div key={`${row.node.name}-${row.index}`} className="relative mb-1">
          <button type="button" onClick={() => { setSelected(row.index); setOpenMenu(null); }} style={{ paddingLeft: `${12 + row.depth * 22}px` }} className={`flex w-full items-center gap-2 rounded-xl py-3 pr-10 text-left ${selected === row.index ? 'bg-cobalt-50 text-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}`}>
            {row.node.type === 'FOLDER' ? <FolderPlus className="h-4 w-4 shrink-0 text-amber-500" /> : <GitBranch className="h-4 w-4 shrink-0 text-cobalt-500" />}
            <div className="min-w-0 flex-1"><p className="truncate text-sm font-bold">{row.node.name}</p><p className="mt-0.5 truncate text-[11px] text-slate-500">{dialogNodeSubtitle(row.node, nodes)}</p></div>
          </button>
          <button type="button" onClick={event => { event.stopPropagation(); setSelected(row.index); setOpenMenu(openMenu === row.index ? null : row.index); }} className="absolute right-2 top-2.5 rounded-lg p-1.5 text-slate-400 hover:bg-white hover:text-slate-700 dark:hover:bg-slate-800" aria-label={`Mở menu ${row.node.name}`}><MoreVertical className="h-4 w-4" /></button>
          {openMenu === row.index && <div className="absolute right-2 top-10 z-40 w-48 rounded-xl border border-slate-200 bg-white p-1.5 shadow-xl dark:border-slate-700 dark:bg-slate-900">
            <DialogMenuButton icon={ChevronRight} label="Add child node" onClick={() => add('ROUTE', row.node.name)} />
            <DialogMenuButton icon={ArrowUp} label="Add node above" onClick={() => addRelative(0)} />
            <DialogMenuButton icon={ArrowDown} label="Add node below" onClick={() => addRelative(1)} />
            <DialogMenuButton icon={FolderPlus} label="Add folder" onClick={() => add('FOLDER', row.node.parentNode || '')} />
            <DialogMenuButton icon={MoveRight} label="Move" disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={() => { setMoveParent(row.node.parentNode || ''); setIsMoveOpen(true); }} />
            <DialogMenuButton icon={CopyPlus} label="Duplicate" disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={duplicate} />
            <DialogMenuButton icon={GitBranch} label="Jump to" disabled={row.node.type === 'FOLDER'} onClick={() => { update({ nextStep: 'JUMP_TO' }); setOpenMenu(null); }} />
            <DialogMenuButton icon={Trash2} label="Delete" danger disabled={row.node.type === 'WELCOME' || row.node.type === 'FALLBACK'} onClick={remove} />
          </div>}
        </div>)}
      </div>
    </aside>
    <main className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-6">
    {current ? <div className="space-y-5">
      <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-violet-600">Dialog node</p><h2 className="mt-1 text-xl font-black">{current.name}</h2></div><button type="button" onClick={remove} disabled={current.type === 'WELCOME' || current.type === 'FALLBACK'} className="rounded-xl p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-30 dark:hover:bg-rose-950/40" aria-label="Xoá dialog node"><Trash2 className="h-4 w-4" /></button></div>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4"><label><span className={labelClass}>Tên node</span><input value={current.name} onChange={event => rename(event.target.value)} className={inputClass} /></label><label><span className={labelClass}>Loại node</span><select value={current.type} onChange={event => update({ type: event.target.value as StudioDialogNode['type'], parentNode: ['WELCOME', 'FALLBACK'].includes(event.target.value) ? '' : current.parentNode })} className={inputClass}><option value="WELCOME">Welcome</option><option value="ROUTE">Route</option><option value="FALLBACK">Fallback</option><option value="FOLDER">Folder</option></select></label><label><span className={labelClass}>Node cha</span><select value={current.parentNode || ''} onChange={event => update({ parentNode: event.target.value })} className={inputClass} disabled={current.type === 'WELCOME' || current.type === 'FALLBACK'}><option value="">Cấp gốc</option>{allowedParents.map(node => <option key={node.name} value={node.name}>{node.name}</option>)}</select></label><label><span className={labelClass}>Priority</span><input type="number" value={current.priority} onChange={event => update({ priority: Number(event.target.value) })} className={inputClass} /></label></div>
      {current.type === 'FOLDER' ? <div className="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm leading-6 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-100"><div className="flex items-center gap-2 font-black"><FolderPlus className="h-4 w-4" />Folder nhóm các nhánh hội thoại</div><p className="mt-2">Folder không gửi phản hồi. Khi luồng đi vào folder, BlueAI tự đánh giá các node con theo điều kiện và priority.</p></div> : <>
      {current.type === 'ROUTE' && <div className="rounded-2xl border border-cobalt-100 bg-cobalt-50/60 p-4 dark:border-cobalt-900 dark:bg-cobalt-950/30"><p className="mb-3 text-xs font-black text-cobalt-800 dark:text-cobalt-200">Nếu BlueAI nhận diện</p><div className="grid gap-3 md:grid-cols-2">
        <label><span className={labelClass}>Intent</span><select value={current.matchIntent || ''} onChange={event => update({ matchIntent: event.target.value })} className={inputClass}><option value="">Không bắt buộc</option>{intents.map(intent => <option key={intent.name} value={intent.name}>#{intent.name}</option>)}</select></label>
        <label><span className={labelClass}>Entity cần thu thập</span><select value={current.requiredEntityName || ''} onChange={event => update({ requiredEntityName: event.target.value })} className={inputClass}><option value="">Không dùng slot</option>{entities.map(entity => <option key={entity.name} value={entity.name}>@{entity.name}</option>)}</select></label>
        <label><span className={labelClass}>Entity điều kiện</span><select value={current.matchEntityName || ''} onChange={event => update({ matchEntityName: event.target.value, matchEntityValue: '' })} className={inputClass}><option value="">Mọi entity</option>{entities.map(entity => <option key={entity.name} value={entity.name}>@{entity.name}</option>)}</select></label>
        <label><span className={labelClass}>Giá trị entity</span><select value={current.matchEntityValue || ''} onChange={event => update({ matchEntityValue: event.target.value })} className={inputClass} disabled={!current.matchEntityName}><option value="">Mọi giá trị</option>{entities.find(entity => entity.name === current.matchEntityName)?.values.map(value => <option key={value.value} value={value.value}>{value.value}</option>)}</select></label>
      </div></div>}
      <label className="block"><span className={labelClass}>Assistant responds</span><textarea value={current.responseText} onChange={event => update({ responseText: event.target.value })} rows={5} className={`${inputClass} resize-y leading-6`} placeholder="Bạn có thể dùng {{location}} để chèn giá trị entity." /></label>
      {current.type === 'ROUTE' && current.requiredEntityName && <label className="block"><span className={labelClass}>Câu hỏi khi thiếu entity</span><textarea value={current.missingEntityPrompt || ''} onChange={event => update({ missingEntityPrompt: event.target.value })} rows={3} className={`${inputClass} resize-y`} placeholder={`Bạn muốn hỏi cho ${current.requiredEntityName} nào?`} /></label>}
      <div className="space-y-4 rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950">
        <div className="flex items-center gap-2"><CircleDot className="h-4 w-4 text-emerald-500" /><p className="text-xs font-black text-slate-800 dark:text-slate-100">Then assistant should</p></div>
        <div className="grid gap-3 md:grid-cols-2">
          <label><span className={labelClass}>Hành động tiếp theo</span><select value={nextStep} onChange={event => update({ nextStep: event.target.value as StudioDialogNode['nextStep'], jumpToNode: event.target.value === 'JUMP_TO' ? current.jumpToNode || '' : '' })} className={inputClass}><option value="WAIT_FOR_REPLY">Wait for reply</option><option value="SKIP_USER_INPUT">Skip user input</option><option value="JUMP_TO">Jump to</option></select></label>
          {nextStep === 'JUMP_TO' && <label><span className={labelClass}>Node đích</span><select value={current.jumpToNode || ''} onChange={event => update({ jumpToNode: event.target.value })} className={inputClass}><option value="">Chọn node đích</option>{nodes.filter(node => node !== current).map(node => <option key={node.name} value={node.name}>{node.name}</option>)}</select></label>}
        </div>
        <p className="text-xs leading-5 text-slate-500">{nextStep === 'WAIT_FOR_REPLY' ? 'Dừng tại node này và chờ người dùng gửi câu tiếp theo.' : nextStep === 'SKIP_USER_INPUT' ? 'Không chờ input; đánh giá node con trước, sau đó mới xét node kế tiếp cùng cấp.' : 'Không chờ input; chuyển thẳng đến node đã chọn.'}</p>
        {nextStep === 'WAIT_FOR_REPLY' && <label className="block"><span className={labelClass}>Phản hồi khi user trả lời không liên quan</span><textarea value={current.irrelevantResponseText || ''} onChange={event => update({ irrelevantResponseText: event.target.value })} rows={3} className={`${inputClass} resize-y`} placeholder="Mình chưa hiểu câu trả lời này. Bạn vui lòng thử lại nhé." /><span className="mt-1.5 block text-[11px] leading-5 text-slate-500">Được dùng khi hệ thống đang chờ tại node này nhưng không nhận diện được intent hoặc entity phù hợp. Để trống sẽ dùng Fallback.</span></label>}
      </div></>}
    </div> : <EmptySelection label="dialog node" />}
    </main>
    {isMoveOpen && current && <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm"><div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-slate-700 dark:bg-slate-900"><h2 className="text-lg font-black">Move “{current.name}”</h2><p className="mt-1 text-sm text-slate-500">Chọn vị trí cha mới trong cây Dialog.</p><label className="mt-5 block"><span className={labelClass}>Node cha mới</span><select value={moveParent} onChange={event => setMoveParent(event.target.value)} className={inputClass}><option value="">Cấp gốc</option>{allowedParents.map(node => <option key={node.name} value={node.name}>{node.name}</option>)}</select></label><div className="mt-6 flex justify-end gap-2"><button type="button" onClick={() => setIsMoveOpen(false)} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-bold dark:border-slate-700">Huỷ</button><button type="button" onClick={move} className="rounded-xl bg-cobalt-600 px-4 py-2 text-sm font-bold text-white">Move node</button></div></div></div>}
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
  if (node.type === 'FOLDER') return `${nodes.filter(item => item.parentNode === node.name).length} node con`;
  const action = node.nextStep === 'SKIP_USER_INPUT' ? 'Skip user input' : node.nextStep === 'JUMP_TO' ? `Jump to ${node.jumpToNode || '…'}` : 'Wait for reply';
  return `${node.type === 'ROUTE' ? `#${node.matchIntent || 'chưa_chọn'}` : node.type} · ${action}`;
}

function EditorLayout({ title, description, items, selected, onSelect, onAdd, addLabel, children }: { title: string; description: string; items: { title: string; subtitle: string }[]; selected: number; onSelect: (index: number) => void; onAdd: () => void; addLabel: string; children: React.ReactNode }) {
  return <div className="mx-auto grid max-w-7xl gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
    <aside className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-[#0B1324]">
      <div className="border-b border-slate-200 p-4 dark:border-slate-800"><h1 className="text-lg font-black">{title}</h1><p className="mt-1 text-xs leading-5 text-slate-500">{description}</p><button type="button" onClick={onAdd} className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-cobalt-600 px-3 py-2.5 text-xs font-bold text-white hover:bg-cobalt-700"><Plus className="h-4 w-4" />{addLabel}</button></div>
      <div className="max-h-[58vh] overflow-y-auto p-2">{items.length === 0 ? <p className="px-3 py-8 text-center text-xs text-slate-400">Chưa có dữ liệu.</p> : items.map((item, index) => <button key={`${item.title}-${index}`} type="button" onClick={() => onSelect(index)} className={`mb-1 flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left ${selected === index ? 'bg-cobalt-50 text-cobalt-800 dark:bg-cobalt-950 dark:text-cobalt-200' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}`}><div className="min-w-0 flex-1"><p className="truncate text-sm font-bold">{item.title}</p><p className="mt-0.5 truncate text-[11px] text-slate-500">{item.subtitle}</p></div><ChevronRight className="h-4 w-4 shrink-0 text-slate-400" /></button>)}</div>
    </aside>
    <main className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-6">{children}</main>
  </div>;
}

function BlueAiCorePanel({ configuration, onChange }: { configuration: StudioBotConfiguration; onChange: (bot: StudioBotConfiguration['bot']) => void }) {
  const enabled = configuration.bot.blueAiCoreEnabled !== false;
  const update = (patch: Partial<StudioBotConfiguration['bot']>) => onChange({ ...configuration.bot, ...patch });

  return <div className="mx-auto max-w-5xl space-y-5">
    <div className="overflow-hidden rounded-3xl border border-cobalt-200 bg-gradient-to-br from-cobalt-700 via-cobalt-600 to-cyan-600 p-6 text-white shadow-xl shadow-cobalt-500/15 sm:p-8">
      <div className="flex flex-col justify-between gap-6 sm:flex-row sm:items-start">
        <div className="max-w-2xl"><div className="flex items-center gap-2 text-xs font-black uppercase tracking-[0.18em] text-cyan-100"><BrainCircuit className="h-4 w-4" />BlueAI Custom</div><h1 className="mt-3 text-2xl font-black sm:text-3xl">Tùy chỉnh chính BlueAI cho từng nghiệp vụ</h1><p className="mt-3 text-sm leading-6 text-blue-50">Cấu hình này dùng chung BlueAI Core, mô hình ngôn ngữ, kho tri thức RAG, tài khoản và guardrails hiện có. Tên, Dialog và chỉ dẫn bên dưới tạo một trải nghiệm chuyên biệt trong BlueAI.</p></div>
        <button type="button" role="switch" aria-checked={enabled} onClick={() => update({ blueAiCoreEnabled: !enabled })} className={`inline-flex shrink-0 items-center gap-2 rounded-full border px-4 py-2 text-xs font-black ${enabled ? 'border-white/40 bg-white text-cobalt-700' : 'border-white/30 bg-slate-950/20 text-white'}`}><span className={`h-2.5 w-2.5 rounded-full ${enabled ? 'bg-emerald-500' : 'bg-slate-400'}`} />{enabled ? 'BlueAI Core đang bật' : 'BlueAI Core đang tắt'}</button>
      </div>
    </div>

    <div className="grid gap-4 md:grid-cols-3">
      <CoreFeature icon={BrainCircuit} title="LLM dùng chung" description="Các câu ngoài Dialog được BlueAI Core xử lý bằng cùng runtime AI của hệ thống." />
      <CoreFeature icon={Boxes} title="Knowledge & RAG" description="Câu trả lời truy xuất kho tri thức đã duyệt của đúng tài khoản sở hữu cấu hình." />
      <CoreFeature icon={ShieldCheck} title="Auth & guardrails" description="Kế thừa phân quyền, xác minh nguồn và chính sách an toàn của BlueAI." />
    </div>

    <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-[#0B1324] sm:p-8">
      <div className="flex items-start gap-3"><div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-violet-100 text-violet-700 dark:bg-violet-950 dark:text-violet-300"><Sparkles className="h-5 w-5" /></div><div><h2 className="text-base font-black">Chỉ dẫn tùy chỉnh</h2><p className="mt-1 text-sm leading-6 text-slate-500">BlueAI áp dụng chỉ dẫn này sau các quy tắc nhận diện danh tính và trích dẫn nguồn của hệ thống.</p></div></div>
      <textarea value={configuration.bot.customInstructions || ''} onChange={event => update({ customInstructions: event.target.value })} rows={8} maxLength={2000} disabled={!enabled} className={`${inputClass} mt-5 resize-y leading-6 disabled:cursor-not-allowed disabled:opacity-50`} placeholder="Ví dụ: Trả lời như chuyên viên chăm sóc khách hàng của BlueAI; ưu tiên tiếng Việt; hỏi lại mã đơn hàng khi cần tra cứu..." />
      <div className="mt-2 flex justify-between text-[11px] text-slate-400"><span>Dialog vẫn được ưu tiên trước BlueAI Core.</span><span>{(configuration.bot.customInstructions || '').length}/2000</span></div>
      {!enabled && <p className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-xs leading-5 text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">Khi tắt BlueAI Core, chỉ các node Dialog và Fallback cố định được dùng để trả lời.</p>}
    </div>
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
      <div className="flex items-start gap-4"><div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl ${isPublished ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'}`}>{isPublished ? <CheckCircle2 className="h-6 w-6" /> : <Rocket className="h-6 w-6" />}</div><div><h1 className="text-xl font-black">{isPublished ? 'BlueAI Custom đã sẵn sàng để nhúng' : 'Phát hành cấu hình trước khi tích hợp'}</h1><p className="mt-1 text-sm leading-6 text-slate-500">Mỗi phiên widget được ghim vào đúng release đã phát hành. Các thay đổi bản nháp sẽ không làm đổi cuộc hội thoại đang diễn ra.</p></div></div>
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
