<template>
  <div :style="rootStyle">
    <div :style="headerStyle">
      <div>
        <div :style="titleStyle">质量看板</div>
        <div :style="subtitleStyle">Quality Platform Dashboard</div>
      </div>
      <div :style="headerRight">
        <span :style="updateStyle">上次更新: {{ lastUpdated }}</span>
        <span v-if="error" :style="errorStyle">⚠ {{ error }}</span>
      </div>
    </div>

    <div :style="scrollStyle">
      <!-- Section 1: Summary Cards -->
      <DashboardSummaryCards
        :total="slo?.total ?? 0"
        :pass-rate="slo?.passRate ?? 0"
        :warn-rate="slo?.warnRate ?? 0"
        :fail-rate="slo?.failRate ?? 0"
        :fabrication-rate="slo?.fabricationRate ?? 0"
        :avg-trust="slo?.avgTrust ?? 0"
      />

      <!-- Section 2+3+4: Charts -->
      <DashboardCharts :slo="slo" :trends="trends" />

      <!-- Section 5+6: SLO Health -->
      <DashboardSloHealth :slo="slo" :calibration="calibration" />

      <!-- Section 7: Critic + Experiments -->
      <DashboardCriticTable :critic-stats="criticStats" :experiments="experiments" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import DashboardSummaryCards from '../components/DashboardSummaryCards.vue';
import DashboardCharts from '../components/DashboardCharts.vue';
import DashboardSloHealth from '../components/DashboardSloHealth.vue';
import DashboardCriticTable from '../components/DashboardCriticTable.vue';
import {
  fetchDashboard,
  fetchTrends,
  type SloReport,
  type HourlyBucket,
  type RuleExperiment,
} from '../api/dashboard';

const POLL_MS = 15000;

const slo = ref<SloReport | null>(null);
const trends = ref<HourlyBucket[]>([]);
const calibration = ref<Record<string, number> | null>(null);
const experiments = ref<RuleExperiment[]>([]);
const criticStats = ref<Record<string, number>>({});
const lastUpdated = ref('--');
const error = ref<string | null>(null);

let timer: ReturnType<typeof setInterval> | null = null;
let trendsFetched = false;

async function refresh() {
  try {
    const data = await fetchDashboard(24);
    slo.value = data.slo;
    calibration.value = data.calibration as Record<string, number>;
    experiments.value = data.experiments;
    criticStats.value = data.criticStats;
    error.value = null;
  } catch (e: any) {
    error.value = '看板数据加载失败';
  }

  // Fetch trends once, then every 5 min
  if (!trendsFetched) {
    try {
      trends.value = await fetchTrends(24);
      trendsFetched = true;
      setTimeout(() => { trendsFetched = false; }, 5 * 60 * 1000);
    } catch { /* trends will retry on next cycle */ }
  }

  lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
}

onMounted(() => {
  refresh();
  timer = setInterval(refresh, POLL_MS);
});

onUnmounted(() => {
  if (timer) clearInterval(timer);
});

const rootStyle: any = {
  display: 'flex', flexDirection: 'column', height: '100%',
  background: '#f1f5f9', overflow: 'hidden',
};
const headerStyle: any = {
  display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
  padding: '20px 28px 16px', flexShrink: 0,
};
const titleStyle: any = { fontSize: 22, fontWeight: 700, color: '#0f172a' };
const subtitleStyle: any = { fontSize: 12, color: '#94a3b8', marginTop: 2 };
const headerRight: any = { display: 'flex', alignItems: 'center', gap: 12 };
const updateStyle: any = { fontSize: 11, color: '#94a3b8', fontFamily: 'ui-monospace, monospace' };
const errorStyle: any = { fontSize: 12, color: '#ef4444', fontWeight: 500 };
const scrollStyle: any = { flex: 1, overflowY: 'auto', padding: '0 28px 28px' };
</script>
