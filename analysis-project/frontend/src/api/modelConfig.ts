import type { UserModelConfig, ModelTestResult } from '../types/modelConfig';
import { apiErrorDetail } from '../utils/apiError';

const BASE = '/api/model-config';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function extractError(res: Response, fallback: string): Promise<string> {
  const detail = await apiErrorDetail(res);
  return detail || `${fallback} (HTTP ${res.status})`;
}

/** 列表 (token 已脱敏) */
export async function listConfigs(): Promise<UserModelConfig[]> {
  const res = await fetch(BASE, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取列表失败: ${res.status}`);
  return res.json();
}

/** 详情 (含完整 token, 编辑弹窗预填用) */
export async function getConfig(userId: string): Promise<UserModelConfig> {
  const res = await fetch(`${BASE}/${encodeURIComponent(userId)}`, { headers: authHeaders() });
  if (!res.ok) {
    if (res.status === 404) throw new Error('记录不存在');
    throw new Error(`获取详情失败: ${res.status}`);
  }
  return res.json();
}

/** 新增 */
export async function createConfig(input: UserModelConfig): Promise<UserModelConfig> {
  const res = await fetch(BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw new Error(await extractError(res, '新增失败'));
  return res.json();
}

/** 修改 (选择性覆盖非 null 字段) */
export async function updateConfig(userId: string, input: Partial<UserModelConfig>): Promise<UserModelConfig> {
  const res = await fetch(`${BASE}/${encodeURIComponent(userId)}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw new Error(await extractError(res, '修改失败'));
  return res.json();
}

/** 删除 */
export async function deleteConfig(userId: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(userId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

/** 连接测试: 测的是表单当前 (可能未保存) 的值 */
export async function testConnection(input: Partial<UserModelConfig>): Promise<ModelTestResult> {
  const res = await fetch(`${BASE}/test`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) throw new Error(await extractError(res, '测试失败'));
  return res.json();
}
