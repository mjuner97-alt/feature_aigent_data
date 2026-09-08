const TERMINAL_FLOW_STATUSES = new Set(['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED']);
const RETRYABLE_NODE_STATUSES = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'BLOCKED']);

export interface NodeErrorLike {
  id?: number | null;
  nodeKey?: string | null;
  skillName?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export function formatNodeErrorDetails(node: NodeErrorLike): string {
  return [
    `节点：${node.skillName || node.nodeKey || '-'}`,
    `节点ID：${node.id ?? '-'}`,
    `错误代码：${node.errorCode || '-'}`,
    '',
    node.errorMessage || '暂无错误详情',
  ].join('\n');
}

export function canRetryNode(flowStatus: string, nodeStatus: string): boolean {
  return TERMINAL_FLOW_STATUSES.has(flowStatus) && RETRYABLE_NODE_STATUSES.has(nodeStatus);
}

export function shouldShowNodeTimes(attempts?: unknown[]): boolean {
  return !attempts?.length;
}

export function statusClass(status: string): string {
  if (status === 'SUCCESS') return 'status-success';
  if (status === 'FAILED' || status === 'BLOCKED') return 'status-failed';
  if (status === 'RUNNING' || status === 'SUMMARIZING') return 'status-running';
  if (['WAITING_METRICS', 'QUEUED', 'PENDING', 'RETRY_WAIT', 'CANCEL_REQUESTED'].includes(status)) return 'status-waiting';
  if (status === 'CANCELLED') return 'status-cancelled';
  return 'status-neutral';
}

export function statusText(status: string): string {
  return ({
    WAITING_METRICS: '排队中',
    QUEUED: '排队中',
    RUNNING: '执行中',
    SUMMARIZING: '汇总中',
    SUCCESS: '成功',
    PARTIAL_SUCCESS: '部分成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    CANCEL_REQUESTED: '取消中',
    PENDING: '排队中',
    RETRY_WAIT: '等待重试',
    BLOCKED: '已阻塞',
  } as Record<string, string>)[status] ?? status;
}

export function triggerTypeText(triggerType?: string | null): string {
  return ({
    AUTO_METRIC: '自动触发',
    MANUAL: '手动触发',
    CHAT: '对话触发',
  } as Record<string, string>)[triggerType ?? ''] ?? '-';
}

export function shouldShowMetricReadiness(triggerType?: string | null): boolean {
  return triggerType === 'AUTO_METRIC';
}

export function manualTriggerMessage(created: boolean): string {
  return created
    ? '已触发任务，可在“长任务执行记录”中查看进度。'
    : '该长任务正在执行中，请勿重复触发，可在“长任务执行记录”中查看进度。';
}
