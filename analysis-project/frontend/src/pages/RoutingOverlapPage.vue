<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  listRoutingOverlap,
  listToolToolOverlap,
  routingOverlapSummary,
  toolToolOverlapSummary,
} from '../api/routingOverlap';
import type {
  RoutingOverlapItem,
  RoutingOverlapSummary,
  ToolToolOverlapItem,
  ToolToolOverlapSummary,
} from '../types/routingOverlap';

const route = useRoute();
const view = ref<'skill-tool' | 'tool-tool'>(route.query.view === 'tool-tool' ? 'tool-tool' : 'skill-tool');

const items = ref<RoutingOverlapItem[]>([]);
const summary = ref<RoutingOverlapSummary | null>(null);
const toolItems = ref<ToolToolOverlapItem[]>([]);
const toolSummary = ref<ToolToolOverlapSummary | null>(null);
const loading = ref(false);
const levelFilter = ref('');
const skillNameFilter = ref(typeof route.query.skillName === 'string' ? route.query.skillName : '');
const toolIdFilter = ref(typeof route.query.toolId === 'string' ? route.query.toolId : '');

async function load() {
  loading.value = true;
  try {
    if (view.value === 'skill-tool') {
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
    } else {
      const [list, sum] = await Promise.all([
        listToolToolOverlap({
          level: levelFilter.value || undefined,
          toolId: toolIdFilter.value.trim() || undefined,
        }),
        toolToolOverlapSummary(),
      ]);
      toolItems.value = list.items;
      toolSummary.value = sum;
    }
  }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
}

function switchView() {
  skillNameFilter.value = '';
  load();
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

function toolSignals(row: ToolToolOverlapItem): string {
  const parts: string[] = [];
  if (row.aliasHit) parts.push('toolId 名称相似');
  if (row.signatureSame) parts.push('参数签名不可区分');
  if (row.differingLayers?.length) parts.push(`差异层: ${row.differingLayers.join('、')}`);
  if (row.topicTagOverlap.length) parts.push(`同候选集: ${row.topicTagOverlap.join('、')}`);
  if (row.cosine > 0) parts.push(`语义相似 ${row.cosine.toFixed(2)}`);
  return parts.join('；') || '-';
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="header">
      <h2>重叠检测</h2>
      <el-radio-group v-model="view" size="small" @change="switchView">
        <el-radio-button value="skill-tool">Skill ↔ Tool</el-radio-button>
        <el-radio-button value="tool-tool">Tool ↔ Tool</el-radio-button>
      </el-radio-group>
      <el-select v-model="levelFilter" placeholder="全部级别" clearable size="small" style="width: 130px" @change="load">
        <el-option label="HIGH" value="HIGH" /><el-option label="MEDIUM" value="MEDIUM" /><el-option label="LOW" value="LOW" />
      </el-select>
      <el-input v-if="view === 'skill-tool'" v-model="skillNameFilter" placeholder="Skill 名称" clearable size="small" style="width: 220px" @change="load" />
      <el-input v-model="toolIdFilter" placeholder="工具 ID" clearable size="small" style="width: 220px" @change="load" />
      <el-button size="small" @click="load">刷新</el-button>
      <span v-if="view === 'skill-tool'" class="hint">派生治理视图：Skill 与工具能力声明的重叠对，只提示不自动处置</span>
      <span v-else class="hint">同候选集内描述/功能签名无法区分的工具对；HIGH 且双方已启用时，启动会自动停用低优先级一方（仅可启用其中一个）；未启用侧为配置期预警，请整改后再启用</span>
    </div>

    <div v-if="view === 'skill-tool' && summary" class="stats">
      <el-alert v-if="summary.degraded" type="warning" :closable="false" show-icon
        title="语义相似信号不可用（无 embedding provider 或预热未完成），当前仅展示名称与业务主题命中" />
      <div class="counts">
        <span class="count high">HIGH {{ summary.counts.HIGH || 0 }}</span>
        <span class="count medium">MEDIUM {{ summary.counts.MEDIUM || 0 }}</span>
        <span class="count low">LOW {{ summary.counts.LOW || 0 }}</span>
      </div>
    </div>

    <div v-if="view === 'tool-tool' && toolSummary" class="stats">
      <el-alert v-if="toolSummary.degraded" type="warning" :closable="false" show-icon
        title="语义相似信号不可用（无 embedding provider 或预热未完成），参数签名完全一致的对仍会判 HIGH 并阻断启动" />
      <div class="counts">
        <span class="count high">HIGH {{ toolSummary.counts.HIGH || 0 }}</span>
        <span class="count medium">MEDIUM {{ toolSummary.counts.MEDIUM || 0 }}</span>
        <span class="count low">LOW {{ toolSummary.counts.LOW || 0 }}</span>
      </div>
    </div>

    <el-table v-if="view === 'skill-tool'" :data="items" v-loading="loading" stripe border size="small">
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

    <el-table v-else :data="toolItems" v-loading="loading" stripe border size="small">
      <el-table-column label="级别" width="90" align="center">
        <template #default="{ row }"><el-tag :type="levelTagType(row.level)" size="small">{{ row.level }}</el-tag></template>
      </el-table-column>
      <el-table-column label="工具 A" width="260" show-overflow-tooltip>
        <template #default="{ row }"><span>{{ row.toolIdA }}</span><el-tag :type="row.enabledA ? 'danger' : 'info'" size="small" style="margin-left: 6px">{{ row.enabledA ? '已启用' : '未启用' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="toolTypeA" label="类型 A" width="80" align="center" />
      <el-table-column label="工具 B" width="260" show-overflow-tooltip>
        <template #default="{ row }"><span>{{ row.toolIdB }}</span><el-tag :type="row.enabledB ? 'danger' : 'info'" size="small" style="margin-left: 6px">{{ row.enabledB ? '已启用' : '未启用' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="toolTypeB" label="类型 B" width="80" align="center" />
      <el-table-column label="信号" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ toolSignals(row) }}</template>
      </el-table-column>
      <el-table-column prop="suggestion" label="治理建议" min-width="300" show-overflow-tooltip />
    </el-table>

    <div v-if="!loading && !(view === 'skill-tool' ? items.length : toolItems.length)" class="empty-tip">当前过滤条件下没有重叠对</div>
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
