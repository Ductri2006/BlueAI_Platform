import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHmac } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const project = 'blueai-preprod-20261003';
const base = 'http://127.0.0.1:23081';
const inspected = spawnSync('docker', ['inspect', `${project}-auth-api-1`], { encoding: 'utf8' });
assert.equal(inspected.status, 0);
const api = JSON.parse(inspected.stdout)[0];
assert.equal(api.Config.Labels['com.docker.compose.project'], project);
assert(api.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
assert.equal(api.NetworkSettings.Ports['8080/tcp'][0].HostIp, '127.0.0.1');
const run = Date.now().toString(36);
const evidence = { scope: 'Disposable QA only; no provider calls', startedAt: new Date().toISOString(), tests: [], fixtures: {} };
const request = async (path, token, method = 'GET', body, headers = {}) => {
  const response = await fetch(base + path, { method, headers: {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers,
  }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  const text = await response.text();
  let json; try { json = JSON.parse(text); } catch { json = null; }
  return { status: response.status, json, headers: response.headers };
};
const status = (response, expected) => assert.equal(response.status, expected, `HTTP ${response.status}, expected ${expected}`);
const check = async (id, name, action) => {
  try { await action(); evidence.tests.push({ id, name, result: 'PASS' }); console.log(`PASS ${id}: ${name}`); }
  catch (error) { evidence.tests.push({ id, name, result: 'FAIL', actual: error.message }); console.log(`FAIL ${id}: ${error.message}`); }
};
const login = await request('/api/v1/auth/login', null, 'POST', { email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only' });
status(login, 200);
const token = login.json.accessToken;
const user = await request('/api/v1/admin/users', token, 'POST', {
  email: `qa-ui-expiry-${run}@example.test`, password: 'QaSynthetic2026!NoRealCredential', fullName: `QA expiry ${run}`, role: 'USER',
});
status(user, 201);
evidence.fixtures.uiExpiryUser = { id: user.json.id, email: user.json.email };

await check('AUTH-05-expiry', 'Correctly signed but expired access JWT is rejected', async () => {
  const [header, payload] = token.split('.');
  const claims = JSON.parse(Buffer.from(payload, 'base64url'));
  const secret = api.Config.Env.find(value => value.startsWith('APP_JWT_SECRET='))?.slice('APP_JWT_SECRET='.length);
  assert(secret);
  const sign = encoded => createHmac('sha256', Buffer.from(secret, 'base64')).update(`${header}.${encoded}`).digest('base64url');
  status(await request('/api/v1/auth/me', `${header}.${payload}.${sign(payload)}`), 200); // Positive signature control.
  const now = Math.floor(Date.now() / 1000);
  Object.assign(claims, { iat: now - 300, nbf: now - 300, exp: now - 120 });
  const encoded = Buffer.from(JSON.stringify(claims)).toString('base64url');
  const signature = sign(encoded);
  status(await request('/api/v1/auth/me', `${header}.${encoded}.${signature}`), 401);
});
await check('AUTH-08-pagination', 'Admin paging, name search, ACTIVE/DISABLED and includeDeleted filters', async () => {
  let page = await request(`/api/v1/admin/users?page=0&size=1&search=${run}&status=ACTIVE`, token);
  status(page, 200); assert.equal(page.json.totalElements, 1); assert.equal(page.json.content[0].id, user.json.id);
  page = await request(`/api/v1/admin/users?page=1&size=1&search=${run}`, token);
  status(page, 200); assert.equal(page.json.content.length, 0);
  status(await request(`/api/v1/admin/users/${user.json.id}/status`, token, 'PATCH', { status: 'DISABLED' }), 200);
  page = await request(`/api/v1/admin/users?search=${run}&status=DISABLED`, token);
  assert.equal(page.json.content[0].id, user.json.id);
  status(await request(`/api/v1/admin/users/${user.json.id}/status`, token, 'PATCH', { status: 'ACTIVE' }), 200);
  for (const query of ['page=-1', 'size=0', 'size=101', 'status=UNKNOWN']) status(await request('/api/v1/admin/users?' + query, token), 400);
});
await check('AUTH-10-includeDeleted', 'Deleted account excluded by default and visible only in explicit deleted filter', async () => {
  const disposable = await request('/api/v1/admin/users', token, 'POST', {
    email: `qa-soft-delete-${run}@example.test`, password: 'QaSynthetic2026!NoRealCredential', fullName: `QA deleted ${run}`, role: 'USER',
  }); status(disposable, 201);
  status(await request(`/api/v1/admin/users/${disposable.json.id}`, token, 'DELETE'), 200);
  const normal = await request('/api/v1/admin/users?search=' + disposable.json.email, token);
  assert.equal(normal.json.totalElements, 0);
  const deleted = await request('/api/v1/admin/users?includeDeleted=true&search=' + disposable.json.email, token);
  status(deleted, 200); assert.equal(deleted.json.content[0].id, disposable.json.id);
});
await check('CTX-11-duplicate', 'Duplicate links are rejected without changing the existing snapshot', async () => {
  const create = async title => { const response = await request('/api/v1/conversations', token, 'POST', { title }); status(response, 201); return response.json.id; };
  const source = await create('QA source ' + run), target = await create('QA target ' + run);
  const link = await request(`/api/v1/conversations/${target}/context-links`, token, 'POST', { sourceConversationId: source }); status(link, 201);
  status(await request(`/api/v1/conversations/${target}/context-links`, token, 'POST', { sourceConversationId: source }), 409);
});
await check('KB-02-filter', 'Document READY/FAILED search, status filters and invalid page limits', async () => {
  for (const state of ['READY', 'FAILED']) {
    const response = await request(`/api/v1/documents/search?q=qa-sales-policy&status=${state}&page=0&size=1`, token);
    status(response, 200); assert(response.json.totalItems >= 1); assert(response.json.items.every(document => document.status === state));
  }
  for (const query of ['page=-1', 'size=0', 'size=101', 'status=UNKNOWN']) status(await request('/api/v1/documents/search?' + query, token), 400);
});
let bot;
await check('DLG-01-starter', 'Starter template creates isolated draft with default entities/intents', async () => {
  const response = await request('/api/v1/studio/bots', token, 'POST', { name: 'QA Starter ' + run, template: 'STARTER', locale: 'vi-VN', timezone: 'Asia/Ho_Chi_Minh' });
  status(response, 201); assert(response.json.intents.length > 0); assert(response.json.dialogNodes.length > 0); assert.equal(response.json.bot.active, false);
});
await check('API-01-unpublished', 'Unpublished and unknown public widget keys cannot create sessions', async () => {
  const response = await request('/api/v1/studio/bots', token, 'POST', { name: 'QA widget boundary ' + run, template: 'SALES_DEMO' });
  status(response, 201); bot = response.json;
  status(await request(`/api/v1/widget/${bot.bot.publicKey}/sessions`, null, 'POST'), 404);
  status(await request('/api/v1/widget/not-a-key/sessions', null, 'POST'), 404);
});
await check('DLG-09-unknown-product', 'Unknown product does not become a confirmed quote slot', async () => {
  assert(bot);
  const first = await request(`/api/v1/studio/bots/${bot.bot.id}/preview`, token, 'POST', { message: 'báo giá', context: null }); status(first, 200);
  const unknown = await request(`/api/v1/studio/bots/${bot.bot.id}/preview`, token, 'POST', { message: 'sản phẩm Z', context: first.json.context }); status(unknown, 200);
  assert.equal(unknown.json.context.pendingEntity, 'san_pham'); assert(!unknown.json.context.variables.san_pham);
});
await check('AUTH-browser-CSRF', 'Live cookie auth rejects missing marker and hostile Origin before issuing cookies', async () => {
  const body = { email: user.json.email, password: 'QaSynthetic2026!NoRealCredential' };
  for (const headers of [{ Origin: 'http://localhost:23000' }, { Origin: 'https://untrusted.example.test', 'X-BlueAI-Browser': '1' }]) {
    const response = await request('/api/v1/auth/browser/login', null, 'POST', body, headers);
    status(response, 403); assert(!response.headers.get('set-cookie'));
  }
});
await check('CTX-08-fixtures', 'Create owned matching/unrelated histories and a separate fresh target for UI automatic recall', async () => {
  const login = await request('/api/v1/auth/login', null, 'POST', { email: user.json.email, password: 'QaSynthetic2026!NoRealCredential' }); status(login, 200);
  const ownerToken = login.json.accessToken;
  const seed = async (title, content) => {
    const conversation = await request('/api/v1/conversations', ownerToken, 'POST', { title }); status(conversation, 201);
    status(await request(`/api/v1/conversations/${conversation.json.id}/messages`, ownerToken, 'POST', { content }), 201);
    return conversation.json.id;
  };
  evidence.fixtures.automaticSource = await seed('QATarvos customer notes ' + run, 'QATarvos has 37 sales, a monthly budget of 2.9 million VND and opportunity code TV-4712. These are synthetic QA facts.');
  evidence.fixtures.unrelatedSource = await seed('Gardening hobby notes ' + run, 'Plant rosemary and lavender in spring. EXCLUDE-UNRELATED-HISTORY.');
  evidence.fixtures.automaticTarget = await seed('QA automatic recall target ' + run, 'Phiên mới để kiểm tra tự tìm lịch sử liên quan. Chưa liên kết thủ công phiên nào.');
});
evidence.finishedAt = new Date().toISOString();
evidence.counts = { pass: evidence.tests.filter(test => test.result === 'PASS').length, fail: evidence.tests.filter(test => test.result === 'FAIL').length };
writeFileSync(new URL('./qa-remediation-api-results-2026-10-04.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
console.log(JSON.stringify(evidence.counts));
process.exitCode = evidence.counts.fail ? 1 : 0;
