import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';

// Uses the real production proxy config with a disposable, counting backend.
// No application accounts, real documents or provider keys are needed.
const prefix = `blueai-ingress-test-${process.pid}-${Date.now()}`;
const network = `${prefix}-net`;
const backend = `${prefix}-api`;
const frontend = `${prefix}-fe`;
const oldAddressHolder = `${prefix}-old-ip`;
// Optional sibling-container runner for testing genuine Linux filesystem modes
// under Docker Desktop. Normal CI/host runs continue to use localhost.
const containerRunner = process.env.BLUEAI_INGRESS_TEST_RUNNER;
const configPath = fileURLToPath(new URL('../../FE/nginx.conf', import.meta.url));
const headersPath = fileURLToPath(new URL('../../FE/security-headers.conf', import.meta.url));
const webRoot = mkdtempSync(join(tmpdir(), 'blueai-ingress-test-'));
writeFileSync(join(webRoot, 'index.html'), '<!doctype html><title>Ingress test</title>blueai-ingress-fixture');
// mkdtemp creates mode 0700 on Linux; Nginx's unprivileged worker must
// traverse this disposable fixture directory and read its static page.
chmodSync(webRoot, 0o755);
chmodSync(join(webRoot, 'index.html'), 0o644);
const deadline = 15_000;

const docker = (...args) => {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout: 180_000 });
  if (result.error || result.status !== 0) {
    throw new Error(`docker ${args.slice(0, 3).join(' ')} failed: ${result.error?.message ?? result.stderr}`);
  }
  return result.stdout.trim();
};
const ignoreCleanup = (...args) => spawnSync('docker', args, { stdio: 'ignore', timeout: 30_000 });
const backendScript = `
  const http = require('node:http');
  let proxiedRequests = 0;
  http.createServer((req, res) => {
    if (req.url === '/api/test-count') {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({proxiedRequests})); return;
    }
    proxiedRequests++;
    let bytes = 0;
    req.on('data', chunk => { bytes += chunk.length; });
    req.on('end', () => {
      res.setHeader('content-type', 'application/json');
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
      res.end(JSON.stringify({path:req.url, bytes, forwardedFor:req.headers['x-forwarded-for']}));
    });
  }).listen(8080, '0.0.0.0');
`;

try {
  docker('info', '--format', '{{.ServerVersion}}');
  docker('network', 'create', network);
  // Older engines require an explicitly configured subnet for --ip. Let Docker
  // choose a free subnet first, then recreate only this still-empty test network.
  const subnet = JSON.parse(docker('network', 'inspect', network))[0].IPAM.Config[0].Subnet;
  assert.match(subnet, /^\d+\.\d+\.\d+\.\d+\/\d+$/, 'Disposable network needs an IPv4 subnet');
  docker('network', 'rm', network);
  docker('network', 'create', '--subnet', subnet, network);
  docker('run', '--detach', '--name', backend, '--network', network, '--network-alias', 'auth-api',
    'node:22-alpine', 'node', '-e', backendScript);
  docker('run', '--detach', '--name', frontend, '--network', network, '--publish', '127.0.0.1::80',
    '--volume', `${configPath}:/etc/nginx/conf.d/default.conf:ro`,
    '--volume', `${headersPath}:/etc/nginx/snippets/security-headers.conf:ro`,
    '--volume', `${webRoot}:/usr/share/nginx/html:ro`, 'nginx:1.30-alpine@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94');
  const binding = JSON.parse(docker('inspect', frontend))[0].NetworkSettings.Ports['80/tcp'][0];
  if (containerRunner) docker('network', 'connect', network, containerRunner);
  const baseUrl = containerRunner ? `http://${frontend}` : `http://127.0.0.1:${binding.HostPort}`;
  const request = (path, options = {}) => fetch(`${baseUrl}${path}`, {
    ...options, signal: AbortSignal.timeout(deadline),
  });
  const assertSecurityHeaders = response => {
    assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(response.headers.get('x-frame-options'), 'DENY');
    assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
  };

  let ready = false;
  const readinessDeadline = Date.now() + deadline;
  while (Date.now() < readinessDeadline) {
    try {
      const probe = await request('/api/test-count');
      if (probe.status === 200) { ready = true; break; }
    } catch { /* isolated backend/proxy are still starting */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert(ready, 'Disposable proxy/backend did not become ready');

  const index = await request('/index.html');
  assert.equal(index.status, 200);
  assertSecurityHeaders(index);
  assert.match(await index.text(), /blueai-ingress-fixture/);
  docker('exec', frontend, 'wget', '-q', '-T', '3', '-O', '/dev/null', 'http://127.0.0.1/index.html');

  const countBefore = (await (await request('/api/test-count')).json()).proxiedRequests;
  for (const path of ['/api/v1/internal', '/api/v1/internal/documents/claim?limit=0',
    '/actuator', '/actuator/health', '/actuator/prometheus']) {
    for (const method of ['GET', 'POST', 'OPTIONS']) {
      const response = await request(path, { method, headers: { origin: 'https://widget.example.test' } });
      assert.equal(response.status, 404, `${method} ${path} must be blocked at the edge`);
      assertSecurityHeaders(response);
      assert.equal(response.headers.has('access-control-allow-origin'), false);
      await response.text();
    }
  }
  const countAfter = (await (await request('/api/test-count')).json()).proxiedRequests;
  assert.equal(countAfter, countBefore, 'A private/management request reached the backend');

  for (const bytes of [2 * 1024 * 1024, 20 * 1024 * 1024]) {
    const multipart = new FormData();
    multipart.append('file', new Blob([Buffer.alloc(bytes, 97)], { type: 'text/plain' }), 'test.txt');
    const response = await request('/api/v1/documents', { method: 'POST', body: multipart });
    assert.equal(response.status, 200, `Upload ${bytes} bytes must reach the backend`);
    const body = await response.json();
    assert(body.bytes > bytes && body.bytes < 21 * 1024 * 1024, 'Multipart overhead limit mismatch');
  }
  const oversized = new FormData();
  oversized.append('file', new Blob([Buffer.alloc(21 * 1024 * 1024, 97)]), 'too-large.txt');
  const beforeRejectedUpload = (await (await request('/api/test-count')).json()).proxiedRequests;
  const rejected = await request('/api/v1/documents', { method: 'POST', body: oversized });
  assert.equal(rejected.status, 413, 'Oversized multipart must be rejected by Nginx');
  assertSecurityHeaders(rejected);
  await rejected.text();
  const afterRejectedUpload = (await (await request('/api/test-count')).json()).proxiedRequests;
  assert.equal(afterRejectedUpload, beforeRejectedUpload, 'Oversized upload reached the backend');
  const otherRoute = await request('/api/v1/rag/ask', {
    method: 'POST', body: Buffer.alloc(2 * 1024 * 1024, 97),
  });
  assert.equal(otherRoute.status, 413, 'Other API routes must retain the smaller body limit');
  await otherRoute.text();

  for (const path of ['/api/test-headers', '/api/v1/widget/bot-test/sessions',
    '/api/v1/runtime/sessions/session-test/messages']) {
    const baseline = (await (await request(path, { method: 'POST', body: '{}' })).json()).forwardedFor;
    assert(isIP(baseline), `${path}: baseline forwarded address must be one valid IP`);
    const forged = await request(path, {
      method: 'POST', body: '{}', headers: { 'x-forwarded-for': '203.0.113.123, 198.51.100.7' },
    });
    assert.equal((await forged.json()).forwardedFor, baseline, `${path}: forwarded header was appended instead of replaced`);
  }

  for (let i = 0; i < 10; i++) {
    const response = await request('/api/v1/auth/login', { method: 'OPTIONS' });
    assert.equal(response.status, 204, 'Preflight consumed the login quota');
  }
  const loginCountBefore = (await (await request('/api/test-count')).json()).proxiedRequests;
  let limited = 0;
  let accepted = 0;
  for (let i = 0; i < 12; i++) {
    const response = await request('/api/v1/auth/login', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}',
    });
    if (response.status === 429) {
      assertSecurityHeaders(response);
      limited++;
      assert.equal(response.headers.get('retry-after'), '60');
      assert.match(response.headers.get('content-type'), /application\/json/);
      assert.equal((await response.json()).code, 'RATE_LIMIT_EXCEEDED');
    } else {
      assert.equal(response.status, 200);
      accepted++;
      await response.json();
    }
  }
  assert(limited > 0 && accepted > 0, 'Login burst did not exercise both accepted and rate-limited requests');
  const loginCountAfter = (await (await request('/api/test-count')).json()).proxiedRequests;
  assert.equal(loginCountAfter - loginCountBefore, accepted, 'Rate-limited login reached the backend');
  const preflightAfter = await request('/api/v1/auth/login', { method: 'OPTIONS' });
  assert.equal(preflightAfter.status, 204, 'Preflight was blocked after login quota exhaustion');
  const browserLogin = await request('/api/v1/auth/browser/login', { method: 'POST', body: '{}' });
  assert.equal(browserLogin.status, 429, 'Browser login must share the existing login quota');
  assertSecurityHeaders(browserLogin);
  await browserLogin.text();

  for (const path of ['/api/v1/auth/refresh', '/api/v1/auth/logout']) {
    for (let i = 0; i < 12; i++) {
      const response = await request(path, { method: 'OPTIONS' });
      assert.equal(response.status, 204, `${path} preflight consumed session quota`);
    }
    const before = (await (await request('/api/test-count')).json()).proxiedRequests;
    let sessionAccepted = 0, sessionLimited = 0;
    for (let i = 0; i < 24; i++) {
      const response = await request(path, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"refreshToken":"invalid"}',
      });
      if (response.status === 429) {
        assertSecurityHeaders(response);
        sessionLimited++;
        assert.equal(response.headers.get('retry-after'), '60');
        assert.match(response.headers.get('content-type'), /application\/json/);
        assert.equal((await response.json()).code, 'RATE_LIMIT_EXCEEDED');
      } else {
        assert.equal(response.status, 200);
        sessionAccepted++;
        await response.json();
      }
    }
    assert(sessionLimited > 0 && sessionAccepted > 0, `${path} burst did not exercise its separate quota`);
    const after = (await (await request('/api/test-count')).json()).proxiedRequests;
    assert.equal(after - before, sessionAccepted, `${path} rate-limited requests reached backend`);
    const preflight = await request(path, { method: 'OPTIONS' });
    assert.equal(preflight.status, 204, `${path} preflight blocked after quota exhaustion`);
    const browserPath = path.replace('/auth/', '/auth/browser/');
    const browserLimited = await request(browserPath, { method: 'POST', body: '{}' });
    assert.equal(browserLimited.status, 429, `${browserPath} must share the session quota`);
    assertSecurityHeaders(browserLimited);
    await browserLimited.text();
  }

  const oldIp = JSON.parse(docker('inspect', backend))[0].NetworkSettings.Networks[network].IPAddress;
  docker('rm', '--force', backend);
  // Hold the old IP so Docker cannot accidentally mask stale-DNS bugs by reusing it.
  docker('run', '--detach', '--name', oldAddressHolder, '--network', network, '--ip', oldIp,
    'node:22-alpine', 'node', '-e', 'setInterval(() => {}, 1000)');
  docker('run', '--detach', '--name', backend, '--network', network, '--network-alias', 'auth-api',
    'node:22-alpine', 'node', '-e', backendScript);
  const newIp = JSON.parse(docker('inspect', backend))[0].NetworkSettings.Networks[network].IPAddress;
  assert.notEqual(newIp, oldIp, 'DNS regression requires a genuinely different backend IP');
  let recovered = false;
  const replacementDeadline = Date.now() + 30_000;
  while (Date.now() < replacementDeadline) {
    try {
      const response = await request('/api/test-count');
      if (response.status === 200 && Number.isInteger((await response.json()).proxiedRequests)) {
        recovered = true; break;
      }
    } catch { /* DNS refresh and the new backend are still starting */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert(recovered, 'Ingress did not recover after backend IP changed without restarting Nginx');
  console.log('Nginx ingress PASS: private boundaries, uploads, exact spoof protection, session quotas/JSON/preflight and backend DNS replacement.');
} finally {
  ignoreCleanup('rm', '--force', frontend, backend, oldAddressHolder);
  if (containerRunner) ignoreCleanup('network', 'disconnect', network, containerRunner);
  ignoreCleanup('network', 'rm', network);
  const cleanupPath = resolve(webRoot);
  const relativePath = relative(resolve(tmpdir()), cleanupPath);
  assert(relativePath.startsWith('blueai-ingress-test-') && !relativePath.includes('..'), 'Unsafe fixture cleanup path');
  rmSync(cleanupPath, { recursive: true, force: true });
}
