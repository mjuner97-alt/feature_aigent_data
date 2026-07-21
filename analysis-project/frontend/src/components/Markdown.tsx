/**
 * Markdown - minimal markdown renderer for chat bubbles.
 *
 * Supports the subset emitted by the agent:
 * <ul>
 *   <li>Headings: #, ##, ###
 *   <li>Lists: -, *, 1.
 *   <li>Tables: | a | b |\n|---|---|
 *   <li>Code blocks: ``` fenced and `inline`
 *   <li>Inline: **bold**, *italic*, `code`, [text](url)
 *   <li>Blockquotes: >
 *   <li>Horizontal rule: ---
 * </ul>
 *
 * No external dependencies — the project has no markdown library in
 * package.json, and adding one would balloon the bundle. This covers the
 * common shapes the agent produces in its reports.
 */

import React from 'react';

interface Props {
  text: string;
}

export default React.memo(function Markdown({ text }: Props) {
  const blocks = parseBlocks(text);
  return (
    <div style={S.root}>
      {blocks.map((b, i) => renderBlock(b, i))}
    </div>
  );
}, (prev, next) => prev.text === next.text);

// ── Block-level parser ────────────────────────────────────────────────────

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

    // Table (a line of | cells | followed by a separator line of | --- |)
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

    // List (ordered or unordered) — group consecutive list items
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

    // Paragraph — gather until blank or block-start
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

// ── Block renderer ────────────────────────────────────────────────────────

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

// ── Inline parser ─────────────────────────────────────────────────────────
// Handles: `code`, **bold**, *italic*, [text](url)

function renderInline(text: string): React.ReactNode {
  const nodes: React.ReactNode[] = [];
  let remaining = text;
  let key = 0;
  // Pattern order matters: code first (so ** inside `..` isn't processed),
  // then bold, then italic, then link.
  const patterns: { re: RegExp; render: (m: RegExpExecArray) => React.ReactNode }[] = [
    { re: /`([^`]+)`/, render: m => <code key={key++} style={S.inlineCode}>{m[1]}</code> },
    { re: /\*\*([^*]+)\*\*/, render: m => <strong key={key++}>{renderInline(m[1])}</strong> },
    { re: /\*([^*]+)\*/, render: m => <em key={key++}>{renderInline(m[1])}</em> },
    { re: /\[([^\]]+)\]\(([^)]+)\)/, render: m => <a key={key++} href={m[2]} target="_blank" rel="noreferrer" style={S.link}>{m[1]}</a> },
  ];
  while (remaining.length > 0) {
    let earliest: { idx: number; match: RegExpExecArray; render: (m: RegExpExecArray) => React.ReactNode } | null = null;
    for (const p of patterns) {
      const m = p.re.exec(remaining);
      if (m && (earliest === null || m.index < earliest.idx)) {
        earliest = { idx: m.index, match: m, render: p.render };
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