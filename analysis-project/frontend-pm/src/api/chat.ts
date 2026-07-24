/**
 * v2 /v2/ai/chat SSE stream client.
 *
 * <p>Adapts the project's SSE protocol to an async generator of normalized events:
 * <ul>
 *   <li>{@code text_block_delta} → token (chunk = lineResult, fullText = resultAll)
 *   <li>{@code done}            → done  (fullText = resultAll)
 *   <li>{@code agent_start / tool_call_start / tool_result_start /
 *       tool_result_end / subagent_exposed / agent_end} → process
 *       (live progress for ActivityFeed — see process-event-streaming.md)
 * </ul>
 *
 * <p>The backend {@code POST /v2/ai/chat} returns SseEmitter with events named
 * after AgentEvent types (lowercased). See V2ChatStreamServiceImpl#handleEvent.
 */

export interface ChatRequest {
  /** User's question/prompt. Backend reads input (preferred) or question. */
  input: string;
  /** Session id; the same value must be passed to /v2/ai/chat/interrupt. */
  conversationId: string;
  /** User id; must match across /v2/ai/chat and /v2/ai/chat/interrupt. */
  user_id: string;
}

/** Process event types forwarded by the backend (process-event-streaming.md). */
export type ProcessEventType =
  | 'agent_start'
  | 'tool_call_start'
  | 'tool_result_start'
  | 'tool_result_end'
  | 'tool_output'
  | 'subagent_exposed'
  | 'agent_end';

const PROCESS_EVENTS = new Set<string>([
  'agent_start',
  'tool_call_start',
  'tool_result_start',
  'tool_result_end',
  'tool_output',
  'subagent_exposed',
  'agent_end',
]);

export interface ProcessEvent {
  eventType: ProcessEventType;
  /** Pre-formatted Chinese message with emoji (the backend's lineResult). */
  message: string;
  /** Source agent name — null for main agent, "analyze_data" etc for subagent. */
  source: string | null;
  /** Tool call id (tool_call_* and tool_result_* events). */
  toolCallId?: string;
  /** Tool name (tool_call_* and tool_result_* events). */
  toolCallName?: string;
  /** ToolResultState.name() (tool_result_end only). */
  toolCallState?: string;
  /**
   * Tool input arguments (tool_call_start / tool_result_end events).
   * Captured by ToolCallTrackingHook on PreActing, attached here so the
   * frontend ActivityFeed can render a collapsible "入参" panel.
   */
  toolInput?: string;
  /**
   * Tool output result (tool_result_end only).
   * Captured by ToolCallTrackingHook on PostActing, attached here so the
   * frontend ActivityFeed can render a collapsible "出参" panel.
   */
  toolOutput?: string;
  /** Subagent id (subagent_exposed only). */
  subagentId?: string;
  /** Subagent label (subagent_exposed only). */
  subagentLabel?: string;
  /** AgentStartEvent.name (agent_start only). */
  agentNameRaw?: string;
  /** AgentStartEvent.role (agent_start only). */
  agentRole?: string;
}

export type ChatEvent =
  | { type: 'token'; chunk: string; fullText: string; source: string | null }
  | { type: 'process'; process: ProcessEvent }
  | { type: 'toolOutputUpdate'; process: ProcessEvent }
  | { type: 'done'; fullText: string; conversationId: string }
  | { type: 'error'; error: string };

/**
 * Stream a chat request. Yields events as they arrive over SSE.
 *
 * @throws Error if the fetch fails or response is not ok.
 */
export async function* streamChat(req: ChatRequest): AsyncGenerator<ChatEvent> {
  const res = await fetch('/v2/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok || !res.body) {
    throw new Error(`Chat stream failed: ${res.status} ${res.statusText}`);
  }

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const rawEvt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);

      // Parse "event: <name>\ndata: <json>\n..." blocks
      const lines = rawEvt.split('\n');
      let eventName = 'message';
      let data = '';
      for (const line of lines) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (!data) continue;

      try {
        const json = JSON.parse(data);
        // Support both old AiChatResult format (lineResult/resultAll) and new
        // ThinkPayload/TextPayload DTO format (data.content/data.action/data.topic/finish).
        // The backend sends SSE events with name "text_block_delta" for streaming tokens
        // and "done" for final results, regardless of which DTO format is used.
        if (eventName === 'text_block_delta') {
          // New DTO format: { type: "think", data: { content, action, topic }, finish, ... }
          // Old format: { lineResult, resultAll, source, ... }
          const isNewFormat = json.data && typeof json.data === 'object';
          const chunk = isNewFormat
            ? (typeof json.data.content === 'string' ? json.data.content : '')
            : (typeof json.lineResult === 'string' ? json.lineResult : '');
          const fullText = isNewFormat
            ? ''  // new format doesn't carry cumulative text; frontend accumulates
            : (typeof json.resultAll === 'string' ? json.resultAll : '');
          const source = json.source ?? null;
          yield { type: 'token', chunk, fullText, source };
        } else if (eventName === 'done') {
          // New DTO format: { type: "text", data: { content, action, topic }, finish, ... }
          // Old format: { resultAll, conversationId, ... }
          const isNewFormat = json.data && typeof json.data === 'object';
          const fullText = isNewFormat
            ? (typeof json.data.content === 'string' ? json.data.content : '')
            : (typeof json.resultAll === 'string' ? json.resultAll : '');
          const conversationId = typeof json.conversationId === 'string' ? json.conversationId : '';
          yield { type: 'done', fullText, conversationId };
        } else if (eventName === 'tool_output') {
          // Supplementary event from PostActing hook — carries the tool output
          // that wasn't available when tool_result_end was emitted (PostActing
          // fires AFTER tool_result_end in the framework). Keyed by toolCallId
          // so the frontend can patch the existing ActivityFeed row.
          yield {
            type: 'toolOutputUpdate',
            process: {
              eventType: 'tool_output' as ProcessEventType,
              message: typeof json.lineResult === 'string' ? json.lineResult : '',
              source: json.source ?? null,
              toolCallId: json.toolCallId ?? undefined,
              toolCallName: json.toolCallName ?? undefined,
              toolOutput: json.toolOutput ?? undefined,
            },
          };
        } else if (PROCESS_EVENTS.has(eventName)) {
          yield {
            type: 'process',
            process: {
              eventType: eventName as ProcessEventType,
              message: typeof json.lineResult === 'string' ? json.lineResult : '',
              source: json.source ?? null,
              toolCallId: json.toolCallId ?? undefined,
              toolCallName: json.toolCallName ?? undefined,
              toolCallState: json.toolCallState ?? undefined,
              toolInput: json.toolInput ?? undefined,
              toolOutput: json.toolOutput ?? undefined,
              subagentId: json.subagentId ?? undefined,
              subagentLabel: json.subagentLabel ?? undefined,
              agentNameRaw: json.agentNameRaw ?? undefined,
              agentRole: json.agentRole ?? undefined,
            },
          };
        }
        // Other AgentEvent types (thinking_block_*, model_call_*, data_block_*,
        // tool_call_delta, tool_result_*_delta, text_block_start/end, hint_block)
        // are intentionally not forwarded — see process-event-streaming.md.
      } catch {
        // Malformed JSON - skip. Backend occasionally sends keepalive blanks.
      }
    }
  }
}

/**
 * Start a brand-new SSE chat (used after interrupt to reuse the panel).
 * Returns the fetch Response so the caller can inspect X-Resume-Stream header.
 */
export async function postChatStream(req: ChatRequest): Promise<Response> {
  return fetch('/v2/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
}