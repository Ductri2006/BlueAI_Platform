// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blueAiApi, conversationMessageToChatMessage, ragAnswerToChatMessage, type RagAnswerResponse } from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { useSharedChatQuota } from './ChatQuotaContext';
import { MessageItem } from './MessageItem';
import { ChatWorkspace } from './ChatWorkspace';
import { RagMiniChat } from './RagMiniChat';
import { RichMarkdownRenderer } from './RichMarkdownRenderer';
import { TryItOutPanel } from '../studio/TryItOutPanel';
import { visibleMessageText, withoutDocumentSources } from './answerText';

vi.mock('../../store/useAppStore', () => ({ useAppStore: vi.fn() }));
vi.mock('./ChatQuotaContext', () => ({ useSharedChatQuota: vi.fn() }));
vi.mock('./Composer', () => ({ Composer: () => <div /> }));
vi.mock('./ConversationContextPanel', () => ({ ConversationContextPanel: () => null }));

const privateFilename = 'INTERNAL_BLUECOM_PRICE_POLICY.pdf';
const privateExcerpt = 'Internal-only approval policy';
const answer: RagAnswerResponse = {
  userMessage: { id: 'u', role: 'USER', content: 'eSIM?', createdAt: '2026-11-28T03:00:00Z' },
  assistantMessage: { id: 'a', role: 'ASSISTANT', content: '**eSIM Nhật Bản**: 10 GB / 30 ngày, 200.000đ [S1].\n\n| Gói | Giá |\n| --- | --- |\n| JP-30 | 200.000đ [S2] |', createdAt: '2026-11-28T03:00:01Z' },
  sources: [1, 2].map(i => ({ documentId: `doc-${i}`, chunkId: `chunk-${i}`, filename: privateFilename, excerpt: privateExcerpt })),
};
let host: HTMLDivElement;
let root: Root;
const copy = vi.fn().mockResolvedValue(undefined);
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.useFakeTimers();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } });
  copy.mockClear();
  vi.mocked(useAppStore).mockReturnValue({
    showToast: vi.fn(), sendMessage: vi.fn(), sessions: [{ id: 'chat', title: 'eSIM', messages: [ragAnswerToChatMessage(answer)] }], activeSessionId: 'chat', streamStatus: 'idle',
  } as unknown as ReturnType<typeof useAppStore>);
  vi.mocked(useSharedChatQuota).mockReturnValue({ blocked: false, seconds: 0, aiError: '', refresh: vi.fn().mockResolvedValue(undefined) } as unknown as ReturnType<typeof useSharedChatQuota>);
  vi.spyOn(blueAiApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(blueAiApi, 'createConversation').mockResolvedValue({ id: 'chat', contextMode: 'CURRENT_CONVERSATION' } as Awaited<ReturnType<typeof blueAiApi.createConversation>>);
  vi.spyOn(blueAiApi, 'askQuestion').mockResolvedValue(answer);
  vi.spyOn(blueAiApi, 'previewStudioBot').mockResolvedValue({
    message: answer.assistantMessage.content, sources: answer.sources, context: { variables: {} },
  } as Awaited<ReturnType<typeof blueAiApi.previewStudioBot>>);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (originalScroll) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
  else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

function assertPrivateSourcesAbsent(text: string) {
  expect(text).not.toMatch(/\[\s*S\d|Nguồn tham chiếu|Xem nguồn tham chiếu|tài liệu tham chiếu|nguồn context tham khảo/i);
  expect(text).not.toContain(privateFilename);
  expect(text).not.toContain(privateExcerpt);
}

describe('customer-facing document references', () => {
  it.each(['[S1]', '[s2]', '[ S3 ]', '[S1,S2]', '[S1, 2, S3]', '[S1; S2]', '**[S1]**', '__[S2]__', '[S3](https://internal.invalid/policy.pdf)'])('removes marker %s', marker => {
    expect(withoutDocumentSources(`Gói 30 ngày ${marker}.`)).toBe('Gói 30 ngày.');
  });

  it.each(['Nguồn tham khảo:', '**Nguồn tham chiếu:**', '## Sources', 'References:', '参考资料：'])('removes a source appendix %s without exposing its filenames', heading => {
    expect(withoutDocumentSources(`Hỗ trợ eSIM.\n\n${heading}\n- [S1] ${privateFilename}\n[S2] ${privateExcerpt}`)).toBe('Hỗ trợ eSIM.');
  });

  it('retains business content after a reference block', () => {
    expect(withoutDocumentSources(`Hỗ trợ.\nSources:\n[S1] ${privateFilename}\n\n## Bước tiếp theo\nBật roaming.`)).toBe('Hỗ trợ.\n## Bước tiếp theo\nBật roaming.');
  });

  it('keeps prices, SKU/model names, ordinary links, numeric lists and unsupported literal brackets', () => {
    const content = 'Samsung S24 Ultra [S24 Ultra], SKU JP-30: 200.000đ / 30 days.\n[1] Enable roaming.\nhttps://shop.example/esim\n[SIMPLE]';
    expect(withoutDocumentSources(content)).toBe(content);
  });

  it('preserves the user message verbatim', () => {
    expect(visibleMessageText({ sender: 'user', content: '  [S1] nghĩa là gì?  ' })).toBe('  [S1] nghĩa là gì?  ');
  });

  it.each(['vi', 'en', 'zh'] as const)('hides sources in %s without changing the answer language', language => {
    const text = { vi: 'Bật chuyển vùng dữ liệu', en: 'Enable data roaming', zh: '请开启数据漫游' }[language];
    expect(withoutDocumentSources(`${text} [S1]。`)).toBe(`${text}。`);
  });

  it('keeps Markdown headings, bold, tables and code while removing markers in every block', () => {
    const html = renderToStaticMarkup(<RichMarkdownRenderer content={'## Hỗ trợ [S1]\n**eSIM [S2]**\n- Data [S3]\n> Roaming [S4]\n| Gói [S1] | Giá |\n| --- | --- |\n| JP | 200.000đ [S2] |\n\n```txt\nEnable roaming [S3]\n```'} />);
    assertPrivateSourcesAbsent(html);
    expect(html).toContain('<h3');
    expect(html).toContain('<strong');
    expect(html).toContain('<table');
    expect(html).toContain('<code');
    expect(html).toContain('200.000đ');
  });

  it('hides source metadata in new replies and persisted history while retaining grounding data', () => {
    for (const message of [ragAnswerToChatMessage(answer), conversationMessageToChatMessage({ ...answer.assistantMessage, evidence: { sources: answer.sources } })]) {
      expect(message.confidence).toBe('confirmed');
      expect(message.citations).toHaveLength(2);
      assertPrivateSourcesAbsent(renderToStaticMarkup(<MessageItem message={message} />));
    }
  });

  it('also strips legacy assistant messages with no source metadata', () => {
    const message = conversationMessageToChatMessage({ ...answer.assistantMessage, evidence: undefined });
    assertPrivateSourcesAbsent(renderToStaticMarkup(<MessageItem message={message} />));
  });

  it('keeps an actual failure warning visible', () => {
    const html = renderToStaticMarkup(<MessageItem message={{ id: 'error', sender: 'system', content: 'Dịch vụ tạm thời gián đoạn.', timestamp: '', confidence: 'danger' }} />);
    expect(html).toContain('Chưa đủ căn cứ xác thực');
    expect(html).toContain('Dịch vụ tạm thời gián đoạn.');
  });

  it('copies a clean assistant reply and retains its product facts', async () => {
    await act(async () => root.render(<MessageItem message={ragAnswerToChatMessage(answer)} />));
    await act(async () => host.querySelector<HTMLButtonElement>('[title="Sao chép câu trả lời"]')!.click());
    expect(copy).toHaveBeenCalledOnce();
    assertPrivateSourcesAbsent(copy.mock.calls[0][0]);
    expect(copy.mock.calls[0][0]).toContain('200.000đ');
  });

  it('exports clean assistant content even when the stored message still has markers', async () => {
    vi.useRealTimers();
    let downloaded: Blob | undefined;
    vi.stubGlobal('URL', { createObjectURL: (blob: Blob) => { downloaded = blob; return 'blob:qa'; } });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await act(async () => root.render(<ChatWorkspace />));
    await act(async () => host.querySelector<HTMLButtonElement>('[title="Xuất hội thoại dạng Markdown (.md)"]')!.click());
    expect(downloaded).toBeDefined();
    const text = await new Promise<string>(resolve => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(downloaded!);
    });
    assertPrivateSourcesAbsent(text);
    expect(text).toContain('200.000đ');
  });

  it.each(['mini', 'preview'] as const)('hides references and filenames in the %s answer', async surface => {
    await act(async () => root.render(surface === 'mini'
      ? <RagMiniChat title="CSKH" placeholder="Câu hỏi" conversationTitle="CSKH" />
      : <TryItOutPanel botId="bot" botName="CSKH" blueAiCoreEnabled onClose={() => {}} />));
    const input = host.querySelector<HTMLInputElement>(surface === 'mini' ? 'input' : 'form input')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'eSIM?');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => {
      const button = host.querySelector<HTMLButtonElement>(surface === 'mini' ? 'input + button' : '[aria-label="Gửi câu thử nghiệm"]')!;
      button.click();
    });
    assertPrivateSourcesAbsent(host.innerHTML);
    expect(host.textContent).toContain('200.000đ');
  });
});
