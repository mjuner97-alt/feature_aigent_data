/**
 * TaskDependencyGraph - SVG visualization of blocks/blockedBy edges.
 *
 * Tasks are arranged horizontally by createdAt order. For each task's blocks[]
 * list, an arrow is drawn from this task to each blocked target (i.e. "A
 * blocks B" means B can't start until A is done, so arrow A→B).
 *
 * MVP: plain SVG with manual layout. If the task count grows large or
 * dependencies get tangled, consider switching to reactflow.
 */

import React from 'react';
import type { TaskState } from '../types/sessionState';

interface Props {
  tasks: TaskState[];
}

const STATE_COLORS: Record<string, string> = {
  PENDING: '#94a3b8',
  IN_PROGRESS: '#3b82f6',
  COMPLETED: '#10b981',
  FAILED: '#ef4444',
};

function colorFor(state: string | undefined) {
  return STATE_COLORS[(state ?? '').toUpperCase()] ?? '#94a3b8';
}

export default function TaskDependencyGraph({ tasks }: Props) {
  // Build node index by id
  const idIndex = new Map<string, number>();
  tasks.forEach((t, i) => {
    if (t.id) idIndex.set(t.id, i);
  });

  // Build edges: for each task's blocks[] list, draw edge from this → target
  const edges: Array<{ from: number; to: number }> = [];
  tasks.forEach((t, i) => {
    if (!t.blocks) return;
    for (const blocked of t.blocks) {
      const target = idIndex.get(blocked);
      if (target !== undefined && target !== i) {
        edges.push({ from: i, to: target });
      }
    }
  });

  if (tasks.length === 0) {
    return (
      <div style={S.root}>
        <div style={S.title}>🔗 Dependency Graph</div>
        <div style={S.muted}>无 Task，无可视化。</div>
      </div>
    );
  }

  // Layout: horizontal row, each node 110px wide, gap 30px
  const nodeW = 110;
  const nodeH = 44;
  const gap = 30;
  const totalWidth = tasks.length * nodeW + (tasks.length - 1) * gap;
  const height = 80;  // 1 row of nodes + arrow space
  const padX = 12;

  const xOf = (i: number) => padX + i * (nodeW + gap);
  const yOf = (i: number) => 30;

  return (
    <div style={S.root}>
      <div style={S.title}>🔗 Dependency Graph</div>
      <div style={S.scroll}>
        <svg
          width={totalWidth + padX * 2}
          height={height}
          style={{ display: 'block' }}
        >
          {/* Edges */}
          {edges.map((e, i) => {
            const x1 = xOf(e.from) + nodeW;
            const y1 = yOf(e.from) + nodeH / 2;
            const x2 = xOf(e.to);
            const y2 = yOf(e.to) + nodeH / 2;
            // Arc upward when on the same row
            const midX = (x1 + x2) / 2;
            const midY = Math.min(y1, y2) - 14;
            const path = `M ${x1} ${y1} Q ${midX} ${midY} ${x2} ${y2}`;
            return (
              <g key={`edge-${i}`}>
                <path d={path} fill="none" stroke="#cbd5e1" strokeWidth={1.5} strokeDasharray="3 3" />
                <circle cx={x2} cy={y2} r={2.5} fill="#64748b" />
              </g>
            );
          })}

          {/* Nodes */}
          {tasks.map((t, i) => {
            const x = xOf(i);
            const y = yOf(i);
            const color = colorFor(t.state);
            const subject = t.subject?.length > 14 ? t.subject.slice(0, 12) + '…' : (t.subject || '(no subject)');
            return (
              <g key={`node-${t.id ?? i}`}>
                <rect
                  x={x}
                  y={y}
                  width={nodeW}
                  height={nodeH}
                  rx={6}
                  fill="#f8fafc"
                  stroke={color}
                  strokeWidth={1.5}
                />
                <text
                  x={x + 8}
                  y={y + 18}
                  fontSize={11}
                  fontWeight={600}
                  fill="#0f172a"
                  fontFamily="system-ui, sans-serif"
                >
                  {subject}
                </text>
                <text
                  x={x + 8}
                  y={y + 34}
                  fontSize={9}
                  fill={color}
                  fontFamily="ui-monospace, monospace"
                  fontWeight={700}
                >
                  {(t.state ?? 'UNKNOWN').toUpperCase()}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      {edges.length > 0 && (
        <div style={S.legend}>
          <span style={{ color: '#94a3b8', marginRight: 8 }}>→</span>
          {edges.length} 条依赖关系（blocks）
        </div>
      )}
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
    textTransform: 'uppercase',
    marginBottom: 10,
  },
  scroll: {
    overflowX: 'auto',
    paddingBottom: 6,
  },
  muted: {
    fontSize: '0.78rem',
    color: '#94a3b8',
    lineHeight: 1.6,
  },
  legend: {
    marginTop: 8,
    fontSize: '0.72rem',
    color: '#64748b',
    display: 'flex',
    alignItems: 'center',
  },
};