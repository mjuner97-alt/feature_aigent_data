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

// ==================== 全局关键词优先级 (平铺行模型) ====================

/** 一行配置: L1 | L2 | L3 词集 (顿号分隔, 格内词彼此等价), 行序即 L2/L3 优先级; L1 无优先级 */
export interface SkillKeywordPriorityLine {
  id: number | null;
  level1Keywords: string;
  level2Keywords: string;
  level3Keywords: string;
}

/** 全量行 (行序有序) */
export const listKeywordPriorities = () =>
  fetch(`${PRIORITY_BASE}`, { headers: headers() }).then(r => parse<SkillKeywordPriorityLine[]>(r, '加载关键词优先级'));

/** 整表替换保存 (平铺行全量提交, 服务端按列表位次生成 row_order) */
export const replaceKeywordPriorities = (lines: SkillKeywordPriorityLine[]) =>
  fetch(`${PRIORITY_BASE}`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(lines) })
    .then(r => parse<SkillKeywordPriorityLine[]>(r, '保存关键词优先级'));
