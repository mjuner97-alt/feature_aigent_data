<template>
  <div :style="grid">
    <!-- Verdict distribution pie -->
    <div :style="cardStyle">
      <div :style="titleStyle">判决分布</div>
      <div ref="pieRef" :style="chartStyle"></div>
    </div>
    <!-- Trends line chart -->
    <div :style="cardStyle">
      <div :style="titleStyle">质量趋势 (24h)</div>
      <div ref="trendRef" :style="chartStyle"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import * as echarts from 'echarts';
import type { HourlyBucket, SloReport } from '../api/dashboard';

const props = defineProps<{
  slo: SloReport | null;
  trends: HourlyBucket[];
}>();

const pieRef = ref<HTMLElement | null>(null);
const trendRef = ref<HTMLElement | null>(null);
let pieChart: echarts.ECharts | null = null;
let trendChart: echarts.ECharts | null = null;

function initCharts() {
  if (pieRef.value) pieChart = echarts.init(pieRef.value);
  if (trendRef.value) trendChart = echarts.init(trendRef.value);
}

function updateCharts() {
  if (!pieChart || !trendChart) return;

  // Pie chart
  if (props.slo && props.slo.total > 0) {
    pieChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie', radius: ['40%', '70%'], avoidLabelOverlap: true,
        label: { show: true, formatter: '{b}\n{d}%' },
        data: [
          { value: Math.round(props.slo.passRate * props.slo.total), name: '通过', itemStyle: { color: '#10b981' } },
          { value: Math.round(props.slo.warnRate * props.slo.total), name: '警告', itemStyle: { color: '#f59e0b' } },
          { value: Math.round(props.slo.failRate * props.slo.total), name: '失败', itemStyle: { color: '#ef4444' } },
        ],
      }],
    });
  } else {
    pieChart.setOption({ title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#94a3b8', fontSize: 14 } } });
  }

  // Trends line chart
  if (props.trends.length > 0) {
    const hours = props.trends.map(t => t.hour.slice(5, 16)); // "MM-DD HH:00"
    const passes = props.trends.map(t => t.pass);
    const warns = props.trends.map(t => t.warn);
    const fails = props.trends.map(t => t.fail);
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['通过', '警告', '失败'], bottom: 0 },
      grid: { left: 40, right: 16, bottom: 36, top: 16 },
      xAxis: { type: 'category', data: hours, axisLabel: { fontSize: 10, rotate: 45 } },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        { name: '通过', type: 'line', data: passes, smooth: true, lineStyle: { color: '#10b981' }, itemStyle: { color: '#10b981' }, areaStyle: { color: 'rgba(16,185,129,0.1)' } },
        { name: '警告', type: 'line', data: warns, smooth: true, lineStyle: { color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, areaStyle: { color: 'rgba(245,158,11,0.1)' } },
        { name: '失败', type: 'line', data: fails, smooth: true, lineStyle: { color: '#ef4444' }, itemStyle: { color: '#ef4444' }, areaStyle: { color: 'rgba(239,68,68,0.1)' } },
      ],
    });
  } else {
    trendChart.setOption({ title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#94a3b8', fontSize: 14 } } });
  }
}

onMounted(() => {
  initCharts();
  updateCharts();
});

watch(
  () => [props.slo, props.trends],
  () => updateCharts(),
  { deep: true },
);

onUnmounted(() => {
  pieChart?.dispose();
  trendChart?.dispose();
});

const grid: any = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 };
const cardStyle = (): any => ({ background: '#ffffff', borderRadius: 10, padding: '16px 20px', boxShadow: '0 1px 3px rgba(0,0,0,0.06)' });
const titleStyle: any = { fontSize: 14, fontWeight: 600, color: '#0f172a', marginBottom: 12 };
const chartStyle: any = { width: '100%', height: 300 };
</script>
