import type { DimensionAlias, DimensionAliasInput, DimensionPath } from '../types/dimensionAlias';

const BASE = '/v2/dimension/alias';
const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

async function parse<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
  return response.json() as Promise<T>;
}

async function parseVoid(response: Response, action: string): Promise<void> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
}

export const listDimensionAlias = (dimension: DimensionPath) =>
  fetch(`${BASE}/${dimension}`, { headers: headers() }).then(r => parse<DimensionAlias[]>(r, '加载同义词'));

export const createDimensionAlias = (dimension: DimensionPath, input: DimensionAliasInput) =>
  fetch(`${BASE}/${dimension}`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionAlias>(r, '新增同义词'));

export const updateDimensionAlias = (id: number, input: DimensionAliasInput) =>
  fetch(`${BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionAlias>(r, '更新同义词'));

/** 软删 (enabled=false) */
export const deleteDimensionAlias = (id: number) =>
  fetch(`${BASE}/${id}`, { method: 'DELETE', headers: headers() }).then(r => parseVoid(r, '删除同义词'));

/** 立即失效 TTL 快照 (否则最长 5 分钟生效) */
export const reloadDimensionAlias = () =>
  fetch(`${BASE}/reload`, { method: 'POST', headers: headers() }).then(r => parseVoid(r, '刷新快照'));
