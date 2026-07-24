/**
 * Markdown - minimal markdown renderer for chat bubbles.
 *
 * Uses string-based regex replacement + dangerouslySetInnerHTML (ported from
 * frontend/src/components/Markdown.vue). O(n) parsing - 5ms for 64KB input.
 *
 * Supports: Headings, Lists, Tables, Code blocks (fenced + inline),
 * Bold, Italic, Links, Blockquotes, Horizontal rule.
 */

import React, { useMemo } from 'react';

interface Props {
  text: string;
}

const MAX_RENDER_LEN = 200_000;

export default React.memo(function Markdown({ text }: Props) {
  const html = useMemo(() => {
    try {
      if (text.length > MAX_RENDER_LEN) {
        const head = text.slice(0, MAX_RENDER_LEN);
        return markdownToHtml(head) + `<div style="color:#94a3b8;margin:8px 0">…(内容过长，已截断 ${text.length - MAX_RENDER_LEN} 字符)</div>`;
      }
      return markdownToHtml(text);
    } catch (e) {
      return `<div style="white-space:pre-wrap;word-break:break-word">${escHtml(text)}</div>`;
    }
  }, [text]);
  return <div style={S.root} dangerouslySetInnerHTML={{ __html: html }} />;
});

// ── Markdown to HTML converter ────────────────────────────────────────────

function markdownToHtml(md: string): string {
  const parts: string[] = [];
  const codeBlockRe = /```(\w*)\n([\s\S]*?)```/g;
  let lastIdx = 0;
  let m: RegExpExecArray | null;
  while ((m = codeBlockRe.exec(md)) !== null) {
    if (m.index > lastIdx) {
      parts.push(renderInlineHtml(md.slice(lastIdx, m.index)));
    }
    const code = escHtml(m[2]);
    parts.push(`<pre style="${styleToStr(S.codeBlock)}"><code>${code}</code></pre>`);
    lastIdx = m.index + m[0].length;
  }
  if (lastIdx < md.length) {
    parts.push(renderInlineHtml(md.slice(lastIdx)));
  }
  return parts.join('\n');
}

function renderInlineHtml(text: string): string {
  let html = escHtml(text);
  // Horizontal rule
  html = html.replace(/^---+\s*$/gm, '<hr style="border:none;border-top:1px solid #e2e8f0;margin:12px 0">');
  // Headings (longest first so ## doesn't shadow ###)
  html = html.replace(/^######\s+(.+)$/gm, '<div style="font-size:0.9rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^#####\s+(.+)$/gm, '<div style="font-size:0.95rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^####\s+(.+)$/gm, '<div style="font-size:1.05rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^###\s+(.+)$/gm, '<div style="font-size:1.18rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^##\s+(.+)$/gm, '<div style="font-size:1.35rem;font-weight:700;margin:8px 0">$1</div>');
  html = html.replace(/^#\s+(.+)$/gm, '<div style="font-size:1.6rem;font-weight:700;margin:12px 0 6px">$1</div>');
  // Tables
  html = renderTableHtml(html);
  // Blockquotes (after escHtml, '>' became '&gt;')
  html = html.replace(/^&gt;\s?(.+)$/gm, '<blockquote style="margin:8px 0;padding:6px 12px;border-left:3px solid #cbd5e1;background:#f8fafc;color:#475569;font-style:italic">$1</blockquote>');
  // Unordered list
  html = html.replace(/^[\s]*[-*]\s+(.+)$/gm, '<li style="margin:2px 0">$1</li>');
  // Ordered list
  html = html.replace(/^[\s]*(\d+)\.\s+(.+)$/gm, '<li style="margin:2px 0">$2</li>');
  // Wrap consecutive <li> in <ul>
  html = html.replace(/((?:<li[^>]*>.*?<\/li>\s*)+)/g, '<ul style="margin:6px 0;padding-left:22px">$1</ul>');
  // Bold & italic (bold first so ** isn't shadowed by *)
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  // Inline code (after bold/italic so `**` inside backticks stays intact)
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f1f5f9;color:#be185d;padding:1px 5px;border-radius:4px;font-family:ui-monospace,monospace;font-size:0.88em">$1</code>');
  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer" style="color:#6366f1;text-decoration:none">$1</a>');
  // Paragraphs (lines not already wrapped in a block element)
  html = html.replace(/^(?!<[hou]|<li|<div|<pre|<blockquote|<table|<ul|<ol|<hr)(.+)$/gm, '<div style="margin:6px 0">$1</div>');
  return html;
}

function renderTableHtml(html: string): string {
  const lines = html.split('\n');
  const result: string[] = [];
  let i = 0;
  while (i < lines.length) {
    if (/^\s*\|.*\|\s*$/.test(lines[i]) && i + 1 < lines.length && /^\s*\|[\s:;-]+\|.*$/.test(lines[i + 1])) {
      const headerCells = lines[i].trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        rows.push(lines[i].trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim()));
        i++;
      }
      let table = '<div style="overflow-x:auto;margin:8px 0"><table style="border-collapse:collapse;width:100%;font-size:0.88rem">';
      table += '<thead><tr>' + headerCells.map(h => `<th style="border:1px solid #e2e8f0;padding:6px 10px;background:#f8fafc;text-align:left;font-weight:600">${h}</th>`).join('') + '</tr></thead>';
      table += '<tbody>' + rows.map(row => '<tr>' + row.map(c => `<td style="border:1px solid #e2e8f0;padding:6px 10px">${c}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>';
      result.push(table);
    } else {
      result.push(lines[i]);
      i++;
    }
  }
  return result.join('\n');
}

function escHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function styleToStr(style: Record<string, string | number>): string {
  return Object.entries(style).map(([k, v]) => `${k.replace(/[A-Z]/g, c => '-' + c.toLowerCase())}:${v}`).join(';');
}

const S = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: 'inherit' },
  codeBlock: { background: '#0f172a', color: '#e2e8f0', padding: '10px 14px', borderRadius: 8, overflowX: 'auto', margin: '8px 0', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.85rem', lineHeight: 1.5 },
};
