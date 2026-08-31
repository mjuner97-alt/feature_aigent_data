import type { SkillRoutingInput, SkillRoutingMetadata } from '../types/skillRouting';

const BASE = '/api/skill-routing';

function headers(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...headers(), 'Content-Type': 'application/json' };
}

async function ensureOk(res: Response, action: string): Promise<void> {
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `${action}失败 (HTTP ${res.status})`);
  }
}

export async function listSkillRouting(keyword?: string, active?: boolean): Promise<SkillRoutingMetadata[]> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  if (active != null) query.set('active', String(active));
  const res = await fetch(`${BASE}?${query}`, { headers: headers() });
  await ensureOk(res, '加载 Skill 配置');
  return res.json();
}

export async function saveSkillRouting(skillName: string, input: SkillRoutingInput): Promise<SkillRoutingMetadata> {
  const res = await fetch(`${BASE}/${encodeURIComponent(skillName)}`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input),
  });
  await ensureOk(res, '保存 Skill 配置');
  return res.json();
}

export async function setSkillRoutingActive(skillName: string, active: boolean): Promise<SkillRoutingMetadata> {
  const res = await fetch(`${BASE}/${encodeURIComponent(skillName)}/active`, {
    method: 'PATCH', headers: jsonHeaders(), body: JSON.stringify({ active }),
  });
  await ensureOk(res, '切换 Skill 配置');
  return res.json();
}
