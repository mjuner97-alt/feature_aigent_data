<template>
  <div v-if="events.length === 0 && !active" :style="S.root">
    <div :style="S.header"><span>⚙️ 智能体活动</span></div>
    <div :style="S.empty">发送消息后，这里会实时显示<br />智能体的工具调用、派单、结果返回。</div>
  </div>
  <div v-else :style="S.root">
    <div :style="S.header">
      <span>⚙️ 智能体活动</span>
      <span :style="S.count">{{ events.length }}</span>
    </div>
    <div ref="scrollRef" :style="S.list">
      <div
        v-for="(e, i) in events"
        :key="i"
        :style="rowStyle(e)"
      >
        <div :style="{ display: 'flex', gap: 8, alignItems: 'flex-start' }">
          <span :style="S.ts">{{ timestamp() }}</span>
          <span :style="messageStyle(e)">{{ e.message }}</span>
        </div>
        <div v-if="hasIo(e)" :style="S.ioWrap">
          <details v-if="e.toolInput?.length" :style="S.ioDetails">
            <summary :style="S.ioSummary">📥 入参 ({{ e.toolInput.length }} 字符)</summary>
            <pre :style="S.ioPre">{{ e.toolInput }}</pre>
          </details>
          <details v-if="e.toolOutput?.length" :style="S.ioDetails">
            <summary :style="S.ioSummary">📤 出参 ({{ e.toolOutput.length }} 字符)</summary>
            <pre :style="S.ioPre">{{ e.toolOutput }}</pre>
          </details>
        </div>
      </div>
      <div v-if="active" :style="S.runningHint">
        <span :style="S.runningDot" class="pulse-opacity"></span>
        <span :style="{ color: '#64748b', fontSize: '0.74rem' }">运行中…</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import type { ProcessEvent } from '../api/chat';

const props = defineProps<{
  events: ProcessEvent[];
  active: boolean;
}>();

const scrollRef = ref<HTMLDivElement | null>(null);

watch(
  () => props.events.length,
  async () => {
    await nextTick();
    scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' });
  },
);

function timestamp(): string {
  return new Date().toLocaleTimeString('zh-CN', {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function isFailure(e: ProcessEvent): boolean {
  return e.eventType === 'tool_result_end'
    && e.toolCallState != null
    && e.toolCallState !== 'SUCCESS'
    && e.toolCallState !== 'OK';
}

function rowStyle(e: ProcessEvent) {
  const isSub = e.source != null;
  const isFail = isFailure(e);
  return {
    ...S.row,
    ...(isSub ? S.rowSubagent : {}),
    ...(isFail ? S.rowFailure : {}),
    flexDirection: 'column' as const,
    alignItems: 'stretch' as const,
  };
}

function messageStyle(e: ProcessEvent) {
  const isSub = e.source != null;
  const isFail = isFailure(e);
  return {
    ...S.message,
    color: isFail ? '#dc2626' : isSub ? '#0f172a' : '#1e293b',
  };
}

function hasIo(e: ProcessEvent): boolean {
  return (e.toolInput?.length ?? 0) > 0 || (e.toolOutput?.length ?? 0) > 0;
}

const S = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#ffffff' },
  header: {
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    padding: '12px 16px', borderBottom: '1px solid #f1f5f9', fontSize: '0.82rem',
    fontWeight: 700, color: '#475569', letterSpacing: '0.04em', textTransform: 'uppercase' as const, flexShrink: 0,
  },
  count: { fontSize: '0.7rem', fontWeight: 700, color: '#475569', background: '#e0e7ff', borderRadius: 10, padding: '2px 8px' },
  list: { flex: 1, overflowY: 'auto', padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 4 },
  row: { display: 'flex', gap: 8, padding: '6px 8px', borderRadius: 6, background: '#f8fafc', border: '1px solid #e2e8f0', alignItems: 'flex-start', fontSize: '0.78rem', lineHeight: 1.5 },
  rowSubagent: { marginLeft: 16, borderLeft: '3px solid #6366f1', background: '#f5f3ff' },
  rowFailure: { background: '#fef2f2', border: '1px solid #fecaca' },
  ts: { color: '#94a3b8', fontFamily: 'ui-monospace, monospace', fontSize: '0.7rem', flexShrink: 0, paddingTop: 1 },
  message: { flex: 1, wordBreak: 'break-word' },
  empty: { padding: '32px 16px', textAlign: 'center', color: '#94a3b8', fontSize: '0.82rem', lineHeight: 1.7 },
  runningHint: { display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', fontStyle: 'italic' },
  runningDot: { width: 8, height: 8, borderRadius: '50%', background: '#3b82f6', display: 'inline-block' },
  ioWrap: { marginTop: 4, display: 'flex', flexDirection: 'column', gap: 4 },
  ioDetails: { background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 4, padding: '2px 6px' },
  ioSummary: { cursor: 'pointer', fontSize: '0.72rem', color: '#475569', fontWeight: 600, userSelect: 'none', padding: '2px 0' },
  ioPre: {
    margin: '4px 0 2px 0', padding: '6px 8px', background: '#0f172a', color: '#e2e8f0', borderRadius: 4,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.72rem', lineHeight: 1.4,
    whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 280, overflowY: 'auto',
  },
};
</script>

<style scoped>
@keyframes fade-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes pulse-opacity {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
.pulse-opacity {
  animation: pulse-opacity 1.2s ease-in-out infinite;
}
</style>
