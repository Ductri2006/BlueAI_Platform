import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const project = 'blueai-preprod-20261003';
const inspected = spawnSync('docker', ['inspect', `${project}-auth-api-1`], { encoding: 'utf8' });
assert.equal(inspected.status, 0);
const container = JSON.parse(inspected.stdout)[0];
assert.equal(container.Config.Labels['com.docker.compose.project'], project);
assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const fixtures = JSON.parse(readFileSync(new URL('./qa-api-results-2026-10-04.json', import.meta.url))).fixtures;
const call = async (path, token, method = 'GET', body) => {
  const response = await fetch('http://127.0.0.1:23081' + path, { method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30_000) });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
};
const login = async email => {
  const response = await call('/api/v1/auth/login', null, 'POST', { email, password: 'QaSynthetic2026!NoRealCredential' });
  assert.equal(response.status, 200); return response.body.accessToken;
};
const token = await login(fixtures['user-a'].email), otherToken = await login(fixtures['user-b'].email);
const marker = 'QA-PAGING-20261004-' + Date.now().toString(36), created = [];
const result = { scope: 'Owned QA conversations only; API paging, not a claim of a paginated UI', marker, result: 'FAIL' };
try {
  for (let i = 0; i < 6; i++) {
    const response = await call('/api/v1/conversations', token, 'POST', { title: marker + '-' + i });
    assert.equal(response.status, 201); created.push(response.body.id);
  }
  const seen = [];
  for (let page = 0; page < 3; page++) {
    const response = await call(`/api/v1/conversations/search?q=${marker}&page=${page}&size=2`, token);
    assert.equal(response.status, 200);
    assert.equal(response.body.totalItems, 6); assert.equal(response.body.totalPages, 3);
    assert.equal(response.body.items.length, 2);
    assert.equal(response.body.hasPrevious, page > 0); assert.equal(response.body.hasNext, page < 2);
    seen.push(...response.body.items.map(value => value.id));
  }
  assert.deepEqual([...seen].sort(), [...created].sort());
  assert.equal(new Set(seen).size, 6, 'No duplicate or missing rows between pages');
  const other = await call('/api/v1/conversations/search?q=' + marker, otherToken);
  assert.equal(other.status, 200); assert.equal(other.body.totalItems, 0); assert.deepEqual(other.body.items, []);
  for (const query of ['page=-1', 'size=0', 'size=101']) {
    assert.equal((await call('/api/v1/conversations/search?' + query, token)).status, 400);
  }
  result.result = 'PASS'; result.pages = 3; result.items = 6;
  result.ownerIsolation = 'USER B sees zero rows'; result.invalidPageLimits = 'HTTP 400';
  console.log('Conversation API paging PASS: 6 rows / 3 pages, no duplicates, correct navigation, owner isolation and invalid limits.');
} catch (error) {
  result.error = error.message; throw error;
} finally {
  for (const id of created) assert.equal((await call('/api/v1/conversations/' + id, token, 'DELETE')).status, 204);
  result.cleanup = 'Only six exact newly created QA conversations soft-deleted';
  result.finishedAt = new Date().toISOString();
  writeFileSync(new URL('./qa-conversation-pagination-results-2026-10-04.json', import.meta.url), JSON.stringify(result, null, 2) + '\n');
}
