<template>
  <div :style="grid">
    <!-- Critic effectiveness -->
    <div :style="cardStyle">
      <div :style="titleStyle">Critic 挑战类型命中率</div>
      <table v-if="Object.keys(criticStats).length > 0" :style="tableStyle">
        <thead>
          <tr>
            <th :style="thStyle">挑战类型</th>
            <th :style="thRight">命中率</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(rate, type) in criticStats" :key="type">
            <td :style="tdStyle">{{ type }}</td>
            <td :style="tdRight">
              <span :style="rateBadge(rate)">{{ (rate * 100).toFixed(0) }}%</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else :style="emptyStyle">暂无 Critic 数据</div>
    </div>

    <!-- Active experiments -->
    <div :style="cardStyle">
      <div :style="titleStyle">活跃 A/B 实验</div>
      <table v-if="experiments.length > 0" :style="tableStyle">
        <thead>
          <tr>
            <th :style="thStyle">实验名称</th>
            <th :style="thStyle">流量</th>
            <th :style="thStyle">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in experiments" :key="e.id">
            <td :style="tdStyle">{{ e.name }}</td>
            <td :style="tdStyle">{{ e.trafficPct ?? '--' }}%</td>
            <td :style="tdStyle">
              <span :style="statusBadge(e.status)">{{ e.status }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else :style="emptyStyle">无活跃实验</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { RuleExperiment } from '../api/dashboard';

defineProps<{
  criticStats: Record<string, number>;
  experiments: RuleExperiment[];
}>();

const grid: any = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 };
const cardStyle: any = { background: '#ffffff', borderRadius: 10, padding: '16px 20px', boxShadow: '0 1px 3px rgba(0,0,0,0.06)' };
const titleStyle: any = { fontSize: 14, fontWeight: 600, color: '#0f172a', marginBottom: 12 };
const tableStyle: any = { width: '100%', borderCollapse: 'collapse', fontSize: 13 };
const thStyle: any = { textAlign: 'left', padding: '8px 6px', borderBottom: '2px solid #e2e8f0', color: '#64748b', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' };
const thRight: any = { ...thStyle, textAlign: 'right' };
const tdStyle: any = { padding: '6px 6px', borderBottom: '1px solid #f1f5f9' };
const tdRight: any = { ...tdStyle, textAlign: 'right' };
const emptyStyle: any = { color: '#94a3b8', fontSize: 13, textAlign: 'center', padding: '24px 0' };
const rateBadge = (rate: number): any => ({
  fontSize: 12, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
  background: rate > 0.5 ? '#fef2f2' : rate > 0.2 ? '#fffbeb' : '#f0fdf4',
  color: rate > 0.5 ? '#ef4444' : rate > 0.2 ? '#f59e0b' : '#10b981',
});
const statusBadge = (status: string): any => ({
  fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4,
  background: status === 'running' ? '#e0e7ff' : status === 'promoted' ? '#dcfce7' : '#f1f5f9',
  color: status === 'running' ? '#4338ca' : status === 'promoted' ? '#15803d' : '#64748b',
});
</script>
