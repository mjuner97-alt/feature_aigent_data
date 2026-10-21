<template>
  <div :style="rootStyle">
    <button v-if="hasHtmlContent" class="html-expand-button" type="button" aria-label="放大 HTML 内容" @click="expanded = true">⛶ 放大</button>
    <!-- 完整 HTML 文档(<!doctype>/<html>):iframe srcdoc 隔离渲染,自带样式与脚本不污染外层 -->
    <iframe
      v-if="isFullHtmlDoc"
      :srcdoc="renderText"
      class="html-doc-frame"
      sandbox="allow-scripts allow-same-origin"
      @load="onFrameLoad"
    ></iframe>
    <!-- Markdown/HTML 片段 + echarts 片段:分段渲染,html 片段 v-html(isolate 模式进沙箱 iframe),echarts 片段由子组件渲染图表 -->
    <template v-else>
      <template v-for="(seg, i) in segments" :key="i">
        <!-- isolate:html 片段进沙箱 iframe,样式/脚本与外层完全隔离 -->
        <iframe
          v-if="seg.type === 'html' && isolate"
          :srcdoc="wrapFragmentDoc(seg.html)"
          class="fragment-frame"
          sandbox="allow-scripts allow-same-origin"
          @load="onFrameLoad"
        ></iframe>
        <div v-else-if="seg.type === 'html'" v-html="seg.html"></div>
        <EChartsBlock v-else :option="seg.option" :theme="props.theme" />
      </template>
    </template>
  </div>
  <Teleport to="body">
    <div v-if="expanded" class="html-overlay" role="dialog" aria-modal="true" aria-label="放大 HTML 内容" @mousedown.self="expanded = false">
      <div class="html-modal">
        <button class="html-close-button" type="button" aria-label="关闭 HTML 放大视图" @click="expanded = false">×</button>
        <iframe v-if="isFullHtmlDoc" :srcdoc="renderText" class="html-doc-frame html-doc-frame-expanded" sandbox="allow-scripts allow-same-origin" @load="onFrameLoad"></iframe>
        <div v-else class="html-fragment-expanded">
          <template v-for="(seg, i) in segments" :key="i">
            <iframe
              v-if="seg.type === 'html' && isolate"
              :srcdoc="wrapFragmentDoc(seg.html)"
              class="fragment-frame"
              sandbox="allow-scripts allow-same-origin"
              @load="onFrameLoad"
            ></iframe>
            <div v-else-if="seg.type === 'html'" v-html="seg.html"></div>
            <EChartsBlock v-else :option="seg.option" theme="light" />
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import EChartsBlock from './EChartsBlock.vue';

const props = withDefaults(defineProps<{
  text: string;
  theme?: 'light' | 'dark';
  /** isolate=true:html 片段进沙箱 iframe 渲染,任意内容都不影响外层页面(供试运行等场景使用) */
  isolate?: boolean;
}>(), {
  theme: 'light',
});
const expanded = ref(false);
function onKeyDown(e: KeyboardEvent) { if (e.key === 'Escape') expanded.value = false; }
onMounted(() => document.addEventListener('keydown', onKeyDown));
onUnmounted(() => {
  document.removeEventListener('keydown', onKeyDown);
  if (throttleTimer) clearTimeout(throttleTimer);
});

// ── Theme presets ──────────────────────────────────────────────────────────

const LIGHT = {
  echartsBg: '#ffffff',
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
  echartsBg: '#0f172a',
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

// 根节点样式 + echarts 容器背景 CSS 变量(沿 DOM 树继承进 EChartsBlock)
const rootStyle = computed(() => ({ ...theme.value.root, '--echarts-bg': theme.value.echartsBg }));

// ── isolate 模式流式节流 ──────────────────────────────────────────────────
// srcdoc 内容基于节流副本 isolatedText:双沿节流(首 chunk 立即显示,之后每 400ms 最多
// reload 一次 iframe),trailing 定时器回调实时读 props.text,保证流结束后 ≤400ms 内
// 必然 flush 最终完整内容。非 isolate 模式不走节流路径,行为与原来完全一致。
const ISOLATE_THROTTLE_MS = 400;
const isolatedText = ref(props.text);
let throttleTimer: ReturnType<typeof setTimeout> | null = null;
let lastFlush = 0;

watch(() => props.text, (v) => {
  if (!props.isolate) return;
  const now = Date.now();
  if (now - lastFlush >= ISOLATE_THROTTLE_MS) {
    // leading:窗口外立即刷,并取消遗留的 trailing 定时器
    if (throttleTimer) { clearTimeout(throttleTimer); throttleTimer = null; }
    lastFlush = now;
    isolatedText.value = v;
  } else if (!throttleTimer) {
    // trailing:窗口内只排一次定时器,触发时读 props.text 最新值
    throttleTimer = setTimeout(() => {
      throttleTimer = null;
      lastFlush = Date.now();
      isolatedText.value = props.text;
    }, ISOLATE_THROTTLE_MS - (now - lastFlush));
  }
});

// isolate 模式下渲染决策统一基于节流副本;非 isolate 保持实时(props.text)
const renderText = computed(() => (props.isolate ? isolatedText.value : props.text));

// 完整 HTML 文档检测:先剥掉 BOM / 前导空白 / 注释 / <?xml?> 声明,再判断起点。
// 兼容"生成工具在 <!DOCTYPE html> 前塞了注释或 xml 声明"这类内容,避免漏检掉进 v-html 被拆碎。
const isFullHtmlDoc = computed(() => {
  const stripped = renderText.value
    .replace(/^(?:\s|<!--[\s\S]*?-->|<\?[\s\S]*?\?>)*/, '')
    .trimStart()
    .slice(0, 200)
    .toLowerCase();
  return stripped.startsWith('<!doctype') || stripped.startsWith('<html');
});
const hasHtmlContent = computed(() => isFullHtmlDoc.value || /<\/?[a-z][^>]*>/i.test(renderText.value));

// ── echarts 片段切分 ──────────────────────────────────────────────────────
// 4 种形态 = 闭合标签 / 流式未闭合标签(到文末) / 闭合围栏 / 流式未闭合围栏(到文末)。
// echarts 区域在进入围栏循环/escapePreservingHtml 之前整体切出,
// 其中的 option JSON 永不经过 HTML 转义管线,由 EChartsBlock 子组件单独渲染。
// 模块级全局正则,使用前必须重置 lastIndex。
const ECHARTS_RE = /<echarts>([\s\S]*?)<\/echarts>|<echarts>([\s\S]*)$|```echarts[ \t]*\n([\s\S]*?)```|```echarts[ \t]*\n([\s\S]*)$/gi;

type Segment = { type: 'html'; html: string } | { type: 'echarts'; option: string };

function splitSegments(md: string): Segment[] {
  const segs: Segment[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  ECHARTS_RE.lastIndex = 0;
  while ((m = ECHARTS_RE.exec(md)) !== null) {
    if (m.index > last) segs.push({ type: 'html', html: renderMarkdownSegment(md.slice(last, m.index)) });
    segs.push({ type: 'echarts', option: (m[1] ?? m[2] ?? m[3] ?? m[4] ?? '').trim() });
    last = m.index + m[0].length;
  }
  if (last < md.length) segs.push({ type: 'html', html: renderMarkdownSegment(md.slice(last)) });
  return segs;
}

// Markdown / HTML 片段 + echarts 片段列表(html 片段已转好 HTML,echarts 片段为原始 option 文本)
const segments = computed<Segment[]>(() => splitSegments(renderText.value));

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

// isolate 模式:把 html 片段包成完整文档放进沙箱 iframe,样式/脚本只作用于 iframe 内部,
// 无论如何都不会污染外层页面。背景透明,由外层容器(rootStyle)透出主题底色。
function wrapFragmentDoc(html: string): string {
  const color = props.theme === 'dark' ? '#e2e8f0' : '#1e293b';
  return [
    '<!DOCTYPE html><html><head><meta charset="utf-8"><base target="_blank"><style>',
    'html,body{margin:0;padding:0;background:transparent;}',
    `body{color:${color};font-size:14px;line-height:1.6;word-break:break-word;overflow-wrap:anywhere;`,
    'font-family:system-ui,-apple-system,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;}',
    // 兜底:iframe 内任何元素都不允许把文档撑破
    'img,video,canvas,svg,table,iframe{max-width:100%!important;height:auto;}',
    'pre{white-space:pre-wrap;word-break:break-all;overflow-x:auto;}',
    '</style></head><body>', html, '</body></html>',
  ].join('');
}

// ── Markdown to HTML converter(单段渲染,不含 echarts 区域) ─────────────────

function renderMarkdownSegment(md: string): string {
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
    // HTML 围栏作为可渲染 HTML 片段处理，便于报告中的表格/卡片支持放大查看。
    if (/^html?$/i.test(lang)) {
      parts.push(`<div class="rendered-html-block">${m[2]}</div>`);
      lastIdx = m.index + m[0].length;
      continue;
    }
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
  // 只含空白/全角空格的行归一化为空行,避免被下方段落包装成正文的空 div(margin 撑出可见空白)
  text = text.replace(/^[ \t　 ]+$/gm, '');
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
.html-expand-button {
  float: right;
  margin: 4px 0 6px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  color: #334155;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 12px;
}
.html-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(15, 23, 42, .58);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.html-modal {
  position: relative;
  width: min(1200px, 96vw);
  height: min(900px, 94vh);
  overflow: auto;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 20px 60px rgba(15, 23, 42, .35);
  padding: 16px;
}
.html-close-button {
  position: absolute;
  top: 8px;
  right: 10px;
  z-index: 2;
  width: 30px;
  height: 30px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
}
.html-doc-frame-expanded { height: 100%; border: 0; }
.html-fragment-expanded { min-height: 100%; overflow: auto; background: #fff; color: #111827; }
/* isolate 模式 html 片段沙箱 iframe:无框透明,视觉上与容器融为一体,高度由 onFrameLoad 自适应 */
.fragment-frame {
  width: 100%;
  min-height: 40px;
  border: 0;
  background: transparent;
  display: block;
}
</style>
