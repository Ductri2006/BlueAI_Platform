import { describe, expect, it } from 'vitest';
import { validApiRateLimit } from './apiKeySettings';

describe('API key rate limit input', () => {
  it('accepts whole requests within the server range', () => {
    for (const value of [1, 30, 1000]) expect(validApiRateLimit(value)).toBe(true);
  });
  it('rejects empty, fractional, negative and excessive values before saving a draft', () => {
    for (const value of [Number.NaN, 0, -1, 1.5, 1001, Number.POSITIVE_INFINITY]) expect(validApiRateLimit(value)).toBe(false);
  });
});
