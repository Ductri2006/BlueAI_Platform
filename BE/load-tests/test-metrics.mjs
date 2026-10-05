import assert from 'node:assert/strict';
import { summarizeRequestMetrics } from './metrics.mjs';

// A request with an HTTP response but a failed status/contract still counts
// once, as does a network timeout with no latency sample and a cleanup failure.
assert.deepEqual(summarizeRequestMetrics(10, 3), {
  requests: 10,
  failures: 3,
  errorRate: 0.3,
});
assert.deepEqual(summarizeRequestMetrics(1, 1), {
  requests: 1,
  failures: 1,
  errorRate: 1,
});
assert.deepEqual(summarizeRequestMetrics(0, 0), {
  requests: 0,
  failures: 0,
  errorRate: 1,
});
assert.throws(() => summarizeRequestMetrics(2, 3), RangeError);
assert.throws(() => summarizeRequestMetrics(-1, 0), RangeError);

console.log('Load-test request metric tests PASS (contract, network, and cleanup failures use attempted-request denominator).');
