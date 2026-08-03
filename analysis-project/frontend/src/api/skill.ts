import type { SkillListItem, SkillDetail, LikeStatus, SkillInput, SkillPublishRecord, PublishTargetGroup, PublishPendingItem, SkillFileUploadResponse, SkillFileItem, SkillFileReferenceItem, SkillFileReferenceRequest } from '../types/skill';

const BASE = '/api/skills';

/** 当前登录用户标识:从 localStorage 取(登录后写入),未登录时回退 demo-user。 */
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
  if (detail.startsWith('SkillPendingApproval')) return new Error('审批中的 Skill 不可编辑或删除,请等待审批完成');
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

// ============ 审批流 API ============

/** 待我审批的发布列表(GET /api/publish/pending),返回 SkillPublish 记录。 */
export async function listPendingPublishes(): Promise<PublishPendingItem[]> {
  const res = await fetch('/api/publish/pending', { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取待审批发布列表失败');
  return res.json();
}

/** 我已审批的发布列表(GET /api/publish/history),返回 APPROVED/REJECTED 记录。 */
export async function listApprovedPublishes(): Promise<PublishPendingItem[]> {
  const res = await fetch('/api/publish/history', { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取已审批发布列表失败');
  return res.json();
}

/** 通过发布审批(POST /api/publish/{id}/approve)。 */
export async function approvePublish(id: number, comment: string): Promise<void> {
  const res = await fetch(`/api/publish/${id}/approve`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ comment }),
  });
  if (!res.ok) throw await skillError(res, '审批通过失败');
}

/** 退回发布审批(POST /api/publish/{id}/reject)。 */
export async function rejectPublish(id: number, comment: string): Promise<void> {
  const res = await fetch(`/api/publish/${id}/reject`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ comment }),
  });
  if (!res.ok) throw await skillError(res, '审批退回失败');
}

// ============ 文件附件 API ============

const FILE_BASE = '/api/files';

/** 上传文件(POST /api/files/upload, multipart/form-data) */
export async function uploadFile(file: File, description?: string): Promise<SkillFileUploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  if (description) formData.append('description', description);
  const res = await fetch(`${FILE_BASE}/upload`, {
    method: 'POST',
    headers: authHeaders(),
    body: formData,
  });
  if (!res.ok) {
    let detail = '';
    try { const body = await res.json(); detail = body.error || ''; } catch { /* ignore */ }
    if (detail.startsWith('FileSizeExceeded')) throw new Error('文件大小超过 1MB 限制');
    if (detail.startsWith('FileExtensionNotAllowed')) throw new Error('不支持的文件类型,仅允许 .py 和 .sql');
    if (detail.startsWith('FileNameEmpty')) throw new Error('文件名不能为空');
    if (detail.startsWith('FileNameTooLong')) throw new Error('文件名过长(超过 255 字符)');
    if (detail.startsWith('FileNameInvalid')) throw new Error('文件名非法:不能含路径分隔符或 . ..');
    if (detail.startsWith('FileNameInvalidControlChar')) throw new Error('文件名含非法控制字符');
    if (detail.startsWith('FileBinaryNotAllowed')) throw new Error('文件内容为二进制,仅支持文本类 .py/.sql');
    if (detail.startsWith('FileConcurrentUpload')) throw new Error('该文件正被并发上传,请重试');
    if (detail.startsWith('FileReadFailed')) throw new Error('读取文件失败');
    throw new Error(`上传失败(HTTP ${res.status})`);
  }
  return res.json();
}

/** 列出当前用户文件(GET /api/files,支持 fileType 筛选) */
export async function listFiles(fileType?: string): Promise<SkillFileItem[]> {
  const qs = new URLSearchParams();
  if (fileType) qs.set('fileType', fileType);
  const res = await fetch(`${FILE_BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取文件列表失败: ${res.status}`);
  return res.json();
}

/**
 * 下载文件为 Blob(GET /api/files/{id}/download)。
 * window.open 无法携带 X-User-Id 自定义头,后端 @RequestHeader 会 400,故改用 fetch。
 * 返回 blob 与文件名(优先取 RFC 5987 的 filename*=UTF-8'',兼容旧 filename="")。
 */
export async function fetchFileBlob(id: number): Promise<{ blob: Blob; filename: string }> {
  const res = await fetch(`${FILE_BASE}/${id}/download`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`下载失败(HTTP ${res.status})`);
  const blob = await res.blob();
  let filename = `file-${id}`;
  const cd = res.headers.get('Content-Disposition') || '';
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  if (star) {
    filename = decodeURIComponent(star[1]);
  } else {
    const m = cd.match(/filename="([^"]+)"/);
    if (m) filename = m[1];
  }
  return { blob, filename };
}

/** 删除文件(DELETE /api/files/{id}) */
export async function deleteFile(id: number): Promise<void> {
  const res = await fetch(`${FILE_BASE}/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`删除文件失败: ${res.status}`);
}

/** 更新文件描述(PUT /api/files/{id}) */
export async function updateFileDescription(id: number, description: string): Promise<SkillFileItem> {
  const res = await fetch(`${FILE_BASE}/${id}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ description }),
  });
  if (!res.ok) throw new Error(`更新描述失败: ${res.status}`);
  return res.json();
}

/** 获取 Skill 引用的文件列表(GET /api/skills/{id}/files) */
export async function getSkillFiles(skillId: number): Promise<SkillFileReferenceItem[]> {
  const res = await fetch(`${BASE}/${skillId}/files`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取附件列表失败: ${res.status}`);
  return res.json();
}

/** Skill 引用一个文件(POST /api/skills/{id}/files) */
export async function addSkillFile(skillId: number, fileId: number, referenceType?: string): Promise<void> {
  const res = await fetch(`${BASE}/${skillId}/files`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ fileId, referenceType } as SkillFileReferenceRequest),
  });
  if (!res.ok) throw new Error(`引用文件失败: ${res.status}`);
}

/** Skill 取消引用文件(DELETE /api/skills/{id}/files/{fileId}) */
export async function removeSkillFile(skillId: number, fileId: number): Promise<void> {
  const res = await fetch(`${BASE}/${skillId}/files/${fileId}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`取消引用失败: ${res.status}`);
}
