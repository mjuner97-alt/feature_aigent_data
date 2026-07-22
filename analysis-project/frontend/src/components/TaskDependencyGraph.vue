<template>
  <div v-if="tasks.length === 0" :style="S.root">
    <div :style="S.title">🔗 Dependency Graph</div>
    <div :style="S.muted">无 Task，无可视化。</div>
  </div>
  <div v-else :style="S.root">
    <div :style="S.title">🔗 Dependency Graph</div>
    <div :style="S.scroll">
      <svg :width="totalWidth + padX * 2" :height="height" style="display:block">
        <!-- Edges -->
        <g v-for="(e, i) in edges" :key="'edge-' + i">
          <path :d="e.path" fill="none" stroke="#cbd5e1" stroke-width="1.5" stroke-dasharray="3 3" />
          <circle :cx="e.x2" :cy="e.y2" r="2.5" fill="#64748b" />
        </g>
        <!-- Nodes -->
        <g v-for="(t, i) in tasks" :key="'node-' + (t.id ?? i)">
          <rect :x="xOf(i)" :y="yOf" :width="nodeW" :height="nodeH" rx="6" fill="#f8fafc" :stroke="colorFor(t.state)" stroke-width="1.5" />
          <text :x="xOf(i) + 8" :y="yOf + 18" font-size="11" font-weight="600" fill="#0f172a" font-family="system-ui, sans-serif">{{ shortSubject(t.subject) }}</text>
          <text :x="xOf(i) + 8" :y="yOf + 34" font-size="9" :fill="colorFor(t.state)" font-family="ui-monospace, monospace" font-weight="700">{{ (t.state ?? 'UNKNOWN').toUpperCase() }}</text>
        </g>
      </svg>
    </div>
    <div v-if="edges.length > 0" :style="S.legend">
      <span :style="{ color: '#94a3b8', marginRight: 8 }">→</span>
      {{ edges.length }} 条依赖关系（blocks）
    </div>
  </div>
</template>

<script setup lang="ts">
import type { TaskState } from '../types/sessionState';

const props = defineProps<{
  tasks: TaskState[];
}>();

const nodeW = 110;
const nodeH = 44;
const gap = 30;
const height = 80;
const padX = 12;

const STATE_COLORS: Record<string, string> = {
  PENDING: '#94a3b8',
  IN_PROGRESS: '#3b82f6',
  COMPLETED: '#10b981',
  FAILED: '#ef4444',
};

function colorFor(state: string | undefined) {
  return STATE_COLORS[(state ?? '').toUpperCase()] ?? '#94a3b8';
}

function shortSubject(s: string | undefined): string {
  if (!s) return '(no subject)';
  return s.length > 14 ? s.slice(0, 12) + '…' : s;
}

const yOf = 30;

function xOf(i: number): number {
  return padX + i * (nodeW + gap);
}

// Build edges
const idIndex = new Map<string, number>();
props.tasks.forEach((t, i) => {
  if (t.id) idIndex.set(t.id, i);
});

const edges = props.tasks.flatMap((t, i) => {
  if (!t.blocks) return [];
  return t.blocks
    .map(blocked => {
      const target = idIndex.get(blocked);
      if (target === undefined || target === i) return null;
      const x1 = xOf(i) + nodeW;
      const y1 = yOf + nodeH / 2;
      const x2 = xOf(target);
      const y2 = yOf + nodeH / 2;
      const midX = (x1 + x2) / 2;
      const midY = Math.min(y1, y2) - 14;
      const path = `M ${x1} ${y1} Q ${midX} ${midY} ${x2} ${y2}`;
      return { x2, y2, path };
    })
    .filter(Boolean) as { x2: number; y2: number; path: string }[];
});

const totalWidth = props.tasks.length * nodeW + (props.tasks.length - 1) * gap;

const S = {
  root: { padding: '14px 18px', borderBottom: '1px solid #f1f5f9' },
  title: { fontSize: '0.82rem', fontWeight: 700, color: '#475569', letterSpacing: '0.04em', textTransform: 'uppercase' as const, marginBottom: 10 },
  scroll: { overflowX: 'auto', paddingBottom: 6 },
  muted: { fontSize: '0.78rem', color: '#94a3b8', lineHeight: 1.6 },
  legend: { marginTop: 8, fontSize: '0.72rem', color: '#64748b', display: 'flex', alignItems: 'center' },
};
</script>
