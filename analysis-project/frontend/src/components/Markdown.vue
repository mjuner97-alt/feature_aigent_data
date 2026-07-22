<template>
  <div :style="S.root">
    <!-- Short text: render as v-html (fast string replacement) -->
    <template v-if="text.length <= 4000">
      <!-- Keep the React component tree approach? In Vue, use v-html for both modes since
           the performance bottleneck (O(n²) re-renders) is React-specific. Vue's fine-grained
           reactivity doesn't suffer from this, so v-html is fine for both modes. -->
      <div v-html="shortHtml"></div>
    </template>
    <!-- Long text: v-html with fast string replacement -->
    <template v-else>
      <div v-html="longHtml"></div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  text: string;
}>();

const INNERHTML_THRESHOLD = 4000;

// For short text: full markdown to HTML including block-level parsing
const shortHtml = computed(() => markdownToHtml(props.text));

// For long text: fast string-based rendering
const longHtml = computed(() => markdownToHtml(props.text));

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
  // Ordered list
  html = html.replace(/^[\s]*(\d+)\.\s+(.+)$/gm, '<li style="margin:2px 0">$2</li>');
  // Wrap consecutive <li> in <ul>
  html = html.replace(/((?:<li[^>]*>.*?<\/li>\s*)+)/g, '<ul style="margin:6px 0;padding-left:22px">$1</ul>');
  // Bold & italic
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f1f5f9;color:#be185d;padding:1px 5px;border-radius:4px;font-family:ui-monospace,monospace;font-size:0.88em">$1</code>');
  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer" style="color:#6366f1;text-decoration:none">$1</a>');
  // Paragraphs
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

function s(style: Record<string, string | number>): string {
  return Object.entries(style).map(([k, v]) => `${k.replace(/[A-Z]/g, c => '-' + c.toLowerCase())}:${v}`).join(';');
}

const S = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: 'inherit' },
  heading: { color: '#0f172a' },
  p: { margin: '6px 0' },
  ul: { margin: '6px 0', paddingLeft: 22 },
  ol: { margin: '6px 0', paddingLeft: 22 },
  quote: { margin: '8px 0', padding: '6px 12px', borderLeft: '3px solid #cbd5e1', background: '#f8fafc', color: '#475569', fontStyle: 'italic' },
  hr: { border: 'none', borderTop: '1px solid #e2e8f0', margin: '12px 0' },
  codeBlock: { background: '#0f172a', color: '#e2e8f0', padding: '10px 14px', borderRadius: 8, overflowX: 'auto', margin: '8px 0', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.85rem', lineHeight: 1.5 },
  inlineCode: { background: '#f1f5f9', color: '#be185d', padding: '1px 5px', borderRadius: 4, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88em' },
  tableWrap: { overflowX: 'auto', margin: '8px 0' },
  table: { borderCollapse: 'collapse', width: '100%', fontSize: '0.88rem' },
  th: { border: '1px solid #e2e8f0', padding: '6px 10px', background: '#f8fafc', textAlign: 'left', fontWeight: 600 },
  td: { border: '1px solid #e2e8f0', padding: '6px 10px' },
  link: { color: '#6366f1', textDecoration: 'none' },
};
</script>
