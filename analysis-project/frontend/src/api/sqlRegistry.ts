import type { SqlRegistryEntry, SqlRegistryInput, SqlRegistryListItem, SqlTestRequest, SqlTestResult } from '../types/sqlRegistry';
import { apiErrorDetail } from '../utils/apiError';

const BASE = '/api/sql-registry';

function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

/** 列表 (可选按 datasource / createdBy 筛选) */
export async function listEntries(datasource?: string, createdBy?: string): Promise<SqlRegistryListItem[]> {
  const qs = new URLSearchParams();
  if (datasource) qs.set('datasource', datasource);
  if (createdBy) qs.set('createdBy', createdBy);
  const res = await fetch(`${BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取列表失败: ${res.status}`);
  return res.json();
}

/** 详情 (含 sql_template) */
export async function getEntry(id: number): Promise<SqlRegistryEntry> {
  const res = await fetch(`${BASE}/get?id=${id}`, { headers: authHeaders() });
  if (!res.ok) {
    if (res.status === 404) throw new Error('记录不存在');
    throw new Error(`获取详情失败: ${res.status}`);
  }
  return res.json();
}

/** 新增 */
export async function createEntry(input: SqlRegistryInput): Promise<SqlRegistryEntry> {
  const res = await fetch(BASE, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) {
    throw new Error((await apiErrorDetail(res)) || `新增失败 (HTTP ${res.status})`);
  }
  return res.json();
}

/** 修改 */
export async function updateEntry(id: number, input: SqlRegistryInput): Promise<SqlRegistryEntry> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) {
    throw new Error((await apiErrorDetail(res)) || `修改失败 (HTTP ${res.status})`);
  }
  return res.json();
}

/**
 * 切换启用状态: 只传 enabled, 复用后端选择性更新 (非 null 字段才覆盖, null 保留原值).
 * 故意不重发 sql_template -- 列表项不含它, 传空串会被后端当作"模板改为空"触发校验失败.
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

/** SQL 测试 */
export async function testSql(request: SqlTestRequest): Promise<SqlTestResult> {
  const res = await fetch(`${BASE}/test`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(request) });
  if (!res.ok) throw new Error(`测试失败: ${res.status}`);
  return res.json();
}
