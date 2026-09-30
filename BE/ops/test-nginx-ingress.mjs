import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Uses the real production proxy config with a disposable, counting backend.
// No application accounts, real documents or provider keys are needed.
const prefix = `blueai-ingress-test-${process.pid}-${Date.now()}`;
const network = `${prefix}-net`;
const backend = `${prefix}-api`;
const frontend = `${prefix}-fe`;
const configPath = fileURLToPath(new URL('../../FE/nginx.conf', import.meta.url));
const webRoot = mkdtempSync(join(tmpdir(), 'blueai-ingress-test-'));
writeFileSync(join(webRoot, 'index.html'), '<!doctype html><title>Ingress test</title>blueai-ingress-fixture');
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
  docker('run', '--detach', '--name', backend, '--network', network, '--network-alias', 'auth-api',
    'node:22-alpine', 'node', '-e', backendScript);
  docker('run', '--detach', '--name', frontend, '--network', network, '--publish', '127.0.0.1::80',
    '--volume', `${configPath}:/etc/nginx/conf.d/default.conf:ro`,
    '--volume', `${webRoot}:/usr/share/nginx/html:ro`, 'nginx:1.27-alpine');
  const binding = JSON.parse(docker('inspect', frontend))[0].NetworkSettings.Ports['80/tcp'][0];
  const baseUrl = `http://127.0.0.1:${binding.HostPort}`;
  const request = (path, options = {}) => fetch(`${baseUrl}${path}`, {
    ...options, signal: AbortSignal.timeout(deadline),
  });

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
  assert.match(await index.text(), /blueai-ingress-fixture/);
  docker('exec', frontend, 'wget', '-q', '-T', '3', '-O', '/dev/null', 'http://127.0.0.1/index.html');

  const countBefore = (await (await request('/api/test-count')).json()).proxiedRequests;
  for (const path of ['/api/v1/internal', '/api/v1/internal/documents/claim?limit=0',
    '/actuator', '/actuator/health', '/actuator/prometheus']) {
    for (const method of ['GET', 'POST', 'OPTIONS']) {
      const response = await request(path, { method, headers: { origin: 'https://widget.example.test' } });
      assert.equal(response.status, 404, `${method} ${path} must be blocked at the edge`);
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
  await rejected.text();
  const afterRejectedUpload = (await (await request('/api/test-count')).json()).proxiedRequests;
  assert.equal(afterRejectedUpload, beforeRejectedUpload, 'Oversized upload reached the backend');
  const otherRoute = await request('/api/v1/rag/ask', {
    method: 'POST', body: Buffer.alloc(2 * 1024 * 1024, 97),
  });
  assert.equal(otherRoute.status, 413, 'Other API routes must retain the smaller body limit');
  await otherRoute.text();

  const forwarded = await request('/api/test-headers', { headers: { 'x-forwarded-for': '203.0.113.123' } });
  assert.notEqual((await forwarded.json()).forwardedFor, '203.0.113.123', 'Untrusted forwarded IP survived');

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

  console.log('Nginx ingress PASS: private boundaries, upload sizes, spoofed IP, login quota/JSON/retry and preflight.');
} finally {
  ignoreCleanup('rm', '--force', frontend, backend);
  ignoreCleanup('network', 'rm', network);
  const cleanupPath = resolve(webRoot);
  const relativePath = relative(resolve(tmpdir()), cleanupPath);
  assert(relativePath.startsWith('blueai-ingress-test-') && !relativePath.includes('..'), 'Unsafe fixture cleanup path');
  rmSync(cleanupPath, { recursive: true, force: true });
}
