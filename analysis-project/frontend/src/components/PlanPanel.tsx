/**
 * PlanPanel - renders PlanModeContextState + plan file content.
 *
 * Shows plan_active badge + current plan file path + the file's Markdown
 * content (rendered as plain preformatted text; no MD library dependency
 * for the MVP - line wrapping is enough to read the plan).
 *
 * When plan is inactive, shows a muted placeholder so the panel doesn't
 * collapse to zero height (keeps the right-column layout stable).
 */

import React from 'react';
import type { PlanModeState } from '../types/sessionState';

interface Props {
  planMode: PlanModeState;
}

export default function PlanPanel({ planMode }: Props) {
  const active = planMode.planActive;

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={S.title}>📋 Plan Mode</span>
        <span style={{
          ...S.badge,
          background: active ? '#dcfce7' : '#f1f5f9',
          color: active ? '#15803d' : '#94a3b8',
          border: `1px solid ${active ? '#86efac' : '#e2e8f0'}`,
        }}>
          {active ? '● ACTIVE' : '○ inactive'}
        </span>
      </div>

      {active && planMode.currentPlanFile && (
        <div style={S.fileRow}>
          <span style={S.fileLabel}>File:</span>
          <code style={S.fileCode}>{planMode.currentPlanFile}</code>
        </div>
      )}

      {active && planMode.planContent && (
        <pre style={S.content}>{planMode.planContent}</pre>
      )}

      {active && !planMode.planContent && (
        <div style={S.muted}>Plan 文件暂未读取到内容（路径：{planMode.currentPlanFile ?? '(null)'}）</div>
      )}

      {!active && (
        <div style={S.muted}>未进入 Plan Mode。触发词："分析+报告" / "多步" / "完整方案" / "详细" / "全面" 或 ≥3 个清晰步骤。</div>
      )}
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
  badge: {
    fontSize: '0.7rem',
    fontWeight: 700,
    padding: '3px 8px',
    borderRadius: 6,
    letterSpacing: '0.04em',
  },
  fileRow: {
    display: 'flex',
    gap: 6,
    alignItems: 'center',
    fontSize: '0.78rem',
    color: '#475569',
    marginBottom: 8,
  },
  fileLabel: { color: '#94a3b8' },
  fileCode: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.75rem',
    background: '#f1f5f9',
    padding: '1px 6px',
    borderRadius: 4,
  },
  content: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 8,
    padding: 12,
    fontSize: '0.82rem',
    lineHeight: 1.55,
    color: '#334155',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    maxHeight: 280,
    overflowY: 'auto',
  },
  muted: {
    fontSize: '0.78rem',
    color: '#94a3b8',
    lineHeight: 1.6,
  },
};