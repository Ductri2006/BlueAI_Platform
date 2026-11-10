import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it, vi} from 'vitest';
import {QuotaIndicator} from './QuotaIndicator';

describe('quota presentation', () => {
  it('shows the shared account quota and recovery countdown', () => {
    const html = renderToStaticMarkup(<QuotaIndicator quota={{limit:30, remaining:0, windowSeconds:60, resetsAt:null}}
      seconds={17} error="" refresh={vi.fn()} blocked />);
    expect(html).toContain('Quota gửi câu hỏi');
    expect(html).toContain('Có thể gửi lại');
    expect(html).toContain('17');
    expect(html).toContain('dùng chung giữa các tab');
  });
  it('shows unavailability explicitly when the counter cannot be read', () => {
    const html = renderToStaticMarkup(<QuotaIndicator seconds={0} error="Chưa đọc được quota" refresh={vi.fn()} blocked={false} />);
    expect(html).toContain('Chưa đọc được quota');
    expect(html).not.toContain('Còn 0');
  });
});
