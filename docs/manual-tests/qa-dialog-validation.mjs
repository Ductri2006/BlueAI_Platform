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
  return { status: response.status, body: await response.json() };
};
const login = await call('/api/v1/auth/login', null, 'POST', { email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only' });
assert.equal(login.status, 200);
const token = login.body.accessToken;
const route = `/api/v1/studio/bots/${fixtures.bot}`;
const fetched = await call(route, token);
assert.equal(fetched.status, 200);
const baseline = fetched.body;
assert(baseline.bot.name.startsWith('QA Sales demo '));
const body = () => ({ expectedRevision: baseline.bot.draftRevision,
  name: baseline.bot.name, description: baseline.bot.description, locale: baseline.bot.locale, timezone: baseline.bot.timezone,
  blueAiCoreEnabled: baseline.bot.blueAiCoreEnabled, customInstructions: baseline.bot.customInstructions,
  customization: structuredClone(baseline.customization), intents: structuredClone(baseline.intents),
  entities: structuredClone(baseline.entities), dialogNodes: structuredClone(baseline.dialogNodes) });
const mutations = [
  ['unknown-parent', value => { value.dialogNodes.find(node => node.type === 'ROUTE').parentNode = 'qa-missing-parent'; }],
  ['parent-self-cycle', value => { const node = value.dialogNodes.find(node => node.type === 'ROUTE'); node.parentNode = node.name; }],
  ['two-node-jump-cycle', value => { const [a, b] = value.dialogNodes.filter(node => node.type === 'ROUTE');
    a.nextStep = 'JUMP_TO'; a.jumpToNode = b.name; b.nextStep = 'JUMP_TO'; b.jumpToNode = a.name; }],
  ['unknown-required-slot', value => { value.dialogNodes.find(node => node.requiredEntityName).requiredEntityName = 'qa-missing-slot'; }],
  ['unknown-match-entity', value => { value.dialogNodes.find(node => node.type === 'ROUTE').matchEntityName = 'qa-missing-entity'; }],
  ['unknown-match-intent', value => { value.dialogNodes.find(node => node.type === 'ROUTE').matchIntent = 'qa-missing-intent'; }],
  ['unknown-jump-target', value => { const node = value.dialogNodes.find(node => node.type === 'ROUTE');
    node.nextStep = 'JUMP_TO'; node.jumpToNode = 'qa-missing-target'; }],
];
const result = { scope: 'Owned QA draft only; invalid saves must be atomic, no provider calls', tests: [] };
for (const [id, mutate] of mutations) {
  try {
    const value = body(); mutate(value);
    const saved = await call(route + '/configuration', token, 'PUT', value);
    assert.equal(saved.status, 400);
    const reloaded = await call(route, token);
    assert.equal(reloaded.status, 200);
    assert.deepEqual(reloaded.body, baseline, 'Invalid save must not modify ANY persisted configuration or revision');
    result.tests.push({ id, result: 'PASS', status: saved.status });
    console.log('PASS ' + id + ': HTTP 400, entire persisted configuration unchanged');
  } catch (error) {
    result.tests.push({ id, result: 'FAIL', actual: error.message });
    console.log('FAIL ' + id + ': ' + error.message);
  }
}
result.finishedAt = new Date().toISOString();
writeFileSync(new URL('./qa-dialog-validation-results-2026-10-04.json', import.meta.url), JSON.stringify(result, null, 2) + '\n');
if (result.tests.some(test => test.result !== 'PASS')) process.exitCode = 1;
