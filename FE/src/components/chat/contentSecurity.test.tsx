import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { RichMarkdownRenderer } from './RichMarkdownRenderer';
import { MessageItem } from './MessageItem';
import { DialogValidationNotice } from '../studio/DialogContextPolicy';

const payload = '<img src=x onerror="window.__qaXss=1"><script>window.__qaXss=1</script>';

function assertEscaped(html: string) {
  expect(html).toContain('&lt;img');
  expect(html).toContain('&lt;script&gt;');
  expect(html).not.toMatch(/<img\b[^>]*onerror|<script\b|<iframe\b|href="javascript:/i);
}

describe('untrusted conversation and Dialog content', () => {
  it.each(['user', 'assistant'] as const)('escapes %s message HTML', sender => {
    assertEscaped(renderToStaticMarkup(<MessageItem message={{
      id: 'qa-xss', sender, content: payload, timestamp: '10:00',
    }} />));
  });

  it.each([
    payload,
    `## ${payload}`,
    `- ${payload}`,
    `> ${payload}`,
    `**${payload}**`,
    `\`${payload}\``,
    `\`\`\`html\n${payload}\n\`\`\``,
    `| Name | Content |\n| --- | --- |\n| fixture | ${payload} |`,
  ])('escapes HTML in every supported Markdown block: %s', content => {
    assertEscaped(renderToStaticMarkup(<RichMarkdownRenderer content={content} />));
  });

  it('does not turn javascript links or event handlers into active markup', () => {
    const html = renderToStaticMarkup(<RichMarkdownRenderer content={'[click](javascript:alert(1)) <iframe srcdoc="bad"></iframe>'} />);
    expect(html).not.toContain('href="javascript:');
    expect(html).not.toContain('<iframe');
    expect(html).toContain('&lt;iframe');
  });

  it('escapes node-name payloads in Dialog validation feedback', () => {
    assertEscaped(renderToStaticMarkup(<DialogValidationNotice errors={[payload]} warnings={[payload]} />));
  });
});
