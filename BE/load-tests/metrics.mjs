export function summarizeRequestMetrics(attemptedRequests, failureCount) {
  if (!Number.isSafeInteger(attemptedRequests) || attemptedRequests < 0) {
    throw new RangeError('attemptedRequests must be a non-negative safe integer.');
  }
  if (!Number.isSafeInteger(failureCount) || failureCount < 0 || failureCount > attemptedRequests) {
    throw new RangeError('failureCount must be an integer between zero and attemptedRequests.');
  }
  return {
    requests: attemptedRequests,
    failures: failureCount,
    errorRate: attemptedRequests ? failureCount / attemptedRequests : 1,
  };
}
