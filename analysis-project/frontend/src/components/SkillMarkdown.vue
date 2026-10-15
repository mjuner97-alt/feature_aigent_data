<script setup lang="ts">
import { computed } from 'vue';
import Markdown from './Markdown.vue';
import { SkillMarkdownRenderer } from './SkillMarkdownRenderer';

const props = withDefaults(defineProps<{
  text: string;
  theme?: 'light' | 'dark';
}>(), { theme: 'light' });

const renderer = new SkillMarkdownRenderer();
const fullHtml = computed(() => renderer.isFullHtmlDocument(props.text));
const renderedHtml = computed(() => fullHtml.value ? '' : renderer.render(props.text));
</script>

<template>
  <Markdown v-if="fullHtml" :text="text" :theme="theme" />
  <div v-else class="skill-markdown" :class="{ 'skill-markdown-dark': theme === 'dark' }" v-html="renderedHtml"></div>
</template>

<style scoped>
.skill-markdown {
  --md-border: #e2e8f0;
  --md-muted-bg: #f1f5f9;
  --md-link: #6366f1;
  --md-inline-code: #be185d;
  font-size: 0.95rem;
  line-height: 1.7;
  overflow-wrap: anywhere;
  min-width: 0;
}
.skill-markdown-dark {
  --md-border: #334155;
  --md-muted-bg: #1e293b;
  --md-link: #60a5fa;
  --md-inline-code: #f472b6;
  color: #e2e8f0;
  background: #0f172a;
  padding: 14px 16px;
  border: 1px solid #1e293b;
  border-radius: 8px;
  overflow-x: auto;
}
.skill-markdown :deep(h1), .skill-markdown :deep(h2) {
  border-bottom: 1px solid var(--md-border);
  padding-bottom: 0.3em;
}
.skill-markdown :deep(h1) { font-size: 1.8em; }
.skill-markdown :deep(h2) { font-size: 1.5em; }
.skill-markdown :deep(h3) { font-size: 1.25em; }
.skill-markdown :deep(h4), .skill-markdown :deep(h5), .skill-markdown :deep(h6) { font-size: 1em; }
.skill-markdown :deep(p), .skill-markdown :deep(pre), .skill-markdown :deep(ul), .skill-markdown :deep(ol) { margin: 0.75em 0; }
.skill-markdown :deep(ul), .skill-markdown :deep(ol) { padding-left: 2em; }
.skill-markdown :deep(ul) { list-style-type: disc; }
.skill-markdown :deep(ol) { list-style-type: decimal; }
.skill-markdown :deep(li > ul), .skill-markdown :deep(li > ol) { margin: 0.25em 0; }
.skill-markdown :deep(li > input[type='checkbox']) { margin-right: 0.5em; }
.skill-markdown :deep(blockquote) {
  margin: 0.75em 0;
  padding: 0.2em 1em;
  border-left: 3px solid var(--md-link);
  background: var(--md-muted-bg);
}
.skill-markdown :deep(code) {
  color: var(--md-inline-code);
  background: var(--md-muted-bg);
  padding: 0.15em 0.35em;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.88em;
}
.skill-markdown :deep(pre) {
  overflow-x: auto;
  padding: 12px 16px;
  border: 1px solid #334155;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  overflow-wrap: normal;
}
.skill-markdown :deep(pre code) { padding: 0; color: inherit; background: transparent; }
.skill-markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; margin: 1em 0; }
.skill-markdown :deep(th), .skill-markdown :deep(td) { border: 1px solid var(--md-border); padding: 6px 10px; }
.skill-markdown :deep(th) { background: var(--md-muted-bg); }
.skill-markdown :deep(a) { color: var(--md-link); text-decoration: underline; }
.skill-markdown :deep(img) { max-width: 100%; height: auto; }
.skill-markdown :deep(hr) { border: 0; border-top: 1px solid var(--md-border); margin: 1em 0; }
</style>
