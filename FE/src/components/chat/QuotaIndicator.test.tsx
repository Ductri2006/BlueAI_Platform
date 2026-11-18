import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';
import {QuotaIndicator} from './QuotaIndicator';

describe('quota presentation', () => {
  it('only displays the recovery message during an active cooldown', () => {
    const html = renderToStaticMarkup(<QuotaIndicator seconds={17} blocked />);
    expect(html).toContain('Có thể gửi lại');
    expect(html).toContain('17');
    expect(html).not.toContain('lượt /');
  });
  it('occupies no space while sending is available', () => {
    expect(renderToStaticMarkup(<QuotaIndicator seconds={0} blocked={false} />)).toBe('');
    expect(renderToStaticMarkup(<QuotaIndicator seconds={28} blocked={false} />)).toBe('');
  });
  it('does not invent a recovery countdown while waiting for the server', () => {
    expect(renderToStaticMarkup(<QuotaIndicator seconds={0} blocked />)).toContain('Đang cập nhật');
  });
});
