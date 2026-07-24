/**
 * ChatPanel - v2 /v2/ai/chat streaming chat with interrupt support.
 *
 * Adapted from dataagent's ChatPanel. Key differences:
 * <ul>
 *   <li>No agentId - v2 uses single runner instance, no agent routing
 *   <li>No sessionKey - v2 uses conversationId (passed through from ChatPage)
 *   <li>No turns rehydration - v2 backend doesn't expose session history REST
 *       (state is recovered server-side via agentscope_sessions.state_data)
 *   <li>No tool_call/tool_result SSE events - v2 doesn't currently expose them;
 *       ToolCallBlock is imported but receives no data (kept for future use)
 *   <li>Interrupt+resume via onInterrupt callback: the InterruptButton in
 *       the right sidebar calls interrupt(supplement) which POSTs
 *       /v2/ai/chat/interrupt with the supplement; the server swaps the
 *       in-flight call to a resume stream and the response SSE becomes the
 *       continuation. The original /v2/ai/chat reader is closed first.
 * </ul>
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { streamChat, type ChatRequest, type ProcessEvent } from '../api/chat';
import { interruptAndResume } from '../api/interrupt';
import type { SubagentPlanState } from '../types/sessionState';
import ToolCallBlock from './ToolCallBlock';
import ActivityFeed from './ActivityFeed';
import Markdown from './Markdown';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  pending?: boolean;
  /**
   * For subagent messages: the source agent name (e.g. "analyze_data").
   * When set, the bubble is rendered with a distinct subagent style (indigo
   * header + monospace label) so the user can visually distinguish subagent
   * streaming text from the main agent's reply. Text_block_delta events
   * arriving with source != null are appended to a dedicated subagent
   * message; main-agent text (source == null) goes to the main reply bubble.
   */
  source?: string | null;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#f8fafc' },
  bodyRow: {
    flex: 1, display: 'flex', minHeight: 0,
  },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
  activityCol: {
    width: 340,
    flexShrink: 0,
    borderLeft: '1px solid #e2e8f0',
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
  },
  empty: { color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center', marginTop: 100 },
  bubble: {
    maxWidth: '78%', padding: '14px 18px', borderRadius: 14,
    fontSize: '0.95rem', lineHeight: 1.6, wordBreak: 'break-word',
  },
  user: {
    alignSelf: 'flex-end',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
  },
  assistant: {
    alignSelf: 'flex-start', background: '#ffffff', color: '#0f172a',
    border: '1px solid #e2e8f0',
    boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
  },
  subagent: {
    alignSelf: 'flex-start', background: '#eef2ff', color: '#0f172a',
    border: '1px solid #c7d2fe',
    boxShadow: '0 1px 2px rgba(99,102,241,0.10)',
  },
  subagentLabel: {
    display: 'flex', alignItems: 'center', gap: 6,
    marginBottom: 6, paddingBottom: 6,
    borderBottom: '1px solid #c7d2fe',
    fontSize: '0.78rem', color: '#4338ca', fontWeight: 600,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  subagentDot: {
    width: 6, height: 6, borderRadius: '50%', background: '#6366f1',
  },
  system: {
    alignSelf: 'center', background: 'transparent', color: '#94a3b8',
    fontSize: '0.85rem', fontStyle: 'italic',
  },
  composer: {
    borderTop: '1px solid #e2e8f0', padding: '18px 28px',
    display: 'flex', gap: 12, background: '#ffffff',
  },
  textarea: {
    flex: 1, padding: '12px 16px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 10,
    color: '#0f172a', fontSize: '0.95rem', resize: 'none',
    minHeight: 48, maxHeight: 200, lineHeight: 1.55,
  },
  send: {
    padding: '0 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  sendDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
};

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

export interface ChatPanelHandle {
  /** Whether a streaming call is currently in-flight (interruptable). */
  busy: boolean;
  /** Trigger interrupt on the in-flight call with a supplement; server swaps to resume stream. */
  interrupt: (supplement: string) => Promise<void>;
}

export interface ChatPanelProps {
  userId: string;
  conversationId: string | null;
  /** Called when conversationId gets minted (first send) or confirmed (server echo). */
  onConversationId?: (id: string) => void;
  /** Called when a user message is sent (so the sidebar can remember the conversation). */
  onUserMessage?: (text: string) => void;
  /** Receives a handle to trigger interrupt from outside (e.g. InterruptButton). */
  registerInterruptHandle?: (h: ChatPanelHandle | null) => void;
  /** Called when subagent plan/todo state changes inferred from SSE events. */
  onSubagentPlanChange?: (plans: Record<string, SubagentPlanState>, todoCounts: Record<string, number>) => void;
}

export default function ChatPanel({
  userId, conversationId, onConversationId, onUserMessage, registerInterruptHandle,
  onSubagentPlanChange,
}: ChatPanelProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [hasInFlight, setHasInFlight] = useState(false);
  const [activityEvents, setActivityEvents] = useState<ProcessEvent[]>([]);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  // Subagent plan/todo state inferred from SSE tool_call_start events.
  // The main agent no longer has plan mode — it lives on subagents (e.g. analyze_data).
  // Since subagent AgentState is not accessible via /v2/ai/session/state, we infer
  // plan mode transitions from SSE events:
  //   tool_call_start + toolCallName="plan_enter" + source="analyze_data" → PLAN mode
  //   tool_call_start + toolCallName="plan_exit"  + source="analyze_data" → BUILD mode
  //   tool_call_start + toolCallName="todo_write"  + source="analyze_data" → track count
  // Reset on conversation done/error.
  const subagentPlansRef = useRef<Record<string, SubagentPlanState>>({});
  const subagentTodoCountsRef = useRef<Record<string, number>>({});

  // Track the current in-flight AbortController so interrupt can cancel cleanly.
  // We don't actually call .abort() on the fetch (the backend handles cancel via
  // the interrupt endpoint), but we use it as a "is this stream still relevant" flag
  // to ignore late events from a stream that was superseded by interrupt.
  const streamEpochRef = useRef(0);

  // Throttle scrollTo with rAF - firing on every token causes layout thrashing
  // for long streamed responses (each scrollTo forces a synchronous reflow).
  const scrollRafRef = useRef<number | null>(null);
  useEffect(() => {
    if (scrollRafRef.current != null) return;
    scrollRafRef.current = requestAnimationFrame(() => {
      scrollRafRef.current = null;
      threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
    });
    return () => {
      if (scrollRafRef.current != null) {
        cancelAnimationFrame(scrollRafRef.current);
        scrollRafRef.current = null;
      }
    };
  }, [messages]);

  const canSend = useMemo(() => !busy && input.trim().length > 0, [busy, input]);

  // Expose interrupt handle to parent
  useEffect(() => {
    if (!registerInterruptHandle) return;
    registerInterruptHandle({
      busy: hasInFlight,
      interrupt: (supplement: string) => triggerInterrupt(supplement),
    });
    return () => registerInterruptHandle(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasInFlight, conversationId, userId]);

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    await sendMessage(text, null);
  }

  async function sendMessage(text: string, supplementalForInterrupt: string | null) {
    // Resolve conversationId: use existing, or mint a fresh one for the first send
    let convId = conversationId;
    if (!convId) {
      convId = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
      onConversationId?.(convId);
    }

    // Only fire onUserMessage for the original send (not for the resume stream
    // started by interrupt - that's a supplement, not a fresh user message).
    if (!supplementalForInterrupt) {
      setHasInFlight(true);
      onUserMessage?.(text);
    }
    setBusy(true);

    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    setMessages(prev => [...prev, userMsg, replyMsg]);

    // Track per-source subagent message ids created for THIS turn. Each new
    // source (e.g. "analyze_data", "query_data") gets its own bubble appended
    // after the main replyMsg; subsequent chunks with the same source append
    // to that same bubble. Main-agent chunks (source == null) keep going to
    // replyMsg. We use a ref-style local map so interrupt-supersession checks
    // can't drop our id mapping mid-stream.
    const subagentMsgIds: Record<string, string> = {};

    // Reset activity feed and subagent state for this turn — each turn starts fresh.
    // The previous turn's subagent plan/todo state is cleared when a new message is sent.
    setActivityEvents([]);
    subagentPlansRef.current = {};
    subagentTodoCountsRef.current = {};
    onSubagentPlanChange?.({}, {});

    const myEpoch = ++streamEpochRef.current;
    const req: ChatRequest = {
      input: text,
      conversationId: convId,
      user_id: userId,
    };

    // Stalled-stream watchdog: if no SSE event arrives for 90s, assume the
    // backend is stuck in a loop (observed: agent_spawn async result never
    // completes, AsyncToolMiddleware polls every 2s until SSE timeout at
    // 180s) and surface an error instead of hanging forever. The backend's
    // own SSE timeout will eventually fire, but the user shouldn't stare at
    // a spinner for 3 minutes with no feedback.
    let stallTimer: ReturnType<typeof setTimeout> | null = null;
    const STALL_MS = 90_000;
    const armStallTimer = () => {
      if (stallTimer) clearTimeout(stallTimer);
      stallTimer = setTimeout(() => {
        if (myEpoch !== streamEpochRef.current) return;
        const subIds = Object.values(subagentMsgIds);
        setMessages(prev => prev.map(m => {
          if (m.id === replyMsg.id) {
            return { ...m, pending: false, text: m.text + (m.text ? '\n\n' : '') + '[等待超时] 后端 90 秒内未产生新事件，可能已卡死。可点击"中断"按钮结束当前会话。' };
          }
          if (subIds.includes(m.id)) return { ...m, pending: false };
          return m;
        }));
        setBusy(false);
        setHasInFlight(false);
      }, STALL_MS);
    };
    const disarmStallTimer = () => {
      if (stallTimer) { clearTimeout(stallTimer); stallTimer = null; }
    };
    armStallTimer();

    try {
      const evts = supplementalForInterrupt
        ? interruptAndResume({ user_id: userId, conversationId: convId, supplement: supplementalForInterrupt })
        : streamChat(req);
      for await (const evt of evts) {
        if (myEpoch !== streamEpochRef.current) return;  // superseded by a later interrupt
        armStallTimer();  // any event resets the stall countdown
        if (evt.type === 'token') {
          const chunk = evt.chunk;
          if (evt.source) {
            // Subagent text_block_delta — route to a dedicated subagent bubble
            // (creates one on first chunk for this source, appends thereafter).
            // Main chat area renders these inline as a separate bubble so the
            // user can see the subagent's LLM-written reasoning text streaming
            // in real time, not just the right-side ActivityFeed meta events.
            let subId = subagentMsgIds[evt.source];
            if (!subId) {
              subId = nextId();
              subagentMsgIds[evt.source] = subId;
              const newMsg: Message = {
                id: subId,
                role: 'assistant',
                text: chunk,
                tools: [],
                pending: true,
                source: evt.source,
              };
              setMessages(prev => [...prev, newMsg]);
            } else {
              setMessages(prev => prev.map(m => m.id === subId
                ? { ...m, text: m.text + chunk }
                : m));
            }
          } else {
            // Main agent text — append to main replyMsg
            setMessages(prev => prev.map(m => m.id === replyMsg.id
              ? { ...m, text: m.text + chunk }
              : m));
          }
        } else if (evt.type === 'process') {
          // Append to activity feed (do NOT touch replyMsg.text — process
          // events are progress markers, not final answer content).
          setActivityEvents(prev => [...prev, evt.process]);

          // Infer subagent plan/todo state from tool_call_start events.
          // Only tool_call_start carries the signal; tool_result_end for subagent
          // internal tools (todo_write etc.) is NOT forwarded by
          // SubagentEventForwardingMiddleware (only text_block_delta is mirrored).
          // After ToolCallTrackingHook is installed on subagents, toolInput is available
          // for plan_enter/plan_write/todo_write calls, enabling task detail display.
          const p = evt.process;
          if (p.eventType === 'tool_call_start' && p.source) {
            if (p.toolCallName === 'plan_enter') {
              subagentPlansRef.current = {
                ...subagentPlansRef.current,
                [p.source]: { agentName: p.source, planActive: true, planContent: null },
              };
              onSubagentPlanChange?.(subagentPlansRef.current, subagentTodoCountsRef.current);
            } else if (p.toolCallName === 'plan_exit') {
              subagentPlansRef.current = {
                ...subagentPlansRef.current,
                [p.source]: { agentName: p.source, planActive: false, planContent: null },
              };
              onSubagentPlanChange?.(subagentPlansRef.current, subagentTodoCountsRef.current);
            } else if (p.toolCallName === 'plan_write') {
              // plan_write carries the plan content in toolInput — extract it
              const planContent = p.toolInput ?? null;
              const existing = subagentPlansRef.current[p.source];
              if (existing) {
                subagentPlansRef.current = {
                  ...subagentPlansRef.current,
                  [p.source]: { ...existing, planContent },
                };
              }
              onSubagentPlanChange?.(subagentPlansRef.current, subagentTodoCountsRef.current);
            } else if (p.toolCallName === 'todo_write') {
              subagentTodoCountsRef.current = {
                ...subagentTodoCountsRef.current,
                [p.source]: (subagentTodoCountsRef.current[p.source] ?? 0) + 1,
              };
              onSubagentPlanChange?.(subagentPlansRef.current, subagentTodoCountsRef.current);
            }
          }
        } else if (evt.type === 'toolOutputUpdate') {
          // Supplementary tool_output event from PostActing hook (see
          // ToolCallTrackingHook.sendToolOutputSseEvent). The framework's
          // tool_result_end AgentEvent fires BEFORE PostActing, so the output
          // captured by the hook isn't available when tool_result_end is sent.
          // This update event carries the output keyed by toolCallId — we
          // patch the existing ActivityFeed row instead of appending a new one.
          const update = evt.process;
          setActivityEvents(prev => prev.map(e =>
            e.toolCallId === update.toolCallId
              ? { ...e, toolOutput: update.toolOutput }
              : e));
        } else if (evt.type === 'done') {
          // Mark main reply + all subagent bubbles as not pending.
          // Replace main reply text with the final answer text from the done
          // event (new DTO format sends the answer as `done`'s data.content,
          // not as text_block_delta chunks - those carry the think text during
          // streaming). Without this, the chat bubble keeps showing the streamed
          // think text (which can be long + complex) and markdown rendering freezes.
          disarmStallTimer();
          const finalText = evt.fullText || '';
          const subIds = Object.values(subagentMsgIds);
          setMessages(prev => prev.map(m => {
            if (m.id === replyMsg.id) return { ...m, pending: false, text: finalText || m.text };
            if (subIds.includes(m.id)) return { ...m, pending: false };
            return m;
          }));
          // NOTE: do NOT reset subagentPlans/subagentTodoCounts on done.
          // The plan/todo state should persist in the UI until the next turn
          // starts (where setActivityEvents([]) resets the activity feed).
          // Resetting here would clear the state the moment the user sees the
          // result, making PlanPanel/TodoListPanel flash empty.
          break;
        } else if (evt.type === 'error') {
          disarmStallTimer();
          const subIds = Object.values(subagentMsgIds);
          setMessages(prev => prev.map(m => m.id === replyMsg.id
            ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${evt.error}` }
            : subIds.includes(m.id)
              ? { ...m, pending: false }
              : m));
        }
      }
    } catch (e: unknown) {
      if (myEpoch !== streamEpochRef.current) return;
      disarmStallTimer();
      const msg = e instanceof Error ? e.message : 'stream failed';
      const subIds = Object.values(subagentMsgIds);
      setMessages(prev => prev.map(m => m.id === replyMsg.id
        ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${msg}` }
        : subIds.includes(m.id)
          ? { ...m, pending: false }
          : m));
    } finally {
      disarmStallTimer();
      if (myEpoch === streamEpochRef.current) {
        setBusy(false);
        setHasInFlight(false);
        inputRef.current?.focus();
      }
    }
  }

  async function triggerInterrupt(supplement: string) {
    // No in-flight call to interrupt - just send the supplement as a normal
    // message. This lets the InterruptButton double as a "send anyway" path
    // when the user hasn't started a stream yet.
    if (!busy || !hasInFlight) {
      await sendMessage(supplement, null);
      return;
    }

    // Mark the current stream as superseded so any late events from the dying
    // stream are ignored. The for-await loop in sendMessage checks
    // myEpoch === streamEpochRef.current on each iteration.
    streamEpochRef.current++;

    // Kick off the resume stream - this POSTs /v2/ai/chat/interrupt with the
    // supplement, the server swaps the in-flight call to a resume stream, and
    // we consume the response SSE as a new turn. The original /v2/ai/chat
    // reader will be closed by the browser when this fetch starts (or by the
    // server when it disposes the in-flight subscription).
    await sendMessage(supplement, supplement);
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div style={S.root}>
      <div style={S.bodyRow}>
        <div style={S.thread} ref={threadRef}>
          {messages.length === 0 && (
            <div style={S.empty}>
              发送消息开始对话。<br />
              尝试：<code style={{ background: '#e2e8f0', padding: '1px 6px', borderRadius: 4 }}>
                分析2026年Q1各部门质量分趋势，生成详细报告
              </code>
            </div>
          )}
          {messages.map(m => {
            const isSub = !!m.source;
            const bubbleStyle = m.role === 'user'
              ? S.user
              : m.role === 'system'
                ? S.system
                : isSub
                  ? S.subagent
                  : S.assistant;
            return (
              <div key={m.id} style={{ ...S.bubble, ...bubbleStyle }}>
                {isSub && (
                  <div style={S.subagentLabel}>
                    <span style={S.subagentDot} />
                    <span>{m.source}</span>
                  </div>
                )}
                {m.tools.length > 0 && (
                  <div style={{ marginBottom: m.text ? 10 : 0 }}>
                    {m.tools.map(t => (
                      <ToolCallBlock
                        key={t.id}
                        toolName={t.name}
                        toolCallId={t.id}
                        result={t.result}
                      />
                    ))}
                  </div>
                )}
                {m.role === 'user'
                  ? (m.text || (m.pending ? <span style={{ color: '#94a3b8' }}>…</span> : null))
                  : (m.text
                      ? (m.pending
                          // During streaming (pending), render as plain pre-wrap text.
                          // Running Markdown.parse on every text_block_delta token is O(n²)
                          // — each token grows the text, triggering a full re-parse of the
                          // entire accumulated string. For an 800-token response that's
                          // 800 re-parses of growing input, which freezes the UI. Plain
                          // text is O(n) per token (just a string append + DOM text node
                          // update) and stays smooth. When `done` arrives we flip
                          // pending=false and the final render uses Markdown for proper
                          // formatting (tables, headings, bold, etc.).
                          ? <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{m.text}</div>
                          : <Markdown text={m.text} />)
                      : (m.pending ? <span style={{ color: '#94a3b8' }}>…</span> : null))}
              </div>
            );
          })}
        </div>
        <div style={S.activityCol}>
          <ActivityFeed events={activityEvents} active={busy} />
        </div>
      </div>
      <div style={S.composer}>
        <textarea
          ref={inputRef}
          style={S.textarea}
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={busy ? '处理中…' : '输入消息，Enter 发送 / Shift+Enter 换行'}
          rows={1}
          autoFocus
          disabled={busy}
        />
        <button
          style={{ ...S.send, ...(canSend ? {} : S.sendDisabled) }}
          onClick={handleSend}
          disabled={!canSend}
        >
          {busy ? '…' : '发送'}
        </button>
      </div>
    </div>
  );
}