<template>
  <div class="latency-analysis">
    <div v-if="!analysis" class="empty-state">
      <el-empty description="暂无耗时分析数据" :image-size="80" />
    </div>

    <template v-else>
      <!-- Summary cards -->
      <div class="summary-cards">
        <div class="summary-card">
          <div class="card-label">总耗时</div>
          <div class="card-value">{{ formatDuration(analysis.totalDurationMs) }}</div>
        </div>
        <div class="summary-card bottleneck">
          <div class="card-label">瓶颈类型</div>
          <div class="card-value">
            <span class="type-badge" :style="{ background: spanColor((analysis as any).bottleneckSpanType) }">
              {{ (analysis as any).bottleneckSpanType || '-' }}
            </span>
          </div>
        </div>
        <div class="summary-card">
          <div class="card-label">Span 类型数</div>
          <div class="card-value">{{ analysis.bySpanType?.length ?? 0 }}</div>
        </div>
        <div class="summary-card">
          <div class="card-label">Top N</div>
          <div class="card-value">{{ analysis.topN }}</div>
        </div>
      </div>

      <!-- Latency stats table -->
      <div class="section-title">按 Span 类型聚合</div>
      <el-table :data="analysis.bySpanType" stripe size="small" class="latency-table">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="type-badge" :style="{ background: spanColor(row.spanType) }">
              {{ row.spanType }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="count" label="数量" width="70" align="right" />
        <el-table-column label="总耗时" width="90" align="right">
          <template #default="{ row }">{{ formatDuration(row.totalMs) }}</template>
        </el-table-column>
        <el-table-column label="平均" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'warn-cell': row.avgMs > 3000 }">{{ formatDuration(row.avgMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最大" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'warn-cell': row.maxMs > 5000 }">{{ formatDuration(row.maxMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="P50" width="90" align="right">
          <template #default="{ row }">{{ (row as any).p50Ms != null ? formatDuration((row as any).p50Ms) : '-' }}</template>
        </el-table-column>
        <el-table-column label="P95" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'warn-cell': ((row as any).p95Ms ?? 0) > 5000 }">
              {{ (row as any).p95Ms != null ? formatDuration((row as any).p95Ms) : '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="占比" min-width="160">
          <template #default="{ row }">
            <div class="pct-bar-wrap">
              <div class="pct-bar" :style="{ width: row.pct + '%', background: spanColor(row.spanType) }"></div>
              <span class="pct-text">{{ row.pct.toFixed(1) }}%</span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- Slowest spans -->
      <div class="section-title">最慢 Top {{ analysis.topN }}</div>
      <el-table :data="analysis.slowestSpans" stripe size="small">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="type-badge" :style="{ background: spanColor(row.spanType) }">
              {{ row.spanType }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">
            <span class="slow-duration">{{ formatDuration(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="spanId" label="SpanId" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono-text">{{ row.spanId }}</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import type { LatencyAnalysis as LatencyAnalysisType, SpanType } from '../../types/trace';
import { SPAN_TYPE_COLORS } from '../../types/trace';

defineProps<{
  analysis: LatencyAnalysisType | null;
}>();

function spanColor(type: SpanType): string {
  return SPAN_TYPE_COLORS[type] ?? '#909399';
}

function formatDuration(ms: number): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}
</script>

<style scoped>
.latency-analysis {
  padding: 12px 16px;
  background: #fff;
  height: 100%;
  overflow-y: auto;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.summary-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.summary-card.bottleneck {
  border-left: 3px solid #f56c6c;
}

.card-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.card-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin: 16px 0 8px;
}

.latency-table {
  margin-bottom: 8px;
}

.type-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
}

.pct-bar-wrap {
  position: relative;
  height: 18px;
  background: #f5f7fa;
  border-radius: 3px;
  overflow: hidden;
  display: flex;
  align-items: center;
}

.pct-bar {
  height: 100%;
  border-radius: 3px;
  min-width: 2px;
  transition: width 0.3s;
}

.pct-text {
  position: absolute;
  right: 6px;
  font-size: 11px;
  color: #606266;
  font-weight: 600;
}

.warn-cell {
  color: #e6a23c;
  font-weight: 600;
}

.slow-duration {
  color: #f56c6c;
  font-weight: 600;
}

.mono-text {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  color: #909399;
}
</style>
