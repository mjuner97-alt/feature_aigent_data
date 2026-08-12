import type { SkillJob, SkillJobInput, SkillJobUpdateInput, SkillJobExecution } from '../types/skillJob';

const BASE = '/api/skill-jobs';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function jobError(res: Response, fallback: string): Promise<Error> {
  let detail = '';
  try {
    const body = await res.json();
    detail = (body && (body.message || body.error)) || '';
  } catch { /* ignore */ }
  if (detail.startsWith('JobNameConflict')) return new Error('任务名称已存在');
  if (detail.startsWith('JobNotFound')) return new Error('任务不存在或已删除');
  if (detail.startsWith('JobAccessDenied')) return new Error('无权限：仅创建人可操作此任务');
  if (detail.startsWith('JobAlreadyRunning')) return new Error('任务正在执行中，请稍后再试');
  if (detail.startsWith('JobQueueFull')) return new Error('执行队列已满，请稍后重试');
  if (detail.startsWith('MetricNotFound')) return new Error('依赖指标不存在或已删除');
  if (detail.startsWith('MetricDisabled')) return new Error('依赖指标已停用，不可选用');
  return new Error(detail ? `${fallback}: ${detail}` : `${fallback} (HTTP ${res.status})`);
}

/** 列表查询 */
export async function listJobs(enabled?: boolean, keyword?: string, createdBy?: string): Promise<SkillJob[]> {
  const qs = new URLSearchParams();
  if (enabled != null) qs.set('enabled', String(enabled));
  if (keyword) qs.set('keyword', keyword);
  if (createdBy) qs.set('createdBy', createdBy);
  const res = await fetch(`${BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '查询失败');
  return res.json();
}

/** 查询详情 */
export async function getJob(id: number): Promise<SkillJob> {
  const res = await fetch(`${BASE}/${id}`, { headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '查询失败');
  return res.json();
}

/** 创建 Job */
export async function createJob(input: SkillJobInput): Promise<SkillJob> {
  const res = await fetch(BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await jobError(res, '创建失败');
  return res.json();
}

/** 更新 Job（skillId / createdBy 不可变，后端忽略 skillId） */
export async function updateJob(id: number, input: SkillJobUpdateInput): Promise<SkillJob> {
  const res = await fetch(`${BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await jobError(res, '保存失败');
  return res.json();
}

/** 删除 Job */
export async function deleteJob(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '删除失败');
}

/** 触发执行（按 ID） */
export async function triggerJob(id: number): Promise<SkillJobExecution> {
  const res = await fetch(`${BASE}/${id}/trigger`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '触发失败');
  return res.json();
}

/** 触发执行（按任务名，外部系统调用入口；不携带用户信息，执行身份由后端取 Job.createdBy） */
export async function triggerJobByName(name: string): Promise<SkillJobExecution> {
  const res = await fetch(`${BASE}/trigger/${encodeURIComponent(name)}`, { method: 'POST' });
  if (!res.ok) throw await jobError(res, '触发失败');
  return res.json();
}

/** 执行记录列表 */
export async function listExecutions(jobId: number, status?: string): Promise<SkillJobExecution[]> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  const res = await fetch(`${BASE}/${jobId}/executions?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '查询执行记录失败');
  return res.json();
}

/** 单条执行记录 */
export async function getExecution(execId: number): Promise<SkillJobExecution> {
  const res = await fetch(`${BASE}/executions/${execId}`, { headers: authHeaders() });
  if (!res.ok) throw await jobError(res, '查询执行记录失败');
  return res.json();
}

/** 下载执行记录生成的报告文件（.html；查看渲染用 viewExecutionFile） */
export async function downloadExecutionFile(execId: number): Promise<void> {
  const res = await fetch(`${BASE}/executions/${execId}/download`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`下载失败(HTTP ${res.status})`);
  const blob = await res.blob();
  let filename = `execution-${execId}.md`;
  const cd = res.headers.get('Content-Disposition') || '';
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  if (star) {
    filename = decodeURIComponent(star[1]);
  } else {
    const m = cd.match(/filename="([^"]+)"/);
    if (m) filename = m[1];
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * 在新标签页内渲染执行记录生成的 HTML 报告（表格样式 + echarts 图表）。
 * 带 X-User-Id 鉴权头取 html blob，转 object URL 后 window.open：
 * 浏览器直接渲染，不触发下载。3 分钟后释放 object URL，留够阅读时间。
 */
export async function viewExecutionFile(execId: number): Promise<void> {
  const res = await fetch(`${BASE}/executions/${execId}/download`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`打开失败(HTTP ${res.status})`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  window.open(url, '_blank');
  setTimeout(() => URL.revokeObjectURL(url), 3 * 60 * 1000);
}
