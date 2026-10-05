import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const project = 'blueai-preprod-20261003';
const base = 'http://127.0.0.1:23000';
const docker = args => {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout: 90000 });
  assert.equal(result.status, 0, `QA Docker ${args[0]} failed; raw output withheld`);
  return result.stdout;
};
for (const service of ['auth-api', 'redis', 'mysql']) {
  const container = JSON.parse(docker(['inspect', `${project}-${service}-1`]))[0];
  assert.equal(container.Config.Labels['com.docker.compose.project'], project);
  if (service === 'auth-api') {
    assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
    assert.equal(container.Config.Image, 'blueai-preprod-api:followup-20261004');
  }
}
const evidence = { generatedAt: new Date().toISOString(), scope: 'Isolated QA only; synthetic inputs; no provider chat calls', tests: [], fixtures: {} };
const request = async (path, { token, body, method = 'GET', headers = {}, form } = {}) => {
  const result = await fetch(base + path, { method, headers: {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers,
  }, body: form || (body !== undefined ? JSON.stringify(body) : undefined), signal: AbortSignal.timeout(60000) });
  const text = await result.text();
  let json; try { json = JSON.parse(text); } catch { json = null; }
  return { status: result.status, json, headers: result.headers };
};
const expect = (result, status) => assert.equal(result.status, status, `HTTP ${result.status}; expected ${status}`);
const check = async (id, fn) => {
  try { await fn(); evidence.tests.push({ id, result: 'PASS' }); console.log(`PASS ${id}`); }
  catch (error) { evidence.tests.push({ id, result: 'FAIL', message: error.message }); console.log(`FAIL ${id}: ${error.message}`); }
};
const login = await request('/api/v1/auth/login', { method: 'POST', body: {
  email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only',
} });
expect(login, 200);
const token = login.json.accessToken;
const run = Date.now().toString(36);
const payload = '<img src=x onerror="window.__qaXss=1"><script>window.__qaXss=1</script>';
evidence.fixtures.payload = payload;

await check('KB-07-spoofed-file-through-real-ingress', async () => {
  for (const name of ['fake.pdf', 'fake.docx', 'binary.txt']) {
    const form = new FormData(); form.append('file', new Blob([new Uint8Array([77, 90, 0, 1])]), name);
    const response = await request('/api/v1/documents', { token, method: 'POST', form });
    expect(response, 400); assert.equal(response.json.code, 'INVALID_FILE_CONTENT');
  }
});
await check('KB-08-2MB-20MB-21MB-real-ingress', async () => {
  for (const size of [2, 20, 21]) {
    // Header-only CSV cannot produce any data rows, so this size-boundary test
    // cannot invoke embeddings even if the worker claims it before soft deletion.
    const form = new FormData(); form.append('file', new Blob(['a'.repeat(size * 1024 * 1024)], { type: 'text/csv' }), `qa-size-${size}-${run}.csv`);
    const response = await request('/api/v1/documents', { token, method: 'POST', form });
    if (size > 20) { expect(response, 413); continue; }
    expect(response, 201); assert.equal(response.json.sizeBytes, size * 1024 * 1024);
    evidence.fixtures[`size${size}Document`] = response.json.id;
    expect(await request(`/api/v1/documents/${response.json.id}`, { token, method: 'DELETE' }), 204);
  }
});
await check('SEC-01-create-literal-conversation-message-fixture', async () => {
  const conversation = await request('/api/v1/conversations', { token, method: 'POST', body: { title: `QA XSS ${run}` } });
  expect(conversation, 201); evidence.fixtures.conversationId = conversation.json.id;
  expect(await request(`/api/v1/conversations/${conversation.json.id}/messages`, { token, method: 'POST', body: { content: payload } }), 201);
});
const created = await request('/api/v1/studio/bots', { token, method: 'POST', body: {
  name: `QA security preview ${run}`, description: 'Disposable non-active Core-disabled fixture',
  locale: 'vi-VN', timezone: 'Asia/Ho_Chi_Minh', template: 'SALES_DEMO',
} });
expect(created, 201);
const config = created.json;
assert.equal(config.bot.blueAiCoreEnabled, false);
assert.equal(config.bot.active, false);
evidence.fixtures.botId = config.bot.id; evidence.fixtures.botName = config.bot.name;
config.dialogNodes.find(node => node.type === 'FALLBACK').name = payload;
config.dialogNodes.find(node => node.type === 'FALLBACK').responseText = payload;
await check('SEC-01-literal-node-and-preview-fixture', async () => {
  const saved = await request(`/api/v1/studio/bots/${config.bot.id}/configuration`, { token, method: 'PUT', body: {
    expectedRevision: config.bot.draftRevision, name: config.bot.name, description: config.bot.description,
    locale: config.bot.locale, timezone: config.bot.timezone, blueAiCoreEnabled: false,
    customInstructions: config.bot.customInstructions, customization: config.customization,
    intents: config.intents, entities: config.entities, dialogNodes: config.dialogNodes,
  } });
  expect(saved, 200); assert.equal(saved.json.bot.active, false);
  const preview = await request(`/api/v1/studio/bots/${config.bot.id}/preview`, { token, method: 'POST', body: { message: 'qa security synthetic unmatched input' } });
  expect(preview, 200); assert.equal(preview.json.message, payload);
});
const published = await request(`/api/v1/studio/bots/${config.bot.id}/publish`, { token, method: 'POST' });
expect(published, 200);
const createSession = () => request(`/api/v1/widget/${published.json.publicKey}/sessions`, { method: 'POST' });
const first = await createSession(); expect(first, 201);
const session = first.json;
evidence.fixtures.widgetSessionId = session.sessionId;
const message = (requestId, text) => request(`/api/v1/runtime/sessions/${session.sessionId}/messages`, {
  method: 'POST', headers: { 'X-Widget-Token': session.accessToken }, body: { requestId, message: text },
});
const completed = await message('safe-before-redis-outage', 'qa fixture before outage'); expect(completed, 200);
await check('API-06-live-Redis-outage-fails-closed-and-recovers', async () => {
  try {
    docker(['stop', `${project}-redis-1`]);
    const newSession = await createSession(); expect(newSession, 503);
    assert.equal(newSession.json.code, 'WIDGET_PROTECTION_UNAVAILABLE');
    const newMessage = await message('not-completed-during-outage', 'qa new request during outage'); expect(newMessage, 503);
    assert.equal(newMessage.json.code, 'WIDGET_PROTECTION_UNAVAILABLE');
    const replay = await message('safe-before-redis-outage', 'qa fixture before outage'); expect(replay, 200);
    assert.deepEqual(replay.json, completed.json);
  } finally {
    docker(['start', `${project}-redis-1`]);
  }
  // Docker briefly removes the service's DNS record while it is stopped.
  // Java caches negative DNS lookups for 10 seconds by default; allow a
  // bounded 30-second recovery budget, not an assumption of instant recovery.
  const recoveryStarted = Date.now();
  const recoveryDeadline = recoveryStarted + 30000;
  let recovered = false;
  while (Date.now() < recoveryDeadline) {
    // Poll the loopback-only QA health endpoint. Repeated session creation
    // would consume Nginx's burst budget and confuse edge 429 with Redis 503.
    const response = await fetch('http://127.0.0.1:23081/actuator/health', {
      signal: AbortSignal.timeout(5000),
    });
    const health = await response.json();
    if (response.status === 200 && health.status === 'UP') { recovered = true; break; }
    assert.equal(response.status, 503);
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert(recovered, 'Shared rate limiter did not recover');
  evidence.redisRecoveryMs = Date.now() - recoveryStarted;
  expect(await createSession(), 201);
  expect(await message('safe-after-redis-outage', 'qa fixture after recovery'), 200);
});
await check('API-06-live-session-window-quota', async () => {
  let blocked = false;
  for (let index = 0; index < 14; index++) {
    const response = await message(`session-quota-${index}`, `qa unique quota fixture ${index}`);
    if (response.status === 429) {
      assert.equal(response.json.code, 'WIDGET_RATE_LIMITED');
      assert(response.headers.get('retry-after')); blocked = true; break;
    }
    expect(response, 200);
  }
  assert(blocked, 'Per-session rate limit was not enforced');
});
evidence.summary = { pass: evidence.tests.filter(test => test.result === 'PASS').length,
  fail: evidence.tests.filter(test => test.result === 'FAIL').length };
// Keep the initial failed recovery drill as evidence, rather than overwrite it.
writeFileSync(new URL('./qa-followup-api-results-final-2026-10-04.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
console.log(JSON.stringify(evidence.summary));
if (evidence.summary.fail) { process.exitCode = 1; }
