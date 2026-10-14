import type { DimensionKeyAlias } from '../types/dimensionKey';

const BASE = '/v2/dimension/key';
const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

export interface DimensionKey {
  id: number;
  key: string;
  label: string;
  builtin: boolean;
  aliases: DimensionKeyAlias[];
  enabled: boolean;
  remark: string | null;
}

export interface DimensionKeyInput {
  key: string;
  label: string;
  aliases: DimensionKeyAlias[];
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
export const listDimensionKeys = () =>
  fetch(`${BASE}`, { headers: headers() }).then(r => parse<DimensionKey[]>(r, '加载维度键'));

export const createDimensionKey = (input: DimensionKeyInput) =>
  fetch(`${BASE}`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionKey>(r, '新增维度键'));

export const updateDimensionKey = (id: number, input: DimensionKeyInput) =>
  fetch(`${BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<DimensionKey>(r, '更新维度键'));

/** 软删 (enabled=false), 内置键拒绝 */
export const deleteDimensionKey = (id: number) =>
  fetch(`${BASE}/${id}`, { method: 'DELETE', headers: headers() }).then(r => parseVoid(r, '删除维度键'));

/** 立即失效 TTL 快照 (否则最长 5 分钟生效) */
export const reloadDimensionKeys = () =>
  fetch(`${BASE}/reload`, { method: 'POST', headers: headers() }).then(r => parseVoid(r, '刷新快照'));
