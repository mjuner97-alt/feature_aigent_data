import type { SkillListItem, SkillDetail, LikeStatus, SkillInput, SkillPublishRecord, PublishTargetGroup } from '../types/skill';

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
  dimension?: string;
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

/** 获取全部去重 tag 列表(GET /api/skills/tags)。 */
export async function getTags(): Promise<string[]> {
  const res = await fetch(`${BASE}/tags`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getTags failed: ${res.status}`);
  return res.json();
}

export interface OrgInfo { orgType: string; orgId: string; orgName: string; }
export interface UserInfo { userId: string; orgs: OrgInfo[]; }

/** 获取用户信息(含所属组织),GET /api/org/user-info?userId=。 */
export async function getUserInfo(userId: string): Promise<UserInfo> {
  const res = await fetch(`/api/org/user-info?userId=${encodeURIComponent(userId)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getUserInfo failed: ${res.status}`);
  return res.json();
}

/** 获取引用某 Skill 的用户列表(GET /api/skills/{id}/referencers)。 */
export async function getReferencers(skillId: number): Promise<string[]> {
  const res = await fetch(`${BASE}/${skillId}/referencers`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getReferencers failed: ${res.status}`);
  return res.json();
}

/** 查询 Skill 的发布记录列表(GET /api/skills/{id}/publishes),含 APPROVED 和 PENDING。 */
export async function getSkillPublishes(id: number): Promise<SkillPublishRecord[]> {
  const res = await fetch(`${BASE}/${id}/publishes`, { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取发布记录失败');
  return res.json();
}

/** 查询当前用户可选的发布目标(GET /api/skills/publish-targets),按维度类型分组。 */
export async function getPublishTargets(): Promise<PublishTargetGroup[]> {
  const res = await fetch(`${BASE}/publish-targets`, { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取发布目标失败');
  return res.json();
}

/** 申请发布 Skill(POST /api/skills/{id}/publish),提交后进入审批流。返回新建 publishId。 */
export async function submitPublish(id: number, targetType: string, targetId: string, targetName: string): Promise<number> {
  const res = await fetch(`${BASE}/${id}/publish`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ targetType, targetId, targetName }),
  });
  if (!res.ok) throw await skillError(res, '申请发布失败');
  const data = await res.json();
  return data.publishId;
}
