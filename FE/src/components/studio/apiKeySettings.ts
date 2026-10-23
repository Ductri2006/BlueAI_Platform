export function validApiRateLimit(value: number): boolean {
  return Number.isInteger(value) && value >= 1 && value <= 1000;
}
