/**
 * 检索配置 API（docs/history/skill-retrieval-logic.md §8）:
 * 检索旁路词 (skill_bypass_keyword) + 全局关键词优先级 (skill_keyword_priority)。
 */

const BYPASS_BASE = '/v2/skill/bypass-keyword';
const PRIORITY_BASE = '/v2/skill/keyword-priority';

const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

async function parse<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
  return response.json() as Promise<T>;
}

async function parseVoid(response: Response, action: string): Promise<void> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
}

// ==================== 检索旁路词 ====================

export interface SkillBypassKeywordRow {
  id: number;
  groupName: string;
  keyword: string;
  enabled: boolean;
  remark: string | null;
}

export interface SkillBypassKeywordInput {
  groupName: string;
  keyword: string;
  enabled: boolean;
  remark: string | null;
}

/** 全量行 (含未启用), 管理页用 */
export const listBypassKeywords = () =>
  fetch(`${BYPASS_BASE}`, { headers: headers() }).then(r => parse<SkillBypassKeywordRow[]>(r, '加载旁路词'));

export const createBypassKeyword = (input: SkillBypassKeywordInput) =>
  fetch(`${BYPASS_BASE}`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<SkillBypassKeywordRow>(r, '新增旁路词'));

export const updateBypassKeyword = (id: number, input: SkillBypassKeywordInput) =>
  fetch(`${BYPASS_BASE}/${id}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(input) })
    .then(r => parse<SkillBypassKeywordRow>(r, '更新旁路词'));

/** 软删 (enabled=false) */
export const deleteBypassKeyword = (id: number) =>
  fetch(`${BYPASS_BASE}/${id}`, { method: 'DELETE', headers: headers() }).then(r => parseVoid(r, '删除旁路词'));

/** 立即失效 TTL 快照 (否则最长 5 分钟生效) */
export const reloadBypassKeywords = () =>
  fetch(`${BYPASS_BASE}/reload`, { method: 'POST', headers: headers() }).then(r => parseVoid(r, '刷新快照'));

// ==================== 全局关键词优先级 ====================

export interface SkillKeywordPriorityRow {
  id: number | null;
  level: number;
  parentKeyword: string;
  keyword: string;
  sortOrder: number | null;
}

/** 全量 (level, sort_order 有序) */
export const listKeywordPriorities = () =>
  fetch(`${PRIORITY_BASE}`, { headers: headers() }).then(r => parse<SkillKeywordPriorityRow[]>(r, '加载关键词优先级'));

/** 整表替换保存 (拖曳页全量树提交, 服务端按列表位次生成 sort_order; 行须按 level 升序) */
export const replaceKeywordPriorities = (rows: SkillKeywordPriorityRow[]) =>
  fetch(`${PRIORITY_BASE}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(rows) })
    .then(r => parse<SkillKeywordPriorityRow[]>(r, '保存关键词优先级'));
