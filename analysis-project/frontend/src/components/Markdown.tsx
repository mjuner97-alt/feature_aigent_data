/**
 * Markdown - minimal markdown renderer for chat bubbles.
 *
 * Performance-critical: for long agent reports (which can be 5000+ chars with
 * multiple tables), the React component tree approach (parseBlocks → renderBlock
 * → renderInline with recursive regex) is O(n²) and freezes the main thread.
 *
 * Strategy:
 * - Short text (≤4000 chars): React component tree (interactive, correct styling)
 * - Long text (>4000 chars): dangerouslySetInnerHTML with a fast string-replacement
 *   renderer. No React reconciliation overhead, no recursive calls, pure O(n).
 *   This trades some rendering fidelity for responsiveness.
 */

import React, { useMemo } from 'react';

interface Props {
  text: string;
}

/** Below this threshold, render with React components. Above it, use innerHTML. */
const INNERHTML_THRESHOLD = 4000;

export default React.memo(function Markdown({ text }: Props) {
  if (text.length <= INNERHTML_THRESHOLD) {
    const blocks = parseBlocks(text);
    return (
      <div style={S.root}>
        {blocks.map((b, i) => renderBlock(b, i))}
      </div>
    );
  }

  // Long text: fast string-based rendering via dangerouslySetInnerHTML.
  const html = useMemo(() => markdownToHtml(text), [text]);
  return <div style={S.root} dangerouslySetInnerHTML={{ __html: html }} />;
}, (prev, next) => prev.text === next.text);

// ── Fast string-based renderer for long text ───────────────────────────────

function markdownToHtml(md: string): string {
  // Split into fenced code blocks first (they must not be processed further)
  const parts: string[] = [];
  const codeBlockRe = /```(\w*)\n([\s\S]*?)```/g;
  let lastIdx = 0;
  let m: RegExpExecArray | null;
  while ((m = codeBlockRe.exec(md)) !== null) {
    if (m.index > lastIdx) {
      parts.push(renderInlineHtml(md.slice(lastIdx, m.index)));
    }
    const lang = m[1] || '';
    const code = escHtml(m[2]);
    parts.push(`<pre style="${s(S.codeBlock)}"><code>${code}</code></pre>`);
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
  // Headings
  html = html.replace(/^######\s+(.+)$/gm, '<div style="font-size:0.9rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^#####\s+(.+)$/gm, '<div style="font-size:0.95rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^####\s+(.+)$/gm, '<div style="font-size:1.05rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^###\s+(.+)$/gm, '<div style="font-size:1.18rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^##\s+(.+)$/gm, '<div style="font-size:1.35rem;font-weight:700;margin:8px 0">$1</div>');
  html = html.replace(/^#\s+(.+)$/gm, '<div style="font-size:1.6rem;font-weight:700;margin:12px 0 6px">$1</div>');
  // Tables
  html = renderTableHtml(html);
  // Blockquotes
  html = html.replace(/^&gt;\s?(.+)$/gm, '<blockquote style="margin:8px 0;padding:6px 12px;border-left:3px solid #cbd5e1;background:#f8fafc;color:#475569;font-style:italic">$1</blockquote>');
  // Unordered list
  html = html.replace(/^[\s]*[-*]\s+(.+)$/gm, '<li style="margin:2px 0">$1</li>');
  // Ordered list — wrap consecutive <li> in <ul>/<ol> is complex; keep simple
  html = html.replace(/^[\s]*(\d+)\.\s+(.+)$/gm, '<li style="margin:2px 0">$2</li>');
  // Wrap consecutive <li> in <ul>
  html = html.replace(/((?:<li[^>]*>.*?<\/li>\s*)+)/g, '<ul style="margin:6px 0;padding-left:22px">$1</ul>');
  // Bold & italic (after escaping, * is still * since it's not HTML special)
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  // Inline code (already escaped, backtick boundaries preserved)
  // Re-handle backtick code since escHtml converted ` to &amp;#96; — no, ` is not HTML special
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f1f5f9;color:#be185d;padding:1px 5px;border-radius:4px;font-family:ui-monospace,monospace;font-size:0.88em">$1</code>');
  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer" style="color:#6366f1;text-decoration:none">$1</a>');
  // Paragraphs: wrap lines that aren't already in a block element
  html = html.replace(/^(?!<[hou]|<li|<div|<pre|<blockquote|<table|<ul|<ol|<hr)(.+)$/gm, '<div style="margin:6px 0">$1</div>');
  return html;
}

function renderTableHtml(html: string): string {
  // Simple table: | ... | lines followed by | --- | separator
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

function s(style: React.CSSProperties): string {
  return Object.entries(style).map(([k, v]) => `${k.replace(/[A-Z]/g, c => '-' + c.toLowerCase())}:${v}`).join(';');
}

// ── Block-level parser (for short text, React component rendering) ─────────

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'code'; lang: string; code: string }
  | { kind: 'table'; header: string[]; rows: string[][] }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'quote'; text: string }
  | { kind: 'hr' }
  | { kind: 'p'; text: string };

function parseBlocks(text: string): Block[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    // Fenced code block
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      const lang = fence[1] || '';
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        code.push(lines[i]);
        i++;
      }
      i++; // skip closing ```
      blocks.push({ kind: 'code', lang, code: code.join('\n') });
      continue;
    }

    // Horizontal rule
    if (/^---+\s*$/.test(line) || /^\*\*\*+\s*$/.test(line)) {
      blocks.push({ kind: 'hr' });
      i++;
      continue;
    }

    // Heading
    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) {
      blocks.push({ kind: 'heading', level: h[1].length, text: h[2] });
      i++;
      continue;
    }

    // Blockquote
    if (/^>\s?/.test(line)) {
      const quote: string[] = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) {
        quote.push(lines[i].replace(/^>\s?/, ''));
        i++;
      }
      blocks.push({ kind: 'quote', text: quote.join('\n') });
      continue;
    }

    // Table
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:-]+\|.*$/.test(lines[i + 1])) {
      const header = splitRow(line);
      i += 2; // skip header + separator
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        rows.push(splitRow(lines[i]));
        i++;
      }
      blocks.push({ kind: 'table', header, rows });
      continue;
    }

    // List (ordered or unordered)
    const ulMatch = line.match(/^\s*[-*]\s+(.*)$/);
    const olMatch = line.match(/^\s*\d+\.\s+(.*)$/);
    if (ulMatch || olMatch) {
      const ordered = !!olMatch;
      const items: string[] = [];
      while (i < lines.length) {
        const um = lines[i].match(/^\s*[-*]\s+(.*)$/);
        const om = lines[i].match(/^\s*\d+\.\s+(.*)$/);
        if (ordered && om) {
          items.push(om[1]);
          i++;
        } else if (!ordered && um) {
          items.push(um[1]);
          i++;
        } else {
          break;
        }
      }
      blocks.push({ kind: 'list', ordered, items });
      continue;
    }

    // Blank line — skip
    if (line.trim() === '') {
      i++;
      continue;
    }

    // Paragraph
    const para: string[] = [];
    while (i < lines.length
      && lines[i].trim() !== ''
      && !/^```/.test(lines[i])
      && !/^#{1,6}\s+/.test(lines[i])
      && !/^>\s?/.test(lines[i])
      && !/^\s*[-*]\s+/.test(lines[i])
      && !/^\s*\d+\.\s+/.test(lines[i])
      && !/^---+\s*$/.test(lines[i])
      && !/^\s*\|.*\|\s*$/.test(lines[i])) {
      para.push(lines[i]);
      i++;
    }
    if (para.length > 0) {
      blocks.push({ kind: 'p', text: para.join('\n') });
    }
  }
  return blocks;
}

function splitRow(line: string): string[] {
  return line.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
}

// ── Block renderer (for short text) ────────────────────────────────────────

function renderBlock(b: Block, key: number): React.ReactNode {
  switch (b.kind) {
    case 'heading': {
      const size = [1.6, 1.35, 1.18, 1.05, 0.95, 0.9][b.level - 1] || 1;
      return (
        <div key={key} style={{ ...S.heading, fontSize: `${size}rem`, marginTop: 12, marginBottom: 6, fontWeight: 700 }}>
          {renderInline(b.text)}
        </div>
      );
    }
    case 'code':
      return (
        <pre key={key} style={S.codeBlock}>
          <code>{b.code}</code>
        </pre>
      );
    case 'table':
      return (
        <div key={key} style={S.tableWrap}>
          <table style={S.table}>
            <thead>
              <tr>{b.header.map((h, i) => <th key={i} style={S.th}>{renderInline(h)}</th>)}</tr>
            </thead>
            <tbody>
              {b.rows.map((row, ri) => (
                <tr key={ri}>
                  {row.map((c, ci) => <td key={ci} style={S.td}>{renderInline(c)}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'list':
      return b.ordered ? (
        <ol key={key} style={S.ol}>
          {b.items.map((it, i) => <li key={i}>{renderInline(it)}</li>)}
        </ol>
      ) : (
        <ul key={key} style={S.ul}>
          {b.items.map((it, i) => <li key={i}>{renderInline(it)}</li>)}
        </ul>
      );
    case 'quote':
      return (
        <blockquote key={key} style={S.quote}>
          {renderInline(b.text)}
        </blockquote>
      );
    case 'hr':
      return <hr key={key} style={S.hr} />;
    case 'p':
      return (
        <div key={key} style={S.p}>
          {b.text.split('\n').map((line, i) => (
            <React.Fragment key={i}>
              {i > 0 && <br />}
              {renderInline(line)}
            </React.Fragment>
          ))}
        </div>
      );
  }
}

// ── Inline parser (for short text) ─────────────────────────────────────────

function renderInline(text: string): React.ReactNode {
  const nodes: React.ReactNode[] = [];
  let remaining = text;
  let key = 0;
  const patterns: { re: RegExp; render: (m: RegExpExecArray) => React.ReactNode }[] = [
    { re: /`([^`]+)`/, render: m => <code key={key++} style={S.inlineCode}>{m[1]}</code> },
    { re: /\*\*([^*]+)\*\*/, render: m => <strong key={key++}>{renderInline(m[1])}</strong> },
    { re: /\*([^*]+)\*/, render: m => <em key={key++}>{renderInline(m[1])}</em> },
    { re: /\[([^\]]+)\]\(([^)]+)\)/, render: m => <a key={key++} href={m[2]} target="_blank" rel="noreferrer" style={S.link}>{m[1]}</a> },
  ];
  while (remaining.length > 0) {
    let earliest: { idx: number; match: RegExpExecArray; render: (m: RegExpExecArray) => React.ReactNode } | null = null;
    for (const p of patterns) {
      const match = p.re.exec(remaining);
      if (match && (earliest === null || match.index < earliest.idx)) {
        earliest = { idx: match.index, match, render: p.render };
      }
    }
    if (!earliest) {
      nodes.push(remaining);
      break;
    }
    if (earliest.idx > 0) {
      nodes.push(remaining.slice(0, earliest.idx));
    }
    nodes.push(earliest.render(earliest.match));
    remaining = remaining.slice(earliest.idx + earliest.match[0].length);
  }
  return <>{nodes}</>;
}

// ── Styles ────────────────────────────────────────────────────────────────

const S: Record<string, React.CSSProperties> = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: 'inherit' },
  heading: { color: '#0f172a' },
  p: { margin: '6px 0' },
  ul: { margin: '6px 0', paddingLeft: 22 },
  ol: { margin: '6px 0', paddingLeft: 22 },
  quote: {
    margin: '8px 0', padding: '6px 12px',
    borderLeft: '3px solid #cbd5e1', background: '#f8fafc',
    color: '#475569', fontStyle: 'italic',
  },
  hr: { border: 'none', borderTop: '1px solid #e2e8f0', margin: '12px 0' },
  codeBlock: {
    background: '#0f172a', color: '#e2e8f0',
    padding: '10px 14px', borderRadius: 8,
    overflowX: 'auto', margin: '8px 0',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem', lineHeight: 1.5,
  },
  inlineCode: {
    background: '#f1f5f9', color: '#be185d',
    padding: '1px 5px', borderRadius: 4,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.88em',
  },
  tableWrap: { overflowX: 'auto', margin: '8px 0' },
  table: { borderCollapse: 'collapse', width: '100%', fontSize: '0.88rem' },
  th: {
    border: '1px solid #e2e8f0', padding: '6px 10px',
    background: '#f8fafc', textAlign: 'left', fontWeight: 600,
  },
  td: { border: '1px solid #e2e8f0', padding: '6px 10px' },
  link: { color: '#6366f1', textDecoration: 'none' },
};