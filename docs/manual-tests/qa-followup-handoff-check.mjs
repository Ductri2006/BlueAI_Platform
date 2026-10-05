import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const project = 'blueai-preprod-20261003';
const report = { generatedAt: new Date().toISOString(), scope: 'QA-only readiness and durable fixture checks after containers were restarted', services: [] };
for (const service of ['auth-api', 'rag-ai', 'frontend', 'mysql', 'redis']) {
  const inspected = spawnSync('docker', ['inspect', `${project}-${service}-1`], { encoding: 'utf8' });
  assert.equal(inspected.status, 0);
  const container = JSON.parse(inspected.stdout)[0];
  assert.equal(container.Config.Labels['com.docker.compose.project'], project);
  assert.equal(container.State.Health.Status, 'healthy');
  report.services.push({ name: service, image: container.Image, health: 'healthy' });
}
for (const url of ['http://127.0.0.1:23000/', 'http://127.0.0.1:23081/actuator/health', 'http://127.0.0.1:23800/ready']) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200, `QA readiness failed for ${url}`);
}
const base = 'http://127.0.0.1:23081';
const login = await fetch(`${base}/api/v1/auth/login`, { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
    email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only',
  }), signal: AbortSignal.timeout(10000) });
assert.equal(login.status, 200);
const { accessToken } = await login.json();
const get = async path => {
  const response = await fetch(base + path, { headers: { Authorization: `Bearer ${accessToken}` }, signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200);
  return response.json();
};
const documents = await get('/api/v1/documents');
const source = documents.find(item => item.id === 'b35c58bf-d447-47cb-90b5-583d82bb489a');
assert.equal(source?.status, 'READY'); assert.equal(source.producedChunkCount, 1);
const fixtures = JSON.parse(readFileSync(new URL('./qa-followup-api-results-final-2026-10-04.json', import.meta.url), 'utf8')).fixtures;
const conversation = await get(`/api/v1/conversations/${fixtures.conversationId}`);
assert.equal(conversation.id, fixtures.conversationId);
const bot = await get(`/api/v1/studio/bots/${fixtures.botId}`);
assert.equal(bot.bot.active, false); assert.equal(bot.bot.blueAiCoreEnabled, false);
report.document = { id: source.id, status: source.status, chunks: source.producedChunkCount };
report.durableConversation = conversation.id;
report.previewBot = { id: bot.bot.id, active: false, blueAiCoreEnabled: false };
report.result = 'PASS';
// No tokens, container environments, document bodies or provider keys retained.
writeFileSync(new URL('./qa-handoff-results-followup-2026-10-04.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log('QA handoff PASS: 5 healthy services, 3 HTTP readiness checks, durable READY document/conversation, non-active preview bot.');
