import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { StudioDiscardDialog } from './StudioDiscardDialog';

describe('studio discard confirmation', () => {
  it('labels the decision accessibly and defaults focus to keeping the draft', () => {
    const html = renderToStaticMarkup(<StudioDiscardDialog message="Bản nháp QA" onDecision={vi.fn()} />);
    expect(html).toContain('role="alertdialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('aria-labelledby=');
    expect(html).toContain('aria-describedby=');
    expect(html).toMatch(/<button[^>]*autofocus=""[^>]*>Ở lại chỉnh sửa<\/button>/);
    expect(html).toContain('Bỏ thay đổi và tiếp tục');
  });

  it('escapes the confirmation text rather than interpreting it as markup', () => {
    const html = renderToStaticMarkup(<StudioDiscardDialog message={'<script>alert("QA")</script>'} onDecision={vi.fn()} />);
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });
});
