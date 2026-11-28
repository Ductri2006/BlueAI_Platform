import { describe, expect, it } from 'vitest';
import { BlueAiApiError, conversationMessageToChatMessage, ragAnswerToChatMessage, type ConversationMessageResponse, type RagAnswerResponse } from './blueAiApi';
import { chatErrorToMessage } from './chatErrors';

const upstream = 'Provider failed: API_KEY=private https://internal.example/v1 model=private-model';

describe('chat request errors', () => {
  it.each([
    ['LLM_RESPONSE_LANGUAGE_MISMATCH', 502, 'language'],
    ['REPLY_TRANSLATION_FAILED', 502, 'language'],
    ['AI_TOKEN_QUOTA_EXHAUSTED', 429, 'quota'],
    ['AI_PROVIDER_RATE_LIMITED', 429, 'quota'],
    ['AI_PROJECT_RATE_LIMITED', 429, 'quota'],
    ['AI_REQUEST_TOO_LARGE', 413, 'quota'],
    ['RAG_RATE_LIMITED', 429, 'quota'],
    ['SESSION_TURN_LIMIT', 429, 'quota'],
    ['UNAUTHORIZED', 401, 'access'],
    ['FORBIDDEN', 403, 'access'],
    ['CONVERSATION_NOT_FOUND', 404, 'access'],
    ['API_UNREACHABLE', 0, 'network'],
    ['LLM_REQUEST_FAILED', 502, 'service'],
    ['RAG_RETRIEVAL_FAILED', 502, 'service'],
    ['RAG_RETRIEVER_NOT_CONFIGURED', 503, 'service'],
    ['PRODUCT_UPSTREAM_FAILED', 502, 'service'],
    ['PRODUCT_CATALOG_NOT_CONFIGURED', 503, 'service'],
    ['UNKNOWN_FAILURE', 500, 'service'],
  ] as const)('categorizes %s without forwarding upstream messages', (code, status, category) => {
    const message = chatErrorToMessage(new BlueAiApiError(upstream, status, code, 30));
    expect(message.error).toEqual({ category, code });
    expect(message.confidence).toBeUndefined();
    expect(message.content).not.toContain(upstream);
    expect(message.content).not.toContain(code);
    expect(message.content).not.toMatch(/private|internal\.example|API_KEY|model=/);
    expect(message.content).toMatch(/vui lòng|hãy/i);
  });

  it('retains cooldown guidance and distinguishes oversized requests', () => {
    expect(chatErrorToMessage(new BlueAiApiError(upstream, 429, 'RAG_RATE_LIMITED', 30)).content).toContain('30 giây');
    expect(chatErrorToMessage(new BlueAiApiError(upstream, 413, 'AI_REQUEST_TOO_LARGE')).content).toContain('rút ngắn');
    expect(chatErrorToMessage(new BlueAiApiError(upstream, 429, 'SESSION_TURN_LIMIT')).content).toContain('hội thoại mới');
  });

  it('handles fetch rejection and unknown thrown values without exposing details', () => {
    expect(chatErrorToMessage(new TypeError(upstream)).error?.category).toBe('network');
    expect(chatErrorToMessage(upstream).error?.category).toBe('service');
    expect(chatErrorToMessage(new BlueAiApiError(upstream, 500, upstream)).error?.code).toBeUndefined();
  });
});

function answerFixture(answerStatus?: ConversationMessageResponse['answerStatus']): RagAnswerResponse {
  return {
    userMessage: { id: 'u', role: 'USER', content: '你好 — Chính sách hoàn tiền?', createdAt: '2026-11-28T10:00:00Z' },
    assistantMessage: { id: 'a', role: 'ASSISTANT', content: '暂时无法生成准确回答，请稍后重试。', createdAt: '2026-11-28T10:00:01Z', answerStatus },
    sources: [],
  };
}

describe('answer status provenance', () => {
  it('maps a localized safe fallback to a service error without inventing a technical code', () => {
    const message = ragAnswerToChatMessage(answerFixture('SAFE_FALLBACK'));
    expect(message.error).toEqual({ category: 'service' });
    expect(message.confidence).toBeUndefined();
    expect(message.content).toBe('暂时无法生成准确回答，请稍后重试。');
    expect(message.citations).toBeUndefined();
    expect(message.toolsUsed).toBeUndefined();
  });

  it.each(['SAFE_FALLBACK', 'NEEDS_DETAILS', 'INSUFFICIENT_EVIDENCE', 'ANSWERED'] as const)('preserves top-level %s after history reload without changing evidence JSON', status => {
    const fresh = answerFixture(status);
    expect(conversationMessageToChatMessage(fresh.assistantMessage)).toEqual(ragAnswerToChatMessage(fresh));
    fresh.assistantMessage.evidence = { sources: [] };
    expect(conversationMessageToChatMessage(fresh.assistantMessage)).toEqual(ragAnswerToChatMessage(fresh));
    expect(fresh.assistantMessage.evidence).toEqual({ sources: [] });
  });

  it('reserves insufficient-evidence status for actual knowledge gaps', () => {
    const message = ragAnswerToChatMessage(answerFixture('INSUFFICIENT_EVIDENCE'));
    expect(message.confidence).toBe('danger');
    expect(message.error).toBeUndefined();
  });

  it('treats a clarification as neutral without an out-of-knowledge warning', () => {
    const message = ragAnswerToChatMessage(answerFixture('NEEDS_DETAILS'));
    expect(message.confidence).toBeUndefined();
    expect(message.error).toBeUndefined();
    expect(message.citations).toBeUndefined();
  });

  it('preserves legacy and verified product answer mapping', () => {
    expect(ragAnswerToChatMessage(answerFixture()).confidence).toBe('ungrounded');
    expect(conversationMessageToChatMessage(answerFixture().assistantMessage).confidence).toBeUndefined();
    const product = answerFixture('ANSWERED');
    product.sources = [{ documentId: 'product:JP-7D', filename: 'Japan', chunkId: 'JP-7D', excerpt: '80000 VND', sourceType: 'PRODUCT_CATALOG' }];
    expect(ragAnswerToChatMessage(product).confidence).toBe('confirmed');
    expect(ragAnswerToChatMessage(product).citations?.[0].section).toBe('SKU JP-7D');
  });

  it.each([undefined, null, '', 'FUTURE_STATUS'])('keeps legacy history behavior for absent or unknown status %s', status => {
    // API payloads can contain values introduced by a newer server.
    const fresh = answerFixture(status as ConversationMessageResponse['answerStatus']);
    const legacy = answerFixture();
    expect(conversationMessageToChatMessage(fresh.assistantMessage)).toEqual(conversationMessageToChatMessage(legacy.assistantMessage));
    expect(ragAnswerToChatMessage(fresh)).toEqual(ragAnswerToChatMessage(legacy));
    fresh.assistantMessage.evidence = { sources: [] };
    legacy.assistantMessage.evidence = { sources: [] };
    expect(conversationMessageToChatMessage(fresh.assistantMessage)).toEqual(conversationMessageToChatMessage(legacy.assistantMessage));
  });
});
