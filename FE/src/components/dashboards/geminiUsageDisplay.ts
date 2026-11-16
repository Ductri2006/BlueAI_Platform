export type UsagePeriod = 'month' | 'day' | 'custom';
export type UsageCurrency = 'VND' | 'USD';
export const USAGE_TIMEZONE = 'Asia/Ho_Chi_Minh';

export function vietnamToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: USAGE_TIMEZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now);
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-');
}

export function monthRange(month: string) {
  const [year, index] = month.split('-').map(Number);
  const last = new Date(Date.UTC(year, index, 0)).getUTCDate();
  return { startDate: `${month}-01`, endDate: `${month}-${last}` };
}

export function usageRangeError(start: string, end: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(start) || !/^\d{4}-\d{2}-\d{2}$/.test(end)) return 'Vui lòng chọn đủ ngày bắt đầu và kết thúc.';
  const first = Date.parse(`${start}T00:00:00Z`);
  const last = Date.parse(`${end}T00:00:00Z`);
  if (!Number.isFinite(first) || !Number.isFinite(last) || start > end) return 'Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.';
  if (new Date(first).toISOString().slice(0, 10) !== start || new Date(last).toISOString().slice(0, 10) !== end) return 'Vui lòng chọn ngày hợp lệ.';
  if ((last - first) / 86_400_000 >= 366) return 'Mỗi lần xem tối đa 366 ngày. Hãy thu hẹp khoảng thời gian.';
  return '';
}

export function usageMoney(usd: number, currency: UsageCurrency, exchangeRate: number): string {
  const amount = currency === 'VND' ? usd * exchangeRate : usd;
  if (amount > 0 && amount < (currency === 'VND' ? 1 : 0.000001)) return currency === 'VND' ? '< 1 ₫' : '< 0,000001 US$';
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency', currency,
    minimumFractionDigits: currency === 'VND' ? 0 : 2,
    maximumFractionDigits: currency === 'VND' ? 0 : amount < 0.01 ? 6 : amount < 1 ? 4 : 2,
  }).format(amount);
}
