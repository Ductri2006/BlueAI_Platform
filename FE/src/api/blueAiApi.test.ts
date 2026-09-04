import { describe, expect, it, vi } from 'vitest';
import {
  BlueAiApi,
  normalizeAssistantContent,
  ragAnswerToChatMessage,
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
