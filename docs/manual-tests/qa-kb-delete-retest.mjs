import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

// Exact disposable fixture only. Never parameterize this to a live environment.
const project = 'blueai-preprod-20261003';
const documentId = '61b78d0d-ae15-4743-a0e9-a91d6aed2d7b';
const inspected = spawnSync('docker', ['inspect', `${project}-auth-api-1`], { encoding: 'utf8' });
assert.equal(inspected.status, 0);
const container = JSON.parse(inspected.stdout)[0];
assert.equal(container.Config.Labels['com.docker.compose.project'], project);
assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const output = new URL('./qa-kb-delete-results-2026-10-04.json', import.meta.url);
const call = async (path, token, method = 'GET', body) => {
  const response = await fetch('http://127.0.0.1:23081' + path, {
    method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30_000),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
};
const login = await call('/api/v1/auth/login', null, 'POST', {
  email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only',
});
assert.equal(login.status, 200);
const token = login.body.accessToken;
const answerFor = async marker => {
  const listed = await call('/api/v1/conversations?q=' + encodeURIComponent(marker), token);
  assert.equal(listed.status, 200);
  const conversations = listed.body.filter(value => value.title.startsWith(marker));
  assert.equal(conversations.length, 1, 'Complete the uniquely named real UI chat first');
  const messages = await call(`/api/v1/conversations/${conversations[0].id}/messages`, token);
  assert.equal(messages.status, 200);
  const answer = messages.body.filter(value => value.role === 'ASSISTANT').at(-1);
  assert(answer, 'Wait for the real UI provider response');
  return { conversationId: conversations[0].id, answer: answer.content, sources: answer.evidence.sources,
    contextSources: answer.evidence.contextSources };
};

if (process.argv[2] === 'verify') {
  const evidence = JSON.parse(readFileSync(output));
  const after = await answerFor('QA-KB-DELETED-20261004');
  assert.deepEqual(after.sources, [], 'A deleted document must not be cited in a fresh conversation');
  assert(!after.contextSources.some(source => source.type === 'RELATED_HISTORY' || source.type === 'LINKED_CONVERSATION'));
  assert(!/1[.,]250[.,]000/.test(after.answer), 'No authoritative price may be invented from a deleted source');
  evidence.after = { result: 'PASS', ...after };
  evidence.finishedAt = new Date().toISOString();
  writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n');
  console.log('Deleted KB real UI PASS: fresh conversation has no document citation or cross-session policy recall.');
} else {
  const document = await call(`/api/v1/documents/${documentId}`, token);
  assert.equal(document.status, 200);
  assert.equal(document.body.status, 'READY');
  assert.equal(document.body.originalFilename, 'qa-sales-policy.md');
  assert.equal(document.body.producedChunkCount, 1);
  const before = await answerFor('QA-KB-RESTART-20261004');
  assert(/1[.,]250[.,]000/.test(before.answer));
  assert(before.answer.includes('25'));
  assert(before.sources.some(source => source.documentId === documentId));
  // Save successful citation evidence BEFORE soft-deleting this exact QA row.
  const evidence = { scope: 'Owned QA only; real UI before/after tombstone', documentId,
    startedAt: new Date().toISOString(), before: { result: 'PASS', ...before }, after: { result: 'NOT RUN' } };
  writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n');
  assert.equal((await call(`/api/v1/documents/${documentId}`, token, 'DELETE')).status, 204);
  assert.equal((await call(`/api/v1/documents/${documentId}`, token)).status, 404);
  const listed = await call('/api/v1/documents', token);
  assert.equal(listed.status, 200);
  assert(!listed.body.some(value => value.id === documentId));
  evidence.tombstone = 'DELETE 204, GET 404, absent from owned document list';
  writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n');
  console.log('Only exact disposable READY fixture soft-deleted; before-citation evidence preserved. Start a NEW UI conversation for the negative check.');
}
