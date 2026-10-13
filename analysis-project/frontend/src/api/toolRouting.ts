import type { TagType, ToolRoutingInput, ToolRoutingMetadata, ToolRoutingScanCandidate, ToolRoutingStatus, ToolRoutingTag } from '../types/toolRouting';

const BASE = '/api/tool-routing';
const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

async function parse<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
  return response.json() as Promise<T>;
}

export interface PageResult<T> { items: T[]; page: number; pageSize: number; total: number; }
export const listToolRouting = (page = 1, pageSize = 20) => fetch(`${BASE}?page=${page}&pageSize=${pageSize}`, { headers: headers() }).then(r => parse<PageResult<ToolRoutingMetadata>>(r, '加载工具路由'));
export const listToolRoutingAll = () => fetch(`${BASE}/all`, { headers: headers() }).then(r => parse<ToolRoutingMetadata[]>(r, '加载工具路由'));
export const scanToolRouting = (page = 1, pageSize = 20, keyword = '', toolType = '') => {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (keyword) query.set('keyword', keyword);
  if (toolType) query.set('type', toolType);
  return fetch(`${BASE}/scan?${query}`, { headers: headers() }).then(r => parse<PageResult<ToolRoutingScanCandidate>>(r, '扫描工具注册表'));
};
export const getToolRoutingStatus = () => fetch(`${BASE}/status`, { headers: headers() }).then(r => parse<ToolRoutingStatus>(r, '加载路由状态'));
export const saveToolRouting = (toolId: string, input: ToolRoutingInput) => fetch(`${BASE}/${encodeURIComponent(toolId)}`, {
  method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input),
}).then(r => parse<ToolRoutingMetadata>(r, '保存工具路由'));
export const setToolRoutingEnabled = (toolId: string, metadata: ToolRoutingMetadata, enabled: boolean) => fetch(
  `${BASE}/${encodeURIComponent(toolId)}`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ ...metadata, enabled }),
  }).then(r => parse<ToolRoutingMetadata>(r, '更新工具状态'));
export const listTags = (type: TagType) => fetch(`${BASE}/tags/${type}`, { headers: headers() }).then(r => parse<ToolRoutingTag[]>(r, '加载标签词典'));
export const saveTag = (type: TagType, tagName: string, description: string, enabled = true) => fetch(
  `${BASE}/tags/${type}/${encodeURIComponent(tagName)}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ description, enabled }) },
).then(r => parse<ToolRoutingTag>(r, '保存标签'));
