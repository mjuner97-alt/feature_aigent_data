import type { SkillListItem, SkillDetail, LikeStatus, SkillInput } from '../types/skill';

const BASE = '/api/skills';

/** 临时用户标识:本工程无鉴权,从 localStorage 取,默认 demo-user。 */
function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

/** 当前用户标识,供前端做 owner 门控(仅所有者显示编辑/删除)。 */
export function currentUserId(): string {
  return localStorage.getItem('skill-user-id') || 'demo-user';
}

/**
 * 把后端错误响应转成可读中文消息。
 * 依赖 application.properties 的 server.error.include-message=always,
 * 否则 SkillAccessDenied/SkillNameConflict 都会以 500 空消息返回。
 */
async function skillError(res: Response, fallback: string): Promise<Error> {
  let detail = '';
  try {
    const body = await res.json();
    detail = (body && (body.message || body.error)) || '';
  } catch {
    /* 非 JSON 响应体,忽略 */
  }
  if (detail.startsWith('SkillAccessDenied')) return new Error('无权限:仅所有者可操作此 Skill');
  if (detail.startsWith('SkillNameConflict')) return new Error('名称已存在,请更换 Skill 名称');
  if (detail.startsWith('SkillNotFound')) return new Error('Skill 不存在或已被删除');
  return new Error(detail ? `${fallback}:${detail}` : `${fallback}(HTTP ${res.status})`);
}

export interface SkillListParams {
  view?: 'all' | 'used' | 'liked' | 'created' | 'popular';
  sort?: 'likes' | 'updated' | 'name';
  category?: string;
  tag?: string;
  keyword?: string;
  limit?: number;
  offset?: number;
}

export async function listSkills(params: SkillListParams): Promise<SkillListItem[]> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== '') qs.set(k, String(v));
  }
  const res = await fetch(`${BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`listSkills failed: ${res.status}`);
  return res.json();
}

export async function getSkill(id: number): Promise<SkillDetail> {
  const res = await fetch(`${BASE}/get?id=${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getSkill failed: ${res.status}`);
  return res.json();
}

export async function likeSkill(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw new Error(`like failed: ${res.status}`);
  return res.json();
}

export async function unlikeSkill(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`unlike failed: ${res.status}`);
  return res.json();
}

export async function getLikeStatus(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getLikeStatus failed: ${res.status}`);
  return res.json();
}

export async function referenceSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/reference`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw new Error(`reference failed: ${res.status}`);
}

export async function unreferenceSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/reference`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`unreference failed: ${res.status}`);
}

/** 创建 Skill(POST /api/skills)。后端自填 ownerUserId/status/likeCount/时间戳。 */
export async function createSkill(input: SkillInput): Promise<SkillDetail> {
  const res = await fetch(BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await skillError(res, '创建失败');
  return res.json();
}

/** 编辑 Skill(PUT /api/skills?id=)。后端做 owner 校验,非 owner 抛 SkillAccessDenied。 */
export async function updateSkill(id: number, input: SkillInput): Promise<SkillDetail> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw await skillError(res, '保存失败');
  return res.json();
}

/** 删除 Skill(DELETE /api/skills?id=,软删除)。后端做 owner 校验。 */
export async function deleteSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '删除失败');
}
