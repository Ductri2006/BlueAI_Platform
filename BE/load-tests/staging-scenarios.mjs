import { performance } from 'node:perf_hooks';
import { summarizeRequestMetrics } from './metrics.mjs';

const baseUrl = (process.env.STAGING_BASE_URL ?? '').replace(/\/$/, '');
const confirm = process.env.STAGING_CONFIRM ?? '';
if (!baseUrl || confirm !== 'I_UNDERSTAND_STAGING_LOAD') {
  throw new Error('This load test is staging-only. Set STAGING_BASE_URL and STAGING_CONFIRM=I_UNDERSTAND_STAGING_LOAD.');
}
if (/localhost|127\.0\.0\.1|0\.0\.0\.0/i.test(baseUrl)) {
  throw new Error('Use a real HTTPS staging ingress, not localhost.');
}
if (!/^https:\/\//i.test(baseUrl)) throw new Error('STAGING_BASE_URL must use HTTPS.');

const email = process.env.AUTH_EMAIL;
const password = process.env.AUTH_PASSWORD;
const widgetPublicKey = process.env.WIDGET_PUBLIC_KEY;
const widgetOrigin = process.env.WIDGET_ORIGIN;
const timeoutMs = Number(process.env.TIMEOUT_MS ?? 30_000);
const maxP95Ms = Number(process.env.MAX_P95_MS ?? 3_000);
const maxErrorRate = Number(process.env.MAX_ERROR_RATE ?? 0.01);
const runConversationAsk = process.env.RUN_CONVERSATION_ASK === 'true';
const stages = (process.env.CONCURRENCY_STAGES ?? '5,10,15').split(',').map(Number);
if (!email || !password || !widgetPublicKey || !widgetOrigin) {
  throw new Error('AUTH_EMAIL, AUTH_PASSWORD, WIDGET_PUBLIC_KEY and WIDGET_ORIGIN are required.');
}
if (stages.some((n) => !Number.isSafeInteger(n) || n < 1 || n > 100)) throw new Error('CONCURRENCY_STAGES must be 1..100.');

const percentile = (samples, fraction) => {
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted.length ? Number(sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)].toFixed(1)) : null;
};
const jsonHeaders = { accept: 'application/json', 'content-type': 'application/json' };
const assert = (condition, message) => { if (!condition) throw new Error(message); };

async function request(path, options = {}) {
  const started = performance.now();
  const response = await fetch(`${baseUrl}${path}`, { ...options, signal: AbortSignal.timeout(timeoutMs) });
  const bodyText = await response.text();
  let body = null;
  try { body = bodyText ? JSON.parse(bodyText) : null; } catch { /* endpoint may be text/plain */ }
  return { response, body, bodyText, latencyMs: performance.now() - started };
}

function expectJson(result, label) {
  const contentType = result.response.headers.get('content-type') ?? '';
  assert(contentType.includes('application/json'), `${label}: expected JSON content-type, got ${contentType}`);
}

async function scenario(index) {
  const started = performance.now();
  const latencies = [];
  const failed = [];
  let requestsAttempted = 0;
  let conversationId = null;
  const run = async (label, path, options, expected, check) => {
    requestsAttempted += 1;
    try {
      const result = await request(path, options);
      latencies.push(result.latencyMs);
      assert(result.response.status === expected, `${label}: expected ${expected}, got ${result.response.status}: ${result.bodyText.slice(0, 300)}`);
      if (check) check(result);
      return result;
    } catch (error) {
      failed.push(error instanceof Error ? `${label}: ${error.message}` : `${label}: ${String(error)}`);
      return null;
    }
  };

  const login = await run('login', '/api/v1/auth/login', { method: 'POST', headers: jsonHeaders, body: JSON.stringify({ email, password }) }, 200, (r) => {
    expectJson(r, 'login');
    assert(!r.response.headers.has('set-cookie'), 'login: bearer tokens must not be placed in a cookie by this API contract');
    assert(typeof r.body?.accessToken === 'string' && r.body.accessToken.length > 20, 'login: missing accessToken');
    assert(typeof r.body?.refreshToken === 'string' && r.body.refreshToken.length > 20, 'login: missing refreshToken');
    assert(r.body?.user?.email?.toLowerCase() === email.toLowerCase(), 'login: response user mismatch');
  });
  if (!login) return { index, failed, latencies, requestsAttempted, scenarioMs: performance.now() - started };
  try {
  const token = login.body.accessToken;
  const authHeaders = { ...jsonHeaders, authorization: `Bearer ${token}` };

  await run('me', '/api/v1/auth/me', { headers: { accept: 'application/json', authorization: `Bearer ${token}` } }, 200, (r) => {
    expectJson(r, 'me');
    assert(r.body?.email?.toLowerCase() === email.toLowerCase(), 'me: authenticated identity mismatch');
  });
  const conversation = await run('conversation-create', '/api/v1/conversations', { method: 'POST', headers: authHeaders, body: JSON.stringify({ title: `staging-load-${index}` }) }, 201, (r) => {
    expectJson(r, 'conversation-create');
    assert(typeof r.body?.id === 'string' && r.body.id.length > 10, 'conversation-create: missing id');
  });
  if (conversation) {
    conversationId = conversation.body.id;
    const id = conversationId;
    await run('conversation-list', '/api/v1/conversations', { headers: { accept: 'application/json', authorization: `Bearer ${token}` } }, 200, (r) => {
      expectJson(r, 'conversation-list');
      assert(Array.isArray(r.body) && r.body.some((item) => item.id === id), 'conversation-list: created conversation not visible to owner');
    });
    if (runConversationAsk) {
      await run('conversation-ask', `/api/v1/conversations/${encodeURIComponent(id)}/ask`, { method: 'POST', headers: authHeaders, body: JSON.stringify({ question: 'Reply with a short staging health acknowledgement.' }) }, 200, (r) => {
        expectJson(r, 'conversation-ask');
        assert(typeof r.body?.assistantMessage?.content === 'string' && r.body.assistantMessage.content.length > 0, 'conversation-ask: response has no assistantMessage.content');
      });
    }
  }

  const widgetHeaders = { accept: 'application/json', 'content-type': 'application/json', origin: widgetOrigin };
  const session = await run('widget-session', `/api/v1/widget/${encodeURIComponent(widgetPublicKey)}/sessions`, { method: 'POST', headers: widgetHeaders }, 201, (r) => {
    expectJson(r, 'widget-session');
    assert(r.response.headers.get('access-control-allow-origin') === widgetOrigin, 'widget-session: allowed origin was not echoed');
    assert(typeof r.body?.sessionId === 'string' && typeof r.body?.accessToken === 'string', 'widget-session: missing session credentials');
  });
  if (session) {
    const message = await run('widget-message', `/api/v1/runtime/sessions/${encodeURIComponent(session.body.sessionId)}/messages`, { method: 'POST', headers: { ...widgetHeaders, 'x-widget-token': session.body.accessToken }, body: JSON.stringify({ requestId: `load-${index}-${Date.now()}`, message: process.env.WIDGET_MESSAGE ?? 'Hello from staging load test' }) }, 200, (r) => {
      expectJson(r, 'widget-message');
      assert(r.response.headers.get('access-control-allow-origin') === widgetOrigin, 'widget-message: allowed origin was not echoed');
      assert(r.body?.sessionId === session.body.sessionId && typeof r.body?.requestId === 'string', 'widget-message: response/session correlation mismatch');
    });
  }
  } finally {
    if (conversationId) {
      requestsAttempted += 1;
      try {
        const cleanup = await request(`/api/v1/conversations/${encodeURIComponent(conversationId)}`, {
          method: 'DELETE',
          headers: { accept: 'application/json', authorization: `Bearer ${login.body.accessToken}` },
        });
        latencies.push(cleanup.latencyMs);
        assert(cleanup.response.status === 204, `conversation-cleanup: expected 204, got ${cleanup.response.status}`);
      } catch (error) {
        failed.push(error instanceof Error ? `conversation-cleanup: ${error.message}` : `conversation-cleanup: ${String(error)}`);
      }
    }
  }
  return { index, failed, latencies, requestsAttempted, scenarioMs: performance.now() - started };
}

console.log(JSON.stringify({ target: baseUrl, stages, runConversationAsk }, null, 2));
let failedOverall = false;
for (const concurrency of stages) {
  const waveStarted = performance.now();
  const results = await Promise.all(Array.from({ length: concurrency }, (_, index) => scenario(`${concurrency}-${index}`)));
  const latencies = results.flatMap((r) => r.latencies);
  const failures = results.flatMap((r) => r.failed);
  const metric = summarizeRequestMetrics(
    results.reduce((total, result) => total + result.requestsAttempted, 0),
    failures.length,
  );
  const summary = {
    concurrency,
    scenarios: results.length,
    requests: metric.requests,
    failures: metric.failures,
    errorRate: Number(metric.errorRate.toFixed(4)),
    elapsedMs: Number((performance.now() - waveStarted).toFixed(1)),
    latencyMs: { p50: percentile(latencies, 0.5), p95: percentile(latencies, 0.95), p99: percentile(latencies, 0.99), max: latencies.length ? Number(Math.max(...latencies).toFixed(1)) : null },
    sampleErrors: failures.slice(0, 5),
  };
  console.log(JSON.stringify(summary));
  if (metric.errorRate > maxErrorRate || summary.latencyMs.p95 == null || summary.latencyMs.p95 > maxP95Ms) failedOverall = true;
}
if (failedOverall) {
  console.error(`FAIL: staging scenarios exceeded MAX_P95_MS=${maxP95Ms} or MAX_ERROR_RATE=${maxErrorRate}.`);
  process.exitCode = 1;
} else {
  console.log(`PASS: staging auth/widget/conversation scenarios stayed under p95 ${maxP95Ms} ms and error rate ${maxErrorRate}.`);
}
