<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { listSkillFlowExecutions } from '../api/skillFlow';
import type { SkillFlowExecution } from '../types/skillFlow';
import SkillFlowExecutionDrawer from './SkillFlowExecutionDrawer.vue';

const executions = ref<SkillFlowExecution[]>([]);
const loading = ref(false); const error = ref(''); const currentStatus = ref(''); const currentCreatedBy = ref(''); const page = ref(1); const pageSize = ref(20); const detailId = ref<number | null>(null); const detailOpen = ref(false);
const paged = computed(() => executions.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));
let timer: ReturnType<typeof setInterval> | undefined;
function formatTime(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function statusText(value: string) { return ({ WAITING_METRICS: '等待指标', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '取消中' } as Record<string, string>)[value] || value; }
function statusClass(value: string) { return ({ WAITING_METRICS: 'st-pending', QUEUED: 'st-pending', RUNNING: 'st-running', SUMMARIZING: 'st-pending', SUCCESS: 'st-success', PARTIAL_SUCCESS: 'st-success', FAILED: 'st-failed', CANCELLED: 'st-off', CANCEL_REQUESTED: 'st-pending' } as Record<string, string>)[value] || ''; }
function notificationClass(value?: string | null) { return value ? `nt-${value.toLowerCase()}` : 'nt-none'; }
function duration(start?: string | null, end?: string | null) { if (!start || !end) return '-'; const seconds = Math.round((new Date(end).getTime() - new Date(start).getTime()) / 1000); return Number.isFinite(seconds) && seconds >= 0 ? (seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m${seconds % 60}s`) : '-'; }
async function load(status = currentStatus.value, createdBy = currentCreatedBy.value, silent = false) { currentStatus.value = status; currentCreatedBy.value = createdBy; if (!silent) loading.value = true; error.value = ''; try { executions.value = await listSkillFlowExecutions(status || undefined, createdBy.trim() || undefined); page.value = 1; } catch (e) { error.value = e instanceof Error ? e.message : '加载长任务执行记录失败'; if (!silent) executions.value = []; } finally { loading.value = false; } }
function showDetail(id: number) { detailId.value = id; detailOpen.value = true; }
defineExpose({ load });
onMounted(() => { load(); timer = setInterval(() => { if (executions.value.some(item => ['WAITING_METRICS', 'QUEUED', 'RUNNING', 'SUMMARIZING', 'CANCEL_REQUESTED'].includes(item.status))) load(currentStatus.value, currentCreatedBy.value, true); }, 5000); }); onUnmounted(() => { if (timer) clearInterval(timer); });
</script>

<template>
  <section><div v-if="error" class="center-error">{{ error }}</div><div v-if="loading" class="loading">加载中…</div><div v-else-if="!executions.length" class="empty">暂无长任务执行记录</div><div v-else class="job-table-wrap"><table class="job-table execution-table"><thead><tr><th>任务名称</th><th>状态</th><th>指标进度</th><th>Skill 进度</th><th>触发人</th><th>创建时间</th><th>耗时</th><th>通知</th><th>操作</th></tr></thead><tbody><tr v-for="item in paged" :key="item.id"><td><span class="col-name">{{ item.flowName }}</span><span class="col-time">#{{ item.id }}</span></td><td><span class="status-badge" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td><td>{{ item.readyMetricCount }} / {{ item.requiredMetricCount }}</td><td>{{ item.completedNodeCount ?? 0 }} / {{ item.totalNodeCount ?? '-' }}</td><td class="col-owner">{{ item.triggerUserName || item.triggerUserId || '-' }}</td><td class="col-time">{{ formatTime(item.createdAt) }}</td><td>{{ duration(item.startedAt, item.completedAt) }}</td><td><span class="notify-badge" :class="notificationClass(item.latestNotificationStatus)">{{ item.latestNotificationStatus || '暂无记录' }}</span></td><td class="col-actions"><button class="btn-action" @click="showDetail(item.id)">查看详情</button><a v-if="item.reportUrl" class="btn-action" :href="item.reportUrl" target="_blank" rel="noopener">查看报告</a></td></tr></tbody></table></div><div v-if="executions.length > pageSize" class="pagination-bar"><el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50]" :total="executions.length" layout="total, sizes, prev, pager, next" @size-change="(size: number) => { pageSize = size; page = 1; }" /></div><SkillFlowExecutionDrawer v-model:open="detailOpen" :execution-id="detailId" @changed="() => load(currentStatus, currentCreatedBy, true)" /></section>
</template>

<style scoped>
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
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 2px; }
</style>
