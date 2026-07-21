/**
 * TodoListPanel - renders TaskContextState.tasks with state badges.
 *
 * Tasks have state PENDING / IN_PROGRESS / COMPLETED / FAILED. The panel
 * shows them in order received (the backend keeps insertion order; for
 * sorted display use TaskDependencyGraph).
 *
 * Color mapping:
 *   PENDING      → gray (#94a3b8)  ○
 *   IN_PROGRESS  → blue (#3b82f6)  ▸  + pulse
 *   COMPLETED    → green (#10b981) ✓
 *   FAILED       → red (#ef4444)  ✗
 */

import React from 'react';
import type { TaskState } from '../types/sessionState';

interface Props {
  tasks: TaskState[];
}

const STATE_STYLES: Record<string, { icon: string; color: string; pulse?: boolean }> = {
  PENDING:     { icon: '○', color: '#94a3b8' },
  IN_PROGRESS: { icon: '▸', color: '#3b82f6', pulse: true },
  COMPLETED:   { icon: '✓', color: '#10b981' },
  FAILED:      { icon: '✗', color: '#ef4444' },
};

function styleFor(state: string) {
  return STATE_STYLES[state.toUpperCase()] ?? { icon: '?', color: '#94a3b8' };
}

export default function TodoListPanel({ tasks }: Props) {
  const counts = tasks.reduce<Record<string, number>>((acc, t) => {
    const k = (t.state ?? 'UNKNOWN').toUpperCase();
    acc[k] = (acc[k] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={S.title}>✅ Task List</span>
        <span style={S.count}>{tasks.length}</span>
      </div>

      {tasks.length === 0 && (
        <div style={S.muted}>无 Task。LLM 调用 todo_write 工具后会在此显示。</div>
      )}

      {tasks.length > 0 && (
        <div style={S.summaryRow}>
          {Object.entries(STATE_STYLES).map(([k, v]) => {
            const c = counts[k] ?? 0;
            if (c === 0) return null;
            return (
              <span key={k} style={{ ...S.summaryChip, color: v.color, borderColor: v.color + '55' }}>
                {v.icon} {c} {k.replace('IN_PROGRESS', '进行中').replace('PENDING', '待办').replace('COMPLETED', '完成').replace('FAILED', '失败')}
              </span>
            );
          })}
        </div>
      )}

      <div style={S.list}>
        {tasks.map((t, i) => {
          const s = styleFor(t.state ?? '');
          return (
            <div key={t.id ?? i} style={S.item}>
              <span style={{
                ...S.icon,
                color: s.color,
                animation: s.pulse ? 'pulse-opacity 1.2s ease-in-out infinite' : 'none',
              }}>{s.icon}</span>
              <div style={S.itemBody}>
                <div style={S.itemSubject}>{t.subject || '(no subject)'}</div>
                {t.description && (
                  <div style={S.itemDesc}>{t.description}</div>
                )}
                <div style={S.itemMeta}>
                  <span style={{ color: s.color, fontWeight: 600 }}>{(t.state ?? '').toUpperCase()}</span>
                  {t.owner && <span style={S.metaMuted}>owner: {t.owner}</span>}
                  {t.createdAt && <span style={S.metaMuted}>{t.createdAt}</span>}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <style>{`
        @keyframes pulse-opacity {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.45; }
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
    textTransform: 'uppercase',
  },
  count: {
    fontSize: '0.72rem',
    fontWeight: 700,
    color: '#475569',
    background: '#e0e7ff',
    borderRadius: 10,
    padding: '2px 8px',
  },
  summaryRow: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 4,
    marginBottom: 10,
  },
  summaryChip: {
    fontSize: '0.7rem',
    fontWeight: 600,
    background: '#f8fafc',
    border: '1px solid',
    borderRadius: 6,
    padding: '2px 6px',
  },
  list: {
    display: 'flex',
    flexDirection: 'column',
    gap: 6,
  },
  item: {
    display: 'flex',
    gap: 10,
    padding: '8px 10px',
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 8,
  },
  icon: {
    fontSize: '1rem',
    fontWeight: 700,
    flexShrink: 0,
    width: 18,
    textAlign: 'center',
    lineHeight: 1.4,
  },
  itemBody: {
    flex: 1,
    minWidth: 0,
  },
  itemSubject: {
    fontSize: '0.85rem',
    fontWeight: 600,
    color: '#0f172a',
    wordBreak: 'break-word',
  },
  itemDesc: {
    fontSize: '0.78rem',
    color: '#64748b',
    marginTop: 2,
    wordBreak: 'break-word',
    whiteSpace: 'pre-wrap',
  },
  itemMeta: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 4,
    fontSize: '0.7rem',
  },
  metaMuted: {
    color: '#94a3b8',
  },
  muted: {
    fontSize: '0.78rem',
    color: '#94a3b8',
    lineHeight: 1.6,
  },
};