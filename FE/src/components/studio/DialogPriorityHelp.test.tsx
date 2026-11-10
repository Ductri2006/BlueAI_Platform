import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';
import {DialogPriorityHelp} from './DialogPriorityHelp';

describe('dialog priority guide', () => {
  it('explains conditional priority, hierarchy and the guide entry point', () => {
    const html = renderToStaticMarkup(<DialogPriorityHelp />);
    expect(html).toContain('số nhỏ được xét trước nếu điều kiện khớp');
    expect(html).toContain('cha–con');
    expect(html).toContain('Mỗi bước cần một số nguyên riêng');
    expect(html).toContain('/guides/blueai-custom.html');
  });
});
