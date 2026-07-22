<template>
  <div :style="S.root">
    <div :style="S.title">🔄 Agent State Machine</div>
    <div :style="S.grid">
      <div
        v-for="c in cards"
        :key="c.label"
        :style="{
          ...S.card,
          borderColor: c.color,
          boxShadow: c.pulse ? `0 0 0 2px ${c.color}55` : 'none',
        }"
        :class="c.pulse ? 'pulse-border' : ''"
      >
        <div :style="{ ...S.cardLabel, color: c.color }">{{ c.label }}</div>
        <div :style="{ ...S.cardValue, color: c.color }">{{ c.value }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { SessionStateResponse, SubagentPlanState } from '../types/sessionState';

const props = defineProps<{
  state: SessionStateResponse;
  subagentPlans?: Record<string, SubagentPlanState>;
}>();

const cards = computed(() => {
  const plans = props.subagentPlans ? Object.values(props.subagentPlans) : [];
  const activePlan = plans.find(p => p.planActive);
  const planModeValue = plans.length === 0
    ? '无 Plan'
    : activePlan
      ? `${activePlan.agentName}: PLAN`
      : plans.map(p => p.agentName).join(', ') + ': BUILD';
  const planModeColor = plans.length === 0 ? '#94a3b8' : activePlan ? '#10b981' : '#eab308';

  return [
    {
      label: 'PlanMode',
      value: planModeValue,
      color: planModeColor,
      pulse: false,
    },
    {
      label: 'TaskList',
      value: `${props.state.tasks.length} task${props.state.tasks.length === 1 ? '' : 's'}`,
      color: props.state.tasks.length > 0 ? '#3b82f6' : '#94a3b8',
      pulse: false,
    },
    {
      label: 'Permission',
      value: props.state.permission.mode,
      color: '#6366f1',
      pulse: false,
    },
    {
      label: 'InterruptControl',
      value: props.state.interruptControl.flag ? 'FLAG=true' : 'flag=false',
      color: props.state.interruptControl.flag ? '#ef4444' : '#94a3b8',
      pulse: props.state.interruptControl.flag,
    },
  ];
});

const S = {
  root: { padding: '14px 18px', borderBottom: '1px solid #f1f5f9' },
  title: { fontSize: '0.82rem', fontWeight: 700, color: '#475569', letterSpacing: '0.04em', marginBottom: 10, textTransform: 'uppercase' as const },
  grid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 },
  card: { padding: '10px 12px', borderRadius: 8, background: '#f8fafc', border: '1px solid #e2e8f0' },
  cardLabel: { fontSize: '0.7rem', fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase' as const, marginBottom: 4 },
  cardValue: { fontSize: '0.9rem', fontWeight: 700, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
};
</script>

<style scoped>
@keyframes pulse-border {
  0%, 100% { box-shadow: 0 0 0 2px #ef444455; }
  50% { box-shadow: 0 0 0 4px #ef444433; }
}
.pulse-border {
  animation: pulse-border 1.2s ease-in-out infinite;
}
</style>
