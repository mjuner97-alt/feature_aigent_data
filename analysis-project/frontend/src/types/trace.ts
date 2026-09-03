/**
 * Trace TypeScript type definitions.
 *
 * 后端直接返回 AgentEvent 的 JSON 字符串（多态 @JsonTypeInfo(type)）。
 * 前端不维护 DTO 镜像，只在解析时按 type 字段分发。
 */

export type TraceStatus = 'SUCCESS' | 'ERROR' | 'TIMEOUT' | 'RUNNING';

/**
 * Element Plus <el-tag> 的 type 映射，按 TraceStatus 取色。
 * 用于 SessionHistoryPage / ConversationListPanel 的状态列。
 */
export const STATUS_TAG_TYPE: Record<TraceStatus, string> = {
  SUCCESS: 'success',
  ERROR: 'danger',
  TIMEOUT: 'warning',
  RUNNING: 'primary',
};

/** 会话汇总（GET /api/trace/conversations 列表项 / detail.conversation）。 */
export interface ConversationSummary {
  conversationId: string;
  traceId: string;
  userId: string;
  source: string;
  agentId: string;
  agentName: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  status: TraceStatus | string;
  errorMessage: string;
  eventCount: number;
  tokenInput: number;
  tokenOutput: number;
  model: string;
}

/** 会话列表响应。 */
export interface ConversationListResponse {
  conversations: ConversationSummary[];
  total: number;
  page: number;
  size: number;
}

/**
 * 旧名兼容别名 - SessionHistoryPage 历史代码用 `Conversation`，
 * 新代码统一用 `ConversationSummary`。两者等价。
 */
export type Conversation = ConversationSummary;

/** 会话详情响应：会话 + 事件 JSON 字符串列表（按 createdAt ASC）。 */
export interface TraceDetailResponse {
  conversation: ConversationSummary;
  events: string[];
}

/**
 * 解析后的 AgentEvent（最小字段集合）。
 * 真正的 type 决定子字段；这里只声明公共部分，子字段用 unknown 透传。
 */
export interface AgentEvent {
  id: string;
  type: string;
  createdAt: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  source?: string;
  replyId?: string;
  toolCallId?: string;
  toolCallName?: string;
  // 文本流（TEXT_BLOCK_DELTA / THINKING_BLOCK_DELTA / TOOL_RESULT_TEXT_DELTA）
  delta?: string;
  // ToolCallDeltaEvent 的累积 input
  input?: string;
  // ModelCallEndEvent 的 usage
  usage?: { inputTokens?: number; outputTokens?: number; totalTokens?: number };
  // ToolResultEndEvent 的 state
  state?: string;
  // TraceSession 注入的捕获值
  capturedInput?: string;
  capturedOutput?: string;
  // 其他透传字段
  [key: string]: unknown;
}
