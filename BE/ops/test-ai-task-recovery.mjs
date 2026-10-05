import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

// Kill a REAL backend process after four durable claims enter the HTTP provider.
// Everything (DB, owner, credential and provider) is disposable. No real keys.
const artifactVolume = process.env.BLUEAI_TEST_ARTIFACT_VOLUME;
assert(artifactVolume && /^blueai-[a-z0-9-]+$/.test(artifactVolume),
  'Set BLUEAI_TEST_ARTIFACT_VOLUME to a disposable Maven target volume containing the verified JAR.');
const prefix = `blueai-crash-test-${process.pid}-${Date.now()}`;
const network = `${prefix}-net`;
const database = `${prefix}-db`;
const provider = `${prefix}-provider`;
const backend = `${prefix}-api`;
const docker = (...args) => {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout: 180_000 });
  if (result.error || result.status !== 0) {
    throw new Error(`Disposable Docker fixture failed (${args[0]}): ${result.error?.message ?? result.stderr}`);
  }
  return result.stdout.trim();
};
const cleanup = (...args) => spawnSync('docker', args, { stdio: 'ignore', timeout: 30_000 });
const sql = query => docker('exec', database, 'mysql', '-uroot', '-pci-disposable-root-password',
  '-N', '-B', 'blueai_crash_test', '-e', query);
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const waitFor = async (probe, label, timeout = 90_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { if (await probe()) return; } catch { /* fixture is starting */ }
    await pause(250);
  }
  throw new Error(`Timed out: ${label}`);
};
const port = (name, containerPort) => JSON.parse(docker('inspect', name))[0]
  .NetworkSettings.Ports[`${containerPort}/tcp`][0].HostPort;
const providerScript = `
  const http = require('node:http');
  let calls = 0, released = false;
  const pending = [];
  const complete = res => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({choices:[{message:{content:JSON.stringify({label:'sales',abstain:false,reason:'fixture'})}}]}));
  };
  http.createServer((req,res) => {
    if (req.url === '/state') { res.end(JSON.stringify({calls})); return; }
    if (req.url === '/release') { released = true; pending.splice(0).forEach(complete); res.end('{}'); return; }
    if (req.method !== 'POST' || req.url !== '/v1/chat/completions') { res.writeHead(404); res.end(); return; }
    req.resume();
    req.on('end', () => { calls++; if (released) complete(res); else pending.push(res); });
  }).listen(8080, '0.0.0.0');
`;

let interruptedRequests = [];
try {
  docker('network', 'create', network);
  docker('run', '-d', '--name', database, '--network', network, '--network-alias', 'fixture-db',
    '--tmpfs', '/var/lib/mysql', '-e', 'MYSQL_DATABASE=blueai_crash_test',
    '-e', 'MYSQL_ROOT_PASSWORD=ci-disposable-root-password', 'mysql:8.4');
  docker('run', '-d', '--name', provider, '--network', network, '--network-alias', 'fixture-provider',
    '-p', '127.0.0.1::8080', 'node:22-alpine', 'node', '-e', providerScript);
  await waitFor(() => sql('SELECT 1') === '1', 'disposable MySQL');
  docker('run', '-d', '--name', backend, '--network', network, '-p', '127.0.0.1::8080',
    '--user', '10001', '-v', `${artifactVolume}:/artifacts:ro`,
    '-e', 'SPRING_DATASOURCE_URL=jdbc:mysql://fixture-db:3306/blueai_crash_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
    '-e', 'SPRING_DATASOURCE_USERNAME=root', '-e', 'SPRING_DATASOURCE_PASSWORD=ci-disposable-root-password',
    '-e', 'APP_BOOTSTRAP_ADMIN_EMAIL=crash-admin@example.test', '-e', 'APP_BOOTSTRAP_ADMIN_PASSWORD=FixtureAdmin123!',
    '-e', 'APP_DOCUMENTS_STORAGE_DIR=/tmp/fixture-documents',
    '-e', 'APP_LLM_BASE_URL=http://fixture-provider:8080', '-e', 'APP_LLM_API_KEY=not-a-real-provider-key',
    '-e', 'APP_AI_TASKS_DEADLINE=10s',
    '--entrypoint', 'java', 'maven:3.9.16-eclipse-temurin-21-alpine',
    '-Xmx384m', '-jar', '/artifacts/blueai-auth-api-0.1.0-SNAPSHOT.jar');
  let baseUrl = `http://127.0.0.1:${port(backend, 8080)}`;
  const providerUrl = `http://127.0.0.1:${port(provider, 8080)}`;
  const request = (path, options = {}) => fetch(`${baseUrl}${path}`, {
    ...options, signal: AbortSignal.timeout(45_000),
  });
  const ready = () => request('/actuator/health').then(response => response.status === 200);
  await waitFor(ready, 'backend startup with Flyway V7');
  const login = await request('/api/v1/auth/login', { method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({email:'crash-admin@example.test',password:'FixtureAdmin123!'}),
  });
  assert.equal(login.status, 200, 'Fixture admin login');
  const owner = await login.json();
  const provision = await request('/api/v1/admin/integration-clients', { method: 'POST',
    headers: { 'content-type': 'application/json', authorization:`Bearer ${owner.accessToken}` },
    body: JSON.stringify({name:'Crash fixture',capabilities:['CLASSIFY'],documentIds:[]}),
  });
  assert.equal(provision.status, 201, 'Fixture integration provisioning');
  const client = await provision.json();
  const body = JSON.stringify({capability:'CLASSIFY',input:{text:'hello'},configuration:{allowedLabels:['sales']}});
  const submit = key => request('/api/v1/ai/tasks', { method: 'POST', body,
    headers: { 'content-type': 'application/json', authorization:`Bearer ${client.credential}`, 'Idempotency-Key':key },
  });
  interruptedRequests = Array.from({length:4}, (_, i) => submit(`crash:${i}`)
    .then(response => ({status:response.status}), () => ({interrupted:true})));
  await waitFor(async () => (await (await fetch(`${providerUrl}/state`)).json()).calls === 4, 'four active provider requests', 8000);
  assert.equal(sql("SELECT COUNT(*) FROM ai_task_ledger WHERE status='PROCESSING'"), '4');
  const tasks = sql('SELECT id,idempotency_key FROM ai_task_ledger ORDER BY idempotency_key')
    .split('\n').map(line => line.trim().split('\t'));
  docker('kill', backend);
  const interrupted = await Promise.all(interruptedRequests);
  assert(interrupted.every(value => value.interrupted), 'All four requests must be interrupted by process termination');
  assert.equal(sql("SELECT COUNT(*) FROM ai_task_ledger WHERE status='PROCESSING'"), '4', 'Claims survive the process crash');
  console.log('AI recovery fixture: backend killed with four durable PROCESSING tasks; restarting.');
  docker('start', backend);
  // Docker can allocate a different ephemeral host port when starting again.
  baseUrl = `http://127.0.0.1:${port(backend, 8080)}`;
  await waitFor(ready, 'backend restart');
  // Do not poll task endpoints yet: prove the scheduled sweep alone recovers them.
  await waitFor(() => sql("SELECT COUNT(*) FROM ai_task_ledger WHERE status='PROCESSING'") === '0', 'background expiration after restart');
  assert.equal(sql("SELECT COUNT(*) FROM ai_task_ledger WHERE status='FAILED' AND error_code='AI_TASK_EXPIRED'"), '4');
  for (const [id, key] of tasks) {
    const poll = await request(`/api/v1/ai/tasks/${id}`, {headers:{authorization:`Bearer ${client.credential}`}});
    assert.equal(poll.status, 200);
    const saved = await poll.json();
    const replay = await submit(key);
    assert.equal(replay.status, 200);
    assert.deepEqual(await replay.json(), saved);
    assert.equal(saved.error.code, 'AI_TASK_EXPIRED');
  }
  assert.equal((await (await fetch(`${providerUrl}/state`)).json()).calls, 4, 'Recovery and replay must not call the provider again');
  await fetch(`${providerUrl}/release`, {method:'POST'});
  const fresh = await submit('fresh:after-restart');
  assert.equal(fresh.status, 200, 'Recovered concurrency slots accept new work');
  const result = await fresh.json();
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.result.label, 'sales');
  assert.equal((await (await fetch(`${providerUrl}/state`)).json()).calls, 5);
  console.log('AI process crash/restart PASS: four claims expired, scheduler recovered, exact replay, no duplicate provider calls, fresh task completed.');
} finally {
  cleanup('rm', '--force', backend, provider, database);
  cleanup('network', 'rm', network);
  await Promise.allSettled(interruptedRequests);
}
