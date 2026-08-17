import type { ScriptRegistryEntry, ScriptRegistryInput, ScriptRegistryListItem } from '../types/scriptRegistry';
import { apiErrorDetail } from '../utils/apiError';

const BASE = '/api/script-registry';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

/** 列表 (可选按 datasource / createdBy 筛选) */
export async function listEntries(datasource?: string, createdBy?: string): Promise<ScriptRegistryListItem[]> {
  const qs = new URLSearchParams();
  if (datasource) qs.set('datasource', datasource);
  if (createdBy) qs.set('createdBy', createdBy);
  const res = await fetch(`${BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取列表失败: ${res.status}`);
  return res.json();
}

/** 详情 (含 params_schema) */
export async function getEntry(id: number): Promise<ScriptRegistryEntry> {
  const res = await fetch(`${BASE}/get?id=${id}`, { headers: authHeaders() });
  if (!res.ok) {
    if (res.status === 404) throw new Error('记录不存在');
    throw new Error(`获取详情失败: ${res.status}`);
  }
  return res.json();
}

/** 新增 */
export async function createEntry(input: ScriptRegistryInput): Promise<ScriptRegistryEntry> {
  const res = await fetch(BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) {
    throw new Error((await apiErrorDetail(res)) || `新增失败 (HTTP ${res.status})`);
  }
  return res.json();
}

/** 修改 */
export async function updateEntry(id: number, input: ScriptRegistryInput): Promise<ScriptRegistryEntry> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) {
    throw new Error((await apiErrorDetail(res)) || `修改失败 (HTTP ${res.status})`);
  }
  return res.json();
}

/**
 * 切换启用状态: 只传 enabled, 复用后端选择性更新 (非 null 字段才覆盖, null 保留原值).
 * 故意不重发 script_path / params_schema -- 列表项不含 params_schema, 传空串会被后端覆盖.
 */
export async function setEntryEnabled(id: number, enabled: number): Promise<void> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ enabled }) });
  if (!res.ok) {
    throw new Error((await apiErrorDetail(res)) || `切换失败 (HTTP ${res.status})`);
  }
}

/** 删除 */
export async function deleteEntry(id: number): Promise<void> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}
