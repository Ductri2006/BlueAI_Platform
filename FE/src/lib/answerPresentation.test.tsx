import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { presentAssistantAnswer } from './answerPresentation';
import { MessageItem } from '../components/chat/MessageItem';
import { conversationMessageToChatMessage, ragAnswerToChatMessage } from '../api/blueAiApi';

describe('customer answer presentation', () => {
  it('removes source markers and private source links while preserving useful formatting', () => {
    expect(presentAssistantAnswer('**15 GB** giá 430.000 VND [S1, S2].\n[S1](/internal/file)\nMục [1], https://support.test'))
      .toBe('**15 GB** giá 430.000 VND.\n\nMục [1], https://support.test');
  });

  it('hides source names, counts and snippets even when a legacy API returns evidence', () => {
    const message = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'Giá bao nhiêu?', createdAt: '' },
      assistantMessage: { id: 'a', role: 'ASSISTANT', content: '430.000 VND [S1]. private-policy.csv', createdAt: '' },
      sources: [{ documentId: 'internal-id', filename: 'private-policy.csv', chunkId: 'internal-chunk', excerpt: 'INTERNAL_EXCERPT' }],
    });
    expect(message.content).toBe('430.000 VND. tài liệu nội bộ');
    const html = renderToStaticMarkup(<MessageItem message={message} />);
    expect(html).toContain('430.000 VND');
    for (const hidden of ['private-policy.csv', 'INTERNAL_EXCERPT', 'internal-id', '[S1]', 'Nguồn tham chiếu', 'tài liệu tham chiếu']) {
      expect(html).not.toContain(hidden);
    }
  });

  it('sanitizes old assistant history but preserves user messages verbatim', () => {
    const old = { id: 'old', content: 'Thông tin [S1].', createdAt: '' };
    expect(conversationMessageToChatMessage({ ...old, role: 'ASSISTANT' }).content).toBe('Thông tin.');
    expect(conversationMessageToChatMessage({ ...old, role: 'USER' }).content).toBe(old.content);
  });
});
