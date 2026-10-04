import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHmac } from 'node:crypto';
import { writeFileSync, readFileSync } from 'node:fs';
const project = 'blueai-preprod-20261003';
const inspect = name => {
  const process = spawnSync('docker', ['inspect', `${project}-${name}-1`], { encoding: 'utf8' });
  assert.equal(process.status, 0);
  const container = JSON.parse(process.stdout)[0];
  assert.equal(container.Config.Labels['com.docker.compose.project'], project);
  return container;
};
const api = inspect('auth-api');
assert(api.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const request = async (route, token, method = 'GET', body, headers = {}) => {
  const response = await fetch('http://127.0.0.1:23081' + route, { method, headers: {
    ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers,
  }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30_000) });
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
};
const auth = await request('/api/v1/auth/login', null, 'POST', { email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only' });
assert.equal(auth.status, 200);
const token = auth.json.accessToken;
const evidence = { scope: 'Owned QA only, no provider calls', tests: [] };
const check = async (id, action) => {
  await action(); evidence.tests.push({ id, result: 'PASS' }); console.log('PASS ' + id);
};
await check('AUTH-05-expiry-positive-control', async () => {
  const [header, payload] = token.split('.');
  assert.equal(JSON.parse(Buffer.from(header, 'base64url')).alg, 'HS256');
  const secret = api.Config.Env.find(value => value.startsWith('APP_JWT_SECRET='))?.slice('APP_JWT_SECRET='.length);
  assert(secret);
  const sign = encoded => `${header}.${encoded}.` + createHmac('sha256', Buffer.from(secret, 'base64')).update(`${header}.${encoded}`).digest('base64url');
  assert.equal((await request('/api/v1/auth/me', sign(payload))).status, 200);
  const claims = JSON.parse(Buffer.from(payload, 'base64url'));
  const now = Math.floor(Date.now() / 1000);
  Object.assign(claims, { iat: now - 300, nbf: now - 300, exp: now - 120 });
  assert.equal((await request('/api/v1/auth/me', sign(Buffer.from(JSON.stringify(claims)).toString('base64url')))).status, 401);
  evidence.validSignatureControl = 'HTTP 200'; evidence.expiredSignatureControl = 'HTTP 401';
});
await check('API-02-expired-widget', async () => {
  const result = JSON.parse(readFileSync(new URL('./qa-api-results-2026-10-04.json', import.meta.url)));
  const bot = await request('/api/v1/studio/bots/' + result.fixtures.bot, token); assert.equal(bot.status, 200);
  const session = await request('/api/v1/widget/' + bot.json.bot.publicKey + '/sessions', null, 'POST'); assert.equal(session.status, 201);
  assert.match(session.json.sessionId, /^[a-f0-9-]{36}$/);
  const send = suffix => request(`/api/v1/runtime/sessions/${session.json.sessionId}/messages`, null, 'POST',
    { requestId: 'qa-expiry-' + suffix, message: 'xin chào' }, { 'X-Widget-Token': session.json.accessToken });
  assert.equal((await send('valid')).status, 200); // Core is off for this synthetic bot.
  inspect('mysql');
  const sql = `UPDATE studio_bot_sessions SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE id='${session.json.sessionId}';`;
  const updated = spawnSync('docker', ['exec', `${project}-mysql-1`, 'sh', '-c', 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot blueai_auth -e "$1"', 'sh', sql], { encoding: 'utf8' });
  assert.equal(updated.status, 0, 'Expire only the just-created owned synthetic session');
  assert.equal((await send('expired')).status, 410); // Published contract: an expired session is Gone.
});
await check('API-09-document-allowlist', async () => {
  const body = { name: 'QA grant scope ' + Date.now(), capabilities: ['RAG_ANSWER'], documentIds: ['61b78d0d-ae15-4743-a0e9-a91d6aed2d7b'] };
  const other = JSON.parse(readFileSync(new URL('./qa-api-results-2026-10-04.json', import.meta.url))).fixtures['admin-b'];
  const otherAuth = await request('/api/v1/auth/login', null, 'POST', { email: other.email, password: 'QaSynthetic2026!NoRealCredential' }); assert.equal(otherAuth.status, 200);
  assert.equal((await request('/api/v1/admin/integration-clients', otherAuth.json.accessToken, 'POST', body)).status, 400);
  const own = await request('/api/v1/admin/integration-clients', token, 'POST', body); assert.equal(own.status, 201);
  assert.deepEqual(own.json.documentIds, body.documentIds);
  assert.equal((await request('/api/v1/admin/integration-clients/' + own.json.id, token, 'DELETE')).status, 204);
});
evidence.finishedAt = new Date().toISOString();
writeFileSync(new URL('./qa-session-edge-results-2026-10-04.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
