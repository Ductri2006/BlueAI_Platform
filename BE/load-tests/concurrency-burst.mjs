import { performance } from 'node:perf_hooks';

const baseUrl = (process.env.BASE_URL ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const route = process.env.ROUTE ?? '/actuator/health';
const timeoutMs = Number(process.env.TIMEOUT_MS ?? 15_000);
const maxP95Ms = Number(process.env.MAX_P95_MS ?? 2_000);
const stages = (process.env.CONCURRENCY_STAGES ?? '100,250,500,1000')
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isSafeInteger(value) && value > 0);

if (stages.length === 0 || stages.some((value) => value > 1_000)) {
  throw new Error('CONCURRENCY_STAGES must contain integers from 1 through 1000.');
}

const percentile = (samples, fraction) => {
  if (samples.length === 0) return null;
  const sorted = [...samples].sort((a, b) => a - b);
  return Number(sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(1));
};

let failed = false;
console.log(`Target: ${baseUrl}${route}`);
console.log('This is a single-machine concurrent burst test, not a sustained RPS benchmark.');

for (const concurrency of stages) {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const samples = [];
  const statuses = new Map();
  const errors = [];

  const requests = Array.from({ length: concurrency }, async (_, index) => {
    await gate;
    const startedAt = performance.now();
    try {
      const response = await fetch(`${baseUrl}${route}`, {
        headers: { accept: 'application/json' },
        signal: AbortSignal.timeout(timeoutMs),
      });
      await response.arrayBuffer();
      samples[index] = performance.now() - startedAt;
      statuses.set(response.status, (statuses.get(response.status) ?? 0) + 1);
      if (!response.ok) errors.push(`HTTP ${response.status}`);
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }
  });

  const waveStartedAt = performance.now();
  release();
  await Promise.all(requests);
  const elapsedMs = performance.now() - waveStartedAt;
  const p95Ms = percentile(samples, 0.95);
  const result = {
    concurrency,
    completed: samples.filter(Number.isFinite).length,
    failed: errors.length,
    statusCounts: Object.fromEntries(statuses),
    elapsedMs: Number(elapsedMs.toFixed(1)),
    latencyMs: {
      p50: percentile(samples, 0.50),
      p95: p95Ms,
      p99: percentile(samples, 0.99),
      max: samples.length ? Number(Math.max(...samples).toFixed(1)) : null,
    },
    errors: [...new Set(errors)].slice(0, 5),
  };
  console.log(JSON.stringify(result));
  if (errors.length > 0 || p95Ms == null || p95Ms > maxP95Ms) failed = true;
}

if (failed) {
  console.error(`FAIL: errors occurred or a wave exceeded MAX_P95_MS=${maxP95Ms}.`);
  process.exitCode = 1;
} else {
  console.log(`PASS: all waves completed with p95 <= ${maxP95Ms} ms and no HTTP/transport errors.`);
}
