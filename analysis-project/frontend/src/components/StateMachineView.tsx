/**
 * StateMachineView - 4-card overview of AgentState sub-contexts.
 *
 * Shows PlanMode / Task / Permission / InterruptControl with state badges.
 * Highlights the InterruptControl card red when flag=true.
 */

import React from 'react';
import type { SessionStateResponse } from '../types/sessionState';

interface Props {
  state: SessionStateResponse;
}

export default function StateMachineView({ state }: Props) {
  const cards: Array<{
    label: string;
    value: string;
    color: string;
    pulse?: boolean;
  }> = [
    {
      label: 'PlanMode',
      value: state.planMode.planActive ? 'ACTIVE' : 'INACTIVE',
      color: state.planMode.planActive ? '#10b981' : '#94a3b8',
    },
    {
      label: 'TaskList',
      value: `${state.tasks.length} task${state.tasks.length === 1 ? '' : 's'}`,
      color: state.tasks.length > 0 ? '#3b82f6' : '#94a3b8',
    },
    {
      label: 'Permission',
      value: state.permission.mode,
      color: '#6366f1',
    },
    {
      label: 'InterruptControl',
      value: state.interruptControl.flag ? 'FLAG=true' : 'flag=false',
      color: state.interruptControl.flag ? '#ef4444' : '#94a3b8',
      pulse: state.interruptControl.flag,
    },
  ];

  return (
    <div style={S.root}>
      <div style={S.title}>🔄 Agent State Machine</div>
      <div style={S.grid}>
        {cards.map((c, i) => (
          <div
            key={i}
            style={{
              ...S.card,
              borderColor: c.color,
              boxShadow: c.pulse ? `0 0 0 2px ${c.color}55` : 'none',
              animation: c.pulse ? 'pulse-border 1.2s ease-in-out infinite' : 'none',
            }}
          >
            <div style={{ ...S.cardLabel, color: c.color }}>{c.label}</div>
            <div style={{ ...S.cardValue, color: c.color }}>{c.value}</div>
          </div>
        ))}
      </div>
      <style>{`
        @keyframes pulse-border {
          0%, 100% { box-shadow: 0 0 0 2px #ef444455; }
          50% { box-shadow: 0 0 0 4px #ef444433; }
        }
      `}</style>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    padding: '14px 18px',
    borderBottom: '1px solid #f1f5f9',
  },
  title: {
    fontSize: '0.82rem',
    fontWeight: 700,
    color: '#475569',
    letterSpacing: '0.04em',
    marginBottom: 10,
    textTransform: 'uppercase',
  },
  grid: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: 8,
  },
  card: {
    padding: '10px 12px',
    borderRadius: 8,
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
  },
  cardLabel: {
    fontSize: '0.7rem',
    fontWeight: 600,
    letterSpacing: '0.05em',
    textTransform: 'uppercase',
    marginBottom: 4,
  },
  cardValue: {
    fontSize: '0.9rem',
    fontWeight: 700,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
};