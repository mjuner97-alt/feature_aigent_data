<template>
  <div :style="S.root">
    <div :style="S.header">
      <span :style="S.title">✅ Task List</span>
      <span :style="S.count">{{ tasks.length }}</span>
    </div>

    <div v-if="tasks.length === 0" :style="S.muted">无 Task。LLM 调用 todo_write 工具后会在此显示。</div>

    <template v-if="tasks.length > 0">
      <div :style="S.summaryRow">
        <span
          v-for="cfg in stateStyles"
          :key="cfg.key"
          v-show="counts[cfg.key] > 0"
          :style="{ ...S.summaryChip, color: cfg.color, borderColor: cfg.color + '55' }"
        >
          {{ cfg.icon }} {{ counts[cfg.key] }} {{ cfg.label }}
        </span>
      </div>
      <div :style="S.list">
        <div v-for="(t, i) in tasks" :key="t.id ?? i" :style="S.item">
          <span
            :style="{
              ...S.icon,
              color: styleFor(t.state).color,
            }"
            :class="styleFor(t.state).pulse ? 'pulse-opacity' : ''"
          >{{ styleFor(t.state).icon }}</span>
          <div :style="S.itemBody">
            <div :style="S.itemSubject">{{ t.subject || '(no subject)' }}</div>
            <div v-if="t.description" :style="S.itemDesc">{{ t.description }}</div>
            <div :style="S.itemMeta">
              <span :style="{ color: styleFor(t.state).color, fontWeight: 600 }">{{ (t.state ?? '').toUpperCase() }}</span>
              <span v-if="t.owner" :style="S.metaMuted">owner: {{ t.owner }}</span>
              <span v-if="t.createdAt" :style="S.metaMuted">{{ t.createdAt }}</span>
            </div>
          </div>
        </div>

        <!-- Subagent todo_write call counts -->
        <div
          v-for="[agent, count] in todoEntries"
          :key="agent"
          :style="{ ...S.item, background: '#eef2ff', borderColor: '#c7d2fe' }"
        >
          <span :style="{ ...S.icon, color: '#6366f1' }">▸</span>
          <div :style="S.itemBody">
            <div :style="S.itemSubject">
              <span :style="{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', color: '#4338ca' }">{{ agent }}</span>
              已跟踪 {{ count }} 次任务更新
            </div>
            <div :style="S.itemDesc">子智能体任务详情暂不可读（workspace 隔离）</div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { TaskState } from '../types/sessionState';

const props = defineProps<{
  tasks: TaskState[];
  subagentTodoWriteCounts?: Record<string, number>;
}>();

const stateStyles = [
  { key: 'PENDING', icon: '○', color: '#94a3b8', label: '待办' },
  { key: 'IN_PROGRESS', icon: '▸', color: '#3b82f6', label: '进行中', pulse: true },
  { key: 'COMPLETED', icon: '✓', color: '#10b981', label: '完成' },
  { key: 'FAILED', icon: '✗', color: '#ef4444', label: '失败' },
];

function styleFor(state: string | undefined): { icon: string; color: string; pulse?: boolean } {
  const found = stateStyles.find(s => s.key === (state ?? '').toUpperCase());
  return found ?? { icon: '?', color: '#94a3b8' };
}

const counts = computed(() => {
  const acc: Record<string, number> = {};
  for (const t of props.tasks) {
    const k = (t.state ?? 'UNKNOWN').toUpperCase();
    acc[k] = (acc[k] ?? 0) + 1;
  }
  return acc;
});

const todoEntries = computed(() => {
  if (!props.subagentTodoWriteCounts) return [];
  return Object.entries(props.subagentTodoWriteCounts);
});

const S = {
  root: { padding: '14px 18px', borderBottom: '1px solid #f1f5f9' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  title: { fontSize: '0.82rem', fontWeight: 700, color: '#475569', letterSpacing: '0.04em', textTransform: 'uppercase' as const },
  count: { fontSize: '0.72rem', fontWeight: 700, color: '#475569', background: '#e0e7ff', borderRadius: 10, padding: '2px 8px' },
  summaryRow: { display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 10 },
  summaryChip: { fontSize: '0.7rem', fontWeight: 600, background: '#f8fafc', border: '1px solid', borderRadius: 6, padding: '2px 6px' },
  list: { display: 'flex', flexDirection: 'column', gap: 6 },
  item: { display: 'flex', gap: 10, padding: '8px 10px', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8 },
  icon: { fontSize: '1rem', fontWeight: 700, flexShrink: 0, width: 18, textAlign: 'center', lineHeight: 1.4 },
  itemBody: { flex: 1, minWidth: 0 },
  itemSubject: { fontSize: '0.85rem', fontWeight: 600, color: '#0f172a', wordBreak: 'break-word' },
  itemDesc: { fontSize: '0.78rem', color: '#64748b', marginTop: 2, wordBreak: 'break-word', whiteSpace: 'pre-wrap' },
  itemMeta: { display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 4, fontSize: '0.7rem' },
  metaMuted: { color: '#94a3b8' },
  muted: { fontSize: '0.78rem', color: '#94a3b8', lineHeight: 1.6 },
};
</script>

<style scoped>
@keyframes pulse-opacity {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
.pulse-opacity {
  animation: pulse-opacity 1.2s ease-in-out infinite;
}
</style>
