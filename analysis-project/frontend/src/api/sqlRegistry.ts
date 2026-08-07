import type { SqlRegistryEntry, SqlRegistryInput, SqlRegistryListItem, SqlTestRequest, SqlTestResult } from '../types/sqlRegistry';

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
    let detail = '';
    try { const body = await res.json(); detail = body.message || body.error || ''; } catch { /* ignore */ }
    throw new Error(detail || `新增失败 (HTTP ${res.status})`);
  }
  return res.json();
}

/** 修改 */
export async function updateEntry(id: number, input: SqlRegistryInput): Promise<SqlRegistryEntry> {
  const res = await fetch(`${BASE}?id=${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) });
  if (!res.ok) {
    let detail = '';
    try { const body = await res.json(); detail = body.message || body.error || ''; } catch { /* ignore */ }
    throw new Error(detail || `修改失败 (HTTP ${res.status})`);
  }
  return res.json();
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
