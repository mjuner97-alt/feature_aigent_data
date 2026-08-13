<template>
  <div :style="theme.root">
    <!-- 完整 HTML 文档(<!doctype>/<html>):iframe srcdoc 隔离渲染,自带样式与脚本不污染外层 -->
    <iframe
      v-if="isFullHtmlDoc"
      :srcdoc="text"
      class="html-doc-frame"
      sandbox="allow-scripts allow-same-origin"
      @load="onFrameLoad"
    ></iframe>
    <!-- Markdown 或 HTML 片段:v-html 渲染,HTML 标签透传不转义 -->
    <div v-else v-html="renderedHtml"></div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  text: string;
  theme?: 'light' | 'dark';
}>(), {
  theme: 'light',
});

// ── Theme presets ──────────────────────────────────────────────────────────

const LIGHT = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: 'inherit' },
  heading: { color: '#0f172a' },
  hr: '1px solid #e2e8f0',
  quoteBorder: '#cbd5e1',
  quoteBg: '#f8fafc',
  quoteColor: '#475569',
  codeBlockBg: '#0f172a',
  codeBlockColor: '#e2e8f0',
  codeBlockBorder: '1px solid #334155',
  inlineCodeBg: '#f1f5f9',
  inlineCodeColor: '#be185d',
  tableBorder: '#e2e8f0',
  thBg: '#f8fafc',
  thColor: '#1e293b',
  tdColor: 'inherit',
  linkColor: '#6366f1',
  strongColor: 'inherit',
  emColor: 'inherit',
};

const DARK = {
  root: { fontSize: '0.95rem', lineHeight: 1.6, color: '#e2e8f0', background: '#0f172a', padding: '14px 16px', borderRadius: 8, border: '1px solid #1e293b', overflowX: 'auto' },
  heading: { color: '#f1f5f9' },
  hr: '1px solid #334155',
  quoteBorder: '#6366f1',
  quoteBg: '#1e293b',
  quoteColor: '#94a3b8',
  codeBlockBg: '#0f172a',
  codeBlockColor: '#e2e8f0',
  codeBlockBorder: '1px solid #334155',
  inlineCodeBg: '#1e293b',
  inlineCodeColor: '#f472b6',
  tableBorder: '#334155',
  thBg: '#1e293b',
  thColor: '#e2e8f0',
  tdColor: '#cbd5e1',
  linkColor: '#60a5fa',
  strongColor: '#f1f5f9',
  emColor: '#a5b4fc',
};

const theme = computed(() => props.theme === 'dark' ? DARK : LIGHT);

// 完整 HTML 文档检测:先剥掉 BOM / 前导空白 / 注释 / <?xml?> 声明,再判断起点。
// 兼容"生成工具在 <!DOCTYPE html> 前塞了注释或 xml 声明"这类内容,避免漏检掉进 v-html 被拆碎。
const isFullHtmlDoc = computed(() => {
  const stripped = props.text
    .replace(/^(?:\s|<!--[\s\S]*?-->|<\?[\s\S]*?\?>)*/, '')
    .trimStart()
    .slice(0, 200)
    .toLowerCase();
  return stripped.startsWith('<!doctype') || stripped.startsWith('<html');
});

// Markdown / HTML 片段 -> HTML(保留 HTML 标签,仅转义纯文本)
const renderedHtml = computed(() => markdownToHtml(props.text));

// iframe 加载后按内容高度自适应(srcdoc 同源,可读 contentDocument)
function onFrameLoad(e: Event) {
  const frame = e.target as HTMLIFrameElement;
  const adjust = () => {
    try {
      const doc = frame.contentDocument;
      if (!doc) return;
      const h = Math.max(
        doc.documentElement.scrollHeight,
        doc.body ? doc.body.scrollHeight : 0,
      );
      frame.style.height = (h + 24) + 'px';
    } catch { /* 跨域忽略 */ }
  };
  adjust();
  setTimeout(adjust, 300);
}

// ── Markdown to HTML converter ────────────────────────────────────────────

function markdownToHtml(md: string): string {
  const t = theme.value;
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
    const langLabel = lang ? ` class="language-${lang}"` : '';
    parts.push(`<pre style="background:${t.codeBlockBg};color:${t.codeBlockColor};border:${t.codeBlockBorder};padding:10px 14px;border-radius:6px;overflow-x:auto;margin:8px 0;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:0.85rem;line-height:1.5"><code${langLabel}>${code}</code></pre>`);
    lastIdx = m.index + m[0].length;
  }
  if (lastIdx < md.length) {
    parts.push(renderInlineHtml(md.slice(lastIdx)));
  }
  return parts.join('\n');
}

function renderInlineHtml(text: string): string {
  const t = theme.value;
  let html = escapePreservingHtml(text);
  // Horizontal rule
  html = html.replace(/^---+\s*$/gm, `<hr style="border:none;border-top:${t.hr};margin:12px 0">`);
  // Headings
  const hc = t.heading.color;
  html = html.replace(/^######\s+(.+)$/gm, `<div style="font-size:0.9rem;font-weight:700;margin:4px 0;color:${hc}">$1</div>`);
  html = html.replace(/^#####\s+(.+)$/gm, `<div style="font-size:0.95rem;font-weight:700;margin:4px 0;color:${hc}">$1</div>`);
  html = html.replace(/^####\s+(.+)$/gm, `<div style="font-size:1.05rem;font-weight:700;margin:6px 0;color:${hc}">$1</div>`);
  html = html.replace(/^###\s+(.+)$/gm, `<div style="font-size:1.18rem;font-weight:700;margin:6px 0;color:${hc}">$1</div>`);
  html = html.replace(/^##\s+(.+)$/gm, `<div style="font-size:1.35rem;font-weight:700;margin:8px 0;color:${hc};border-bottom:${t.hr};padding-bottom:4px">$1</div>`);
  html = html.replace(/^#\s+(.+)$/gm, `<div style="font-size:1.6rem;font-weight:700;margin:12px 0 6px;color:${hc};border-bottom:${t.hr};padding-bottom:6px">$1</div>`);
  // Tables
  html = renderTableHtml(html);
  // Blockquotes
  html = html.replace(/^&gt;\s?(.+)$/gm, `<blockquote style="margin:8px 0;padding:6px 12px;border-left:3px solid ${t.quoteBorder};background:${t.quoteBg};color:${t.quoteColor};font-style:italic">$1</blockquote>`);
  // Unordered list
  html = html.replace(/^[\s]*[-*]\s+(.+)$/gm, '<li style="margin:2px 0">$1</li>');
  // Ordered list
  html = html.replace(/^[\s]*(\d+)\.\s+(.+)$/gm, '<li style="margin:2px 0">$2</li>');
  // Wrap consecutive <li> in <ul>
  html = html.replace(/((?:<li[^>]*>.*?<\/li>\s*)+)/g, '<ul style="margin:6px 0;padding-left:22px">$1</ul>');
  // Bold & italic
  html = html.replace(/\*\*([^*]+)\*\*/g, `<strong style="color:${t.strongColor}">$1</strong>`);
  html = html.replace(/\*([^*]+)\*/g, `<em style="color:${t.emColor}">$1</em>`);
  // Inline code
  html = html.replace(/`([^`]+)`/g, `<code style="background:${t.inlineCodeBg};color:${t.inlineCodeColor};padding:1px 5px;border-radius:4px;font-family:ui-monospace,monospace;font-size:0.88em">$1</code>`);
  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, `<a href="$2" target="_blank" rel="noreferrer" style="color:${t.linkColor};text-decoration:none">$1</a>`);
  // Paragraphs:行首(忽略缩进)以 HTML 标签开头的行不包 <div>,避免破坏嵌入的 HTML 结构
  // (escapePreservingHtml 后,真标签是字面 <tag>,纯文本的 < 已转义为 &lt;,故此处只命中真标签)
  html = html.replace(/^(?!\s*<[a-zA-Z!\/])(.+)$/gm, '<div style="margin:6px 0">$1</div>');
  return html;
}

function renderTableHtml(html: string): string {
  const t = theme.value;
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
      let table = `<div style="overflow-x:auto;margin:8px 0"><table style="border-collapse:collapse;width:100%;font-size:0.88rem">`;
      table += '<thead><tr>' + headerCells.map(h => `<th style="border:1px solid ${t.tableBorder};padding:6px 10px;background:${t.thBg};text-align:left;font-weight:600;color:${t.thColor}">${h}</th>`).join('') + '</tr></thead>';
      table += '<tbody>' + rows.map(row => '<tr>' + row.map(c => `<td style="border:1px solid ${t.tableBorder};padding:6px 10px;color:${t.tdColor}">${c}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>';
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

// 转义纯文本,但保留 HTML 标签/注释/doctype/CDATA 原样透传,
// 使 skill 内容里嵌入的 HTML 能正常渲染而不是显示成文字
// (i 标志让 <!doctype> 大小写都保留,兼容 <!DOCTYPE html> 与 <!doctype html>)
function escapePreservingHtml(text: string): string {
  const tagRe = /(<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<!doctype[^>]*>|<\/?[a-zA-Z][^>]*>)/gi;
  let out = '';
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = tagRe.exec(text)) !== null) {
    if (m.index > last) out += escHtml(text.slice(last, m.index));
    out += m[0];
    last = m.index + m[0].length;
  }
  if (last < text.length) out += escHtml(text.slice(last));
  return out;
}
</script>

<style scoped>
.html-doc-frame {
  width: 100%;
  min-height: 240px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
}
</style>
