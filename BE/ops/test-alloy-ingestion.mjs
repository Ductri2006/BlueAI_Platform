import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// Real Alloy -> real Loki, scoped only to containers this fixture owns.
// Never discover or read the user's real application's logs.
const project = `blueai-alloy-test-${process.pid}-${Date.now()}`;
const network = `${project}-net`, loki = `${project}-loki`, alloy = `${project}-agent`;
const logger = `${project}-logger`, outsider = `${project}-outsider`;
const directory = mkdtempSync(join(tmpdir(), 'blueai-alloy-test-'));
const configPath = join(directory, 'config.alloy');
const image = 'grafana/alloy:v1.20.1@sha256:2aa2099af76c0098d4af7a4d6e48f86cb66dc1a000222ad927a1c67c6542d13f';
writeFileSync(configPath, readFileSync(new URL('./alloy/config.alloy', import.meta.url), 'utf8').replaceAll('blueai-auth', project));
const docker = (...args) => {
  const r = spawnSync('docker', args, { encoding: 'utf8', timeout: 180000 });
  assert.equal(r.status, 0, `Docker fixture failed: ${r.error?.message ?? r.stderr}`);
  return r.stdout.trim();
};
const waitFor = async (operation, label) => {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    try { const value = await operation(); if (value) return value; } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out: ${label}`);
};
try {
  docker('network', 'create', '--label', `blueai.qa.run=${project}`, network);
  docker('run', '-d', '--name', loki, '--label', `blueai.qa.run=${project}`, '--network', network,
    '--network-alias', 'loki', '-p', '127.0.0.1::3100',
    '-v', `${fileURLToPath(new URL('./loki/loki-config.yml', import.meta.url))}:/etc/loki/config.yml:ro`,
    'grafana/loki:3.4.2', '-config.file=/etc/loki/config.yml');
  const port = JSON.parse(docker('inspect', loki))[0].NetworkSettings.Ports['3100/tcp'][0].HostPort;
  const base = `http://127.0.0.1:${port}`;
  await waitFor(async () => (await fetch(base + '/ready')).status === 200, 'Loki ready');
  const records = [
    {marker: project, log: {level: 'INFO', logger: 'qa.nested'}, password: 'QA-DO-NOT-SHIP-PASSWORD with spaces and "QA-SPACE-PASSWORD-LEAK"', authorization: 'Bearer QA-DO-NOT-SHIP-BEARER'},
    {marker: project, 'log.level': 'WARN', 'log.logger': 'qa.flat', api_key: 'QA-DO-NOT-SHIP-KEY', refreshToken: 'QA-DO-NOT-SHIP-REFRESH'},
  ];
  const script = `const lines=${JSON.stringify(records)}; setInterval(()=>lines.forEach(line=>console.log(JSON.stringify({...line,emittedAt:Date.now()}))),1000);`;
  for (const [name, label] of [[logger, project], [outsider, `${project}-other`]]) {
    docker('run', '-d', '--name', name, '--label', `blueai.qa.run=${project}`, '--label', `com.docker.compose.project=${label}`,
      '--label', 'com.docker.compose.service=qa-logger', 'node:22-alpine', 'node', '-e', script);
  }
  docker('run', '-d', '--name', alloy, '--label', `blueai.qa.run=${project}`, '--network', network,
    '-v', `${configPath}:/etc/alloy/config.alloy:ro`, '-v', '/var/run/docker.sock:/var/run/docker.sock:ro',
    image, 'run', '/etc/alloy/config.alloy', '--storage.path=/tmp/qa-alloy', '--server.http.listen-addr=127.0.0.1:12345');
  const query = async expression => {
    const response = await fetch(base + '/loki/api/v1/query_range?' + new URLSearchParams({ query: expression, limit: '100' }));
    assert.equal(response.status, 200);
    return (await response.json()).data.result;
  };
  const streams = await waitFor(async () => {
    const result = await query(`{compose_project="${project}"}`);
    return result.length >= 2 && result.every(stream => stream.stream.level && stream.stream.logger) ? result : null;
  }, 'Both nested and dotted ECS streams');
  const shipped = JSON.stringify(streams);
  for (const secret of ['PASSWORD', 'BEARER', 'KEY', 'REFRESH']) assert(!shipped.includes('QA-DO-NOT-SHIP-' + secret));
  assert(!shipped.includes('QA-SPACE-PASSWORD-LEAK'));
  const passwordRows = streams.flatMap(stream => stream.values.map(([, line]) => JSON.parse(line))).filter(line => Object.hasOwn(line, 'password'));
  assert(passwordRows.length > 0);
  assert(passwordRows.every(line => line.password === '[REDACTED]'), 'Quoted password must be redacted in full, not just up to the first space');
  assert(shipped.includes('[REDACTED]'));
  assert(streams.some(stream => stream.stream.level === 'INFO' && stream.stream.logger === 'qa.nested'));
  assert(streams.some(stream => stream.stream.level === 'WARN' && stream.stream.logger === 'qa.flat'));
  assert.deepEqual(await query(`{compose_project="${project}-other"}`), []);
  docker('restart', alloy);
  const restartedAt = Date.now();
  await waitFor(async () => {
    const result = await query(`{compose_project="${project}"}`);
    // Old rows are not proof of recovery. Require new log records from BOTH
    // formats, emitted only after Docker confirms the collector restarted.
    return ['qa.nested', 'qa.flat'].every(loggerName => result.some(stream =>
      stream.stream.logger === loggerName && stream.values.some(([, line]) =>
        JSON.parse(line).emittedAt > restartedAt)));
  }, 'collector ships fresh nested and dotted logs after restart');
  console.log('Alloy -> Loki PASS: real ingestion, project isolation, nested/dotted ECS labels, credential redaction and restart.');
} finally {
  for (const name of [alloy, logger, outsider, loki]) {
    const inspected = spawnSync('docker', ['inspect', name], { encoding: 'utf8' });
    if (inspected.status !== 0) continue;
    assert.equal(JSON.parse(inspected.stdout)[0].Config.Labels['blueai.qa.run'], project);
    docker('rm', '-f', name);
  }
  spawnSync('docker', ['network', 'rm', network], { stdio: 'ignore' });
  const target = resolve(directory), root = resolve(tmpdir());
  assert(!relative(root, target).startsWith('..') && target !== root);
  rmSync(target, { recursive: true, force: true });
  console.log('Disposable log fixture containers/network/files removed; real logs untouched.');
}
