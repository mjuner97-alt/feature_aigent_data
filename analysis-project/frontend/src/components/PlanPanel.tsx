/**
 * PlanPanel - renders subagent plan mode state inferred from SSE events.
 *
 * The main agent no longer has plan mode (it's a pure router). Plan mode now
 * lives on subagents (e.g. analyze_data). Since subagent AgentState is not
 * accessible via /v2/ai/session/state, we infer plan mode transitions from
 * SSE tool_call_start events:
 *   - plan_enter + source="analyze_data" → PLAN mode
 *   - plan_exit  + source="analyze_data" → BUILD mode
 *
 * planContent is always null for now — there's no endpoint to read the
 * subagent's PLAN.md file. Future: add GET /v2/ai/session/plan?file=...
 */

import React from 'react';
import type { SubagentPlanState } from '../types/sessionState';

interface Props {
  subagentPlans: SubagentPlanState[];
}

export default function PlanPanel({ subagentPlans }: Props) {
  if (subagentPlans.length === 0) {
    return (
      <div style={S.root}>
        <div style={S.header}>
          <span style={S.title}>📋 Plan Mode</span>
          <span style={{ ...S.badge, background: '#f1f5f9', color: '#94a3b8', border: '1px solid #e2e8f0' }}>
            ○ 无
          </span>
        </div>
        <div style={S.muted}>
          子智能体尚未进入 Plan Mode。发送分析类任务时，analyze_data 会自动进入 Plan Mode 制定计划。
        </div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={S.title}>📋 Plan Mode</span>
        <span style={S.badgeCount}>
          {subagentPlans.length} 个子智能体
        </span>
      </div>
      {subagentPlans.map(plan => {
        const active = plan.planActive;
        return (
          <div key={plan.agentName} style={S.agentRow}>
            <span style={S.agentName}>{plan.agentName}</span>
            <span style={{
              ...S.stateBadge,
              background: active ? '#dcfce7' : '#fef9c3',
              color: active ? '#15803d' : '#a16207',
              border: `1px solid ${active ? '#86efac' : '#fde047'}`,
            }}>
              {active ? '● PLAN' : '▸ BUILD'}
            </span>
          </div>
        );
      })}
      <div style={S.muted}>
        Plan 文件内容暂不可读（子智能体 workspace 隔离）
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    padding: '14px 18px',
    borderBottom: '1px solid #f1f5f9',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  title: {
    fontSize: '0.82rem',
    fontWeight: 700,
    color: '#475569',
    letterSpacing: '0.04em',
    textTransform: 'uppercase' as const,
  },
  badgeCount: {
    fontSize: '0.72rem',
    fontWeight: 700,
    color: '#475569',
    background: '#e0e7ff',
    borderRadius: 10,
    padding: '2px 8px',
  },
  agentRow: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '6px 10px',
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 8,
    marginBottom: 6,
  },
  agentName: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem',
    fontWeight: 600,
    color: '#0f172a',
  },
  stateBadge: {
    fontSize: '0.7rem',
    fontWeight: 700,
    padding: '3px 8px',
    borderRadius: 6,
    letterSpacing: '0.04em',
  },
  badge: {
    fontSize: '0.7rem',
    fontWeight: 700,
    padding: '3px 8px',
    borderRadius: 6,
    letterSpacing: '0.04em',
  },
  muted: {
    fontSize: '0.78rem',
    color: '#94a3b8',
    lineHeight: 1.6,
  },
};