import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const project = 'blueai-preprod-20261003';
const inspected = spawnSync('docker', ['inspect', `${project}-auth-api-1`], { encoding: 'utf8' });
assert.equal(inspected.status, 0);
const container = JSON.parse(inspected.stdout)[0];
assert.equal(container.Config.Labels['com.docker.compose.project'], project);
assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const fixtures = JSON.parse(readFileSync(new URL('./qa-remediation-api-results-2026-10-04.json', import.meta.url))).fixtures;
const path = new URL('./qa-context-retest-results-2026-10-04.json', import.meta.url);
const call = async (route, token, method = 'GET', body) => {
  const response = await fetch('http://127.0.0.1:23081' + route, { method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30_000) });
  const text = await response.text();
  assert(response.ok, `QA request failed: HTTP ${response.status}`);
  return text ? JSON.parse(text) : null;
};
const auth = await call('/api/v1/auth/login', null, 'POST', { email: fixtures.uiExpiryUser.email, password: 'QaSynthetic2026!NoRealCredential' });
if (process.argv[2] === 'verify') {
  const evidence = JSON.parse(readFileSync(path));
  const messages = await call(`/api/v1/conversations/${evidence.targetId}/messages`, auth.accessToken);
  const answer = messages.filter(message => message.role === 'ASSISTANT').at(-1);
  assert(answer, 'UI must complete a real provider answer first');
  assert(answer.content.includes('37'));
  assert(/2[.,]9|2[.,]900[.,]000/.test(answer.content));
  assert(answer.content.includes('TV-4712'));
  assert(answer.evidence.contextSources.some(source => source.type === 'RELATED_HISTORY' && source.conversationId === fixtures.automaticSource));
  assert(!answer.evidence.contextSources.some(source => source.conversationId === fixtures.unrelatedSource));
  evidence.after = { result: 'PASS', answer: answer.content, contextSources: answer.evidence.contextSources };
  evidence.finishedAt = new Date().toISOString();
  writeFileSync(path, JSON.stringify(evidence, null, 2) + '\n');
  console.log('Automatic recall real UI PASS: 37 sales / 2.9 million / TV-4712; matching owned source present, unrelated history excluded.');
} else {
  const old = await call(`/api/v1/conversations/${fixtures.automaticTarget}/messages`, auth.accessToken);
  const answer = old.filter(message => message.role === 'ASSISTANT').at(-1);
  assert(answer, 'Preserve the failed real UI evidence before clearing disposable history');
  const evidence = { scope: 'QA UI regression; not a production quality guarantee',
    before: { result: 'FAIL', answer: answer.content, contextSources: answer.evidence.contextSources }, expected: { sales: 37, budget: 2900000, opportunity: 'TV-4712' } };
  // Soft-delete only the named failed QA conversation, after saving evidence.
  // Otherwise its hallucinated answer could contaminate the retest's history.
  await call(`/api/v1/conversations/${fixtures.automaticTarget}`, auth.accessToken, 'DELETE');
  const fresh = await call('/api/v1/conversations', auth.accessToken, 'POST', { title: 'QA automatic recall AFTER FIX 20261004' });
  await call(`/api/v1/conversations/${fresh.id}/messages`, auth.accessToken, 'POST', { content: 'Phiên mới độc lập để kiểm tra lại tự tìm lịch sử liên quan.' });
  evidence.targetId = fresh.id;
  evidence.after = { result: 'NOT RUN' };
  writeFileSync(path, JSON.stringify(evidence, null, 2) + '\n');
  console.log('Fresh UI retest prepared; original failure evidence preserved.');
}
