<template>
  <div :style="grid">
    <div v-for="c in cards" :key="c.label" :style="cardStyle(c)">
      <div :style="labelStyle">{{ c.label }}</div>
      <div :style="valueStyle">{{ c.value }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  total: number;
  passRate: number;
  warnRate: number;
  failRate: number;
  fabricationRate: number;
  avgTrust: number;
}>();

const cards = computed(() => [
  { label: '总验证数', value: props.total, color: '#64748b' },
  { label: '通过率', value: fmt(props.passRate), color: '#10b981' },
  { label: '警告率', value: fmt(props.warnRate), color: '#f59e0b' },
  { label: '失败率', value: fmt(props.failRate), color: '#ef4444' },
  { label: '伪造率', value: fmt(props.fabricationRate), color: '#8b5cf6' },
  { label: '平均信任分', value: props.avgTrust.toFixed(1), color: '#3b82f6' },
]);

function fmt(r: number): string {
  return (r * 100).toFixed(1) + '%';
}

const grid: any = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 16 };
const labelStyle: any = { fontSize: 12, color: '#64748b', fontWeight: 500, marginBottom: 4 };
const valueStyle: any = { fontSize: 28, fontWeight: 700, lineHeight: 1.2 };

function cardStyle(c: { color: string }): any {
  return {
    background: '#ffffff', borderRadius: 10, padding: '16px 20px',
    border: `1px solid ${c.color}33`, borderLeft: `3px solid ${c.color}`,
    boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
  };
}
</script>
