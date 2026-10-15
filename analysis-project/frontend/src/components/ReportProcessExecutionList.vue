<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { listSkillFlowExecutions, getSkillFlowExecutionReportUrl } from '../api/skillFlow';
import type { SkillFlowExecution } from '../types/skillFlow';
import ReportProcessExecutionDrawer from './ReportProcessExecutionDrawer.vue';
import { formatDuration } from '../utils/flowDuration.js';
import { InfoFilled } from '@element-plus/icons-vue';
import { batchUserNames } from '../api/skill';

const props = withDefaults(defineProps<{ scope?: 'mine' | 'all'; createdBy?: string }>(), { scope: 'mine', createdBy: '' });
const recordLabel = computed(() => '流程报告');

const executions = ref<SkillFlowExecution[]>([]);
const userNames = ref<Record<string, string>>({});
const loading = ref(false); const error = ref(''); const currentStatus = ref(''); const currentCreatedBy = ref(''); const page = ref(1); const pageSize = ref(20); const detailId = ref<number | null>(null); const detailOpen = ref(false);
const total = ref(0);
const paged = computed(() => executions.value);
let timer: ReturnType<typeof setInterval> | undefined;
function formatTime(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function statusText(value: string) { return ({ WAITING_METRICS: '排队中', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '取消中' } as Record<string, string>)[value] || value; }
function statusClass(value: string) { return ({ WAITING_METRICS: 'st-pending', QUEUED: 'st-pending', RUNNING: 'st-running', SUMMARIZING: 'st-pending', SUCCESS: 'st-success', PARTIAL_SUCCESS: 'st-success', FAILED: 'st-failed', CANCELLED: 'st-off', CANCEL_REQUESTED: 'st-pending' } as Record<string, string>)[value] || ''; }
// 有效执行耗时:后端按尝试审计记录聚合(各执行阶段之和,不含排队/等指标/重跑间隔);执行中也能显示已耗时
function activeDuration(item: SkillFlowExecution) { return formatDuration(item.activeDurationSeconds) ?? '-'; }
let loadSequence = 0;
async function load(status = currentStatus.value, createdBy = props.createdBy, silent = false, scope: 'mine' | 'all' = props.scope) {
  const sequence = ++loadSequence;
  currentStatus.value = status; currentCreatedBy.value = createdBy;
  if (!silent) loading.value = true;
  error.value = '';
  try {
    const data = await listSkillFlowExecutions(status || undefined, createdBy.trim() || undefined, scope, page.value, pageSize.value, 'report-template');
    if (sequence !== loadSequence) return;
    executions.value = data.items; total.value = data.total;
    // 执行接口仅返回用户 ID，复用人员查询补齐姓名；缓存已查到的姓名供轮询使用。
    const missingUserIds = data.items.filter(item => !item.triggerUserName && item.triggerUserId && !userNames.value[item.triggerUserId]).map(item => item.triggerUserId!);
    if (missingUserIds.length) {
      const names = await batchUserNames(missingUserIds);
      if (sequence !== loadSequence) return;
      userNames.value = { ...userNames.value, ...names };
    }
  } catch (e) {
    if (sequence !== loadSequence) return;
    error.value = e instanceof Error ? e.message : `加载${recordLabel.value}执行记录失败`;
    if (!silent) executions.value = [];
  } finally { if (sequence === loadSequence) loading.value = false; }
}
function showDetail(id: number) { detailId.value = id; detailOpen.value = true; }
function triggerPerson(item: SkillFlowExecution) {
  const name = item.triggerUserName || userNames.value[item.triggerUserId || ''];
  return name && item.triggerUserId && name !== item.triggerUserId ? `${name}(${item.triggerUserId})` : name || item.triggerUserId || '-';
}
async function openReport(item: SkillFlowExecution) {
  const reportWindow = window.open('', '_blank');
  if (reportWindow) reportWindow.opener = null;
  try {
    const { url } = await getSkillFlowExecutionReportUrl(item.id);
    if (reportWindow) reportWindow.location.href = url;
    else window.open(url, '_blank', 'noopener');
  } catch (e) {
    reportWindow?.close();
    error.value = e instanceof Error ? e.message : '打开报告失败';
  }
}
defineExpose({ load });
onMounted(() => { load(); timer = setInterval(() => { if (executions.value.some(item => ['WAITING_METRICS', 'QUEUED', 'RUNNING', 'SUMMARIZING', 'CANCEL_REQUESTED'].includes(item.status))) load(currentStatus.value, currentCreatedBy.value, true); }, 5000); }); onUnmounted(() => { if (timer) clearInterval(timer); });
watch(() => [props.scope, props.createdBy, 'report-template'] as const, () => { page.value = 1; load('', props.createdBy, false, props.scope); });
</script>

<template>
  <section>
    <div v-if="error" class="center-error" role="alert">{{ error }}</div>
    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="!executions.length" class="empty">暂无{{ recordLabel }}记录</div>
    <div v-else class="job-table-wrap">
      <table class="job-table execution-table">
        <thead>
          <tr>
            <th>名称</th><th>状态</th><th>节点进度</th><th>触发人</th><th>发起时间</th>
            <th>
              <span class="th-with-info">累计执行耗时
                <el-tooltip placement="top" effect="dark" popper-class="flow-th-tooltip" content="各节点各次尝试的执行耗时之和，不含排队和等待；并行执行时可能大于实际等待时间。">
                  <el-icon class="th-info-icon"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
            </th>
            <th class="report-column"><span class="sr-only">报告</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in paged" :key="item.id">
            <td class="col-record">
              <button type="button" class="name-link" :title="`查看${item.flowName}详情`" @click="showDetail(item.id)">{{ item.flowName }}</button>
            </td>
            <td><span class="status-badge" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td>
            <td>{{ item.completedNodeCount ?? 0 }} / {{ item.totalNodeCount ?? '-' }}</td>
            <td class="col-owner" :title="triggerPerson(item)">{{ triggerPerson(item) }}</td>
            <td class="col-time-cell">{{ formatTime(item.createdAt) }}</td>
            <td>{{ activeDuration(item) }}</td>
            <td class="report-column">
              <button v-if="item.reportPath || item.reportUrl" type="button" class="report-link" @click="openReport(item)">查看报告</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="total > pageSize" class="pagination-bar">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50]" :total="total" layout="total, sizes, prev, pager, next" @size-change="(size: number) => { pageSize = size; page = 1; load(currentStatus, currentCreatedBy); }" />
    </div>
    <ReportProcessExecutionDrawer v-model:open="detailOpen" :execution-id="detailId" :record-label="recordLabel" @changed="() => load(currentStatus, currentCreatedBy, true)" />
  </section>
</template>

<style scoped>
.th-with-info { display: inline-flex; align-items: center; gap: 2px; }
.th-info-icon { color: #94a3b8; cursor: help; font-size: 14px; }
.th-info-icon:hover { color: #64748b; }
.center-error { margin-bottom: 12px; padding: 9px 12px; border: 1px solid #fecaca; border-radius: 5px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
.loading, .empty { padding: 40px 0; border-radius: 8px; background: #fff; color: #94a3b8; text-align: center; font-size: 14px; }
.job-table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgb(0 0 0 / 6%); }
.job-table { width: 100%; min-width: 820px; border-collapse: collapse; background: #fff; }
.execution-table { table-layout: auto; }
.job-table th { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; background: #f8fafc; color: #475569; text-align: left; font-size: 13px; font-weight: 600; white-space: nowrap; }
.job-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; color: #1e293b; font-size: 13px; }
.job-table tr:hover td { background: #f8fafc; }
.col-record { min-width: 180px; max-width: 340px; }
.name-link, .report-link { display: block; padding: 0; border: 0; background: none; text-align: left; cursor: pointer; color: #2563eb; font: inherit; }
.name-link { max-width: 100%; font-weight: 600; overflow-wrap: anywhere; }
.report-link { margin-left: auto; font-size: 12px; white-space: nowrap; }
.report-column { width: 88px; text-align: right; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.name-link:hover, .report-link:hover { color: #1d4ed8; text-decoration: underline; }
.name-link:focus-visible, .report-link:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; border-radius: 2px; }
.job-table .col-time-cell { color: #64748b; font-size: 12px; white-space: nowrap; }
.col-owner { max-width: 240px; overflow-wrap: anywhere; }
.status-badge, .notify-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.st-running, .st-success { background: #dcfce7; color: #166534; }
.st-pending { background: #fef3c7; color: #a16207; }
.st-failed { background: #fee2e2; color: #991b1b; }
.st-off, .nt-none, .nt-skipped { background: #f1f5f9; color: #64748b; }
.nt-success { background: #dcfce7; color: #166534; }
.nt-failed { background: #fee2e2; color: #991b1b; }
.nt-pending, .nt-sending { background: #fef3c7; color: #92400e; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 2px; }
</style>

<style>
/* 列头解释气泡(el-tooltip 渲染在 body 下,scoped 样式作用不到) */
.flow-th-tooltip { max-width: 300px; line-height: 1.6; }
</style>


