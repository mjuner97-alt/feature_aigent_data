<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { listRoutingOverlap, routingOverlapSummary } from '../api/routingOverlap';
import type { RoutingOverlapItem, RoutingOverlapSummary } from '../types/routingOverlap';

const route = useRoute();
const items = ref<RoutingOverlapItem[]>([]);
const summary = ref<RoutingOverlapSummary | null>(null);
const loading = ref(false);
const levelFilter = ref('');
const skillNameFilter = ref(typeof route.query.skillName === 'string' ? route.query.skillName : '');
const toolIdFilter = ref(typeof route.query.toolId === 'string' ? route.query.toolId : '');

async function load() {
  loading.value = true;
  try {
    const [list, sum] = await Promise.all([
      listRoutingOverlap({
        level: levelFilter.value || undefined,
        skillName: skillNameFilter.value.trim() || undefined,
        toolId: toolIdFilter.value.trim() || undefined,
      }),
      routingOverlapSummary(),
    ]);
    items.value = list.items;
    summary.value = sum;
  }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
}

function levelTagType(level: string) {
  if (level === 'HIGH') return 'danger';
  if (level === 'MEDIUM') return 'warning';
  return 'info';
}

function signals(row: RoutingOverlapItem): string {
  const parts: string[] = [];
  if (row.aliasHit) parts.push('名称/关键词命中');
  if (row.toolIdLiteralInDescription) parts.push('描述含 toolId');
  if (row.topicTagOverlap.length) parts.push(`业务主题重叠: ${row.topicTagOverlap.join('、')}`);
  if (row.cosine > 0) parts.push(`语义相似 ${row.cosine.toFixed(2)}`);
  return parts.join('；') || '-';
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="header">
      <h2>重叠检测</h2>
      <el-select v-model="levelFilter" placeholder="全部级别" clearable size="small" style="width: 130px" @change="load">
        <el-option label="HIGH" value="HIGH" /><el-option label="MEDIUM" value="MEDIUM" /><el-option label="LOW" value="LOW" />
      </el-select>
      <el-input v-model="skillNameFilter" placeholder="Skill 名称" clearable size="small" style="width: 220px" @change="load" />
      <el-input v-model="toolIdFilter" placeholder="工具 ID" clearable size="small" style="width: 220px" @change="load" />
      <el-button size="small" @click="load">刷新</el-button>
      <span class="hint">派生治理视图：Skill 与工具能力声明的重叠对，只提示不自动处置</span>
    </div>

    <div v-if="summary" class="stats">
      <el-alert v-if="summary.degraded" type="warning" :closable="false" show-icon
        title="语义相似信号不可用（无 embedding provider 或预热未完成），当前仅展示名称与业务主题命中" />
      <div class="counts">
        <span class="count high">HIGH {{ summary.counts.HIGH || 0 }}</span>
        <span class="count medium">MEDIUM {{ summary.counts.MEDIUM || 0 }}</span>
        <span class="count low">LOW {{ summary.counts.LOW || 0 }}</span>
      </div>
    </div>

    <el-table :data="items" v-loading="loading" stripe border size="small">
      <el-table-column label="级别" width="90" align="center">
        <template #default="{ row }"><el-tag :type="levelTagType(row.level)" size="small">{{ row.level }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="skillName" label="Skill" width="240" show-overflow-tooltip />
      <el-table-column prop="skillOwnerUserId" label="Skill 责任人" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.skillOwnerUserId || '-' }}</template>
      </el-table-column>
      <el-table-column prop="skillSummary" label="Skill 能力声明" min-width="220" show-overflow-tooltip />
      <el-table-column prop="toolId" label="工具 ID" width="220" show-overflow-tooltip />
      <el-table-column prop="toolType" label="类型" width="80" align="center" />
      <el-table-column label="信号" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ signals(row) }}</template>
      </el-table-column>
      <el-table-column prop="suggestion" label="治理建议" min-width="260" show-overflow-tooltip />
    </el-table>

    <div v-if="!loading && !items.length" class="empty-tip">当前过滤条件下没有重叠对</div>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.2rem; }
.hint { color: #64748b; font-size: 0.8rem; margin-left: auto; }
.stats { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.counts { display: flex; gap: 16px; font-size: 0.85rem; }
.count.high { color: #dc2626; font-weight: 600; }
.count.medium { color: #d97706; font-weight: 600; }
.count.low { color: #64748b; }
.empty-tip { color: #64748b; text-align: center; padding: 24px; font-size: 0.85rem; }
</style>
