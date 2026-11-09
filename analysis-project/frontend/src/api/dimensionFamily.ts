const BASE = '/v2/dimension/family';
const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

/** 维度家族映射行 (同级维度消解方案 §3.2): 词表值 → dim 家族 */
export interface DimensionFamily {
  id: number;
  value: string;
  family: string;
  enabled: boolean;
  remark: string | null;
}

export interface DimensionFamilyInput {
  value: string;
  family: string;
  enabled: boolean;
  remark: string | null;
}

async function parse<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
  return response.json() as Promise<T>;
}

async function parseVoid(response: Response, action: string): Promise<void> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
}

/** 全量行 (含未启用), 管理页用 */
export const listDimensionFamilies = () =>
  fetch(`${BASE}`, { headers: headers() }).then(r => parse<DimensionFamily[]>(r, '加载维度家族映射'));

export const createDimensionFamily = (input: DimensionFamilyInput) =>
  fetch(`${BASE}`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionFamily>(r, '新增维度家族映射'));

export const updateDimensionFamily = (id: number, input: DimensionFamilyInput) =>
  fetch(`${BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionFamily>(r, '更新维度家族映射'));

/** 软删 (enabled=false) */
export const deleteDimensionFamily = (id: number) =>
  fetch(`${BASE}/${id}`, { method: 'DELETE', headers: headers() }).then(r => parseVoid(r, '删除维度家族映射'));

/** 立即失效 TTL 快照 (否则最长 5 分钟生效) */
export const reloadDimensionFamilies = () =>
  fetch(`${BASE}/reload`, { method: 'POST', headers: headers() }).then(r => parseVoid(r, '刷新快照'));
