/**
 * Markdown renderer for chat bubbles.
 *
 * ECharts and HTML are rendered as first-class blocks instead of being shown
 * as source code. The renderer keeps the existing lightweight markdown path
 * for ordinary text, tables and fenced code.
 */
import React, { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import * as echarts from 'echarts';

interface Props { text: string; }
const MAX_RENDER_LEN = 200_000;
type Block =
  | { kind: 'markdown'; content: string; key: string }
  | { kind: 'code'; content: string; language: string; key: string }
  | { kind: 'echarts'; content: string; key: string }
  | { kind: 'html'; content: string; key: string };

export default React.memo(function Markdown({ text }: Props) {
  const deferredText = useDeferredValue(text);
  const blocks = useMemo(() => {
    const value = deferredText.length > MAX_RENDER_LEN ? deferredText.slice(0, MAX_RENDER_LEN) : deferredText;
    return parseBlocks(value);
  }, [deferredText]);

  return (
    <div style={S.root}>
      {blocks.map(block => {
        if (block.kind === 'echarts') return <EChartBlock key={block.key} source={block.content} />;
        if (block.kind === 'html') return <HtmlBlock key={block.key} source={block.content} />;
        if (block.kind === 'code') return <pre key={block.key} style={S.codeBlock}><code className={block.language ? `language-${block.language}` : undefined}>{block.content}</code></pre>;
        return <div key={block.key} dangerouslySetInnerHTML={{ __html: markdownToHtml(block.content) }} />;
      })}
      {deferredText.length > MAX_RENDER_LEN && <div style={S.truncated}>...(内容过长，已截断 {deferredText.length - MAX_RENDER_LEN} 字符)</div>}
    </div>
  );
});

function parseBlocks(markdown: string): Block[] {
  const blocks: Block[] = [];
  const fence = /```([\w-]*)\s*\n([\s\S]*?)```/g;
  let last = 0;
  let match: RegExpExecArray | null;
  let index = 0;
  while ((match = fence.exec(markdown)) !== null) {
    if (match.index > last) blocks.push({ kind: 'markdown', content: markdown.slice(last, match.index), key: `md-${index++}` });
    const language = match[1].toLowerCase();
    const content = match[2].trim();
    if (language === 'echarts' || language === 'echart') blocks.push({ kind: 'echarts', content, key: `chart-${index++}` });
    else if (language === 'html' || language === 'htm') blocks.push({ kind: 'html', content, key: `html-${index++}` });
    else blocks.push({ kind: 'code', content: match[2], language, key: `code-${index++}` });
    last = match.index + match[0].length;
  }
  if (last < markdown.length) blocks.push({ kind: 'markdown', content: markdown.slice(last), key: `md-${index++}` });
  return blocks.length ? blocks : [{ kind: 'markdown', content: markdown, key: 'md-0' }];
}

function EChartBlock({ source }: { source: string }) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!ref.current) return undefined;
    let option: echarts.EChartsOption;
    try { option = normalizeOption(JSON.parse(stripJsonFence(source))); }
    catch (e) {
      setError(`ECharts 配置解析失败：${e instanceof Error ? e.message : '不是有效 JSON'}`);
      return undefined;
    }
    const chart = echarts.init(ref.current, undefined, { renderer: 'canvas' });
    chart.setOption(option, true);
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null;
    observer?.observe(ref.current);
    setError(null);
    return () => { observer?.disconnect(); window.removeEventListener('resize', resize); chart.dispose(); };
  }, [source]);
  if (error) return <div style={S.renderError}><strong>图表无法渲染</strong><div>{error}</div><pre style={S.fallbackCode}>{source}</pre></div>;
  return <div ref={ref} role="img" aria-label="ECharts 图表" style={S.chart} />;
}

function HtmlBlock({ source }: { source: string }) {
  const isDocument = /^\s*(?:<!--[\s\S]*?-->\s*|<\?xml[^>]*>\s*)*(?:<!doctype\s+html|<html\b)/i.test(source);
  // Full reports run inside a sandboxed iframe, so their inline ECharts
  // bootstrap is allowed to execute. HTML fragments remain script-free.
  const clean = sanitizeHtml(source, isDocument);
  if (isDocument) return <iframe title="HTML 报告" srcDoc={clean} sandbox="allow-scripts" style={S.htmlFrame} />;
  return <div style={S.htmlFragment} dangerouslySetInnerHTML={{ __html: clean }} />;
}

function stripJsonFence(value: string): string {
  const trimmed = value.trim();
  const fenced = trimmed.match(/^```(?:json|echarts)?\s*([\s\S]*?)```$/i);
  return fenced ? fenced[1].trim() : trimmed;
}

function normalizeOption(value: unknown): echarts.EChartsOption {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('配置必须是 JSON 对象');
  return normalizeNode(value) as echarts.EChartsOption;
}

function normalizeNode(value: unknown, key?: string): unknown {
  if (Array.isArray(value)) return value.map(item => normalizeNode(item));
  if (value && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const [childKey, childValue] of Object.entries(value)) result[childKey] = normalizeNode(childValue, childKey);
    return result;
  }
  if (typeof value === 'string' && key && (key === 'formatter' || key === 'valueFormatter')) {
    if (/^\(value\)\s*=>\s*value\s*\+\s*['"]%['"]$/.test(value)) return (input: unknown) => `${input}%`;
    const fieldValue = value.match(/^\(params\)\s*=>\s*params\.value\[['"](.+?)['"]\]\s*\+\s*['"]%['"]$/);
    if (fieldValue) return (params: { value?: Record<string, unknown> }) => `${params.value?.[fieldValue[1]] ?? ''}%`;
  }
  return value;
}

/** Keep report HTML useful while stripping executable or navigational content. */
function sanitizeHtml(source: string, allowInlineScripts = false): string {
  if (typeof DOMParser === 'undefined') return source;
  const doc = new DOMParser().parseFromString(source, 'text/html');
  doc.querySelectorAll('object, embed, form, link[rel="import"], iframe').forEach(node => node.remove());
  if (!allowInlineScripts) doc.querySelectorAll('script').forEach(node => node.remove());
  else doc.querySelectorAll('script[src]').forEach(node => node.remove());
  doc.querySelectorAll('*').forEach(node => {
    for (const attr of Array.from(node.attributes)) {
      if (/^on/i.test(attr.name) || (['href', 'src', 'action'].includes(attr.name.toLowerCase()) && /^\s*javascript:/i.test(attr.value))) node.removeAttribute(attr.name);
    }
  });
  return /^\s*<!doctype\s+html/i.test(source) || /<html\b/i.test(source) ? `<!doctype html>${doc.documentElement.outerHTML}` : doc.body.innerHTML;
}

function markdownToHtml(md: string): string {
  let html = escapeHtml(md);
  html = html.replace(/^---+\s*$/gm, '<hr style="border:none;border-top:1px solid #e2e8f0;margin:12px 0">');
  html = html.replace(/^######\s+(.+)$/gm, '<div style="font-size:0.9rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^#####\s+(.+)$/gm, '<div style="font-size:0.95rem;font-weight:700;margin:4px 0">$1</div>');
  html = html.replace(/^####\s+(.+)$/gm, '<div style="font-size:1.05rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^###\s+(.+)$/gm, '<div style="font-size:1.18rem;font-weight:700;margin:6px 0">$1</div>');
  html = html.replace(/^##\s+(.+)$/gm, '<div style="font-size:1.35rem;font-weight:700;margin:8px 0">$1</div>');
  html = html.replace(/^#\s+(.+)$/gm, '<div style="font-size:1.6rem;font-weight:700;margin:12px 0 6px">$1</div>');
  html = renderTableHtml(html);
  html = html.replace(/^&gt;\s?(.+)$/gm, '<blockquote style="margin:8px 0;padding:6px 12px;border-left:3px solid #cbd5e1;background:#f8fafc;color:#475569;font-style:italic">$1</blockquote>');
  html = html.replace(/^[\s]*[-*]\s+(.+)$/gm, '<li style="margin:2px 0">$1</li>');
  html = html.replace(/^[\s]*(\d+)\.\s+(.+)$/gm, '<li style="margin:2px 0">$2</li>');
  html = html.replace(/((?:<li[^>]*>.*?<\/li>\s*)+)/g, '<ul style="margin:6px 0;padding-left:22px">$1</ul>');
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f1f5f9;color:#be185d;padding:1px 5px;border-radius:4px;font-family:ui-monospace,monospace;font-size:0.88em">$1</code>');
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer" style="color:#6366f1;text-decoration:none">$1</a>');
  html = html.replace(/^(?!<[hou]|<li|<div|<pre|<blockquote|<table|<ul|<ol|<hr)(.+)$/gm, '<div style="margin:6px 0">$1</div>');
  return linkifyPresentationReportUrls(html);
}

/** Make a plain report URL clickable even when the model omits Markdown link syntax. */
function linkifyPresentationReportUrls(html: string): string {
  if (typeof document === 'undefined') return html;
  const container = document.createElement('div');
  container.innerHTML = html;
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  const textNodes: Text[] = [];
  let current: Node | null;
  while ((current = walker.nextNode())) textNodes.push(current as Text);

  const reportUrl = /(?:https?:\/\/[^\s/<]+)?\/api\/presentation\/reports\/[A-Za-z0-9_-]+/g;
  for (const textNode of textNodes) {
    const parent = textNode.parentElement;
    if (!parent || parent.closest('a, code, pre')) continue;
    const value = textNode.nodeValue ?? '';
    reportUrl.lastIndex = 0;
    if (!reportUrl.test(value)) continue;

    reportUrl.lastIndex = 0;
    const fragment = document.createDocumentFragment();
    let last = 0;
    let match: RegExpExecArray | null;
    while ((match = reportUrl.exec(value)) !== null) {
      fragment.append(document.createTextNode(value.slice(last, match.index)));
      const link = document.createElement('a');
      link.href = match[0];
      link.target = '_blank';
      link.rel = 'noreferrer';
      link.textContent = match[0];
      link.style.color = '#6366f1';
      link.style.textDecoration = 'none';
      link.style.overflowWrap = 'anywhere';
      fragment.append(link);
      last = match.index + match[0].length;
    }
    fragment.append(document.createTextNode(value.slice(last)));
    textNode.replaceWith(fragment);
  }
  return container.innerHTML;
}

function renderTableHtml(html: string): string {
  const lines = html.split('\n');
  const result: string[] = [];
  let i = 0;
  while (i < lines.length) {
    if (/^\s*\|.*\|\s*$/.test(lines[i]) && i + 1 < lines.length && /^\s*\|[\s:;-]+\|.*$/.test(lines[i + 1])) {
      const header = lines[i].trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { rows.push(lines[i].trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim())); i++; }
      let table = '<div style="overflow-x:auto;margin:8px 0"><table style="border-collapse:collapse;width:100%;font-size:0.88rem">';
      table += '<thead><tr>' + header.map(cell => `<th style="border:1px solid #e2e8f0;padding:6px 10px;background:#f8fafc;text-align:left;font-weight:600">${cell}</th>`).join('') + '</tr></thead>';
      table += '<tbody>' + rows.map(row => `<tr>${row.map(cell => `<td style="border:1px solid #e2e8f0;padding:6px 10px">${cell}</td>`).join('')}</tr>`).join('') + '</tbody></table></div>';
      result.push(table);
    } else { result.push(lines[i]); i++; }
  }
  return result.join('\n');
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

const S = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: 'inherit', contain: 'layout paint style', contentVisibility: 'auto', containIntrinsicSize: 'auto 500px' } as React.CSSProperties,
  codeBlock: { background: '#0f172a', color: '#e2e8f0', padding: '10px 14px', borderRadius: 8, overflowX: 'auto', margin: '8px 0', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.85rem', lineHeight: 1.5 } as React.CSSProperties,
  chart: { width: '100%', height: 420, minHeight: 300, margin: '12px 0' } as React.CSSProperties,
  htmlFragment: { overflowX: 'auto', margin: '10px 0', background: '#fff', color: '#111827' } as React.CSSProperties,
  htmlFrame: { width: '100%', minHeight: 520, border: '1px solid #cbd5e1', borderRadius: 8, background: '#fff' } as React.CSSProperties,
  renderError: { margin: '10px 0', padding: 12, border: '1px solid #fecaca', borderRadius: 8, color: '#b91c1c', background: '#fff7f7' } as React.CSSProperties,
  fallbackCode: { maxHeight: 260, overflow: 'auto', color: '#475569', whiteSpace: 'pre-wrap' } as React.CSSProperties,
  truncated: { color: '#94a3b8', margin: '8px 0' } as React.CSSProperties,
};
