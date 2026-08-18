import { apiErrorDetail } from '../utils/apiError';

/**
 * 虚拟组 API(组名+userid)。
 * 虚拟组是私有 Skill 授权的授权对象之一:owner 按"虚拟组"授权,组内成员即时可见,不走审批。
 */
const BASE = '/api/virtual-groups';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function vgError(res: Response, fallback: string): Promise<Error> {
  const detail = await apiErrorDetail(res);
  if (detail.startsWith('VirtualGroupReferenced')) return new Error('该虚拟组正被私有授权引用,请先取消相关授权');
  if (detail.startsWith('VirtualGroupInvalidMember')) return new Error('成员不存在(请确认统一认证号)');
  if (detail.startsWith('VirtualGroupInvalidName')) return new Error('虚拟组名不能为空');
  if (detail.startsWith('VirtualGroupNameExists')) return new Error('该虚拟组名已存在');
  if (detail.startsWith('VirtualGroupNotFound')) return new Error('虚拟组不存在(可能已被删除),请刷新列表');
  return new Error(detail ? `${fallback}:${detail}` : `${fallback}(HTTP ${res.status})`);
}

/** 虚拟组成员项。 */
export interface VirtualGroupMember {
  userId: string;
  name: string;
}

/** 虚拟组(组名+成员列表)。 */
export interface VirtualGroup {
  groupName: string;
  memberCount: number;
  members: VirtualGroupMember[];
}

/** 全部虚拟组(GET /api/virtual-groups)。 */
export async function listVirtualGroups(): Promise<VirtualGroup[]> {
  const res = await fetch(BASE, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取虚拟组列表失败(HTTP ${res.status})`);
  return res.json();
}

/** 建组(POST /api/virtual-groups),可同时带首个成员。 */
export async function createVirtualGroup(groupName: string, firstUserId?: string): Promise<void> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ groupName, firstUserId }),
  });
  if (!res.ok) throw await vgError(res, '建组失败');
}

/** 删除组(DELETE /api/virtual-groups?groupName=)。被私有授权引用时后端拒绝。 */
export async function deleteVirtualGroup(groupName: string): Promise<void> {
  const res = await fetch(`${BASE}?groupName=${encodeURIComponent(groupName)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw await vgError(res, '删除失败');
}

/** 加成员(POST /api/virtual-groups/{name}/members)。 */
export async function addVirtualGroupMember(groupName: string, userId: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(groupName)}/members`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ userId }),
  });
  if (!res.ok) throw await vgError(res, '添加成员失败');
}

/** 移除成员(DELETE /api/virtual-groups/{name}/members?userId=)。 */
export async function removeVirtualGroupMember(groupName: string, userId: string): Promise<void> {
  const res = await fetch(
    `${BASE}/${encodeURIComponent(groupName)}/members?userId=${encodeURIComponent(userId)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok) throw await vgError(res, '移除成员失败');
}
