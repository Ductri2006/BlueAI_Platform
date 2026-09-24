const baseUrl = (process.env.STAGING_BASE_URL ?? '').replace(/\/$/, '');
if (!baseUrl || process.env.STAGING_CONFIRM !== 'I_UNDERSTAND_STAGING_SECURITY_E2E') throw new Error('Set STAGING_BASE_URL and STAGING_CONFIRM=I_UNDERSTAND_STAGING_SECURITY_E2E.');
if (!/^https:\/\//i.test(baseUrl) || /localhost|127\.0\.0\.1|0\.0\.0\.0/i.test(baseUrl)) throw new Error('Security E2E only runs against an HTTPS staging ingress.');

const required = ['USER_A_EMAIL', 'USER_A_PASSWORD', 'USER_B_EMAIL', 'USER_B_PASSWORD', 'ADMIN_EMAIL', 'ADMIN_PASSWORD', 'ALLOWED_ORIGIN', 'DENIED_ORIGIN'];
for (const key of required) if (!process.env[key]) throw new Error(`${key} is required.`);
const widgetKey = process.env.WIDGET_PUBLIC_KEY;
const metricsPath = process.env.PROMETHEUS_PATH ?? '/actuator/prometheus';
const assert = (value, message) => { if (!value) throw new Error(message); };

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, { ...options, signal: AbortSignal.timeout(Number(process.env.TIMEOUT_MS ?? 30_000)) });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { /* text endpoint */ }
  return { response, text, body };
}
function json(result, label) {
  const contentType = result.response.headers.get('content-type') ?? '';
  assert(contentType.includes('application/json'), `${label}: expected JSON content-type, got ${contentType}`);
  assert(result.body && typeof result.body === 'object', `${label}: invalid JSON body`);
}
function status(result, expected, label) { assert(result.response.status === expected, `${label}: expected ${expected}, got ${result.response.status}`); }

async function login(email, password, label) {
  const result = await request('/api/v1/auth/login', { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify({ email, password }) });
  status(result, 200, label);
  json(result, label);
  assert(!result.response.headers.has('set-cookie'), `${label}: tokens must not be set in cookies`);
  assert(typeof result.body.accessToken === 'string' && result.body.accessToken.length > 20, `${label}: accessToken missing`);
  assert(typeof result.body.refreshToken === 'string' && result.body.refreshToken.length > 20, `${label}: refreshToken missing`);
  return result.body.accessToken;
}
async function expectLoginFailure(email, password, label) {
  const result = await request('/api/v1/auth/login', { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify({ email, password }) });
  status(result, 401, label);
  json(result, label);
  assert(!result.body.accessToken && !result.body.refreshToken, `${label}: failure response leaked a token`);
}
function bearer(token, extra = {}) { return { accept: 'application/json', authorization: `Bearer ${token}`, ...extra }; }

const anonymousMe = await request('/api/v1/auth/me', { headers: { accept: 'application/json' } });
status(anonymousMe, 401, 'anonymous /auth/me'); json(anonymousMe, 'anonymous /auth/me');
assert(anonymousMe.body.code === 'AUTHENTICATION_REQUIRED', 'anonymous /auth/me: wrong error code');

const publicHealth = await request('/actuator/health', { headers: { accept: 'application/json' } });
status(publicHealth, 404, 'public actuator health boundary');
assert(!publicHealth.response.headers.has('access-control-allow-origin'), 'public actuator health boundary: must not grant browser CORS');

const tokenA = await login(process.env.USER_A_EMAIL, process.env.USER_A_PASSWORD, 'user A login');
const tokenB = await login(process.env.USER_B_EMAIL, process.env.USER_B_PASSWORD, 'user B login');
const adminToken = await login(process.env.ADMIN_EMAIL, process.env.ADMIN_PASSWORD, 'monitoring ADMIN login');
let conversationId = null;
let secretMarker = null;

try {
const meA = await request('/api/v1/auth/me', { headers: bearer(tokenA) });
status(meA, 200, 'user A /auth/me'); json(meA, 'user A /auth/me'); assert(meA.body.email.toLowerCase() === process.env.USER_A_EMAIL.toLowerCase(), 'user A identity mismatch');

const invalid = await request('/api/v1/auth/me', { headers: bearer('not-a-jwt') });
status(invalid, 401, 'invalid bearer'); json(invalid, 'invalid bearer');

const forbiddenAdmin = await request('/api/v1/admin/users', { headers: bearer(tokenA) });
status(forbiddenAdmin, 403, 'USER -> admin endpoint'); json(forbiddenAdmin, 'USER -> admin endpoint'); assert(forbiddenAdmin.body.code === 'ACCESS_DENIED', 'USER -> admin endpoint: wrong error code');

const publicMetrics = await request(metricsPath, { headers: { accept: 'text/plain' } });
status(publicMetrics, 404, 'public metrics boundary');
assert(!publicMetrics.response.headers.has('access-control-allow-origin'), 'public metrics boundary: must not grant browser CORS');
const userOps = await request('/api/v1/system/metrics', { headers: bearer(tokenA) });
status(userOps, 403, 'USER operational metrics'); json(userOps, 'USER operational metrics');
const adminOps = await request('/api/v1/system/metrics', { headers: bearer(adminToken) });
status(adminOps, 200, 'ADMIN operational metrics'); json(adminOps, 'ADMIN operational metrics');

const userSearch = await request(`/api/v1/admin/users?page=0&size=1&search=${encodeURIComponent(process.env.USER_A_EMAIL)}`, { headers: bearer(adminToken) });
status(userSearch, 200, 'ADMIN paged user search'); json(userSearch, 'ADMIN paged user search');
assert(Array.isArray(userSearch.body.content) && userSearch.body.content.length >= 1, 'ADMIN paged user search: missing content');
assert(userSearch.body.page === 0 && userSearch.body.size === 1, 'ADMIN paged user search: page metadata mismatch');
assert(userSearch.body.content.some((item) => item.email.toLowerCase() === process.env.USER_A_EMAIL.toLowerCase()), 'ADMIN paged user search: filter mismatch');

const publicWorkerRoute = await request('/api/v1/internal/documents/claim?limit=0', { method: 'POST', headers: { accept: 'application/json' } });
assert([403, 404].includes(publicWorkerRoute.response.status), `public worker route must be edge-blocked with 403/404, got ${publicWorkerRoute.response.status}; run ops/worker-private-e2e.ps1 on the Docker network instead`);

if (process.env.RUN_ACCOUNT_MUTATION_E2E === 'true') {
  const throwawayEmail = `security-e2e-${Date.now()}@staging.invalid`;
  const initialPassword = `E2eInitial-${Date.now()}!a`;
  const changedPassword = `E2eChanged-${Date.now()}!b`;
  const resetPassword = `E2eReset-${Date.now()}!c`;
  let throwawayId = null;
  try {
    const createdUser = await request('/api/v1/admin/users', {
      method: 'POST', headers: bearer(adminToken, { 'content-type': 'application/json' }),
      body: JSON.stringify({ email: throwawayEmail, password: initialPassword, fullName: 'Security E2E throwaway', role: 'USER' }),
    });
    status(createdUser, 201, 'throwaway user create'); json(createdUser, 'throwaway user create');
    throwawayId = createdUser.body.id;
    assert(typeof throwawayId === 'string', 'throwaway user create: missing id');

    const throwawayToken = await login(throwawayEmail, initialPassword, 'throwaway initial login');
    const change = await request('/api/v1/auth/password/change', { method: 'POST', headers: bearer(throwawayToken, { 'content-type': 'application/json' }), body: JSON.stringify({ currentPassword: initialPassword, newPassword: changedPassword }) });
    status(change, 204, 'throwaway password change');
    await expectLoginFailure(throwawayEmail, initialPassword, 'throwaway old password rejected');
    await login(throwawayEmail, changedPassword, 'throwaway changed-password login');

    const resetRequest = await request('/api/v1/auth/password-reset/request', { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify({ email: throwawayEmail }) });
    status(resetRequest, 202, 'throwaway password-reset request'); json(resetRequest, 'throwaway password-reset request');
    assert(typeof resetRequest.body.message === 'string' && resetRequest.body.message.toLowerCase().includes('if the account exists'), 'password-reset request: non-generic response');
    assert(!resetRequest.body.token && !resetRequest.body.rawToken, 'password-reset request: raw token leaked');
    const invalidReset = await request('/api/v1/auth/password-reset/confirm', { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify({ token: 'invalid-e2e-token', newPassword: resetPassword }) });
    status(invalidReset, 400, 'invalid password-reset token'); json(invalidReset, 'invalid password-reset token');
    assert(invalidReset.body.code === 'INVALID_PASSWORD_RESET_TOKEN', 'invalid password-reset token: wrong error code');
    if (process.env.PASSWORD_RESET_TOKEN) {
      const reset = await request('/api/v1/auth/password-reset/confirm', { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify({ token: process.env.PASSWORD_RESET_TOKEN, newPassword: resetPassword }) });
      status(reset, 204, 'throwaway password-reset confirm');
      await login(throwawayEmail, resetPassword, 'throwaway reset-password login');
    }

    const activeThrowawaySearch = await request(`/api/v1/admin/users?page=0&size=20&search=${encodeURIComponent(throwawayEmail)}`, { headers: bearer(adminToken) });
    status(activeThrowawaySearch, 200, 'active throwaway filter'); json(activeThrowawaySearch, 'active throwaway filter');
    assert(activeThrowawaySearch.body.content.some((item) => item.id === throwawayId), 'active throwaway filter: created user missing before cleanup');
  } finally {
    if (throwawayId) {
      const deletedThrowaway = await request(`/api/v1/admin/users/${encodeURIComponent(throwawayId)}`, { method: 'DELETE', headers: bearer(adminToken) });
      status(deletedThrowaway, 200, 'throwaway user soft-delete'); json(deletedThrowaway, 'throwaway user soft-delete');
      const hidden = await request(`/api/v1/admin/users?page=0&size=20&search=${encodeURIComponent(throwawayEmail)}`, { headers: bearer(adminToken) });
      status(hidden, 200, 'soft-deleted user hidden by default'); json(hidden, 'soft-deleted user hidden by default');
      assert(!hidden.body.content.some((item) => item.id === throwawayId), 'soft-deleted user still visible without includeDeleted');
      const included = await request(`/api/v1/admin/users?page=0&size=20&search=${encodeURIComponent(throwawayEmail)}&includeDeleted=true`, { headers: bearer(adminToken) });
      status(included, 200, 'soft-deleted user includeDeleted'); json(included, 'soft-deleted user includeDeleted');
      assert(included.body.content.some((item) => item.id === throwawayId), 'includeDeleted did not expose the soft-deleted throwaway for admin audit');
    }
  }
}

secretMarker = `e2e-isolation-${Date.now()}`;
const created = await request('/api/v1/conversations', { method: 'POST', headers: bearer(tokenA, { 'content-type': 'application/json' }), body: JSON.stringify({ title: secretMarker }) });
status(created, 201, 'user A create conversation'); json(created, 'user A create conversation');
conversationId = created.body.id;
assert(typeof conversationId === 'string' && conversationId.length > 10, 'user A create conversation: missing id');

const listA = await request('/api/v1/conversations', { headers: bearer(tokenA) });
status(listA, 200, 'user A list conversations'); json(listA, 'user A list conversations');
assert(listA.body.some((item) => item.id === conversationId && item.title === secretMarker), 'user A list: own conversation missing');
const conversationSearch = await request(`/api/v1/conversations/search?q=${encodeURIComponent(secretMarker)}&page=0&size=1`, { headers: bearer(tokenA) });
status(conversationSearch, 200, 'user A paged conversation search'); json(conversationSearch, 'user A paged conversation search');
assert(Array.isArray(conversationSearch.body.items) && conversationSearch.body.items.some((item) => item.id === conversationId), 'user A paged conversation search: filter mismatch');
assert(conversationSearch.body.page === 0 && conversationSearch.body.size === 1, 'user A paged conversation search: page metadata mismatch');
const listB = await request('/api/v1/conversations', { headers: bearer(tokenB) });
status(listB, 200, 'user B list conversations'); json(listB, 'user B list conversations');
assert(!listB.body.some((item) => item.id === conversationId || item.title === secretMarker), 'cross-user list isolation failed');

const crossGet = await request(`/api/v1/conversations/${encodeURIComponent(conversationId)}`, { headers: bearer(tokenB) });
status(crossGet, 404, 'user B get user A conversation'); json(crossGet, 'user B get user A conversation');
const crossDelete = await request(`/api/v1/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE', headers: bearer(tokenB) });
status(crossDelete, 404, 'user B delete user A conversation'); json(crossDelete, 'user B delete user A conversation');

if (widgetKey) {
  const allowedPreflight = await request(`/api/v1/widget/${encodeURIComponent(widgetKey)}/sessions`, { method: 'OPTIONS', headers: { origin: process.env.ALLOWED_ORIGIN, 'access-control-request-method': 'POST' } });
  assert([200, 204].includes(allowedPreflight.response.status), `allowed widget preflight: expected 200/204, got ${allowedPreflight.response.status}`);
  assert(allowedPreflight.response.headers.get('access-control-allow-origin') === process.env.ALLOWED_ORIGIN, 'allowed widget preflight: ACAO mismatch');
  const deniedPreflight = await request(`/api/v1/widget/${encodeURIComponent(widgetKey)}/sessions`, { method: 'OPTIONS', headers: { origin: process.env.DENIED_ORIGIN, 'access-control-request-method': 'POST' } });
  assert(deniedPreflight.response.status >= 400, `denied widget preflight: expected 4xx, got ${deniedPreflight.response.status}`);
  assert(!deniedPreflight.response.headers.has('access-control-allow-origin'), 'denied widget preflight: ACAO must not be granted');
}

console.log(JSON.stringify({
  result: 'PASS',
  checks: [
    'anonymous 401',
    'invalid bearer 401',
    'USER admin/operational metrics 403',
    'ADMIN operational metrics 200',
    'public actuator health/prometheus boundaries 404',
    'admin/conversation paging and filters',
    'cross-user list/get/delete isolation',
    'soft-deleted conversation hidden from get/search',
    'token/header assertions',
    widgetKey ? 'allowed/denied CORS preflight' : 'CORS skipped (WIDGET_PUBLIC_KEY not set)',
    'public worker route edge boundary 403/404 (no worker secret sent)',
    process.env.RUN_ACCOUNT_MUTATION_E2E === 'true' ? 'throwaway password change/reset and soft-deleted-user checks' : 'account mutation checks skipped (RUN_ACCOUNT_MUTATION_E2E != true)',
  ],
  target: baseUrl,
}));
} finally {
  if (conversationId) {
    const ownerDelete = await request(`/api/v1/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE', headers: bearer(tokenA) });
    status(ownerDelete, 204, 'user A cleanup conversation');
    const deletedGet = await request(`/api/v1/conversations/${encodeURIComponent(conversationId)}`, { headers: bearer(tokenA) });
    status(deletedGet, 404, 'soft-deleted conversation hidden from get'); json(deletedGet, 'soft-deleted conversation hidden from get');
    const deletedSearch = await request(`/api/v1/conversations/search?q=${encodeURIComponent(secretMarker)}&page=0&size=1`, { headers: bearer(tokenA) });
    status(deletedSearch, 200, 'soft-deleted conversation hidden from search'); json(deletedSearch, 'soft-deleted conversation hidden from search');
    assert(!deletedSearch.body.items.some((item) => item.id === conversationId), 'soft-deleted conversation still visible in search');
  }
}
