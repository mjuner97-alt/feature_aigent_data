<template>
  <div class="waterfall-view">
    <div v-if="!spans.length || !conversation" class="empty-state">
      <el-empty description="暂无 Span 数据" :image-size="80" />
    </div>

    <template v-else>
      <!-- Column splitter overlay (drag to resize name column) -->
      <div class="col-splitter" :style="{ left: labelColWidth + 'px' }" @mousedown.stop="onColSplitterDown"></div>

      <!-- Header -->
      <div class="list-header">
        <div class="hdr-label" :style="{ width: labelColWidth + 'px' }">
          <span class="hdr-step">#</span>
          <span class="hdr-step-label">调用步骤</span>
        </div>
        <div class="hdr-cells">
          <div class="hdr-cell cell-status">状态</div>
          <div class="hdr-cell cell-duration">耗时</div>
          <div class="hdr-cell cell-tokens">Token In / Out</div>
        </div>
      </div>

      <!-- Span rows: 从上到下按调用顺序排列，#1 是最早执行的 -->
      <div
        v-for="(span, idx) in sortedSpans"
        :key="span.spanId"
        class="span-row"
        :class="{
          'error-row': span.status === 'ERROR',
          selected: span.spanId === selectedSpanId,
          'llm-row': span.spanType === 'LLM',
          'thinking-row': span.spanType === 'LLM' && span.name === 'thinking',
        }"
        @click="$emit('select-span', span.spanId)"
      >
        <div class="row-label" :style="{ width: labelColWidth + 'px' }">
          <span class="step-num">{{ idx + 1 }}</span>
          <span class="row-indent" :style="{ width: span.depth * 14 + 'px' }"></span>
          <span class="row-type-badge" :style="{ background: spanColor(span.spanType) }">
            {{ spanTypeLabel(span.spanType) }}
          </span>
          <span class="row-name" :title="span.name">
            <span v-if="span.spanType === 'LLM' && span.name === 'thinking'" class="thinking-icon">💭</span>
            {{ span.name }}
          </span>
        </div>
        <div class="row-cells">
          <div class="cell cell-status">
            <span class="status-dot" :class="statusClass(span.status)"></span>
          </div>
          <div class="cell cell-duration">
            <span class="dur-text" :class="{ 'dur-slow': isSlow(span) }">{{ formatDuration(span.durationMs) }}</span>
          </div>
          <div class="cell cell-tokens">
            <template v-if="span.tokenInput || span.tokenOutput">
              <span class="tok-in" :title="`输入 ${span.tokenInput ?? 0} tokens`">{{ formatTokens(span.tokenInput) }}</span>
              <span class="tok-sep">/</span>
              <span class="tok-out" :title="`输出 ${span.tokenOutput ?? 0} tokens`">{{ formatTokens(span.tokenOutput) }}</span>
            </template>
            <span v-else class="tok-none">-</span>
          </div>
        </div>
      </div>

      <!-- 底部提示：调用顺序说明 -->
      <div class="order-hint">
        <span class="hint-arrow">↑</span>
        <span>从上到下为调用执行顺序，#1 最先执行</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import dayjs from 'dayjs';
import type { TraceSpan, TraceConversation, SpanType, TraceStatus } from '../../types/trace';
import { SPAN_TYPE_COLORS, SPAN_TYPE_LABELS } from '../../types/trace';

const props = defineProps<{
  spans: TraceSpan[];
  conversation: TraceConversation | null;
  selectedSpanId?: string;
}>();

defineEmits<{
  (e: 'select-span', spanId: string): void;
}>();

// 名称列宽度，可拖拽调整
const labelColWidth = ref(180);
const colDragging = ref(false);

function onColSplitterDown() {
  colDragging.value = true;
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
}

function onColMouseMove(e: MouseEvent) {
  if (!colDragging.value) return;
  const view = document.querySelector('.waterfall-view') as HTMLElement | null;
  if (!view) return;
  const rect = view.getBoundingClientRect();
  const w = Math.min(rect.width * 0.7, Math.max(160, e.clientX - rect.left));
  labelColWidth.value = Math.round(w);
}

function onColMouseUp() {
  if (!colDragging.value) return;
  colDragging.value = false;
  document.body.style.cursor = '';
  document.body.style.userSelect = '';
}

onMounted(() => {
  window.addEventListener('mousemove', onColMouseMove);
  window.addEventListener('mouseup', onColMouseUp);
});

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onColMouseMove);
  window.removeEventListener('mouseup', onColMouseUp);
});

// 按开始时间升序排列：最早执行的排最前面（#1）
const sortedSpans = computed(() => {
  return [...props.spans].sort((a, b) => {
    const sa = dayjs(a.startTime).valueOf();
    const sb = dayjs(b.startTime).valueOf();
    if (sa !== sb) return sa - sb;
    return a.depth - b.depth;
  });
});

function spanColor(type: SpanType): string {
  return SPAN_TYPE_COLORS[type] ?? '#909399';
}

function spanTypeLabel(type: SpanType): string {
  return SPAN_TYPE_LABELS[type] ?? type;
}

function statusClass(status: TraceStatus): string {
  return `status-${status.toLowerCase()}`;
}

function isSlow(span: TraceSpan): boolean {
  return span.durationMs > 3000;
}

function formatDuration(ms: number): string {
  if (ms == null) return '-';
  if (ms < 1) return '<1ms';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatTokens(n: number | undefined): string {
  if (n == null || n === 0) return '0';
  if (n < 1000) return String(n);
  if (n < 1000000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1000000).toFixed(2)}M`;
}
</script>

<style scoped>
.waterfall-view {
  height: 100%;
  overflow-y: auto;
  background: #fff;
  font-size: 12px;
  position: relative;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

/* ---- Header ---- */
.list-header {
  display: flex;
  align-items: center;
  height: 34px;
  border-bottom: 2px solid #e2e8f0;
  position: sticky;
  top: 0;
  background: #f8fafc;
  z-index: 2;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.hdr-label {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 8px;
  line-height: 34px;
  border-right: 1px solid #e2e8f0;
  gap: 4px;
}

.hdr-step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  font-size: 11px;
  color: #94a3b8;
}

.hdr-step-label {
  flex: 1;
}

.hdr-cells {
  flex: 1;
  display: flex;
  align-items: center;
  height: 100%;
}

.hdr-cell {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 8px;
  height: 100%;
}

.hdr-cell.cell-status { width: 50px; justify-content: center; }
.hdr-cell.cell-duration { width: 60px; justify-content: center; }
.hdr-cell.cell-tokens { flex: 1; justify-content: center; min-width: 0; }

/* ---- Span rows ---- */
.span-row {
  display: flex;
  align-items: center;
  min-height: 38px;
  border-bottom: 1px solid #f1f5f9;
  cursor: pointer;
  transition: background-color 0.1s;
}

.span-row:hover {
  background: #f8fafc;
}

.span-row.selected {
  background: #eff6ff;
  box-shadow: inset 3px 0 0 #3b82f6;
}

.span-row.llm-row {
  background: #fffbeb;
}

.span-row.llm-row:hover {
  background: #fef3c7;
}

.span-row.llm-row.selected {
  background: #fef3c7;
  box-shadow: inset 3px 0 0 #f59e0b;
}

.span-row.thinking-row {
  background: #fdf4ff;
}

.span-row.thinking-row:hover {
  background: #fae8ff;
}

.span-row.thinking-row.selected {
  background: #fae8ff;
  box-shadow: inset 3px 0 0 #c026d3;
}

.thinking-icon {
  margin-right: 2px;
}

.span-row.error-row {
  background: #fef2f2;
}

.span-row.error-row:hover {
  background: #fee2e2;
}

/* ---- Row label (left column) ---- */
.row-label {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  height: 100%;
  padding-right: 8px;
  border-right: 1px solid #e2e8f0;
  overflow: hidden;
  min-height: 38px;
}

.step-num {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 20px;
  margin: 0 4px 0 2px;
  border-radius: 4px;
  background: #f1f5f9;
  font-size: 10px;
  font-weight: 700;
  color: #64748b;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
}

.row-indent {
  flex-shrink: 0;
}

.row-type-badge {
  flex-shrink: 0;
  display: inline-block;
  min-width: 36px;
  text-align: center;
  padding: 2px 5px;
  margin: 0 6px 0 0;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  line-height: 1.3;
  letter-spacing: 0.3px;
}

.row-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1e293b;
  font-size: 12px;
}

/* ---- Row cells ---- */
.row-cells {
  flex: 1;
  display: flex;
  align-items: center;
  height: 100%;
  min-width: 0;
  min-height: 38px;
}

.cell {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 8px;
  height: 100%;
}

.cell.cell-status { width: 50px; justify-content: center; }
.cell.cell-duration { width: 60px; justify-content: center; }
.cell.cell-tokens { flex: 1; justify-content: center; gap: 2px; min-width: 0; }

/* ---- Status dot ---- */
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.status-success { background: #22c55e; }
.status-dot.status-error { background: #ef4444; box-shadow: 0 0 0 3px rgba(239,68,68,0.2); }
.status-dot.status-timeout { background: #f59e0b; }
.status-dot.status-running { background: #3b82f6; animation: pulse 1.5s ease-in-out infinite; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ---- Duration ---- */
.dur-text {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  font-weight: 600;
  color: #475569;
}

.dur-slow {
  color: #ef4444;
}

/* ---- Tokens ---- */
.tok-in {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  font-weight: 600;
  color: #2563eb;
}

.tok-out {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  font-weight: 600;
  color: #059669;
}

.tok-sep {
  font-size: 10px;
  color: #cbd5e1;
  margin: 0 2px;
}

.tok-none {
  font-size: 11px;
  color: #cbd5e1;
}

/* ---- 底部顺序提示 ---- */
.order-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 0;
  font-size: 11px;
  color: #94a3b8;
  border-top: 1px solid #f1f5f9;
  background: #fafbfc;
}

.hint-arrow {
  font-size: 14px;
  color: #3b82f6;
}

/* ---- Column splitter ---- */
.col-splitter {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 8px;
  margin-left: -4px;
  cursor: col-resize;
  z-index: 5;
}

.col-splitter:hover,
.col-splitter:active {
  background: rgba(59, 130, 246, 0.15);
}
</style>
