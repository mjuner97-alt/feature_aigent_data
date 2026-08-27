import type { SkillFlow, SkillFlowExecution, SkillFlowInput, SkillFlowNodeExecution, FlowMetricReadiness, FlowMetricPrecheck, SkillFlowRunResult, SkillFlowNotification } from '../types/skillFlow';
import { apiErrorDetail } from '../utils/apiError';

const FLOW_BASE = '/api/skill-flows';
const EXECUTION_BASE = '/api/skill-flow-executions';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function requestError(res: Response, fallback: string): Promise<Error> {
  const detail = await apiErrorDetail(res);
  if (/keyword.*(exist|conflict|duplicate)/i.test(detail)) return new Error('触发关键词已被其他流程使用');
  if (/cycle|dag/i.test(detail)) return new Error('前置 Skill 不能形成环');
  if (/access|denied/i.test(detail)) return new Error('无权限执行此操作');
  return new Error(detail ? `${fallback}: ${detail}` : `${fallback} (HTTP ${res.status})`);
}

/** Backend DTOs are still evolving; accept a plain array or the common paged/list wrappers in one place. */
function listBody<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[];
  if (body && typeof body === 'object') {
    const candidate = body as { items?: T[]; records?: T[]; content?: T[]; data?: T[] };
    return candidate.items ?? candidate.records ?? candidate.content ?? candidate.data ?? [];
  }
  return [];
}

export async function listSkillFlows(enabled?: boolean, keyword?: string, createdBy?: string, scope: 'mine' | 'all' = 'mine'): Promise<SkillFlow[]> {
  const qs = new URLSearchParams();
  if (enabled != null) qs.set('enabled', String(enabled));
  if (keyword) qs.set('keyword', keyword);
  if (createdBy) qs.set('createdBy', createdBy);
  qs.set('scope', scope);
  const res = await fetch(`${FLOW_BASE}?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询长任务流程失败');
  return listBody<SkillFlow>(await res.json());
}

export async function getSkillFlow(id: number): Promise<SkillFlow> {
  const res = await fetch(`${FLOW_BASE}/${id}`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询流程失败');
  return res.json();
}

export async function createSkillFlow(input: SkillFlowInput): Promise<SkillFlow> {
  const res = await fetch(FLOW_BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await requestError(res, '创建流程失败');
  return res.json();
}

export async function updateSkillFlow(id: number, input: SkillFlowInput): Promise<SkillFlow> {
  const res = await fetch(`${FLOW_BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await requestError(res, '保存流程失败');
  return res.json();
}

export async function setSkillFlowEnabled(id: number, enabled: boolean): Promise<SkillFlow> {
  const res = await fetch(`${FLOW_BASE}/${id}/enabled`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ enabled }) });
  if (!res.ok) throw await requestError(res, '更新流程状态失败');
  return res.json();
}

export async function deleteSkillFlow(id: number): Promise<void> {
  const res = await fetch(`${FLOW_BASE}/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '删除流程失败');
}

export async function validateSkillFlow(id: number): Promise<void> {
  const res = await fetch(`${FLOW_BASE}/${id}/validate`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '流程校验失败');
}

/** 手动执行预检:流程依赖指标今日就绪状态,未就绪时先弹确认再真正触发。 */
export async function getSkillFlowMetricPrecheck(id: number): Promise<FlowMetricPrecheck[]> {
  const res = await fetch(`${FLOW_BASE}/${id}/metrics`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询指标就绪状态失败');
  return listBody<FlowMetricPrecheck>(await res.json());
}

/** 手动触发一次执行;指标未就绪时后端挂 WAITING_METRICS,数据到达后自动开跑。 */
export async function runSkillFlow(id: number): Promise<SkillFlowRunResult> {
  const res = await fetch(`${FLOW_BASE}/${id}/run`, { method: 'POST', headers: jsonHeaders() });
  if (!res.ok) throw await requestError(res, '触发执行失败');
  return res.json();
}

export async function listSkillFlowExecutions(status?: string, createdBy?: string, scope: 'mine' | 'all' = 'mine'): Promise<SkillFlowExecution[]> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (createdBy) qs.set('createdBy', createdBy);
  qs.set('scope', scope);
  const res = await fetch(`${EXECUTION_BASE}?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询长任务执行记录失败');
  return listBody<SkillFlowExecution>(await res.json());
}

export async function getSkillFlowExecution(id: number): Promise<SkillFlowExecution> {
  const res = await fetch(`${EXECUTION_BASE}/${id}`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询执行详情失败');
  return res.json();
}

export async function getSkillFlowExecutionNodes(id: number): Promise<SkillFlowNodeExecution[]> {
  const res = await fetch(`${EXECUTION_BASE}/${id}/nodes`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询节点执行记录失败');
  return listBody<SkillFlowNodeExecution>(await res.json());
}

export async function getSkillFlowExecutionMetrics(id: number): Promise<FlowMetricReadiness[]> {
  const res = await fetch(`${EXECUTION_BASE}/${id}/metrics`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询指标状态失败');
  return listBody<FlowMetricReadiness>(await res.json());
}

export async function getSkillFlowExecutionNotifications(id: number): Promise<SkillFlowNotification[]> {
  const res = await fetch(`${EXECUTION_BASE}/${id}/notifications`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '查询通知记录失败');
  return listBody<SkillFlowNotification>(await res.json());
}

export async function resendSkillFlowExecutionNotification(id: number): Promise<void> {
  const res = await fetch(`${EXECUTION_BASE}/${id}/notifications/resend`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '补发通知失败');
}
export async function retrySkillFlowSummary(id: number): Promise<void> { const res = await fetch(`${EXECUTION_BASE}/${id}/summary/retry`, { method: 'POST', headers: authHeaders() }); if (!res.ok) throw await requestError(res, '重新生成汇总失败'); }
export async function retrySkillFlowNode(executionId: number, nodeId: number): Promise<void> { const res = await fetch(`${EXECUTION_BASE}/${executionId}/nodes/${nodeId}/retry`, { method: 'POST', headers: authHeaders() }); if (!res.ok) throw await requestError(res, '重跑任务失败'); }

export async function getSkillFlowExecutionReportUrl(id: number): Promise<string> {
  const res = await fetch(`${EXECUTION_BASE}/${id}/report`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '打开汇总报告失败');
  return URL.createObjectURL(await res.blob());
}

export async function getSkillFlowNodeReportUrl(executionId: number, nodeId: number): Promise<string> {
  const res = await fetch(`${EXECUTION_BASE}/${executionId}/nodes/${nodeId}/report`, { headers: authHeaders() });
  if (!res.ok) throw await requestError(res, '打开 Skill 内容失败');
  return URL.createObjectURL(await res.blob());
}
