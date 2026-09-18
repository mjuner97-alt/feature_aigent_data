<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { listSkillFlowExecutions, cancelSkillFlowExecution } from '../api/skillFlow';
import type { SkillFlowExecution } from '../types/skillFlow';
import SkillFlowExecutionDrawer from './SkillFlowExecutionDrawer.vue';
import { currentUserId } from '../api/skill';
import { triggerTypeText } from './skillFlowExecutionPresentation';
import { formatDuration } from '../utils/flowDuration.js';
import { InfoFilled } from '@element-plus/icons-vue';

const props = withDefaults(defineProps<{ scope?: 'mine' | 'all'; createdBy?: string }>(), { scope: 'mine', createdBy: '' });

const executions = ref<SkillFlowExecution[]>([]);
const loading = ref(false); const error = ref(''); const currentStatus = ref(''); const currentCreatedBy = ref(''); const page = ref(1); const pageSize = ref(20); const detailId = ref<number | null>(null); const detailOpen = ref(false);
const paged = computed(() => executions.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));
let timer: ReturnType<typeof setInterval> | undefined;
function formatTime(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function statusText(value: string) { return ({ WAITING_METRICS: '排队中', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '取消中' } as Record<string, string>)[value] || value; }
function statusClass(value: string) { return ({ WAITING_METRICS: 'st-pending', QUEUED: 'st-pending', RUNNING: 'st-running', SUMMARIZING: 'st-pending', SUCCESS: 'st-success', PARTIAL_SUCCESS: 'st-success', FAILED: 'st-failed', CANCELLED: 'st-off', CANCEL_REQUESTED: 'st-pending' } as Record<string, string>)[value] || ''; }
function duration(start?: string | null, end?: string | null) { if (!start || !end) return '-'; const seconds = (new Date(end).getTime() - new Date(start).getTime()) / 1000; return formatDuration(seconds) ?? '-'; }
// 有效执行耗时:后端按尝试审计记录聚合(各执行阶段之和,不含排队/等指标/重跑间隔);执行中也能显示已耗时
function activeDuration(item: SkillFlowExecution) { return formatDuration(item.activeDurationSeconds) ?? '-'; }
async function load(status = currentStatus.value, createdBy = currentCreatedBy.value, silent = false, scope: 'mine' | 'all' = props.scope) { currentStatus.value = status; currentCreatedBy.value = createdBy; if (!silent) loading.value = true; error.value = ''; try { executions.value = await listSkillFlowExecutions(status || undefined, createdBy.trim() || undefined, scope); page.value = 1; } catch (e) { error.value = e instanceof Error ? e.message : '加载长任务执行记录失败'; if (!silent) executions.value = []; } finally { loading.value = false; } }
function showDetail(id: number) { detailId.value = id; detailOpen.value = true; }
// 可终止 = 本人触发 且 尚未开始收尾(汇总中不再提供终止;取消中按钮隐藏靠状态过滤)
const CANCELLABLE_STATUSES = ['WAITING_METRICS', 'QUEUED', 'RUNNING'];
const cancelling = ref<number | null>(null);
async function cancelExecution(item: SkillFlowExecution) {
  try {
    await ElMessageBox.confirm(
      `确定终止长任务「${item.flowName}」#${item.id} 吗？正在执行的节点完成后其结果将被丢弃，后续节点不再执行。`,
      '终止任务', { confirmButtonText: '终止', cancelButtonText: '继续执行', type: 'warning' });
  } catch { return; }
  cancelling.value = item.id;
  try { await cancelSkillFlowExecution(item.id); await load(currentStatus.value, currentCreatedBy.value, true); }
  catch (e) { error.value = e instanceof Error ? e.message : '终止任务失败'; }
  finally { cancelling.value = null; }
}
defineExpose({ load });
onMounted(() => { load(); timer = setInterval(() => { if (executions.value.some(item => ['WAITING_METRICS', 'QUEUED', 'RUNNING', 'SUMMARIZING', 'CANCEL_REQUESTED'].includes(item.status))) load(currentStatus.value, currentCreatedBy.value, true); }, 5000); }); onUnmounted(() => { if (timer) clearInterval(timer); });
watch(() => [props.scope, props.createdBy] as const, () => load('', props.createdBy, false, props.scope));
</script>

<template>
  <section><div v-if="error" class="center-error">{{ error }}</div><div v-if="loading" class="loading">加载中…</div><div v-else-if="!executions.length" class="empty">暂无长任务执行记录</div><div v-else class="job-table-wrap"><table class="job-table execution-table"><thead><tr><th>任务名称</th><th>状态</th><th>触发方式</th><th>Skill 进度</th><th>触发人</th><th>创建时间</th><th><span class="th-with-info" aria-label="总消耗时间：所有实际执行阶段耗时之和，不包含排队、暂停和重跑等待时间。">总消耗时间<el-tooltip placement="top" effect="dark" popper-class="flow-th-tooltip" content="所有实际执行阶段耗时之和，不包含排队、等指标、暂停和重跑等待时间。执行中的任务显示已耗时。"><el-icon class="th-info-icon"><InfoFilled /></el-icon></el-tooltip></span></th><th><span class="th-with-info" aria-label="总历时：从任务首次创建到最终结束的完整时间，包含排队、等指标、暂停和等待重跑时间。">总历时<el-tooltip placement="top" effect="dark" popper-class="flow-th-tooltip" content="从任务首次创建到最终结束的完整时间，包含排队、等指标、暂停和等待重跑时间。与总消耗时间的差值即等待开销。"><el-icon class="th-info-icon"><InfoFilled /></el-icon></el-tooltip></span></th><th>操作</th></tr></thead><tbody><tr v-for="item in paged" :key="item.id"><td><span class="col-name">{{ item.flowName }}</span><span class="col-time">#{{ item.id }}</span></td><td><span class="status-badge" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td><td>{{ triggerTypeText(item.triggerType) }}</td><td>{{ item.completedNodeCount ?? 0 }} / {{ item.totalNodeCount ?? '-' }}</td><td class="col-owner">{{ item.triggerUserName || item.triggerUserId || '-' }}</td><td class="col-time">{{ formatTime(item.createdAt) }}</td><td title="所有实际执行阶段耗时之和，不包含排队、暂停和重跑等待时间。">{{ activeDuration(item) }}</td><td>{{ duration(item.createdAt, item.completedAt) }}</td><td class="col-actions"><button class="btn-action" @click="showDetail(item.id)">查看详情</button><button v-if="item.triggerUserId === currentUserId() && CANCELLABLE_STATUSES.includes(item.status)" class="btn-action btn-cancel" :disabled="cancelling === item.id" @click="cancelExecution(item)">{{ cancelling === item.id ? '终止中…' : '终止' }}</button><a v-if="item.reportUrl" class="btn-action" :href="item.reportUrl" target="_blank" rel="noopener">查看报告</a></td></tr></tbody></table></div><div v-if="executions.length > pageSize" class="pagination-bar"><el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50]" :total="executions.length" layout="total, sizes, prev, pager, next" @size-change="(size: number) => { pageSize = size; page = 1; }" /></div><SkillFlowExecutionDrawer v-model:open="detailOpen" :execution-id="detailId" @changed="() => load(currentStatus, currentCreatedBy, true)" /></section>
</template>

<style scoped>
.th-with-info { display: inline-flex; align-items: center; gap: 2px; }
.th-info-icon { color: #94a3b8; cursor: help; font-size: 14px; }
.th-info-icon:hover { color: #64748b; }
.center-error { margin-bottom: 12px; padding: 9px 12px; border: 1px solid #fecaca; border-radius: 5px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
.loading, .empty { padding: 40px 0; border-radius: 8px; background: #fff; color: #94a3b8; text-align: center; font-size: 14px; }
.job-table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgb(0 0 0 / 6%); }
.job-table { width: 100%; min-width: 1080px; border-collapse: collapse; background: #fff; }
.execution-table { min-width: 1240px; }
.job-table th { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; background: #f8fafc; color: #475569; text-align: left; font-size: 13px; font-weight: 600; white-space: nowrap; }
.job-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; color: #1e293b; font-size: 13px; }
.job-table tr:hover td { background: #f8fafc; }
.col-name { display: block; font-weight: 600; }
.col-template { max-width: 260px; overflow: hidden; color: #475569; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.col-time { display: block; color: #94a3b8; font-size: 12px; white-space: nowrap; }
.col-owner { max-width: 180px; overflow: hidden; color: #64748b; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.col-actions { display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.status-badge, .notify-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.st-running, .st-success { background: #dcfce7; color: #166534; }
.st-pending { background: #fef3c7; color: #a16207; }
.st-failed { background: #fee2e2; color: #991b1b; }
.st-off, .nt-none, .nt-skipped { background: #f1f5f9; color: #64748b; }
.nt-success { background: #dcfce7; color: #166534; }
.nt-failed { background: #fee2e2; color: #991b1b; }
.nt-pending, .nt-sending { background: #fef3c7; color: #92400e; }
.btn-action { padding: 4px 10px; border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #475569; cursor: pointer; font-size: 12px; text-decoration: none; }
.btn-action:hover { background: #f1f5f9; }
.btn-cancel { color: #b91c1c; border-color: #fecaca; }
.btn-cancel:hover { background: #fef2f2; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 2px; }
</style>

<style>
/* 列头解释气泡(el-tooltip 渲染在 body 下,scoped 样式作用不到) */
.flow-th-tooltip { max-width: 300px; line-height: 1.6; }
</style>
