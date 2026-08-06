/**
 * Trace API client.
 *
 * 后端只有 2 个端点：
 *   GET /api/trace/conversations       → ConversationListResponse
 *   GET /api/trace/{conversationId}    → TraceDetailResponse
 */

import type {
  ConversationListResponse,
  TraceDetailResponse,
} from '../types/trace';

const BASE = '/api/trace';

async function http<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Trace API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

function toQuery(params: Record<string, unknown>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.append(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

/** 会话列表。 */
export function listConversations(
  source?: string,
  userId?: string,
  page = 0,
  size = 20,
): Promise<ConversationListResponse> {
  return http<ConversationListResponse>(
    `${BASE}/conversations${toQuery({ source, userId, page, size })}`,
  );
}

/** 单会话详情（含事件 JSON 字符串列表）。 */
export function getTraceDetail(conversationId: string): Promise<TraceDetailResponse> {
  return http<TraceDetailResponse>(`${BASE}/${conversationId}`);
}

/**
 * 按关键字搜索会话（conversationId / userId 模糊匹配）。
 * 后端无独立搜索端点时复用 listConversations，前端按关键字过滤。
 */
export async function search(
  keyword: string,
  page = 0,
  size = 20,
): Promise<ConversationListResponse> {
  const data = await listConversations(undefined, page, size);
  if (!keyword.trim()) return data;
  const kw = keyword.toLowerCase();
  const filtered = data.conversations.filter(
    (c) =>
      c.conversationId.toLowerCase().includes(kw) ||
      c.userId.toLowerCase().includes(kw) ||
      (c.agentName ?? '').toLowerCase().includes(kw),
  );
  return {
    conversations: filtered,
    total: filtered.length,
    page,
    size,
  };
}
