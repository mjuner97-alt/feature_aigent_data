/**
 * ActivityFeed - live timeline of process events from the agent.
 *
 * <p>Renders agent_start / tool_call_start / tool_result_start /
 * tool_result_end / subagent_exposed / agent_end events as a scrolling
 * timestamped list. Shows users what the agent is doing during the
 * 30s black-box period before the final markdown report streams out.
 *
 * <p>Events with source != null (subagent events) are indented with a
 * colored left border to distinguish them from main-agent events.
 * Failed tool_result_end (state != SUCCESS/OK) renders in red.
 *
 * <p>See docs/Plan-Machie/process-event-streaming.md for the design.
 */

import React, { useEffect, useRef } from 'react';
import type { ProcessEvent } from '../api/chat';

interface Props {
  events: ProcessEvent[];
  /** Whether a stream is currently in-flight (shows a "running" hint). */
  active: boolean;
}

export default function ActivityFeed({ events, active }: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [events]);

  if (events.length === 0 && !active) {
    return (
      <div style={S.root}>
        <div style={S.header}>
          <span>⚙️ 智能体活动</span>
        </div>
        <div style={S.empty}>
          发送消息后，这里会实时显示<br />
          智能体的工具调用、派单、结果返回。
        </div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span>⚙️ 智能体活动</span>
        <span style={S.count}>{events.length}</span>
      </div>
      <div style={S.list} ref={scrollRef}>
        {events.map((e, i) => (
          <ActivityRow key={i} evt={e} />
        ))}
        {active && (
          <div style={S.runningHint}>
            <span style={S.runningDot} />
            <span style={{ color: '#64748b', fontSize: '0.74rem' }}>运行中…</span>
          </div>
        )}
      </div>
    </div>
  );
}

const ActivityRow = React.memo(function ActivityRow({ evt }: { evt: ProcessEvent }) {
  const isSubagent = evt.source != null;
  const isFailure = evt.eventType === 'tool_result_end'
    && evt.toolCallState != null
    && evt.toolCallState !== 'SUCCESS'
    && evt.toolCallState !== 'OK';

  const ts = new Date().toLocaleTimeString('zh-CN', {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
  });

  // Collapsible tool input/output — only tool_call_start and tool_result_end carry
  // toolInput / toolOutput. Renders a clickable "入参" / "出参" pill that expands
  // to a <pre> block. Auto-collapses if the payload is huge (>2000 chars) to avoid
  // the ActivityFeed column ballooning.
  const hasInput = evt.toolInput != null && evt.toolInput.length > 0;
  const hasOutput = evt.toolOutput != null && evt.toolOutput.length > 0;
  const showCollapsible = hasInput || hasOutput;

  return (
    <div style={{
      ...S.row,
      ...(isSubagent ? S.rowSubagent : {}),
      ...(isFailure ? S.rowFailure : {}),
      flexDirection: 'column',
      alignItems: 'stretch',
    }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
        <span style={S.ts}>{ts}</span>
        <span style={{
          ...S.message,
          color: isFailure ? '#dc2626' : isSubagent ? '#0f172a' : '#1e293b',
        }}>
          {evt.message}
        </span>
      </div>
      {showCollapsible && (
        <ToolIODetail input={evt.toolInput} output={evt.toolOutput} />
      )}
    </div>
  );
}, (prev, next) => {
  // Re-render only when the event content actually changed. Without this memo,
  // every new process event triggers a re-render of ALL existing ActivityRow
  // components (because the parent's activityEvents array identity changes),
  // which becomes O(n²) for long sessions with many tool calls. The toolOutput
  // field is patched in-place by toolOutputUpdate events, so we need to compare
  // it too — otherwise the row wouldn't re-render when the output arrives.
  const a = prev.evt, b = next.evt;
  return a.eventType === b.eventType
    && a.message === b.message
    && a.source === b.source
    && a.toolCallId === b.toolCallId
    && a.toolCallName === b.toolCallName
    && a.toolCallState === b.toolCallState
    && a.toolInput === b.toolInput
    && a.toolOutput === b.toolOutput
    && a.subagentId === b.subagentId
    && a.subagentLabel === b.subagentLabel
    && a.agentNameRaw === b.agentNameRaw
    && a.agentRole === b.agentRole;
});

/**
 * Collapsible tool input/output panel. Renders two <details> elements
 * ("入参" and "出参") when the corresponding field is non-empty. Uses
 * native <details>/<summary> so we don't need state management — clicking
 * the summary toggles open/close via the browser.
 */
function ToolIODetail({ input, output }: { input?: string; output?: string }) {
  return (
    <div style={S.ioWrap}>
      {input != null && input.length > 0 && (
        <details style={S.ioDetails}>
          <summary style={S.ioSummary}>📥 入参 ({input.length} 字符)</summary>
          <pre style={S.ioPre}>{input}</pre>
        </details>
      )}
      {output != null && output.length > 0 && (
        <details style={S.ioDetails}>
          <summary style={S.ioSummary}>📤 出参 ({output.length} 字符)</summary>
          <pre style={S.ioPre}>{output}</pre>
        </details>
      )}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    minHeight: 0,
    background: '#ffffff',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '12px 16px',
    borderBottom: '1px solid #f1f5f9',
    fontSize: '0.82rem',
    fontWeight: 700,
    color: '#475569',
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    flexShrink: 0,
  },
  count: {
    fontSize: '0.7rem',
    fontWeight: 700,
    color: '#475569',
    background: '#e0e7ff',
    borderRadius: 10,
    padding: '2px 8px',
  },
  list: {
    flex: 1,
    overflowY: 'auto',
    padding: '8px 12px',
    display: 'flex',
    flexDirection: 'column',
    gap: 4,
  },
  row: {
    display: 'flex',
    gap: 8,
    padding: '6px 8px',
    borderRadius: 6,
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    alignItems: 'flex-start',
    fontSize: '0.78rem',
    lineHeight: 1.5,
    animation: 'fade-in 0.3s ease-out',
  },
  rowSubagent: {
    marginLeft: 16,
    borderLeft: '3px solid #6366f1',
    background: '#f5f3ff',
  },
  rowFailure: {
    background: '#fef2f2',
    border: '1px solid #fecaca',
  },
  ts: {
    color: '#94a3b8',
    fontFamily: 'ui-monospace, monospace',
    fontSize: '0.7rem',
    flexShrink: 0,
    paddingTop: 1,
  },
  message: {
    flex: 1,
    wordBreak: 'break-word',
  },
  empty: {
    padding: '32px 16px',
    textAlign: 'center',
    color: '#94a3b8',
    fontSize: '0.82rem',
    lineHeight: 1.7,
  },
  runningHint: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '8px 12px',
    fontStyle: 'italic',
  },
  runningDot: {
    width: 8,
    height: 8,
    borderRadius: '50%',
    background: '#3b82f6',
    animation: 'pulse-opacity 1.2s ease-in-out infinite',
  },
  ioWrap: {
    marginTop: 4,
    marginLeft: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: 4,
  },
  ioDetails: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 4,
    padding: '2px 6px',
  },
  ioSummary: {
    cursor: 'pointer',
    fontSize: '0.72rem',
    color: '#475569',
    fontWeight: 600,
    userSelect: 'none',
    padding: '2px 0',
  },
  ioPre: {
    margin: '4px 0 2px 0',
    padding: '6px 8px',
    background: '#0f172a',
    color: '#e2e8f0',
    borderRadius: 4,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.72rem',
    lineHeight: 1.4,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    maxHeight: 280,
    overflowY: 'auto',
  },
};