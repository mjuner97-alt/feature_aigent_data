<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { InfoFilled } from '@element-plus/icons-vue';
import {
  listSkillFlowBatchExecutions,
  type BatchNotificationSelection,
} from '../api/skillFlow';
import type { SkillFlowExecution } from '../types/skillFlow';
import { batchUserNames, currentUserId } from '../api/skill';
import { triggerTypeText } from './skillFlowExecutionPresentation';
import { formatDuration } from '../utils/flowDuration.js';
import SkillFlowBatchNotificationModal from './SkillFlowBatchNotificationModal.vue';
import SkillFlowExecutionNotificationModal from './SkillFlowExecutionNotificationModal.vue';
import SkillFlowExecutionDrawer from './SkillFlowExecutionDrawer.vue';

const props = defineProps<{
  flowId: number | null;
  batchId: string | null;
  flowName?: string;
}>();
const emit = defineEmits<{
  (event: 'changed'): void;
  (event: 'back'): void;
}>();

const rows = ref<SkillFlowExecution[]>([]);
const userNames = ref<Record<string, string>>({});
const loading = ref(false);
const error = ref('');
const selected = ref<Set<number>>(new Set());
const previewOpen = ref(false);
const notificationOpen = ref(false);
const notificationId = ref<number | null>(null);
const detailOpen = ref(false);
const detailId = ref<number | null>(null);
const reportKeyword = ref('');
const page = ref(1);
const pageSize = ref(10);
let generation = 0;

const nodeDurationHelp = '所有节点每次执行耗时的累计值，包含失败、重试和重跑的执行耗时，不包含排队、等指标和等待重试或重跑的时间。';
const elapsedDurationHelp = '从任务首次创建到最终结束实际经过的时间，包含节点执行、汇总、排队、等指标和等待重试或重跑的时间。';
const eligible = computed(() => rows.value.filter((row) =>
  row.triggerType === 'MANUAL' &&
  row.triggerUserId === currentUserId() &&
  !!row.reportPath &&
  ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(row.status)
));
const filteredRows = computed(() => {
  const keyword = reportKeyword.value.trim().toLocaleLowerCase('zh-CN');
  return keyword
    ? rows.value.filter((row) => row.flowName.toLocaleLowerCase('zh-CN').includes(keyword))
    : rows.value;
});
const pageRows = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return filteredRows.value.slice(start, start + pageSize.value);
});
const filteredEligible = computed(() => filteredRows.value.filter((row) => selectable(row)));
const filteredSelectedCount = computed(() => filteredEligible.value.filter((row) => selected.value.has(row.id)).length);
const allSelected = computed(() => !!filteredEligible.value.length && filteredSelectedCount.value === filteredEligible.value.length);
const partlySelected = computed(() => filteredSelectedCount.value > 0 && !allSelected.value);
const selection = computed<BatchNotificationSelection | null>(() =>
  props.flowId && props.batchId && selected.value.size
    ? {
        flowId: props.flowId,
        batchId: props.batchId,
        selectionMode: 'INCLUDE',
        includedExecutionIds: [...selected.value],
        excludedExecutionIds: [],
      }
    : null
);

async function load() {
  const current = ++generation;
  selected.value = new Set();
  reportKeyword.value = '';
  page.value = 1;
  error.value = '';
  if (!props.flowId || !props.batchId) return;
  loading.value = true;
  try {
    const result = await listSkillFlowBatchExecutions(props.flowId, props.batchId);
    if (current !== generation) return;
    rows.value = result;
    const ids = [...new Set(result.map((row) => row.triggerUserId).filter((id): id is string => !!id))];
    userNames.value = ids.length ? await batchUserNames(ids) : {};
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '加载团队报告失败';
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch(() => [props.flowId, props.batchId] as const, () => void load(), { immediate: true });
watch(reportKeyword, () => { page.value = 1; });
watch(pageSize, () => { page.value = 1; });
watch(
  () => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)),
  (lastPage) => { if (page.value > lastPage) page.value = lastPage; }
);

function selectable(row: SkillFlowExecution) { return eligible.value.some((item) => item.id === row.id); }
function toggle(row: SkillFlowExecution) {
  if (!selectable(row)) return;
  const next = new Set(selected.value);
  if (next.has(row.id)) next.delete(row.id); else next.add(row.id);
  selected.value = next;
}
function toggleAll() {
  const next = new Set(selected.value);
  if (allSelected.value) filteredEligible.value.forEach((row) => next.delete(row.id));
  else filteredEligible.value.forEach((row) => next.add(row.id));
  selected.value = next;
}
function close() { emit('back'); }
function formatTime(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function duration(start?: string | null, end?: string | null) {
  if (!start || !end) return '-';
  return formatDuration((new Date(end).getTime() - new Date(start).getTime()) / 1000) ?? '-';
}
function activeDuration(row: SkillFlowExecution) { return formatDuration(row.activeDurationSeconds) ?? '-'; }
function triggerPerson(row: SkillFlowExecution) {
  const name = row.triggerUserName || userNames.value[row.triggerUserId || ''];
  return name && row.triggerUserId && name !== row.triggerUserId
    ? `${name}(${row.triggerUserId})`
    : name || row.triggerUserId || '-';
}
function statusText(status: string) {
  return ({ WAITING_METRICS: '排队中', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '取消中' } as Record<string, string>)[status] || status;
}
function statusClass(status: string) {
  return ({ WAITING_METRICS: 'st-pending', QUEUED: 'st-pending', RUNNING: 'st-running', SUMMARIZING: 'st-pending', SUCCESS: 'st-success', PARTIAL_SUCCESS: 'st-success', FAILED: 'st-failed', CANCELLED: 'st-off', CANCEL_REQUESTED: 'st-pending' } as Record<string, string>)[status] || '';
}
function showNotification(row: SkillFlowExecution) {
  notificationId.value = row.id;
  notificationOpen.value = true;
}
function showDetail(row: SkillFlowExecution) {
  detailId.value = row.id;
  detailOpen.value = true;
}
async function sent(result: { sentExecutionIds: number[]; failedExecutionIds: number[] }) {
  if (result.failedExecutionIds.length) ElMessage.warning(`成功 ${result.sentExecutionIds.length} 份，失败 ${result.failedExecutionIds.length} 份`);
  else ElMessage.success(`已提交 ${result.sentExecutionIds.length} 份报告通知`);
  selected.value = new Set();
  emit('changed');
}
</script>

<template>
  <section class="batch-page" aria-label="团队报告执行记录">
        <header class="batch-header">
          <div class="batch-heading">
            <h2>{{ flowName || '团队报告' }}</h2>
          </div>
          <button type="button" class="back-button" @click="close">返回长任务执行记录</button>
        </header>
        <div class="batch-toolbar">
          <input v-model="reportKeyword" type="search" aria-label="搜索报告名称" placeholder="搜索报告名称" />
          <div class="batch-actions">
            <button type="button" class="btn-action" :disabled="!selected.size" @click="selected = new Set()">清空</button>
            <button type="button" class="btn-action primary" :disabled="!selected.size" @click="previewOpen = true">批量通知（{{ selected.size }}）</button>
          </div>
        </div>
        <main>
          <div v-if="loading" class="state">加载中…</div>
          <div v-else-if="error" class="state error">{{ error }}</div>
          <div v-else-if="!rows.length" class="state">暂无团队报告</div>
          <div v-else-if="!filteredRows.length" class="state">未找到匹配的报告</div>
          <div v-else class="job-table-wrap">
            <table class="job-table execution-table">
              <thead><tr>
                <th class="check-column"><input type="checkbox" aria-label="全选筛选结果中的可发送团队报告" :checked="allSelected" :indeterminate.prop="partlySelected" :disabled="!filteredEligible.length" @change="toggleAll" /></th>
                <th>报告名称</th><th>类型</th><th>状态</th><th>触发方式</th><th>进度</th><th>触发人</th><th>创建时间</th>
                <th><span class="th-with-info">累计节点耗时<el-tooltip placement="top" effect="dark" :content="nodeDurationHelp"><el-icon class="th-info-icon"><InfoFilled /></el-icon></el-tooltip></span></th>
                <th><span class="th-with-info">总历时<el-tooltip placement="top" effect="dark" :content="elapsedDurationHelp"><el-icon class="th-info-icon"><InfoFilled /></el-icon></el-tooltip></span></th>
                <th>操作</th>
              </tr></thead>
              <tbody><tr v-for="row in pageRows" :key="row.id">
                <td class="check-column"><input v-if="selectable(row)" type="checkbox" :checked="selected.has(row.id)" :aria-label="`选择 ${row.flowName}`" @change="toggle(row)" /></td>
                <td><span class="col-name">{{ row.flowName }}</span><span class="col-time">#{{ row.id }}</span></td>
                <td>团队报告</td>
                <td><span class="status-badge" :class="statusClass(row.status)">{{ statusText(row.status) }}</span></td>
                <td>{{ triggerTypeText(row.triggerType) }}</td>
                <td>{{ row.completedNodeCount ?? 0 }} / {{ row.totalNodeCount ?? '-' }}</td>
                <td class="col-owner" :title="triggerPerson(row)">{{ triggerPerson(row) }}</td>
                <td class="col-time">{{ formatTime(row.createdAt) }}</td>
                <td :title="nodeDurationHelp">{{ activeDuration(row) }}</td>
                <td>{{ duration(row.createdAt, row.completedAt) }}</td>
                <td class="col-actions"><button type="button" class="btn-action primary" @click="showDetail(row)">查看详情</button><button v-if="selectable(row)" type="button" class="btn-action" @click="showNotification(row)">通知</button></td>
              </tr></tbody>
            </table>
          </div>
        </main>
        <footer>
          <el-pagination v-if="filteredRows.length" v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" :total="filteredRows.length" layout="total, sizes, prev, pager, next" />
        </footer>
    <SkillFlowBatchNotificationModal v-model:open="previewOpen" :selection="selection" @sent="sent" />
    <SkillFlowExecutionNotificationModal v-model:open="notificationOpen" :execution-id="notificationId" @sent="emit('changed')" />
    <SkillFlowExecutionDrawer v-model:open="detailOpen" :execution-id="detailId" />
  </section>
</template>

<style scoped>
.batch-page{display:flex;flex-direction:column;min-width:0;background:#f1f5f9}
.batch-header{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 24px 16px;border-bottom:1px solid #e2e8f0;background:#fff}
.batch-heading{min-width:0}
.batch-heading h2{margin:0;color:#0f172a;font-size:20px;font-weight:700}
.batch-heading p{max-width:min(760px,70vw);margin:5px 0 0;overflow:hidden;color:#64748b;font-size:13px;text-overflow:ellipsis;white-space:nowrap}
.back-button{flex:none;padding:7px 13px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#475569;cursor:pointer;font-size:13px}
.back-button:hover{border-color:#93c5fd;background:#eff6ff;color:#2563eb}
.batch-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 24px 12px}
.batch-toolbar input{width:min(320px,100%);padding:8px 11px;border:1px solid #cbd5e1;border-radius:7px;background:#fff;color:#1e293b;font:inherit;outline:none}
.batch-toolbar input:focus{border-color:#3b82f6;box-shadow:0 0 0 3px rgb(59 130 246 / 12%)}
.col-actions a{text-decoration:none}
.batch-actions{display:flex;flex:none;gap:8px}
.batch-page main{flex:1;min-height:0;overflow:auto;padding:0 24px 20px}
.batch-page footer{display:flex;justify-content:flex-end;min-height:32px;padding:0 24px 18px}
.state{padding:60px 0;border-radius:8px;background:#fff;color:#94a3b8;text-align:center}.state.error{color:#b91c1c}
.job-table-wrap{overflow:auto;border-radius:8px;box-shadow:0 1px 3px rgb(0 0 0 / 6%)}
.job-table{width:100%;min-width:1370px;border-collapse:collapse;background:#fff}
.job-table th{padding:10px 12px;border-bottom:1px solid #e2e8f0;background:#f8fafc;color:#475569;text-align:left;font-size:13px;font-weight:600;white-space:nowrap}
.job-table td{padding:10px 12px;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px}
.job-table tr:hover td{background:#f8fafc}
.check-column{width:28px;text-align:center!important}
.col-name{display:block;font-weight:600}.col-time{display:block;color:#94a3b8;font-size:12px;white-space:nowrap}
.col-owner{max-width:180px;overflow:hidden;color:#64748b;font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.col-actions{display:flex;align-items:center;gap:4px;white-space:nowrap}
.status-badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:700;white-space:nowrap}
.st-running,.st-success{background:#dcfce7;color:#166534}.st-pending{background:#fef3c7;color:#a16207}.st-failed{background:#fee2e2;color:#991b1b}.st-off{background:#f1f5f9;color:#64748b}
.btn-action{padding:4px 10px;border:1px solid #cbd5e1;border-radius:4px;background:#fff;color:#475569;cursor:pointer;font-size:12px}.btn-action:hover{background:#f1f5f9}.btn-action:disabled{cursor:default;opacity:.45}
.btn-action.primary{border-color:#3b82f6;background:#3b82f6;color:#fff}.btn-action.primary:hover{background:#2563eb}.btn-action.primary:disabled{background:#3b82f6}
.th-with-info{display:inline-flex;align-items:center;gap:2px}.th-info-icon{color:#94a3b8;cursor:help;font-size:14px}
@media(max-width:700px){.batch-header{align-items:flex-start;padding-right:16px;padding-left:16px;flex-direction:column}.batch-heading p{max-width:100%}.batch-page main{padding-right:12px;padding-left:12px}.batch-toolbar{align-items:stretch;padding-right:12px;padding-left:12px;flex-direction:column}.batch-toolbar input{width:100%}.batch-actions{justify-content:flex-end}}
</style>
