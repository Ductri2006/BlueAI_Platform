import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { StudioPreviewFallbackBadge } from './StudioPreviewFallbackBadge';

describe('preview fallback status', () => {
  it('does not label a pending confirmation/slot correction as global fallback', () => {
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge fallback pendingEntity="xac_nhan" />)).toBe('');
  });
  it('shows a real fallback when no required slot is pending', () => {
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge fallback />)).toContain('Dự phòng');
  });
  it('does not invent a fallback for a normal response or legacy empty status', () => {
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge />)).toBe('');
    expect(renderToStaticMarkup(<StudioPreviewFallbackBadge fallback={false} />)).toBe('');
  });
});
