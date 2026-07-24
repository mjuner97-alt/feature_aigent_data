/**
 * ChatPage - three-column layout:
 *   [SessionsSidebar] [ChatPanel] [StateColumn]
 *
 * Holds the active userId + conversationId state, passes them to ChatPanel and
 * to the state panel hook (useSessionState polls /v2/ai/session/state every 2s).
 *
 * The InterruptButton in the right column calls the ChatPanel's interrupt handle;
 * the state panel reflects the live PlanMode/Task/Permission/Interrupt snapshots.
 */

import React, { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import ChatPanel, { type ChatPanelHandle } from '../components/ChatPanel';
import PlanPanel from '../components/PlanPanel';
import TodoListPanel from '../components/TodoListPanel';
import TaskDependencyGraph from '../components/TaskDependencyGraph';
import StateMachineView from '../components/StateMachineView';
import InterruptButton from '../components/InterruptButton';
import { useSessionState } from '../api/sessionState';
import type { SubagentPlanState } from '../types/sessionState';
import { getOrCreateUserId, rememberSession } from '../components/SessionsSidebar';

export default function ChatPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  // userId resolution: URL ?userId= > localStorage random id. URL param takes
  // precedence so the operator can manually switch identity for multi-user
  // testing (e.g. /chat?userId=alice vs /chat?userId=bob) without clearing
  // localStorage. When no URL param is present, fall back to the persisted
  // random id so each browser keeps a stable identity across reloads.
  const urlUserId = searchParams.get('userId');
  const userId = urlUserId || getOrCreateUserId();

  // conversationId: URL ?session= > state (minted by ChatPanel on first send)
  const [conversationId, setConversationId] = useState<string | null>(
    searchParams.get('session'),
  );

  // Poll state every 2s; null while loading or when no conversationId
  const state = useSessionState(userId, conversationId);

  // Interrupt handle (set by ChatPanel)
  const [chatHandle, setChatHandle] = useState<ChatPanelHandle | null>(null);

  // Subagent plan/todo state inferred from SSE tool_call_start events.
  // The main agent no longer has plan mode (it's a pure router), so
  // /v2/ai/session/state always returns planActive=false and empty tasks.
  // Instead, we infer plan mode from SSE events: plan_enter/plan_exit/todo_write
  // with source=analyze_data etc.
  const [subagentPlans, setSubagentPlans] = useState<Record<string, SubagentPlanState>>({});
  const [subagentTodoCounts, setSubagentTodoCounts] = useState<Record<string, number>>({});

  function handleConversationId(id: string) {
    setConversationId(id);
    const next = new URLSearchParams(searchParams);
    if (next.get('session') !== id) {
      next.set('session', id);
      setSearchParams(next, { replace: true });
    }
  }

  function handleUserMessage(text: string) {
    if (conversationId) rememberSession(conversationId, text);
    else {
      // First send: conversationId not yet known (ChatPanel will mint it). Defer
      // remembering until handleConversationId fires - see useEffect below.
      pendingRememberRef.current = text;
    }
  }
  const pendingRememberRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    if (conversationId && pendingRememberRef.current) {
      rememberSession(conversationId, pendingRememberRef.current);
      pendingRememberRef.current = null;
    }
  }, [conversationId]);

  return (
    <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
      {/* Center: chat */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <ChatPanel
          userId={userId}
          conversationId={conversationId}
          onConversationId={handleConversationId}
          onUserMessage={handleUserMessage}
          registerInterruptHandle={setChatHandle}
          onSubagentPlanChange={(plans, counts) => {
            setSubagentPlans(plans);
            setSubagentTodoCounts(counts);
          }}
        />
      </div>

      {/* Right: state panels */}
      <div style={stateColStyle}>
        <div style={stateHeaderStyle}>
          <span>PlanNotebook + 状态机</span>
          {conversationId && (
            <span style={{ fontSize: '0.72rem', color: '#94a3b8', fontFamily: 'ui-monospace, monospace' }}>
              {conversationId.slice(0, 8)}…
            </span>
          )}
        </div>
        <div style={userIdRowStyle}>
          <span style={userIdLabelStyle}>用户身份</span>
          <span style={userIdChipStyle(urlUserId != null)} title={urlUserId ? '来源: URL ?userId=' : '来源: localStorage (改 URL 加 ?userId=xxx 切换)'}>
            {userId}
          </span>
        </div>

        {!conversationId && (
          <div style={emptyStateStyle}>
            发送一条消息开始会话，<br />状态机会在 2 秒内显示。
          </div>
        )}

        {conversationId && !state && (
          <div style={emptyStateStyle}>加载状态…</div>
        )}

        {state && (
          <>
            <StateMachineView state={state} subagentPlans={subagentPlans} />
            <PlanPanel subagentPlans={Object.values(subagentPlans)} />
            <TodoListPanel tasks={state.tasks} subagentTodoWriteCounts={subagentTodoCounts} />
            <TaskDependencyGraph tasks={state.tasks} />
            <InterruptButton
              enabled={!!chatHandle?.busy}
              onSubmit={(supplement) => chatHandle?.interrupt(supplement)}
              interruptFlag={state.interruptControl.flag}
            />
          </>
        )}
      </div>
    </div>
  );
}

const stateColStyle: React.CSSProperties = {
  width: 380,
  flexShrink: 0,
  background: '#ffffff',
  borderLeft: '1px solid #e2e8f0',
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  overflowY: 'auto',
};

const stateHeaderStyle: React.CSSProperties = {
  padding: '14px 18px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.92rem',
  fontWeight: 600,
  color: '#0f172a',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  flexShrink: 0,
};

const emptyStateStyle: React.CSSProperties = {
  padding: '40px 20px',
  textAlign: 'center',
  color: '#94a3b8',
  fontSize: '0.88rem',
  lineHeight: 1.7,
};

const userIdRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 18px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.78rem',
  flexShrink: 0,
};

const userIdLabelStyle: React.CSSProperties = {
  color: '#94a3b8',
  fontWeight: 500,
};

function userIdChipStyle(fromUrl: boolean): React.CSSProperties {
  return {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.74rem',
    fontWeight: 600,
    padding: '2px 8px',
    borderRadius: 4,
    background: fromUrl ? '#ddd6fe' : '#e0e7ff',
    color: fromUrl ? '#5b21b6' : '#3730a3',
    border: fromUrl ? '1px solid #c4b5fd' : '1px solid #c7d2fe',
    cursor: 'help',
  };
}