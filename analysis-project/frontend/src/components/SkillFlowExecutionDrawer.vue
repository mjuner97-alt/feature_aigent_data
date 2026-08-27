<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { getSkillFlowExecution, getSkillFlowExecutionMetrics, getSkillFlowExecutionNodes, getSkillFlowExecutionNotifications, getSkillFlowExecutionReportUrl, getSkillFlowNodeReportUrl, resendSkillFlowExecutionNotification, retrySkillFlowSummary, retrySkillFlowNode, retrySkillFlowFailedNodes } from '../api/skillFlow';
import type { SkillFlowExecution, SkillFlowNodeExecution } from '../types/skillFlow';

const props = defineProps<{ open: boolean; executionId: number | null }>();
const emit = defineEmits<{ (e: 'update:open', open: boolean): void; (e: 'changed'): void }>();
const execution = ref<SkillFlowExecution | null>(null);
const loading = ref(false);
const error = ref('');
const resending = ref(false);
const retrying = ref<string | null>(null);
const errorExpanded = ref<Record<string, boolean>>({});
const reportError = ref('');
const summaryActionError = ref('');

const readyMetrics = computed(() => execution.value?.metrics?.filter(metric => metric.status === 'READY') ?? []);
const waitingMetrics = computed(() => execution.value?.metrics?.filter(metric => metric.status !== 'READY') ?? []);
const failedNodes = computed(() => execution.value?.nodes?.filter(node => node.status === 'FAILED' || node.status === 'BLOCKED') ?? []);
const batchRetryable = computed(() =>
  failedNodes.value.length > 0 && (execution.value?.status === 'FAILED' || execution.value?.status === 'PARTIAL_SUCCESS'));
const summaryRetryable = computed(() =>
  !execution.value?.reportUrl && ['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(execution.value?.status ?? ''));
const summaryGenerationError = computed(() => {
  const summary = execution.value?.summaryJson;
  if (!summary || typeof summary !== 'object' || Array.isArray(summary)) return '';
  const value = (summary as Record<string, unknown>).summaryError;
  return typeof value === 'string' ? value : '';
});
function formatTime(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function statusText(value: string) { return ({ WAITING_METRICS: '等待指标', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '取消中', PENDING: '等待指标', RETRY_WAIT: '等待重试', BLOCKED: '已阻塞' } as Record<string, string>)[value] || value; }
function duration(start?: string | null, end?: string | null) { if (!start || !end) return '-'; const ms = new Date(end).getTime() - new Date(start).getTime(); if (!Number.isFinite(ms) || ms < 0) return '-'; const seconds = Math.round(ms / 1000); return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m${seconds % 60}s`; }
function listText(value?: string[]) { return value?.length ? value.join('、') : '-'; }
function errorKey(node: SkillFlowNodeExecution) { return String(node.id ?? node.nodeKey); }
function toggleError(node: SkillFlowNodeExecution) { const key = errorKey(node); errorExpanded.value[key] = !errorExpanded.value[key]; }
function latestAttempt(node: SkillFlowNodeExecution) { const list = node.attempts ?? []; return list.length ? list[list.length - 1] : null; }
function retryStatusText(node: SkillFlowNodeExecution) {
  if (node.status === 'RETRY_WAIT' && node.attemptCount >= 1) return `等待第 ${node.attemptCount} 次重试`;
  if (node.status === 'RUNNING' && node.attemptCount >= 2) return `第 ${node.attemptCount - 1} 次重试中`;
  return '';
}
async function openNodeReport(node: SkillFlowNodeExecution) {
  if (!props.executionId || !node.id) return;
  const reportWindow = window.open('', '_blank');
  try {
    const url = await getSkillFlowNodeReportUrl(props.executionId, node.id);
    if (reportWindow) reportWindow.location.href = url;
    else window.open(url, '_blank', 'noopener');
  } catch (e) {
    reportWindow?.close();
    error.value = e instanceof Error ? e.message : '打开 Skill 内容失败';
  }
}

async function load() {
  if (!props.executionId) return;
  loading.value = true; error.value = ''; reportError.value = ''; summaryActionError.value = ''; errorExpanded.value = {};
  try {
    const detail = await getSkillFlowExecution(props.executionId);
    const [metrics, nodes, notifications] = await Promise.all([
      getSkillFlowExecutionMetrics(props.executionId), getSkillFlowExecutionNodes(props.executionId), getSkillFlowExecutionNotifications(props.executionId),
    ]);
    if (execution.value?.reportUrl?.startsWith('blob:')) URL.revokeObjectURL(execution.value.reportUrl);
    execution.value = { ...detail, metrics, nodes, notifications, reportUrl: null };
    if (detail.reportPath) {
      try {
        const url = await getSkillFlowExecutionReportUrl(props.executionId);
        if (execution.value) execution.value.reportUrl = url;
      } catch (e) {
        reportError.value = e instanceof Error ? e.message : '汇总文件不存在或无法读取';
      }
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '加载执行详情失败'; execution.value = null; }
  finally { loading.value = false; }
}
async function resend() { if (!props.executionId) return; resending.value = true; try { await resendSkillFlowExecutionNotification(props.executionId); await load(); emit('changed'); } catch (e) { error.value = e instanceof Error ? e.message : '补发通知失败'; } finally { resending.value = false; } }
async function retrySummary() { if (!props.executionId) return; retrying.value = 'summary'; summaryActionError.value = ''; try { await retrySkillFlowSummary(props.executionId); await load(); emit('changed'); } catch (e) { summaryActionError.value = e instanceof Error ? e.message : '重新生成汇总失败'; } finally { retrying.value = null; } }
async function retryNode(node: SkillFlowNodeExecution) { if (!props.executionId || !node.id) return; retrying.value = `node-${node.id}`; try { await retrySkillFlowNode(props.executionId, node.id); node.errorMessage = null; node.errorCode = null; await load(); emit('changed'); } catch (e) { error.value = e instanceof Error ? e.message : '重跑任务失败'; } finally { retrying.value = null; } }
async function retryFailedNodes() { if (!props.executionId) return; retrying.value = 'failed-nodes'; try { await retrySkillFlowFailedNodes(props.executionId); await load(); emit('changed'); } catch (e) { error.value = e instanceof Error ? e.message : '批量重跑失败任务失败'; } finally { retrying.value = null; } }
watch(() => props.open, open => { if (open) load(); });
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="mask" @click.self="emit('update:open', false)">
      <section class="drawer" aria-label="长任务执行详情">
        <header><div><h3>长任务详情</h3><span v-if="execution">#{{ execution.id }} · {{ execution.flowName }}</span></div><button class="icon-button" title="关闭" aria-label="关闭" @click="emit('update:open', false)">×</button></header>
        <main>
          <div v-if="loading" class="empty">加载中…</div>
          <div v-else-if="error" class="error">{{ error }}</div>
          <template v-else-if="execution">
            <section class="summary"><div><span>状态</span><strong>{{ statusText(execution.status) }}</strong></div><div><span>指标</span><strong>{{ execution.readyMetricCount }} / {{ execution.requiredMetricCount }} 已就绪</strong></div><div><span>Skill</span><strong>{{ execution.completedNodeCount ?? 0 }} / {{ execution.totalNodeCount ?? execution.nodes?.length ?? 0 }} 已完成</strong></div><div><span>耗时</span><strong>{{ duration(execution.startedAt, execution.completedAt) }}</strong></div></section>
            <section><h4>指标门闩</h4><p class="caption">已就绪 {{ readyMetrics.length }} 项，待处理或已过期 {{ waitingMetrics.length }} 项</p><div class="metric-list"><div v-for="metric in execution.metrics" :key="`${metric.metricId}-${metric.metricCode}`" class="metric-row"><span class="metric-status" :class="metric.status === 'READY' ? 'ready' : 'waiting'">{{ metric.status === 'READY' ? '已就绪' : metric.status === 'EXPIRED' ? '已过期' : '未就绪' }}</span><div><strong>{{ metric.metricCode || metric.metricName || '未知指标' }}</strong><span>{{ metric.metricName }}</span></div><span>{{ formatTime(metric.readyAt) }}</span><span>影响 {{ listText(metric.affectedSkills) }}</span></div></div></section>
            <section><div class="section-heading"><h4>Skill 执行时间线</h4><button v-if="batchRetryable" class="btn-link" :disabled="!!retrying" @click="retryFailedNodes">{{ retrying === 'failed-nodes' ? '批量重跑中…' : `批量重跑失败任务（${failedNodes.length} 个）` }}</button></div><div class="timeline"><article v-for="node in execution.nodes" :key="node.id || node.nodeKey"><div class="timeline-head"><strong>{{ node.skillName || node.nodeKey }}</strong><span class="status">{{ statusText(node.status) }}</span><span v-if="retryStatusText(node)">{{ retryStatusText(node) }}</span><button v-if="node.status === 'SUCCESS' && node.hasResult" class="btn-link" @click="openNodeReport(node)">查看内容</button><button v-if="node.status !== 'SUCCESS' && node.errorMessage" class="btn-link" @click="toggleError(node)">{{ errorExpanded[errorKey(node)] ? '收起失败原因' : '查看失败原因' }}</button><button v-if="node.status !== 'SUCCESS' && node.status !== 'RUNNING'" class="btn-link" :disabled="!!retrying" @click="retryNode(node)">{{ retrying === `node-${node.id}` ? '重跑中…' : '重跑此任务' }}</button></div><div v-if="node.status !== 'SUCCESS' && node.errorMessage && errorExpanded[errorKey(node)]" class="node-error">{{ node.errorCode ? `${node.errorCode}: ` : '' }}{{ node.errorMessage }}</div><div class="node-detail"><span>{{ node.required ? '必需节点' : '可选节点' }}</span><span>开始时间：{{ formatTime(node.startedAt) }}</span><span>结束时间：{{ formatTime(node.completedAt) }}</span></div><template v-if="latestAttempt(node) && node.status !== 'RUNNING' && node.status !== 'QUEUED'"><div class="attempts"><div><strong>第 {{ latestAttempt(node)?.attemptNo }} 次 · {{ latestAttempt(node) ? statusText(latestAttempt(node).status) : '' }}</strong><span>开始时间：{{ formatTime(latestAttempt(node)?.startedAt) }}</span><span>结束时间：{{ formatTime(latestAttempt(node)?.completedAt) }}</span></div></div></template></article></div></section>
            <section><h4>汇总报告</h4><a v-if="execution.reportUrl" :href="execution.reportUrl" target="_blank" rel="noopener">查看报告</a><button v-if="summaryRetryable" class="btn-link" :disabled="!!retrying" @click="retrySummary">{{ retrying === 'summary' ? '生成中…' : '重新生成汇总' }}</button><p v-else-if="!execution.reportUrl" class="caption">报告生成中或暂不可用</p><p v-if="summaryActionError || summaryGenerationError || reportError" class="summary-error">{{ summaryActionError || summaryGenerationError || reportError }}</p></section>
            <section><div class="notification-heading"><h4>通知记录</h4><button class="btn" :disabled="resending" @click="resend">{{ resending ? '补发中…' : '补发通知' }}</button></div><div v-if="!execution.notifications?.length" class="caption">暂无通知记录</div><div v-for="notification in execution.notifications" :key="notification.id" class="notification-row"><strong>{{ notification.status }}</strong><span>{{ notification.requestType || 'INITIAL' }}</span><span>{{ notification.recipientSummary || '-' }}</span><span>{{ notification.errorMessage || formatTime(notification.completedAt || notification.createdAt) }}</span></div></section>
          </template>
        </main>
        <footer><button class="btn" @click="emit('update:open', false)">关闭</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgb(15 23 42 / 45%); }.drawer { display: flex; width: min(860px, 96vw); height: 100%; flex-direction: column; background: #fff; box-shadow: -8px 0 24px rgb(15 23 42 / 12%); }.drawer header, footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }.drawer footer { justify-content: flex-end; border-top: 1px solid #e2e8f0; border-bottom: 0; }.drawer h3 { margin: 0; color: #0f172a; font-size: 18px; }.drawer header span { color: #64748b; font-size: 12px; }.drawer main { flex: 1; overflow: auto; padding: 20px; }.drawer section { padding: 14px 0; border-bottom: 1px solid #e2e8f0; }.drawer section:last-child { border-bottom: 0; }.drawer h4 { margin: 0 0 8px; color: #0f172a; font-size: 14px; }.drawer section > p { margin: 8px 0 4px; color: #475569; font-size: 12px; }.drawer section > .summary-error { color: #b91c1c; }.summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }.summary div { display: grid; gap: 3px; }.summary span, .caption { color: #64748b; font-size: 12px; }.summary strong { color: #1e293b; font-size: 13px; }.text-block, pre { margin: 0; color: #334155; font: inherit; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }pre { padding: 10px; background: #f8fafc; }.metric-list, .timeline { display: grid; gap: 8px; }.metric-row { display: grid; grid-template-columns: 68px minmax(120px, 1fr) 145px minmax(150px, 1fr); align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid #f1f5f9; color: #475569; font-size: 12px; }.metric-row div { display: grid; gap: 2px; }.metric-row strong { color: #1e293b; }.metric-status, .status { display: inline-block; width: max-content; padding: 2px 7px; border-radius: 4px; font-size: 11px; font-weight: 700; }.metric-status.ready { background: #dcfce7; color: #166534; }.metric-status.waiting { background: #fef3c7; color: #92400e; }.timeline article { padding: 10px 0; border-top: 1px solid #e2e8f0; }.timeline-head, .node-detail, .notification-row, .notification-heading, .section-heading { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }.section-heading, .notification-heading { justify-content: space-between; }.section-heading h4, .notification-heading h4 { margin-bottom: 0; }.timeline-head strong { color: #0f172a; }.timeline-head span, .node-detail, .notification-row { color: #64748b; font-size: 12px; }.status { background: #eff6ff; color: #1d4ed8; }.node-detail { margin: 6px 0; }.timeline details { color: #475569; font-size: 12px; }.timeline summary { cursor: pointer; color: #2563eb; }.timeline details p { margin: 6px 0; white-space: pre-wrap; }.node-error { margin-top: 6px; color: #b91c1c; font-size: 12px; }.attempts { display: grid; gap: 6px; margin-top: 8px; border-left: 2px solid #cbd5e1; padding-left: 8px; color: #64748b; font-size: 12px; }.attempts > div { display: flex; gap: 10px; flex-wrap: wrap; }.notification-row { padding: 7px 0; border-top: 1px solid #f1f5f9; }.btn, .icon-button { border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn-link { border: 0; background: none; padding: 0; color: #2563eb; cursor: pointer; font-size: 12px; }.btn { padding: 7px 14px; }.btn:disabled { opacity: .5; cursor: not-allowed; }.icon-button { width: 28px; height: 28px; padding: 0; font-size: 18px; }.error { padding: 10px; border-left: 3px solid #dc2626; background: #fef2f2; color: #b91c1c; font-size: 13px; }.empty { padding: 40px 0; color: #94a3b8; text-align: center; }a { color: #2563eb; font-size: 13px; }@media (max-width: 700px) { .drawer { width: 100vw; }.drawer main { padding: 14px; }.summary { grid-template-columns: repeat(2, 1fr); }.metric-row { grid-template-columns: 68px 1fr; }.metric-row > span:nth-last-child(-n+2) { grid-column: 2; } }
</style>
