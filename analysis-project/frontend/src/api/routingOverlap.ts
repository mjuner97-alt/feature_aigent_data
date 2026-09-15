import type {
  RoutingOverlapListResponse,
  RoutingOverlapSummary,
  SkillSimilarityCheckResult,
} from '../types/routingOverlap';

const BASE = '/api/routing-overlap';

function headers(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...headers(), 'Content-Type': 'application/json' };
}

export async function listRoutingOverlap(params: {
  level?: string;
  skillName?: string;
  toolId?: string;
}): Promise<RoutingOverlapListResponse> {
  const query = new URLSearchParams();
  if (params.level) query.set('level', params.level);
  if (params.skillName) query.set('skillName', params.skillName);
  if (params.toolId) query.set('toolId', params.toolId);
  const res = await fetch(`${BASE}?${query}`, { headers: headers() });
  if (!res.ok) throw new Error(`加载重叠检测失败 (HTTP ${res.status})`);
  return res.json();
}

export async function routingOverlapSummary(): Promise<RoutingOverlapSummary> {
  const res = await fetch(`${BASE}/summary`, { headers: headers() });
  if (!res.ok) throw new Error(`加载重叠统计失败 (HTTP ${res.status})`);
  return res.json();
}

export async function checkSkillSimilarity(body: {
  name?: string;
  description?: string;
  excludeSkillId?: number;
}): Promise<SkillSimilarityCheckResult> {
  const res = await fetch('/api/skills/similarity-check', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`描述相似检查失败 (HTTP ${res.status})`);
  return res.json();
}
