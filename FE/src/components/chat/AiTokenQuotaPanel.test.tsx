import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AiTokenQuotaPanel, TokenQuotaBar } from './AiTokenQuotaPanel';
import type { TokenWindow } from '../../api/blueAiApi';

const window: TokenWindow = {limit:100000, used:4000, reserved:1200, uncertain:0, remaining:94800,
  resetsAt:'2026-11-17T11:28:00Z', resetAfterSeconds:18000};
describe('AI token quota', () => {
  it('presents both independent windows with Vietnam time and accessible progress', () => {
    const html = renderToStaticMarkup(<AiTokenQuotaPanel quota={{enabled:true, serverTime:'2026-11-17T06:28:00Z', fiveHour:window,
      weekly:{...window, used:0, reserved:0, limit:1000000}}} />);
    expect(html).toContain('5 giờ');
    expect(html).toContain('7 ngày');
    expect(html).toContain('Đã dùng 4%');
    expect(html).toContain('18:28');
    expect(html.match(/role="progressbar"/g)).toHaveLength(2);
  });
  it('shows loading and unavailable states without inventing zero usage', () => {
    expect(renderToStaticMarkup(<AiTokenQuotaPanel />)).toContain('Đang đọc hạn mức AI');
    const html = renderToStaticMarkup(<AiTokenQuotaPanel error="Chưa đọc được hạn mức AI" />);
    expect(html).toContain('Chưa đọc được');
    expect(html).not.toContain('Đã dùng 0%');
  });
  it('does not start a window on a quota read', () => {
    const html = renderToStaticMarkup(<TokenQuotaBar label="5 giờ" window={{...window, resetsAt:null}} />);
    expect(html).toContain('Bắt đầu chu kỳ khi bạn dùng AI');
  });
  it('clamps progress after an overrun and keeps Dialog available', () => {
    const full = {...window, used:120000, reserved:0, remaining:0};
    const html = renderToStaticMarkup(<AiTokenQuotaPanel quota={{enabled:true, serverTime:'', fiveHour:full, weekly:window}} />);
    expect(html).toContain('aria-valuenow="100"');
    expect(html).toContain('Dialog vẫn sử dụng được');
    expect(html).not.toContain('width:120%');
  });
});
