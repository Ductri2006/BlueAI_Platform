import type { ChatMessage, Citation } from '../types';

const DEFAULT_API_BASE_URL = '';
const TOKEN_STORAGE_KEY = 'blueai.accessToken';
const REFRESH_TOKEN_STORAGE_KEY = 'blueai.refreshToken';

type FetchImplementation = typeof fetch;

interface ApiErrorBody {
  code?: string;
  message?: string;
  error?: {
    code?: string;
    message?: string;
  };
}

export interface UserResponse {
  id: string;
  email: string;
  fullName: string;
  role: 'ADMIN' | 'USER';
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserResponse;
}

export interface LoginCredentials {
  email: string;
  password: string;
}

export interface ConversationResponse {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationMessageResponse {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  createdAt: string;
}

export interface RagSourceResponse {
  documentId: string;
  filename: string;
  chunkId: string;
  score?: number;
  excerpt: string;
  sourceType?: string;
  approvalStatus?: string;
}

export interface RagAnswerResponse {
  userMessage: ConversationMessageResponse;
  assistantMessage: ConversationMessageResponse;
  sources: RagSourceResponse[];
}

export interface DocumentResponse {
  id: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  requestedChunkSize: number;
  requestedChunkOverlap: number;
  status: 'PENDING_CHUNKING' | 'PROCESSING' | 'READY' | 'FAILED';
  producedChunkCount?: number;
  processingStartedAt?: string;
  processedAt?: string;
  failureReason?: string;
  createdAt: string;
  /** Only present on the upload response: false means the RAG worker could not be woken. */
  ingestionTriggered?: boolean;
}

export interface SystemMetricsResponse {
  totalUsers: number;
  totalConversations: number;
  totalMessages: number;
  totalDocuments: number;
  readyDocuments: number;
  pendingDocuments: number;
  failedDocuments: number;
  retrieverConfigured: boolean;
  llmConfigured: boolean;
  aiRuntimeReady?: boolean;
  aiRuntimeStatus?: 'READY' | 'NOT_CONFIGURED' | 'NOT_READY' | 'UNREACHABLE';
  llmModel?: string;
  generatedAt: string;
}

export type StudioBotStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type StudioDialogNodeType = 'WELCOME' | 'ROUTE' | 'FALLBACK' | 'FOLDER';
export type StudioDialogNextStep = 'WAIT_FOR_REPLY' | 'SKIP_USER_INPUT' | 'JUMP_TO';

export interface StudioBotSummary {
  id: string;
  name: string;
  description: string;
  locale: string;
  timezone: string;
  blueAiCoreEnabled?: boolean;
  customInstructions?: string;
  status: StudioBotStatus;
  publicKey: string;
  draftRevision: number;
  publishedReleaseId?: string;
  updatedAt: string;
}

export interface StudioIntent {
  name: string;
  description: string;
  examples: string[];
}

export interface StudioEntityValue {
  value: string;
  synonyms: string[];
}

export interface StudioEntity {
  name: string;
  description: string;
  values: StudioEntityValue[];
}

export interface StudioDialogNode {
  name: string;
  type: StudioDialogNodeType;
  parentNode?: string;
  matchIntent: string;
  matchEntityName: string;
  matchEntityValue: string;
  requiredEntityName: string;
  responseText: string;
  missingEntityPrompt: string;
  nextStep?: StudioDialogNextStep;
  jumpToNode?: string;
  irrelevantResponseText?: string;
  priority: number;
}

export interface StudioBotConfiguration {
  bot: StudioBotSummary;
  intents: StudioIntent[];
  entities: StudioEntity[];
  dialogNodes: StudioDialogNode[];
}

export interface StudioPreviewContext {
  pendingIntent?: string;
  pendingEntity?: string;
  lastIntent?: string;
  variables: Record<string, string>;
  waitingNode?: string;
}

export interface StudioRuntimeResponse {
  message: string;
  matchedIntent?: string;
  recognizedEntities: Record<string, string>;
  matchedNode?: string;
  context: StudioPreviewContext;
  fallback: boolean;
}

export interface StudioPublishResponse {
  releaseId: string;
  revision: number;
  publicKey: string;
  publishedAt: string;
}

export interface BlueAiApiOptions {
  baseUrl?: string;
  accessToken?: string;
  refreshToken?: string;
  fetchImpl?: FetchImplementation;
}

export class BlueAiApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string,
  ) {
    super(message);
    this.name = 'BlueAiApiError';
  }
}

function withoutTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

function readStoredToken(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return window.localStorage.getItem(TOKEN_STORAGE_KEY) || undefined;
}

function readStoredRefreshToken(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY) || undefined;
}

function writeStoredTokens(accessToken: string, refreshToken: string): void {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(TOKEN_STORAGE_KEY, accessToken);
    window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
  }
}

function removeStoredTokens(): void {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  }
}

async function readApiError(response: Response): Promise<BlueAiApiError> {
  let body: ApiErrorBody | undefined;
  try {
    body = await response.json() as ApiErrorBody;
  } catch {
    // Preserve the HTTP status when an upstream proxy returns a non-JSON body.
  }

  const code = body?.code || body?.error?.code || `HTTP_${response.status}`;
  const message = body?.message || body?.error?.message || 'BlueAI API returned an unexpected error.';
  return new BlueAiApiError(message, response.status, code);
}

export class BlueAiApi {
  private readonly baseUrl: string;
  private readonly fetchImpl: FetchImplementation;
  private accessToken?: string;
  private refreshToken?: string;
  private refreshing?: Promise<string>;

  constructor(private readonly options: BlueAiApiOptions = {}) {
    this.baseUrl = withoutTrailingSlash(options.baseUrl ?? DEFAULT_API_BASE_URL);
    this.fetchImpl = options.fetchImpl || globalThis.fetch.bind(globalThis);
    this.accessToken = options.accessToken || readStoredToken();
    this.refreshToken = options.refreshToken || readStoredRefreshToken();
  }

  hasSession(): boolean {
    return Boolean(this.accessToken || this.refreshToken);
  }

  async login(credentials: LoginCredentials): Promise<UserResponse> {
    const response = await this.request<AuthResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    });
    this.applyAuthResponse(response);
    return response.user;
  }

  async getCurrentUser(): Promise<UserResponse> {
    return this.authorizedRequest<UserResponse>('/api/v1/auth/me', { method: 'GET' });
  }

  async logout(): Promise<void> {
    const refreshToken = this.refreshToken;
    try {
      if (refreshToken) {
        await this.request<void>('/api/v1/auth/logout', {
          method: 'POST',
          body: JSON.stringify({ refreshToken }),
        });
      }
    } finally {
      this.clearSession();
    }
  }

  clearSession(): void {
    this.accessToken = undefined;
    this.refreshToken = undefined;
    this.refreshing = undefined;
    removeStoredTokens();
  }

  async createConversation(title: string): Promise<ConversationResponse> {
    return this.authorizedRequest<ConversationResponse>('/api/v1/conversations', {
      method: 'POST',
      body: JSON.stringify({ title }),
    });
  }

  async listConversations(): Promise<ConversationResponse[]> {
    return this.authorizedRequest<ConversationResponse[]>('/api/v1/conversations', { method: 'GET' });
  }

  async listMessages(conversationId: string): Promise<ConversationMessageResponse[]> {
    return this.authorizedRequest<ConversationMessageResponse[]>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/messages?limit=100`,
      { method: 'GET' },
    );
  }

  async deleteConversation(conversationId: string): Promise<void> {
    return this.authorizedRequest<void>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
      { method: 'DELETE' },
    );
  }

  async listDocuments(): Promise<DocumentResponse[]> {
    return this.authorizedRequest<DocumentResponse[]>('/api/v1/documents', { method: 'GET' });
  }

  async uploadDocument(file: File): Promise<DocumentResponse> {
    const body = new FormData();
    body.append('file', file);
    return this.authorizedRequest<DocumentResponse>('/api/v1/documents', { method: 'POST', body });
  }

  async getSystemMetrics(): Promise<SystemMetricsResponse> {
    return this.authorizedRequest<SystemMetricsResponse>('/api/v1/system/metrics', { method: 'GET' });
  }

  async listStudioBots(): Promise<StudioBotSummary[]> {
    return this.authorizedRequest<StudioBotSummary[]>('/api/v1/studio/bots', { method: 'GET' });
  }

  async createStudioBot(details: {
    name: string;
    description?: string;
    locale?: string;
    timezone?: string;
    includeStarterTemplate?: boolean;
  }): Promise<StudioBotConfiguration> {
    return this.authorizedRequest<StudioBotConfiguration>('/api/v1/studio/bots', {
      method: 'POST',
      body: JSON.stringify(details),
    });
  }

  async getStudioBot(botId: string): Promise<StudioBotConfiguration> {
    return this.authorizedRequest<StudioBotConfiguration>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}`,
      { method: 'GET' },
    );
  }

  async saveStudioBotConfiguration(
    botId: string,
    configuration: Omit<StudioBotConfiguration, 'bot'> & {
      expectedRevision: number;
      name: string;
      description: string;
      locale: string;
      timezone: string;
      blueAiCoreEnabled?: boolean;
      customInstructions?: string;
    },
  ): Promise<StudioBotConfiguration> {
    return this.authorizedRequest<StudioBotConfiguration>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/configuration`,
      { method: 'PUT', body: JSON.stringify(configuration) },
    );
  }

  async previewStudioBot(
    botId: string,
    message: string,
    context?: StudioPreviewContext,
  ): Promise<StudioRuntimeResponse> {
    return this.authorizedRequest<StudioRuntimeResponse>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/preview`,
      { method: 'POST', body: JSON.stringify({ message, context }) },
    );
  }

  async publishStudioBot(botId: string): Promise<StudioPublishResponse> {
    return this.authorizedRequest<StudioPublishResponse>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/publish`,
      { method: 'POST' },
    );
  }

  async listManagedUsers(): Promise<UserResponse[]> {
    return this.authorizedRequest<UserResponse[]>('/api/v1/admin/users', { method: 'GET' });
  }

  async createManagedUser(details: {
    email: string;
    password: string;
    fullName: string;
    role: 'ADMIN' | 'USER';
  }): Promise<UserResponse> {
    return this.authorizedRequest<UserResponse>('/api/v1/admin/users', {
      method: 'POST',
      body: JSON.stringify(details),
    });
  }

  async updateManagedUserStatus(userId: string, status: 'ACTIVE' | 'DISABLED'): Promise<UserResponse> {
    return this.authorizedRequest<UserResponse>(`/api/v1/admin/users/${encodeURIComponent(userId)}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    });
  }

  async askQuestion(conversationId: string, question: string): Promise<RagAnswerResponse> {
    return this.authorizedRequest<RagAnswerResponse>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/ask`,
      {
        method: 'POST',
        body: JSON.stringify({ question }),
      },
    );
  }

  private async authorizedRequest<T>(path: string, init: RequestInit): Promise<T> {
    const token = await this.ensureAccessToken();
    try {
      return await this.request<T>(path, init, token);
    } catch (error) {
      if (!(error instanceof BlueAiApiError) || error.status !== 401 || !this.refreshToken) {
        if (error instanceof BlueAiApiError && error.status === 401) this.clearSession();
        throw error;
      }

      const refreshedToken = await this.refreshAccessToken();
      return this.request<T>(path, init, refreshedToken);
    }
  }

  private async ensureAccessToken(): Promise<string> {
    if (this.accessToken) return this.accessToken;
    if (this.refreshToken) return this.refreshAccessToken();
    throw new BlueAiApiError('Vui lòng đăng nhập để tiếp tục.', 401, 'AUTH_REQUIRED');
  }

  private async refreshAccessToken(): Promise<string> {
    if (this.refreshing) return this.refreshing;
    const refreshToken = this.refreshToken;
    if (!refreshToken) {
      throw new BlueAiApiError('Phiên đăng nhập đã hết hạn.', 401, 'AUTH_REQUIRED');
    }

    this.refreshing = this.request<AuthResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }).then((response) => {
      this.applyAuthResponse(response);
      return response.accessToken;
    }).catch((error) => {
      this.clearSession();
      throw error;
    }).finally(() => {
      this.refreshing = undefined;
    });

    return this.refreshing;
  }

  private applyAuthResponse(response: AuthResponse): void {
    this.accessToken = response.accessToken;
    this.refreshToken = response.refreshToken;
    writeStoredTokens(response.accessToken, response.refreshToken);
  }

  private async request<T>(path: string, init: RequestInit, token?: string): Promise<T> {
    const headers = new Headers(init.headers);
    if (!(init.body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
    }
    if (token) headers.set('Authorization', `Bearer ${token}`);

    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      ...init,
      headers,
    });
    if (!response.ok) throw await readApiError(response);
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
}

const LEAKED_CONTENT_BLOCKS = /^\s*\[?\s*\{\s*(['"])(?:type|text)\1\s*:/;
const TEXT_BLOCK_FIELD = /(['"])text\1\s*:\s*('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")/g;

function unquoteBlockText(value: string): string {
  return value
    .slice(1, -1)
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\r/g, '')
    .replace(/\\(['"\\])/g, '$1');
}

/**
 * Gemini answers with a list of content blocks. An older RAG build stringified that
 * list, so some stored answers look like
 * `[{'type': 'text', 'text': 'Xin chào', 'extras': {'signature': '...'}}]`.
 * Those rows stay in MySQL forever, so the UI unwraps them at render time instead of
 * showing raw JSON. Anything that is not such a payload is returned untouched.
 */
export function normalizeAssistantContent(raw: string): string {
  if (!raw || !LEAKED_CONTENT_BLOCKS.test(raw)) return raw;
  const matches = Array.from(raw.matchAll(TEXT_BLOCK_FIELD));
  if (matches.length === 0) return raw;
  const text = matches.map(match => unquoteBlockText(match[2])).join('').trim();
  return text || raw;
}

export function ragSourceToCitation(source: RagSourceResponse): Citation {
  const score = typeof source.score === 'number' ? ` • độ liên quan ${(source.score * 100).toFixed(1)}%` : '';
  const isEnterpriseSource = source.sourceType === 'ENTERPRISE_DOCUMENT';
  return {
    id: `${source.documentId}:${source.chunkId}`,
    sourceId: source.documentId,
    title: source.filename,
    section: `Chunk ${source.chunkId}${score}`,
    version: source.sourceType === 'SYNTHETIC_DEMO'
      ? 'Dữ liệu demo'
      : isEnterpriseSource ? 'Nguồn doanh nghiệp' : 'Nguồn RAG API',
    effectiveDate: source.approvalStatus === 'PENDING_OWNER_REVIEW' ? 'Chưa được phê duyệt' : 'Đã xác minh khi truy xuất',
    owner: source.sourceType === 'SYNTHETIC_DEMO'
      ? 'Bộ dữ liệu mẫu BlueAI'
      : isEnterpriseSource ? 'Kho tri thức do Admin cung cấp' : 'Kho tri thức BlueAI',
    scope: 'Theo quyền truy cập của tài khoản hiện tại',
    snippet: source.excerpt,
    status: source.approvalStatus === 'PENDING_OWNER_REVIEW' ? 'scope_mismatch' : 'valid',
  };
}

export function ragAnswerToChatMessage(response: RagAnswerResponse): ChatMessage {
  const citations = (response.sources || []).map(ragSourceToCitation);
  const hasUnapprovedSource = citations.some(citation => citation.status === 'scope_mismatch');
  const createdAt = new Date(response.assistantMessage.createdAt);
  return {
    id: response.assistantMessage.id,
    sender: 'assistant',
    content: normalizeAssistantContent(response.assistantMessage.content),
    timestamp: Number.isNaN(createdAt.getTime())
      ? new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      : createdAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    // No verified source is not an error: the assistant is expected to say the
    // knowledge base does not cover the question. Red is reserved for real failures.
    confidence: citations.length === 0
      ? 'ungrounded'
      : hasUnapprovedSource ? 'inferred' : 'confirmed',
    citations,
    toolsUsed: ['RAG_Retrieval_API', 'LLM_Chat_API'],
  };
}

export const blueAiApi = new BlueAiApi({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
});
