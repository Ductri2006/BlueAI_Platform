import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { conversationMessageToChatMessage, ragAnswerToChatMessage, type RagAnswerResponse } from '../../api/blueAiApi';
import { MessageItem } from './MessageItem';

function response(origin?: 'CORE' | 'DIALOG' | 'DIALOG_CONTEXT_CONFIRMATION', withContext = false): RagAnswerResponse {
  return {
    userMessage: { id: 'u', role: 'USER', content: 'giờ mở cửa', createdAt: '2026-10-03T01:00:00Z' },
    assistantMessage: {
      id: 'a', role: 'ASSISTANT', content: 'Chi nhánh HCM mở cửa lúc 08:00.', createdAt: '2026-10-03T01:00:01Z',
      evidence: { sources: [], contextSources: [], origin },
    },
    sources: [],
    contextSources: withContext ? [{ type: 'USER_MEMORY', title: 'Ghi nhớ cá nhân', memoryKey: 'dialog.location' }] : [],
  };
}

describe('server-produced Dialog answer origin', () => {
  it('identifies a scripted answer without inventing KB or LLM usage', () => {
    const message = ragAnswerToChatMessage(response('DIALOG'));
    expect(message.confidence).toBe('dialog');
    expect(message.citations).toEqual([]);
    expect(message.toolsUsed).toEqual(['Dialog_Runtime']);
  });

  it('retains context provenance without relabeling a scripted answer as AI inference', () => {
    const message = ragAnswerToChatMessage(response('DIALOG', true));
    expect(message.confidence).toBe('dialog');
    expect(message.contextSources?.[0].memoryKey).toBe('dialog.location');
  });

  it('marks a historical suggestion as pending confirmation, not an applied fact', () => {
    const message = ragAnswerToChatMessage(response('DIALOG_CONTEXT_CONFIRMATION', true));
    expect(message.confidence).toBe('context_confirmation');
    expect(message.toolsUsed).toEqual(['Dialog_Runtime']);
  });

  it.each(['DIALOG', 'DIALOG_CONTEXT_CONFIRMATION'] as const)('keeps %s labels after reloading persisted history', origin => {
    const fresh = response(origin, true);
    fresh.assistantMessage.evidence!.contextSources = fresh.contextSources;
    expect(conversationMessageToChatMessage(fresh.assistantMessage)).toEqual(ragAnswerToChatMessage(fresh));
  });

  it('does not invent a Dialog origin for legacy evidence', () => {
    expect(ragAnswerToChatMessage(response()).confidence).toBe('ungrounded');
    expect(conversationMessageToChatMessage(response().assistantMessage).confidence).toBe('ungrounded');
  });

  it('preserves verified KB labels for Core answers', () => {
    const fresh = response('CORE');
    fresh.sources = [{ documentId: 'kb', filename: 'QA.md', chunkId: '1', excerpt: 'KB fixture' }];
    expect(ragAnswerToChatMessage(fresh).confidence).toBe('confirmed');
    expect(ragAnswerToChatMessage(fresh).toolsUsed).toEqual(['RAG_Retrieval_API', 'LLM_Chat_API']);
  });

  it('renders a scripted answer without an out-of-KB warning', () => {
    const html = renderToStaticMarkup(<MessageItem message={ragAnswerToChatMessage(response('DIALOG'))} />);
    expect(html).toContain('Theo kịch bản Dialog');
    expect(html).toContain('không phải trích dẫn tài liệu KB');
    expect(html).not.toContain('Ngoài phạm vi kho tri thức nội bộ');
    expect(html).not.toContain('hãy đề nghị admin tải tài liệu');
  });

  it('renders a proposal separately from used context', () => {
    const html = renderToStaticMarkup(<MessageItem message={ragAnswerToChatMessage(response('DIALOG_CONTEXT_CONFIRMATION', true))} />);
    expect(html).toContain('Context cần xác nhận');
    expect(html).toContain('Context đề xuất (chưa áp dụng):');
    expect(html).not.toContain('Context đã dùng:');
  });

  it('keeps the out-of-KB explanation for genuinely ungrounded Core answers', () => {
    const html = renderToStaticMarkup(<MessageItem message={ragAnswerToChatMessage(response('CORE'))} />);
    expect(html).toContain('Ngoài phạm vi kho tri thức nội bộ');
    expect(html).not.toContain('Theo kịch bản Dialog');
  });
});
