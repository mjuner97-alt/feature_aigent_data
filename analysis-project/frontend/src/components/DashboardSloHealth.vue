<template>
  <div :style="grid">
    <!-- Calibration state -->
    <div :style="cardStyle">
      <div :style="titleStyle">校准状态</div>
      <div :style="row">
        <span :style="labelStyle">通过阈值</span>
        <span :style="valStyle">{{ calibration?.passThreshold ?? '--' }}</span>
      </div>
      <div :style="row">
        <span :style="labelStyle">警告阈值</span>
        <span :style="valStyle">{{ calibration?.warnThreshold ?? '--' }}</span>
      </div>
      <div :style="row">
        <span :style="labelStyle">直接输出</span>
        <span :style="valStyle">{{ calibration?.directThreshold ?? '--' }}</span>
      </div>
      <div :style="row">
        <span :style="labelStyle">提示阈值</span>
        <span :style="valStyle">{{ calibration?.hintThreshold ?? '--' }}</span>
      </div>
      <div :style="dividerStyle"></div>
      <div :style="chipRow">
        <span v-for="(v, k) in weightLabels" :key="k" :style="chipStyle(v)">{{ v }}: {{ calibration?.[k] ?? '--' }}</span>
      </div>
    </div>

    <!-- SLO health badge -->
    <div :style="cardStyle">
      <div :style="titleStyle">SLO 健康度</div>
      <div :style="sloHealthBody">
        <div v-if="slo" :style="badgeStyle(slo.healthy)">
          {{ slo.healthy ? '✓ 健康' : '✗ 告警' }}
        </div>
        <div v-else :style="{ color: '#94a3b8' }">暂无数据</div>
        <div v-if="slo?.breaches.length" :style="{ marginTop: 8 }">
          <div v-for="b in slo.breaches" :key="b" :style="{ color: '#ef4444', fontSize: 12, marginTop: 2 }">⚠ {{ b }}</div>
        </div>
      </div>
    </div>

    <!-- P95 latency bar -->
    <div :style="cardStyle">
      <div :style="titleStyle">P95 延迟</div>
      <div :style="gaugeBody">
        <div :style="gaugeValStyle(latencyPct)">
          {{ slo ? slo.p95LatencyMs + 'ms' : '--' }}
        </div>
        <div :style="barBg">
          <div :style="barFillStyle(latencyPct)"></div>
        </div>
        <div :style="{ fontSize: 11, color: '#94a3b8', marginTop: 4 }">目标: {{ targetMs }}ms</div>
      </div>
    </div>

    <!-- Fabrication rate bar -->
    <div :style="cardStyle">
      <div :style="titleStyle">伪造率</div>
      <div :style="gaugeBody">
        <div :style="gaugeValStyle(fabPct)">
          {{ slo ? (slo.fabricationRate * 100).toFixed(1) + '%' : '--' }}
        </div>
        <div :style="barBg">
          <div :style="barFillStyle(fabPct)"></div>
        </div>
        <div :style="{ fontSize: 11, color: '#94a3b8', marginTop: 4 }">目标: ≤5%</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { SloReport } from '../api/dashboard';

const props = defineProps<{
  slo: SloReport | null;
  calibration: Record<string, number> | null;
}>();

const targetMs = 30000;

const weightLabels: Record<string, string> = {
  wData: '数据', wTool: '工具', wSemantic: '语义', wAdversarial: '对抗',
};

const latencyPct = computed(() => {
  if (!props.slo) return 0;
  return Math.min(props.slo.p95LatencyMs / targetMs, 1);
});

const fabPct = computed(() => {
  if (!props.slo) return 0;
  return Math.min(props.slo.fabricationRate / 0.05, 1);
});

const grid: any = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 16 };
const cardStyle: any = { background: '#ffffff', borderRadius: 10, padding: '16px 20px', boxShadow: '0 1px 3px rgba(0,0,0,0.06)' };
const titleStyle: any = { fontSize: 14, fontWeight: 600, color: '#0f172a', marginBottom: 12 };
const row: any = { display: 'flex', justifyContent: 'space-between', padding: '4px 0', fontSize: 13 };
const labelStyle: any = { color: '#64748b' };
const valStyle: any = { fontWeight: 600, fontFamily: 'ui-monospace, monospace' };
const dividerStyle: any = { borderTop: '1px solid #e2e8f0', margin: '8px 0' };
const chipRow: any = { display: 'flex', gap: 4, flexWrap: 'wrap' };
const chipStyle = (l: string): any => ({
  fontSize: 11, padding: '2px 6px', borderRadius: 4, background: '#f1f5f9', color: '#475569',
});
const sloHealthBody: any = { display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '12px 0' };
const badgeStyle = (healthy: boolean): any => ({
  fontSize: 24, fontWeight: 700,
  color: healthy ? '#10b981' : '#ef4444',
});
const gaugeBody: any = { padding: '8px 0' };
const gaugeValStyle = (pct: number): any => ({
  fontSize: 22, fontWeight: 700,
  color: pct > 0.8 ? '#ef4444' : pct > 0.6 ? '#f59e0b' : '#10b981',
  marginBottom: 8,
});
const barBg: any = { height: 8, background: '#e2e8f0', borderRadius: 4, overflow: 'hidden' };
const barFillStyle = (pct: number): any => ({
  width: `${Math.min(pct * 100, 100)}%`,
  height: '100%',
  background: pct > 0.8 ? '#ef4444' : pct > 0.6 ? '#f59e0b' : '#10b981',
  borderRadius: 4,
  transition: 'width 0.5s ease',
});
</script>
