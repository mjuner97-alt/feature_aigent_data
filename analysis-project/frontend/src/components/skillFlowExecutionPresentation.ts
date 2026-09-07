const TERMINAL_FLOW_STATUSES = new Set(['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED']);
const RETRYABLE_NODE_STATUSES = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'BLOCKED']);

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
