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
  passwordChangeRequired?: boolean;
}

export interface ChatQuota {
  limit: number;
  remaining: number;
  windowSeconds: number;
  resetsAt: string | null;
  resetAfterSeconds?: number;
}

export interface QuotaOverview {
  chat: ChatQuota;
  loginAttemptsPerMinute: number;
  defaultApiRequestsPerMinute: number;
  widgetMessagesPerWindowPerClient: number;
  widgetWindowSeconds: number;
  blockedRequestsOnThisInstance: number;
}

export interface TokenWindow {
  limit: number;
  used: number;
  reserved: number;
  uncertain: number;
  remaining: number;
  resetsAt: string | null;
  resetAfterSeconds: number;
}

export interface AiTokenQuota {
  enabled: boolean;
  serverTime: string;
  fiveHour: TokenWindow | null;
  weekly: TokenWindow | null;
}

export interface TemporaryPassword {
  temporaryPassword: string;
  expiresAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken?: string;
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
  contextMode: ConversationContextMode;
  createdAt: string;
  updatedAt: string;
}

export type ConversationContextMode = 'CURRENT_CONVERSATION' | 'LINKED_CONVERSATIONS' | 'RELEVANT_HISTORY';

export interface ConversationLinkResponse {
  id: string;
  sourceConversationId: string;
  sourceConversationTitle: string;
  sourceSnapshotAt: string;
  createdAt: string;
}

export interface ConversationContextSettingsResponse {
  mode: ConversationContextMode;
  links: ConversationLinkResponse[];
}

export type MemoryScope = 'USER' | 'CONVERSATION';
export type MemoryStatus = 'PROPOSED' | 'CONFIRMED' | 'REPLACED';

export interface UserMemoryResponse {
  key: string;
  content: string;
  scope: MemoryScope;
  status: MemoryStatus;
  conversationId?: string;
  sourceMessageId?: string;
  expiresAt?: string;
  replacedByMemoryKey?: string;
  createdAt: string;
  updatedAt: string;
}

export interface UpsertUserMemoryInput {
  content: string;
  scope?: MemoryScope;
  conversationId?: string;
  sourceMessageId?: string;
  expiresAt?: string;
  status?: Exclude<MemoryStatus, 'REPLACED'>;
  replacesMemoryKey?: string;
}

export interface ConversationMessageResponse {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  createdAt: string;
  evidence?: (Pick<RagAnswerResponse, 'sources' | 'contextSources'> & {
    origin?: 'CORE' | 'DIALOG' | 'DIALOG_CONTEXT_CONFIRMATION' | null;
  }) | null;
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
  contextSources?: Array<{
    type: 'LINKED_CONVERSATION' | 'RELATED_HISTORY' | 'USER_MEMORY' | string;
    conversationId?: string;
    title: string;
    memoryKey?: string;
  }>;
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

export interface GeminiUsageTotals {
  requests: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface GeminiUsageReport {
  last24Hours: GeminiUsageTotals;
  last7Days: GeminiUsageTotals;
  lifetime: GeminiUsageTotals;
  daily: Array<GeminiUsageTotals & { date: string }>;
  recent: Array<Omit<GeminiUsageTotals, 'requests'> & { id: string; model: string; recordedAt: string }>;
  generatedAt: string;
}

export interface GeminiUsageCost {
  inputUsd: number;
  outputUsd: number;
  totalUsd: number;
  unpricedRequests: number;
}

export interface GeminiUsageFilter {
  startDate: string;
  endDate: string;
  page: number;
  pageSize: number;
}

export interface GeminiUsageRangeReport extends GeminiUsageFilter {
  timezone: string;
  totals: { requests: number; cost: GeminiUsageCost };
  daily: Array<{ date: string; requests: number; cost: GeminiUsageCost }>;
  models: Array<{ model: string; requests: number; cost: GeminiUsageCost }>;
  recent: Array<{ id: string; model: string; recordedAt: string; cost: GeminiUsageCost }>;
  totalPages: number;
  generatedAt: string;
  pricingSource: string;
  pricingAsOf: string;
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
  dialogEnabled?: boolean;
  widgetDocumentIds?: string[];
  customInstructions?: string;
  status: StudioBotStatus;
  publicKey: string;
  draftRevision: number;
  publishedReleaseId?: string;
  updatedAt: string;
  active?: boolean;
  customization?: StudioCustomization;
}

export interface StudioCustomization {
  identityRole?: string;
  tonePreset?: string;
  answerLength?: string;
  responseStructure?: string;
  proactivity?: string;
  citationPolicy?: string;
  noSourcePolicy?: string;
  preferredTerms?: string;
  forbiddenTerms?: string;
  fallbackMessage?: string;
  escalationMessage?: string;
  responseProfile?: string;
}

export interface StudioApiKeySettings {
  requestsPerMinute: number;
  defaultRequestsPerMinute: number;
  published: boolean;
  apiKey?: string;
  publishedRequestsPerMinute?: number;
  releaseId?: string;
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
  englishResponseText?: string;
  englishMissingEntityPrompt?: string;
  englishIrrelevantResponseText?: string;
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
  contextReusePolicy?: 'NONE' | 'CONFIRMED_ONLY';
}

export interface StudioBotConfiguration {
  bot: StudioBotSummary;
  intents: StudioIntent[];
  entities: StudioEntity[];
  dialogNodes: StudioDialogNode[];
  customization?: StudioCustomization;
}

export interface StudioPreviewContext {
  pendingIntent?: string;
  pendingEntity?: string;
  lastIntent?: string;
  variables: Record<string, string>;
  waitingNode?: string;
  suggestedVariables?: Record<string, string>;
  suggestionSource?: string;
}

export interface StudioDialogTrace {
  route: string;
  reason: string;
  visitedNodes: string[];
  confirmedEntities: Record<string, string>;
  contextSource?: string;
}

export interface ConversationContextSourceResponse {
  type: string;
  conversationId?: string;
  title: string;
  memoryKey?: string;
}

export interface StudioPreviewHistoryMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface StudioRuntimeResponse {
  message: string;
  matchedIntent?: string;
  recognizedEntities: Record<string, string>;
  matchedNode?: string;
  context: StudioPreviewContext;
  fallback: boolean;
  dialogHandled?: boolean;
  trace?: StudioDialogTrace;
  sources?: RagSourceResponse[];
  contextSources?: ConversationContextSourceResponse[];
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
  sessionMode?: 'browser' | 'token';
  fetchImpl?: FetchImplementation;
}

export class BlueAiApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string,
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'BlueAiApiError';
  }
}

function withoutTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

function removeStoredTokens(): void {
  if (typeof window !== 'undefined') {
    try {
      // Migrate by discarding old JS-readable credentials, never importing them.
      window.localStorage.removeItem(TOKEN_STORAGE_KEY);
      window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    } catch { /* Storage may be disabled by the browser's privacy policy. */ }
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
  const retryHeader = response.headers.get('Retry-After');
  const numericRetry = retryHeader === null ? NaN : Number(retryHeader);
  const retryAfter = Number.isFinite(numericRetry) ? numericRetry
    : retryHeader ? Math.ceil((Date.parse(retryHeader) - Date.now()) / 1000) : NaN;
  return new BlueAiApiError(message, response.status, code,
    Number.isFinite(retryAfter) ? Math.max(1, retryAfter) : undefined);
}

export class BlueAiApi {
  private readonly baseUrl: string;
  private readonly fetchImpl: FetchImplementation;
  private accessToken?: string;
  private refreshToken?: string;
  private refreshing?: Promise<string>;
  private readonly browserSession: boolean;
  private browserSessionAvailable = true;
  private sessionVersion = 0;
  private readonly sessionClearedListeners = new Set<() => void>();
  private readonly quotaChangedListeners = new Set<(retryAfterSeconds?: number) => void>();

  constructor(private readonly options: BlueAiApiOptions = {}) {
    this.baseUrl = withoutTrailingSlash(options.baseUrl ?? DEFAULT_API_BASE_URL);
    this.fetchImpl = options.fetchImpl || globalThis.fetch.bind(globalThis);
    this.browserSession = (options.sessionMode ?? (options.accessToken || options.refreshToken ? 'token' : 'browser')) === 'browser';
    this.accessToken = options.accessToken;
    this.refreshToken = this.browserSession ? undefined : options.refreshToken;
    removeStoredTokens();
  }

  hasSession(): boolean {
    return Boolean(this.accessToken || this.refreshToken || (this.browserSession && this.browserSessionAvailable));
  }

  onSessionCleared(listener: () => void): () => void {
    this.sessionClearedListeners.add(listener);
    return () => { this.sessionClearedListeners.delete(listener); };
  }

  onQuotaChanged(listener: (retryAfterSeconds?: number) => void): () => void {
    this.quotaChangedListeners.add(listener);
    return () => { this.quotaChangedListeners.delete(listener); };
  }

  async getChatQuota(): Promise<ChatQuota> {
    return this.authorizedRequest<ChatQuota>('/api/v1/auth/quota', { method: 'GET' });
  }

  async getAiTokenQuota(): Promise<AiTokenQuota> {
    return this.authorizedRequest<AiTokenQuota>('/api/v1/auth/ai-quota', { method: 'GET' });
  }

  async getQuotaOverview(): Promise<QuotaOverview> {
    return this.authorizedRequest<QuotaOverview>('/api/v1/admin/quotas', { method: 'GET' });
  }

  async changeMyPassword(newPassword: string, currentPassword?: string): Promise<void> {
    return this.authorizedRequest<void>(this.authPath('password/change'), {
      method: 'POST', body: JSON.stringify({ currentPassword, newPassword }),
    });
  }

  async login(credentials: LoginCredentials): Promise<UserResponse> {
    const response = await this.request<AuthResponse>(this.authPath('login'), {
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
    try {
      // Finish a single-flight rotation before revoking the latest credential.
      await this.refreshing?.catch(() => undefined);
      if (this.browserSession || this.refreshToken) {
        await this.request<void>(this.authPath('logout'), {
          method: 'POST',
          body: this.browserSession ? undefined : JSON.stringify({ refreshToken: this.refreshToken }),
        });
      }
    } finally {
      this.clearSession();
    }
  }

  clearSession(): void {
    const hadAuthenticatedSession = Boolean(this.accessToken || this.refreshToken);
    this.sessionVersion++;
    this.accessToken = undefined;
    this.refreshToken = undefined;
    this.refreshing = undefined;
    this.browserSessionAvailable = false;
    removeStoredTokens();
    if (hadAuthenticatedSession) this.sessionClearedListeners.forEach(listener => listener());
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

  async getConversationContext(conversationId: string): Promise<ConversationContextSettingsResponse> {
    return this.authorizedRequest<ConversationContextSettingsResponse>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/context`,
      { method: 'GET' },
    );
  }

  async updateConversationContext(
    conversationId: string,
    mode: ConversationContextMode,
  ): Promise<ConversationContextSettingsResponse> {
    return this.authorizedRequest<ConversationContextSettingsResponse>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/context`,
      { method: 'PUT', body: JSON.stringify({ mode }) },
    );
  }

  async createConversationLink(conversationId: string, sourceConversationId: string): Promise<ConversationLinkResponse> {
    return this.authorizedRequest<ConversationLinkResponse>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/context-links`,
      { method: 'POST', body: JSON.stringify({ sourceConversationId }) },
    );
  }

  async refreshConversationLink(conversationId: string, linkId: string): Promise<ConversationLinkResponse> {
    return this.authorizedRequest<ConversationLinkResponse>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/context-links/${encodeURIComponent(linkId)}/refresh`,
      { method: 'POST' },
    );
  }

  async deleteConversationLink(conversationId: string, linkId: string): Promise<void> {
    return this.authorizedRequest<void>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/context-links/${encodeURIComponent(linkId)}`,
      { method: 'DELETE' },
    );
  }

  async listMemories(): Promise<UserMemoryResponse[]> {
    return this.authorizedRequest<UserMemoryResponse[]>('/api/v1/me/memories', { method: 'GET' });
  }

  async upsertMemory(memoryKey: string, input: UpsertUserMemoryInput): Promise<UserMemoryResponse> {
    return this.authorizedRequest<UserMemoryResponse>(
      `/api/v1/me/memories/${encodeURIComponent(memoryKey)}`,
      { method: 'PUT', body: JSON.stringify(input) },
    );
  }

  async deleteMemory(memoryKey: string): Promise<void> {
    return this.authorizedRequest<void>(
      `/api/v1/me/memories/${encodeURIComponent(memoryKey)}`,
      { method: 'DELETE' },
    );
  }

  async listDocuments(): Promise<DocumentResponse[]> {
    return this.authorizedRequest<DocumentResponse[]>('/api/v1/documents', { method: 'GET' });
  }

  async deleteDocument(documentId: string): Promise<void> {
    return this.authorizedRequest<void>(`/api/v1/documents/${encodeURIComponent(documentId)}`, { method: 'DELETE' });
  }

  async uploadDocument(file: File): Promise<DocumentResponse> {
    // UX guard only: Java still validates the actual streamed bytes and format.
    if (file.size === 0) {
      throw new BlueAiApiError('Tài liệu đang rỗng. Vui lòng chọn tệp có nội dung.', 400, 'EMPTY_FILE');
    }
    if (file.size > 20 * 1024 * 1024) {
      throw new BlueAiApiError('Tài liệu vượt giới hạn 20 MiB. Vui lòng chọn tệp nhỏ hơn.', 413, 'FILE_TOO_LARGE');
    }
    if (!/\.(pdf|docx|csv|txt|md)$/i.test(file.name)) {
      throw new BlueAiApiError('Chỉ hỗ trợ PDF, DOCX, CSV, TXT hoặc Markdown.', 400, 'UNSUPPORTED_FILE_TYPE');
    }
    const body = new FormData();
    body.append('file', file);
    return this.authorizedRequest<DocumentResponse>('/api/v1/documents', { method: 'POST', body });
  }

  async getSystemMetrics(): Promise<SystemMetricsResponse> {
    return this.authorizedRequest<SystemMetricsResponse>('/api/v1/system/metrics', { method: 'GET' });
  }

  async getGeminiUsage(filters: GeminiUsageFilter): Promise<GeminiUsageRangeReport> {
    const params = new URLSearchParams({
      startDate: filters.startDate, endDate: filters.endDate,
      page: String(filters.page), pageSize: String(filters.pageSize),
    });
    return this.authorizedRequest<GeminiUsageRangeReport>(`/api/v1/system/gemini-usage?${params}`, { method: 'GET' });
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
    template?: 'STARTER' | 'SALES_DEMO' | 'RAG_DIALOG_DEMO';
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

  async getActiveStudioBot(): Promise<StudioBotConfiguration> {
    return this.authorizedRequest<StudioBotConfiguration>('/api/v1/studio/bots/active', { method: 'GET' });
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
      dialogEnabled?: boolean;
      widgetDocumentIds?: string[];
      customInstructions?: string;
      customization?: StudioCustomization;
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
    options: { conversationId?: string; history?: StudioPreviewHistoryMessage[] } = {},
  ): Promise<StudioRuntimeResponse> {
    return this.authorizedRequest<StudioRuntimeResponse>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/preview`,
      { method: 'POST', body: JSON.stringify({ message, context, ...options }) },
    );
  }

  async publishStudioBot(botId: string): Promise<StudioPublishResponse> {
    return this.authorizedRequest<StudioPublishResponse>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/publish`,
      { method: 'POST' },
    );
  }

  async getStudioApiKey(botId: string): Promise<StudioApiKeySettings> {
    return this.authorizedRequest<StudioApiKeySettings>(`/api/v1/studio/bots/${encodeURIComponent(botId)}/api-key`, { method: 'GET' });
  }

  async saveStudioApiKeySettings(botId: string, requestsPerMinute: number): Promise<StudioApiKeySettings> {
    return this.authorizedRequest<StudioApiKeySettings>(`/api/v1/studio/bots/${encodeURIComponent(botId)}/api-key-settings`, {
      method: 'PUT', body: JSON.stringify({ requestsPerMinute }),
    });
  }

  async activateStudioBot(botId: string): Promise<StudioBotConfiguration> {
    return this.authorizedRequest<StudioBotConfiguration>(
      `/api/v1/studio/bots/${encodeURIComponent(botId)}/activate`,
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

  async resetManagedUserPassword(userId: string, currentPassword: string): Promise<TemporaryPassword> {
    return this.authorizedRequest<TemporaryPassword>(`/api/v1/admin/users/${encodeURIComponent(userId)}/password`, {
      method: 'POST', body: JSON.stringify({ currentPassword }),
    });
  }

  async deleteManagedUser(userId: string): Promise<UserResponse> {
    return this.authorizedRequest<UserResponse>(`/api/v1/admin/users/${encodeURIComponent(userId)}`, { method: 'DELETE' });
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
      if (!(error instanceof BlueAiApiError) || error.status !== 401 || !this.hasRefreshCredential()) {
        if (error instanceof BlueAiApiError && error.status === 401) this.clearSession();
        throw error;
      }

      const refreshedToken = await this.refreshAccessToken();
      try {
        return await this.request<T>(path, init, refreshedToken);
      } catch (retryError) {
        if (retryError instanceof BlueAiApiError && retryError.status === 401) this.clearSession();
        throw retryError;
      }
    }
  }

  private async ensureAccessToken(): Promise<string> {
    if (this.accessToken) return this.accessToken;
    if (this.hasRefreshCredential()) return this.refreshAccessToken();
    throw new BlueAiApiError('Vui lòng đăng nhập để tiếp tục.', 401, 'AUTH_REQUIRED');
  }

  private async refreshAccessToken(): Promise<string> {
    if (this.refreshing) return this.refreshing;
    const refreshToken = this.refreshToken;
    if (!this.hasRefreshCredential()) {
      throw new BlueAiApiError('Phiên đăng nhập đã hết hạn.', 401, 'AUTH_REQUIRED');
    }

    const version = this.sessionVersion;
    const pending = this.request<AuthResponse>(this.authPath('refresh'), {
      method: 'POST',
      body: this.browserSession ? undefined : JSON.stringify({ refreshToken }),
    }).then((response) => {
      if (version !== this.sessionVersion) throw new BlueAiApiError('Phiên đăng nhập đã thay đổi.', 401, 'AUTH_REQUIRED');
      this.applyAuthResponse(response);
      return response.accessToken;
    }).catch((error) => {
      if (version === this.sessionVersion) this.clearSession();
      throw error;
    }).finally(() => {
      if (this.refreshing === pending) this.refreshing = undefined;
    });
    this.refreshing = pending;
    return this.refreshing;
  }

  private applyAuthResponse(response: AuthResponse): void {
    this.accessToken = response.accessToken;
    this.refreshToken = this.browserSession ? undefined : response.refreshToken;
    this.browserSessionAvailable = true;
  }

  private hasRefreshCredential(): boolean {
    return Boolean(this.refreshToken || (this.browserSession && this.browserSessionAvailable));
  }

  private authPath(action: 'login' | 'refresh' | 'logout' | 'password/change'): string {
    return `/api/v1/auth/${this.browserSession ? 'browser/' : ''}${action}`;
  }

  private async request<T>(path: string, init: RequestInit, token?: string): Promise<T> {
    const headers = new Headers(init.headers);
    if (!(init.body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
    }
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const cookieAuth = this.browserSession && path.startsWith('/api/v1/auth/browser/');
    if (cookieAuth) headers.set('X-BlueAI-Browser', '1');

    const send = () => this.fetchImpl(`${this.baseUrl}${path}`, {
      ...init,
      headers,
      credentials: cookieAuth ? 'include' : 'omit',
    });
    // Serialize cookie rotation across tabs so both cannot consume the same refresh token.
    const response = cookieAuth && typeof navigator !== 'undefined' && navigator.locks
      ? await navigator.locks.request('blueai-browser-session', send)
      : await send();
    const chatRequest = init.method === 'POST' && /\/(ask|preview)$/.test(path);
    if (!response.ok) {
      const error = await readApiError(response);
      if (chatRequest) this.quotaChangedListeners.forEach(listener => listener(error.status === 429 && error.code === 'RAG_RATE_LIMITED' ? error.retryAfterSeconds : undefined));
      throw error;
    }
    if (chatRequest) this.quotaChangedListeners.forEach(listener => listener());
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
  const isProduct = source.sourceType === 'PRODUCT_CATALOG';
  return {
    id: `${source.documentId}:${source.chunkId}`,
    sourceId: source.documentId,
    title: source.filename,
    section: isProduct ? `SKU ${source.chunkId}` : `Chunk ${source.chunkId}${score}`,
    version: source.sourceType === 'SYNTHETIC_DEMO'
      ? 'Dữ liệu demo'
      : isProduct ? 'Danh mục sản phẩm đã đồng bộ' : isEnterpriseSource ? 'Nguồn doanh nghiệp' : 'Nguồn RAG API',
    effectiveDate: source.approvalStatus === 'PENDING_OWNER_REVIEW' ? 'Chưa được phê duyệt' : 'Đã xác minh khi truy xuất',
    owner: source.sourceType === 'SYNTHETIC_DEMO'
      ? 'Bộ dữ liệu mẫu BlueAI'
      : isProduct ? 'Danh mục Bluecom' : isEnterpriseSource ? 'Kho tri thức do Admin cung cấp' : 'Kho tri thức BlueAI',
    scope: 'Theo quyền truy cập của tài khoản hiện tại',
    snippet: source.excerpt,
    status: source.approvalStatus === 'PENDING_OWNER_REVIEW' ? 'scope_mismatch' : 'valid',
  };
}

export function conversationMessageToChatMessage(message: ConversationMessageResponse): ChatMessage {
  if (message.role === 'ASSISTANT' && message.evidence) {
    return ragAnswerToChatMessage({
      userMessage: message,
      assistantMessage: message,
      ...message.evidence,
    });
  }
  const createdAt = new Date(message.createdAt);
  return {
    id: message.id,
    sender: message.role === 'USER' ? 'user' : 'assistant',
    content: message.role === 'USER' ? message.content : normalizeAssistantContent(message.content),
    timestamp: Number.isNaN(createdAt.getTime()) ? '' : createdAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    // Legacy messages did not persist evidence. Never invent provenance for them.
  };
}

export function ragAnswerToChatMessage(response: RagAnswerResponse): ChatMessage {
  const citations = (response.sources || []).map(ragSourceToCitation);
  const contextSources = response.contextSources || [];
  const hasUnapprovedSource = citations.some(citation => citation.status === 'scope_mismatch');
  const createdAt = new Date(response.assistantMessage.createdAt);
  const origin = response.assistantMessage.evidence?.origin;
  const isDialog = origin === 'DIALOG' || origin === 'DIALOG_CONTEXT_CONFIRMATION';
  return {
    id: response.assistantMessage.id,
    sender: 'assistant',
    content: normalizeAssistantContent(response.assistantMessage.content),
    timestamp: Number.isNaN(createdAt.getTime())
      ? new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      : createdAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    // No verified source is not an error: the assistant is expected to say the
    // knowledge base does not cover the question. Red is reserved for real failures.
    confidence: origin === 'DIALOG_CONTEXT_CONFIRMATION' ? 'context_confirmation'
      : origin === 'DIALOG' ? 'dialog'
      : citations.length === 0
      ? contextSources.length > 0 ? 'context' : 'ungrounded'
      : hasUnapprovedSource ? 'inferred' : 'confirmed',
    citations,
    contextSources,
    toolsUsed: isDialog ? ['Dialog_Runtime']
      : citations.length > 0 ? ['RAG_Retrieval_API', 'LLM_Chat_API'] : ['LLM_Chat_API'],
  };
}

export const blueAiApi = new BlueAiApi({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
});
