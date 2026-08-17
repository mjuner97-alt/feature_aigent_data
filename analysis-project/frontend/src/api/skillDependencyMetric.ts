import type { SkillDependencyMetric, MetricTriggerBatch } from '../types/skillJob';
import { apiError, apiErrorDetail } from '../utils/apiError';

const BASE = '/api/skill-jobs/metrics';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

/** 列出启用的依赖指标（下拉用，admin 预置只读） */
export async function listMetrics(): Promise<SkillDependencyMetric[]> {
  const res = await fetch(BASE, { headers: authHeaders() });
  if (!res.ok) throw await apiError(res, `查询依赖指标失败 (HTTP ${res.status})`);
  return res.json();
}

/** 按指标编码批量触发：一次调用触发该指标下所有"启用且参与批量触发"的 job */
export async function triggerByMetric(code: string): Promise<MetricTriggerBatch> {
  const res = await fetch(`${BASE}/${encodeURIComponent(code)}/trigger`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) {
    const detail = await apiErrorDetail(res);
    if (detail.startsWith('MetricNotFound')) throw new Error('依赖指标不存在');
    if (detail.startsWith('MetricDisabled')) throw new Error('依赖指标已停用，不可触发');
    throw new Error(detail ? `批量触发失败: ${detail}` : `批量触发失败 (HTTP ${res.status})`);
  }
  return res.json();
}
