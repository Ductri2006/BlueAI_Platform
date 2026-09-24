import { describe, expect, it, vi } from 'vitest';
import {
  BlueAiApi,
  normalizeAssistantContent,
  ragAnswerToChatMessage,
  conversationMessageToChatMessage,
  type RagAnswerResponse,
} from './blueAiApi';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('BlueAiApi', () => {
  it('creates a conversation and asks the authenticated RAG endpoint', async () => {
    const fetchImpl = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/api/v1/conversations')) {
        return jsonResponse({
          id: 'conversation-1',
          title: 'RAG test',
          contextMode: 'CURRENT_CONVERSATION',
          createdAt: '2026-09-04T10:00:00Z',
          updatedAt: '2026-09-04T10:00:00Z',
        }, 201);
      }
      return jsonResponse({
        userMessage: {
          id: 'user-message-1',
          role: 'USER',
          content: 'Điều kiện hoàn tiền?',
          createdAt: '2026-09-04T10:01:00Z',
        },
        assistantMessage: {
          id: 'assistant-message-1',
          role: 'ASSISTANT',
          content: 'Mã QR chưa quét có thể được xem xét hoàn tiền. [S1]',
          createdAt: '2026-09-04T10:01:01Z',
        },
        sources: [],
      });
    });
    const api = new BlueAiApi({
      baseUrl: 'http://api.test/',
      accessToken: 'test-access-token',
      fetchImpl: fetchImpl as typeof fetch,
    });

    const conversation = await api.createConversation('RAG test');
    await api.askQuestion(conversation.id, 'Điều kiện hoàn tiền?');

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(fetchImpl.mock.calls[1][0]).toBe('http://api.test/api/v1/conversations/conversation-1/ask');
    const headers = fetchImpl.mock.calls[1][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer test-access-token');
    expect(fetchImpl.mock.calls[1][1]?.body).toBe(JSON.stringify({ question: 'Điều kiện hoàn tiền?' }));
  });

  it('logs in explicitly and uses the authenticated session', async () => {
    const paths: string[] = [];
    const fetchImpl = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      paths.push(new URL(url).pathname);
      if (url.endsWith('/auth/login')) {
        return jsonResponse({
          accessToken: 'login-token', refreshToken: 'refresh-token', tokenType: 'Bearer', expiresIn: 900,
          user: { id: 'user-1', email: 'user@example.com', fullName: 'RAG User', role: 'USER', createdAt: '2026-09-04T10:00:00Z' },
        });
      }
      expect((init?.headers as Headers).get('Authorization')).toBe('Bearer login-token');
      return jsonResponse({ id: 'user-1', email: 'user@example.com', fullName: 'RAG User', role: 'USER', createdAt: '2026-09-04T10:00:00Z' });
    });
    const api = new BlueAiApi({
      baseUrl: 'http://api.test',
      fetchImpl: fetchImpl as typeof fetch,
    });

    const user = await api.login({ email: 'user@example.com', password: 'StrongPassword123!' });
    const currentUser = await api.getCurrentUser();

    expect(paths).toEqual(['/api/v1/auth/login', '/api/v1/auth/me']);
    expect(user.email).toBe('user@example.com');
    expect(currentUser.fullName).toBe('RAG User');
  });

  it('refreshes an expired access token before retrying the original request', async () => {
    const authorizationHeaders: Array<string | null> = [];
    const fetchImpl = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        return jsonResponse({
          accessToken: 'fresh-token', refreshToken: 'fresh-refresh-token', tokenType: 'Bearer', expiresIn: 900,
          user: { id: 'user-1', email: 'user@example.com', fullName: 'RAG User', role: 'USER', createdAt: '2026-09-04T10:00:00Z' },
        });
      }

      authorizationHeaders.push((init?.headers as Headers).get('Authorization'));
      if (authorizationHeaders.length === 1) return jsonResponse({ code: 'UNAUTHORIZED', message: 'Expired.' }, 401);
      return jsonResponse([]);
    });
    const api = new BlueAiApi({
      baseUrl: 'http://api.test',
      accessToken: 'expired-token',
      refreshToken: 'refresh-token',
      fetchImpl: fetchImpl as typeof fetch,
    });

    await expect(api.listConversations()).resolves.toEqual([]);
    expect(authorizationHeaders).toEqual(['Bearer expired-token', 'Bearer fresh-token']);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('uploads multipart documents and handles a 204 conversation delete', async () => {
    const fetchImpl = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
      if (String(input).endsWith('/api/v1/conversations/conversation-1')) {
        return new Response(null, { status: 204 });
      }
      return jsonResponse({
        id: 'document-1', originalFilename: 'guide.md', contentType: 'text/markdown',
        sizeBytes: 5, requestedChunkSize: 1000, requestedChunkOverlap: 150,
        status: 'PENDING_CHUNKING', createdAt: '2026-09-04T10:00:00Z',
      }, 201);
    });
    const api = new BlueAiApi({ baseUrl: 'http://api.test', accessToken: 'token', fetchImpl: fetchImpl as typeof fetch });

    await api.uploadDocument(new File(['guide'], 'guide.md', { type: 'text/markdown' }));
    await api.deleteConversation('conversation-1');

    const uploadInit = fetchImpl.mock.calls[0][1] as RequestInit;
    expect(uploadInit.body).toBeInstanceOf(FormData);
    expect((uploadInit.headers as Headers).has('Content-Type')).toBe(false);
  });

  it('manages conversation context links and scoped memories through authenticated routes', async () => {
    const requests: Array<{ path: string; method?: string; body?: string }> = [];
    const fetchImpl = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      requests.push({ path, method: init?.method, body: init?.body as string | undefined });
      if (path.endsWith('/context')) return jsonResponse({ mode: 'LINKED_CONVERSATIONS', links: [] });
      if (path.endsWith('/context-links')) {
        return jsonResponse({ id: 'link-1', sourceConversationId: 'source-1', sourceConversationTitle: 'Nguồn', sourceSnapshotAt: '2026-09-22T10:00:00Z', createdAt: '2026-09-22T10:00:00Z' }, 201);
      }
      if (path.endsWith('/refresh')) {
        return jsonResponse({ id: 'link-1', sourceConversationId: 'source-1', sourceConversationTitle: 'Nguồn', sourceSnapshotAt: '2026-09-22T10:01:00Z', createdAt: '2026-09-22T10:00:00Z' });
      }
      if (path.endsWith('/me/memories')) return jsonResponse([]);
      if (path.includes('/me/memories/')) {
        return jsonResponse({ key: 'preferred-language', content: 'Trả lời tiếng Việt', scope: 'USER', status: 'CONFIRMED', createdAt: '2026-09-22T10:00:00Z', updatedAt: '2026-09-22T10:00:00Z' });
      }
      return new Response(null, { status: 204 });
    });
    const api = new BlueAiApi({ baseUrl: 'http://api.test', accessToken: 'context-token', fetchImpl: fetchImpl as typeof fetch });

    await api.getConversationContext('target-1');
    await api.updateConversationContext('target-1', 'LINKED_CONVERSATIONS');
    await api.createConversationLink('target-1', 'source-1');
    await api.refreshConversationLink('target-1', 'link-1');
    await api.deleteConversationLink('target-1', 'link-1');
    await api.listMemories();
    await api.upsertMemory('preferred-language', { content: 'Trả lời tiếng Việt', scope: 'USER' });
    await api.deleteMemory('preferred-language');

    expect(requests.map(request => [request.method, request.path])).toEqual([
      ['GET', '/api/v1/conversations/target-1/context'],
      ['PUT', '/api/v1/conversations/target-1/context'],
      ['POST', '/api/v1/conversations/target-1/context-links'],
      ['POST', '/api/v1/conversations/target-1/context-links/link-1/refresh'],
      ['DELETE', '/api/v1/conversations/target-1/context-links/link-1'],
      ['GET', '/api/v1/me/memories'],
      ['PUT', '/api/v1/me/memories/preferred-language'],
      ['DELETE', '/api/v1/me/memories/preferred-language'],
    ]);
    expect(JSON.parse(requests[1].body || '{}')).toEqual({ mode: 'LINKED_CONVERSATIONS' });
    expect(JSON.parse(requests[2].body || '{}')).toEqual({ sourceConversationId: 'source-1' });
    expect(JSON.parse(requests[6].body || '{}')).toEqual({ content: 'Trả lời tiếng Việt', scope: 'USER' });
    for (const call of fetchImpl.mock.calls) expect((call[1]?.headers as Headers).get('Authorization')).toBe('Bearer context-token');
  });

  it('saves, previews, and publishes a Bot Studio draft through authenticated routes', async () => {
    const requests: Array<{ path: string; method?: string; body?: string }> = [];
    const fetchImpl = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      requests.push({ path, method: init?.method, body: init?.body as string | undefined });
      if (path.endsWith('/preview')) {
        return jsonResponse({
          message: 'Chi nhánh HCM mở cửa đến 22:00.',
          matchedIntent: 'store_hours',
          recognizedEntities: { location: 'HCM' },
          matchedNode: 'Giờ mở cửa HCM',
          context: { lastIntent: 'store_hours', variables: { location: 'HCM' } },
          fallback: false,
        });
      }
      if (path.endsWith('/publish')) {
        return jsonResponse({ releaseId: 'release-2', revision: 2, publicKey: 'bot-public', publishedAt: '2026-09-13T10:00:00Z' });
      }
      return jsonResponse({
        bot: { id: 'bot-1', name: 'BlueAI Bot', description: '', locale: 'vi-VN', timezone: 'Asia/Ho_Chi_Minh', status: 'DRAFT', publicKey: 'bot-public', draftRevision: 2, updatedAt: '2026-09-13T10:00:00Z' },
        intents: [{ name: 'store_hours', description: '', examples: ['Mấy giờ đóng cửa?'] }],
        entities: [{ name: 'location', description: '', values: [{ value: 'HCM', synonyms: ['Sài Gòn'] }] }],
        dialogNodes: [{ name: 'Giờ mở cửa HCM', type: 'ROUTE', matchIntent: 'store_hours', matchEntityName: 'location', matchEntityValue: 'HCM', requiredEntityName: 'location', responseText: '22:00', missingEntityPrompt: 'Chi nhánh nào?', priority: 10 }],
      });
    });
    const api = new BlueAiApi({ baseUrl: 'http://api.test', accessToken: 'studio-token', fetchImpl: fetchImpl as typeof fetch });

    await api.saveStudioBotConfiguration('bot-1', {
      expectedRevision: 1,
      name: 'BlueAI Bot', description: '', locale: 'vi-VN', timezone: 'Asia/Ho_Chi_Minh',
      intents: [{ name: 'store_hours', description: '', examples: ['Mấy giờ đóng cửa?'] }],
      entities: [{ name: 'location', description: '', values: [{ value: 'HCM', synonyms: ['Sài Gòn'] }] }],
      dialogNodes: [{ name: 'Giờ mở cửa HCM', type: 'ROUTE', matchIntent: 'store_hours', matchEntityName: 'location', matchEntityValue: 'HCM', requiredEntityName: 'location', responseText: '22:00', missingEntityPrompt: 'Chi nhánh nào?', priority: 10 }],
    });
    const preview = await api.previewStudioBot('bot-1', 'Sài Gòn', { pendingIntent: 'store_hours', pendingEntity: 'location', variables: {} });
    await api.publishStudioBot('bot-1');

    expect(requests.map(request => [request.method, request.path])).toEqual([
      ['PUT', '/api/v1/studio/bots/bot-1/configuration'],
      ['POST', '/api/v1/studio/bots/bot-1/preview'],
      ['POST', '/api/v1/studio/bots/bot-1/publish'],
    ]);
    expect(JSON.parse(requests[0].body || '{}')).toMatchObject({ expectedRevision: 1, locale: 'vi-VN' });
    expect(JSON.parse(requests[1].body || '{}')).toMatchObject({ message: 'Sài Gòn', context: { pendingEntity: 'location' } });
    expect(preview.recognizedEntities).toEqual({ location: 'HCM' });
    for (const call of fetchImpl.mock.calls) expect((call[1]?.headers as Headers).get('Authorization')).toBe('Bearer studio-token');
  });
});

describe('normalizeAssistantContent', () => {
  it('unwraps a leaked Gemini content-block list', () => {
    const leaked = "[{'type': 'text', 'text': 'Hello! How can I help you today?', 'extras': {'signature': 'El4KXAERTTIPg7Ky4JYCNKlNmux3MAYkzxImW74tpxWCcEyd9w74'}}]";
    expect(normalizeAssistantContent(leaked)).toBe('Hello! How can I help you today?');
  });

  it('joins several text blocks and drops reasoning blocks', () => {
    const leaked = "[{'type': 'reasoning', 'reasoning': 'noi bo'}, {'type': 'text', 'text': 'Chính sách hoàn tiền '}, {'type': 'text', 'text': 'là 7 ngày. [S1]'}]";
    expect(normalizeAssistantContent(leaked)).toBe('Chính sách hoàn tiền là 7 ngày. [S1]');
  });

  it('decodes escaped quotes and newlines inside a leaked block', () => {
    const leaked = '[{"type": "text", "text": "Dòng 1\\nDòng 2 \\"trích dẫn\\""}]';
    expect(normalizeAssistantContent(leaked)).toBe('Dòng 1\nDòng 2 "trích dẫn"');
  });

  it('leaves a normal answer untouched', () => {
    const answer = 'Gói Sao Băng 47 gồm 15GB. [S1]\n\n- Điều kiện: chưa kích hoạt';
    expect(normalizeAssistantContent(answer)).toBe(answer);
  });

  it('leaves an answer that merely contains JSON untouched', () => {
    const answer = 'Ví dụ payload:\n\n```json\n[{"type": "text", "text": "abc"}]\n```';
    expect(normalizeAssistantContent(answer)).toBe(answer);
  });

  it('keeps the raw string when no text field can be recovered', () => {
    const broken = "[{'type': 'text', 'extras': {'signature': 'abc'}}]";
    expect(normalizeAssistantContent(broken)).toBe(broken);
  });
});

describe('ragAnswerToChatMessage', () => {
  it('restores evidence and context when loading persisted messages', () => {
    const message = conversationMessageToChatMessage({
      id: 'saved', role: 'ASSISTANT', content: 'Hướng dẫn [S1]', createdAt: '2026-09-23T00:00:00Z',
      evidence: {
        sources: [{ documentId: 'kb', filename: 'KB.csv', chunkId: 'kb:1', excerpt: 'Bật Data Roaming', approvalStatus: 'READY' }],
        contextSources: [{ type: 'LINKED_CONVERSATION', title: 'Hikari', conversationId: 'source' }],
      },
    });
    expect(message.citations?.[0].snippet).toBe('Bật Data Roaming');
    expect(message.contextSources?.[0].title).toBe('Hikari');
    expect(message.confidence).toBe('confirmed');
  });

  it('does not invent citations for legacy history or normalize user text', () => {
    const legacy = conversationMessageToChatMessage({
      id: 'old', role: 'ASSISTANT', content: 'Old [S1]', createdAt: '', evidence: null,
    });
    expect(legacy.citations).toBeUndefined();
    expect(legacy.confidence).toBeUndefined();
    const content = "[{'type':'text','text':'literal user content'}]";
    expect(conversationMessageToChatMessage({ id: 'u', role: 'USER', content, createdAt: '' }).content).toBe(content);
  });
  it('maps verified backend sources into evidence drawer citations', () => {
    const response: RagAnswerResponse = {
      userMessage: {
        id: 'user-message-1',
        role: 'USER',
        content: 'Câu hỏi',
        createdAt: '2026-09-04T10:00:00Z',
      },
      assistantMessage: {
        id: 'assistant-message-1',
        role: 'ASSISTANT',
        content: 'Câu trả lời [S1]',
        createdAt: '2026-09-04T10:00:01Z',
      },
      sources: [{
        documentId: 'document-1',
        filename: 'policy.pdf',
        chunkId: 'chunk-7',
        score: 0.92,
        excerpt: 'Đoạn chính sách được truy xuất.',
      }],
    };

    const message = ragAnswerToChatMessage(response);

    expect(message.confidence).toBe('confirmed');
    expect(message.citations).toHaveLength(1);
    expect(message.citations?.[0]).toMatchObject({
      id: 'document-1:chunk-7',
      sourceId: 'document-1',
      title: 'policy.pdf',
      snippet: 'Đoạn chính sách được truy xuất.',
    });
  });

  it('keeps context provenance separate from document citations', () => {
    const message = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'q', createdAt: '2026-09-22T10:00:00Z' },
      assistantMessage: { id: 'a', role: 'ASSISTANT', content: 'a', createdAt: '2026-09-22T10:00:01Z' },
      sources: [],
      contextSources: [{ type: 'LINKED_CONVERSATION', conversationId: 'conversation-source', title: 'Báo giá khách hàng A' }],
    });

    expect(message.citations).toEqual([]);
    expect(message.contextSources).toEqual([{ type: 'LINKED_CONVERSATION', conversationId: 'conversation-source', title: 'Báo giá khách hàng A' }]);
    expect(message.confidence).toBe('context');
    expect(message.toolsUsed).toEqual(['LLM_Chat_API']);
  });

  it('marks unapproved synthetic sources as demo evidence', () => {
    const citation = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'q', createdAt: '2026-09-04T10:00:00Z' },
      assistantMessage: { id: 'a', role: 'ASSISTANT', content: 'a', createdAt: '2026-09-04T10:00:01Z' },
      sources: [{ documentId: 'demo', filename: 'Demo source', chunkId: '1', excerpt: 'sample', sourceType: 'SYNTHETIC_DEMO', approvalStatus: 'PENDING_OWNER_REVIEW' }],
    }).citations?.[0];

    expect(citation).toMatchObject({ version: 'Dữ liệu demo', effectiveDate: 'Chưa được phê duyệt', status: 'scope_mismatch' });
  });

  it('marks an answer with an unapproved source as inferred, not confirmed', () => {
    const message = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'q', createdAt: '2026-09-04T10:00:00Z' },
      assistantMessage: { id: 'a', role: 'ASSISTANT', content: 'a', createdAt: '2026-09-04T10:00:01Z' },
      sources: [{ documentId: 'demo', filename: 'Demo source', chunkId: '1', excerpt: 'sample', sourceType: 'SYNTHETIC_DEMO', approvalStatus: 'PENDING_OWNER_REVIEW' }],
    });

    expect(message.confidence).toBe('inferred');
  });

  it('marks an answer without any verified source as ungrounded rather than danger', () => {
    const message = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'hello', createdAt: '2026-09-04T10:00:00Z' },
      assistantMessage: { id: 'a', role: 'ASSISTANT', content: 'Chào bạn, tôi có thể giúp gì?', createdAt: '2026-09-04T10:00:01Z' },
      sources: [],
    });

    expect(message.confidence).toBe('ungrounded');
    expect(message.citations).toHaveLength(0);
  });

  it('cleans a leaked content-block payload before it reaches the UI', () => {
    const message = ragAnswerToChatMessage({
      userMessage: { id: 'u', role: 'USER', content: 'hello', createdAt: '2026-09-04T10:00:00Z' },
      assistantMessage: {
        id: 'a',
        role: 'ASSISTANT',
        content: "[{'type': 'text', 'text': 'Hello! How can I help you today?', 'extras': {'signature': 'El4KXAER'}}]",
        createdAt: '2026-09-04T10:00:01Z',
      },
      sources: [],
    });

    expect(message.content).toBe('Hello! How can I help you today?');
  });
});
