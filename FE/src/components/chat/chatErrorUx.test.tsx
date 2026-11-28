// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blueAiApi, BlueAiApiError, type ConversationMessageResponse, type RagAnswerResponse } from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { ChatErrorNotice } from './ChatErrorNotice';
import { Composer } from './Composer';
import { MessageItem } from './MessageItem';
import { RagMiniChat } from './RagMiniChat';

const auth = vi.hoisted(() => ({ status: 'authenticated', user: { role: 'USER' } }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => auth }));
vi.mock('./ChatQuotaContext', () => ({
  useSharedChatQuota: () => ({ blocked: false, seconds: 0, aiError: '', refresh: vi.fn() }),
}));

let host: HTMLDivElement;
let root: Root;
let store: ReturnType<typeof useAppStore>;
type ChatInput = HTMLInputElement | HTMLTextAreaElement;

function MainChat() {
  store = useAppStore();
  const messages = store.sessions.find(session => session.id === store.activeSessionId)?.messages || [];
  return <>{messages.map(message => <MessageItem key={message.id} message={message} />)}<Composer /></>;
}

function answer(answerStatus?: ConversationMessageResponse['answerStatus']): RagAnswerResponse {
  return {
    userMessage: { id: 'user', role: 'USER', content: '你好 — Chính sách hoàn tiền?', createdAt: '2026-11-28T10:00:00Z' },
    assistantMessage: { id: crypto.randomUUID(), role: 'ASSISTANT', content: '暂时无法生成准确回答，请稍后重试。', createdAt: '2026-11-28T10:00:01Z', answerStatus },
    sources: [],
  };
}

async function typeText(input: ChatInput, value: string) {
  await act(async () => {
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function enter(input: ChatInput) {
  const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
  await act(async () => { input.dispatchEvent(event); });
  expect(event.defaultPrevented).toBe(true);
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  auth.status = 'authenticated';
  auth.user.role = 'USER';
  vi.spyOn(blueAiApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(blueAiApi, 'createConversation').mockResolvedValue({ id: crypto.randomUUID(), contextMode: 'CURRENT_CONVERSATION' } as Awaited<ReturnType<typeof blueAiApi.createConversation>>);
  vi.spyOn(blueAiApi, 'askQuestion').mockResolvedValue(answer());
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  window.history.replaceState(null, '', '/');
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe.each([
  { name: 'main composer', render: () => <MainChat />, selector: 'textarea' },
  { name: 'mini chat', render: () => <RagMiniChat title="Support" placeholder="Nhập câu hỏi" conversationTitle="Support" />, selector: 'input' },
])('$name error recovery', surface => {
  async function show() {
    await act(async () => root.render(surface.render()));
    if (surface.name === 'main composer') await act(async () => store.createNewSession());
    const input = host.querySelector<ChatInput>(surface.selector)!;
    input.focus();
    await typeText(input, '你好 — Tôi muốn hỏi chính sách hoàn tiền.');
    return input;
  }

  it.each([
    ['LLM_RESPONSE_LANGUAGE_MISMATCH', 502, 'Lỗi ngôn ngữ phản hồi'],
    ['AI_TOKEN_QUOTA_EXHAUSTED', 429, 'Giới hạn sử dụng'],
    ['PRODUCT_UPSTREAM_FAILED', 502, 'Lỗi dịch vụ'],
    ['API_UNREACHABLE', 0, 'Lỗi kết nối'],
  ] as const)('retains draft, focus and retry after %s without disclosing upstream details', async (code, status, label) => {
    vi.mocked(blueAiApi.askQuestion).mockRejectedValueOnce(new BlueAiApiError('API_KEY=private upstream configuration', status, code));
    const input = await show();
    const draft = input.value;
    await enter(input);
    expect(input.value).toBe(draft);
    expect(document.activeElement).toBe(input);
    expect(host.querySelector('[role="status"]')?.textContent).toContain(label);
    expect(host.textContent).not.toMatch(/API_KEY|private|upstream|Chưa đủ căn cứ xác thực/);
    expect(host.textContent).not.toContain(code);
    expect(host.querySelector('details')).toBeNull();
    if (surface.name === 'main composer') {
      expect(store.streamStatus).toBe('error');
      expect(store.toast?.message).not.toMatch(/API_KEY|private|upstream/);
    }
    await enter(input);
    expect(blueAiApi.askQuestion).toHaveBeenCalledTimes(2);
    expect(blueAiApi.askQuestion).toHaveBeenLastCalledWith(expect.any(String), draft);
    expect(input.value).toBe('');
    expect(document.activeElement).toBe(input);
  });

  it('keeps the draft for a localized HTTP 200 safe fallback', async () => {
    vi.mocked(blueAiApi.askQuestion).mockResolvedValueOnce(answer('SAFE_FALLBACK'));
    const input = await show();
    const draft = input.value;
    await enter(input);
    expect(input.value).toBe(draft);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Lỗi dịch vụ');
    expect(host.textContent).toContain('暂时无法生成准确回答');
    expect(host.textContent).not.toMatch(/Ngoài phạm vi kho tri thức|Chưa đủ căn cứ xác thực|Nguồn tham chiếu/);
    expect(document.activeElement).toBe(input);
  });

  it.each(['NEEDS_DETAILS', 'INSUFFICIENT_EVIDENCE'] as const)('renders %s as an answer, not a service failure', async status => {
    vi.mocked(blueAiApi.askQuestion).mockResolvedValueOnce(answer(status));
    const input = await show();
    await enter(input);
    expect(input.value).toBe('');
    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(host.textContent).not.toMatch(/Lỗi dịch vụ|Ngoài phạm vi kho tri thức/);
    if (status === 'INSUFFICIENT_EVIDENCE') expect(host.textContent).toContain('Chưa đủ căn cứ xác thực');
    else expect(host.textContent).not.toContain('Chưa đủ căn cứ xác thực');
  });

  it('preserves edits made while the request is pending', async () => {
    let finish!: (value: RagAnswerResponse) => void;
    vi.mocked(blueAiApi.askQuestion).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const input = await show();
    await enter(input);
    await typeText(input, 'Dòng tiếp theo — câu hỏi tiếng Việt mới.');
    await act(async () => { finish(answer('ANSWERED')); });
    expect(input.value).toBe('Dòng tiếp theo — câu hỏi tiếng Việt mới.');
    expect(document.activeElement).toBe(input);
  });

  it('handles conversation creation failure without losing the draft', async () => {
    vi.mocked(blueAiApi.createConversation).mockRejectedValueOnce(new TypeError('fetch failed at internal.example'));
    const input = await show();
    const draft = input.value;
    await enter(input);
    expect(input.value).toBe(draft);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Lỗi kết nối');
    expect(host.textContent).not.toContain('internal.example');
    expect(blueAiApi.askQuestion).not.toHaveBeenCalled();
  });
});

describe('diagnostic code disclosure', () => {
  it('ignores a local admin query parameter for an ordinary user', async () => {
    window.history.replaceState(null, '', '/?role=admin');
    await act(async () => root.render(<ChatErrorNotice error={{ category: 'service', code: 'PRODUCT_UPSTREAM_FAILED' }} content="Vui lòng thử lại." />));
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toContain('PRODUCT_UPSTREAM_FAILED');
  });

  it('shows only a collapsible code to an authenticated administrator', async () => {
    auth.user.role = 'ADMIN';
    await act(async () => root.render(<ChatErrorNotice error={{ category: 'service', code: 'LLM_REQUEST_FAILED' }} content="Vui lòng thử lại." />));
    const details = host.querySelector('details')!;
    const summary = details.querySelector('summary')!;
    expect(details.open).toBe(false);
    summary.focus();
    expect(document.activeElement).toBe(summary);
    await act(async () => summary.click());
    expect(details.open).toBe(true);
    expect(details.textContent).toContain('LLM_REQUEST_FAILED');
    expect(details.textContent).not.toMatch(/provider|API_KEY|https?:/);
  });

  it('does not disclose codes while authentication is being checked or when a code contains arbitrary text', async () => {
    auth.user.role = 'ADMIN';
    auth.status = 'checking';
    await act(async () => root.render(<ChatErrorNotice error={{ category: 'language', code: 'REPLY_TRANSLATION_FAILED' }} content="Vui lòng thử lại." />));
    expect(host.querySelector('details')).toBeNull();
    auth.status = 'authenticated';
    await act(async () => root.render(<ChatErrorNotice error={{ category: 'service', code: 'API_KEY=secret https://internal.example' }} content="Vui lòng thử lại." />));
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toMatch(/API_KEY|secret|internal\.example/);
  });

  it('suppresses answer grounding, sources and feedback for error metadata', async () => {
    await act(async () => root.render(<MessageItem message={{
      id: 'error', sender: 'system', timestamp: '10:00', content: 'Hãy thử lại.',
      confidence: 'danger', error: { category: 'service' },
      contextSources: [{ type: 'USER_MEMORY', title: 'Stale context' }],
      suggestedFollowUps: ['Stale follow-up'],
    }} />));
    expect(host.textContent).toContain('Lỗi dịch vụ');
    expect(host.textContent).not.toMatch(/Chưa đủ căn cứ|Stale context|Stale follow-up/);
    expect(host.querySelector('[title="Hữu ích"]')).toBeNull();
  });

  it('keeps merged plain-text rendering for normal product answers', async () => {
    await act(async () => root.render(<MessageItem message={{
      id: 'product', sender: 'assistant', timestamp: '10:00', content: '**JP-7D**\nGiá: 80000 VND\n<script>unsafe</script>', confidence: 'confirmed',
    }} />));
    expect(host.textContent).toContain('**JP-7D**\nGiá: 80000 VND\n<script>unsafe</script>');
    expect(host.querySelector('script')).toBeNull();
    expect(host.textContent).not.toContain('tài liệu tham chiếu');
  });
});
