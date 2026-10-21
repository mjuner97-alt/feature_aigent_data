<script setup lang="ts">
/**
 * ECharts 单图渲染子组件（由 Markdown.vue 的 echarts 片段驱动）
 *
 *  - option 为 JSON 字符串：parse 成功渲染图表，失败（含流式期间 JSON 未闭合）降级展示原始文本
 *  - echarts 懒加载（动态 import 拆独立 chunk），纯文本/表格消息零加载成本
 *  - 实例生命周期由组件托管：卸载 dispose、option 变化时复用/回退
 */
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

const props = defineProps<{
  option: string;
  theme?: 'light' | 'dark';
}>();

// ── echarts 懒加载：模块级缓存 promise，多图表共享只加载一次 ──
let echartsMod: Promise<typeof import('echarts')> | null = null;
function loadEcharts() { return (echartsMod ??= import('echarts')); }

const elRef = ref<HTMLElement | null>(null);
const ok = ref(false); // false = parse 失败/JSON 未闭合 → 降级展示原始文本
let chart: { dispose(): void; resize(): void; setOption(o: unknown): void } | null = null;

// 仅做尾逗号容错的严格 JSON 解析，不做 eval（安全底线）
function parseOption(raw: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(raw.trim().replace(/,\s*([}\]])/g, '$1'));
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

watch(() => props.option, async (raw) => {
  const opt = parseOption(raw);
  ok.value = opt != null;
  if (!opt) {
    // 流式未闭合/非法 JSON：降级为文本展示；复用实例收到新的不完整 option 时先销毁旧图
    chart?.dispose();
    chart = null;
    return;
  }
  await nextTick(); // 等 v-if 把图表容器渲染出来
  if (!ok.value || !elRef.value) return;
  const echarts = await loadEcharts();
  if (!elRef.value) return; // await 期间组件可能已更新/卸载
  const dark = props.theme === 'dark';
  if (!chart) chart = echarts.init(elRef.value, dark ? 'dark' : undefined);
  if (dark) opt.backgroundColor = 'transparent'; // 让容器 CSS 背景透出，避免 dark 主题自带色块
  chart.setOption(opt);
}, { immediate: true, flush: 'post' });

function onWinResize() { chart?.resize(); }
onMounted(() => window.addEventListener('resize', onWinResize));
onUnmounted(() => {
  window.removeEventListener('resize', onWinResize);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <!-- parse 成功：图表容器；失败/流式未闭合：代码风格降级展示原始 JSON -->
  <div v-if="ok" ref="elRef" class="echarts-block"></div>
  <pre v-else class="echarts-fallback">{{ option }}</pre>
</template>

<style scoped>
.echarts-block {
  height: 320px;
  width: 100%;
  margin: 8px 0;
  border-radius: 6px;
  background: var(--echarts-bg, #fff);
  overflow: hidden;
}
.echarts-fallback {
  margin: 8px 0;
  padding: 10px 14px;
  border-radius: 6px;
  background: var(--echarts-bg, #fff);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  overflow-x: auto;
}
</style>
