/**
 * InterruptButton - triggers /v2/ai/chat/interrupt with user supplement.
 *
 * Disabled when no in-flight call (chatHandle.busy === false). When the user
 * submits, calls the ChatPanel's interrupt(supplement) handle, which closes
 * the current /v2/ai/chat SSE stream and starts the /v2/ai/chat/interrupt
 * resume stream.
 *
 * When InterruptControl.flag is true (server-side), the panel shows a red
 * "interrupted" indicator below the form.
 */

import React, { useState } from 'react';

interface Props {
  /** True when a /v2/ai/chat stream is currently in-flight. */
  enabled: boolean;
  /** Called with the user's supplement text. The ChatPanel handles the swap. */
  onSubmit: (supplement: string) => Promise<void> | void;
  /** Live InterruptControl.flag from /v2/ai/session/state polling. */
  interruptFlag: boolean;
}

export default function InterruptButton({ enabled, onSubmit, interruptFlag }: Props) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit() {
    if (!enabled || !text.trim() || submitting) return;
    setSubmitting(true);
    try {
      await onSubmit(text.trim());
      setText('');
      setOpen(false);
    } finally {
      setSubmitting(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      handleSubmit();
    }
  }

  return (
    <div style={S.root}>
      <button
        style={{
          ...S.button,
          ...(enabled ? S.buttonEnabled : S.buttonDisabled),
        }}
        onClick={() => enabled && setOpen(o => !o)}
        disabled={!enabled}
        title={enabled ? '中断当前调用并补充信息' : '当前无在跑调用可中断'}
      >
        ✋ 中断并补充 {enabled ? '' : '(无在跑调用)'}
      </button>

      {interruptFlag && (
        <div style={S.flagIndicator}>
          ⚠ InterruptControl.flag = true（服务端已收到中断信号）
        </div>
      )}

      {open && enabled && (
        <div style={S.form}>
          <div style={S.hint}>
            补充信息将作为新 user message 续跑 LLM。<br />
            原调用会保存状态（含 recovery msg）后退出。
          </div>
          <textarea
            style={S.textarea}
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="例如：改成按产品线分组，重点对比一部 vs 二部"
            rows={3}
            autoFocus
            disabled={submitting}
          />
          <div style={S.actions}>
            <button
              style={S.cancelBtn}
              onClick={() => { setOpen(false); setText(''); }}
              disabled={submitting}
            >取消</button>
            <button
              style={{
                ...S.submitBtn,
                ...(!text.trim() || submitting ? S.submitBtnDisabled : {}),
              }}
              onClick={handleSubmit}
              disabled={!text.trim() || submitting}
            >
              {submitting ? '中断中…' : '中断+续跑 (Ctrl+Enter)'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    padding: '14px 18px',
  },
  button: {
    width: '100%',
    padding: '10px 14px',
    borderRadius: 10,
    fontSize: '0.88rem',
    fontWeight: 600,
    border: 'none',
    cursor: 'pointer',
  },
  buttonEnabled: {
    background: 'linear-gradient(135deg,#f97316 0%,#ef4444 100%)',
    color: '#ffffff',
    boxShadow: '0 2px 6px rgba(239,68,68,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  buttonDisabled: {
    background: '#f1f5f9',
    color: '#94a3b8',
    cursor: 'not-allowed',
    border: '1px solid #e2e8f0',
  },
  flagIndicator: {
    marginTop: 8,
    padding: '6px 10px',
    background: '#fef2f2',
    border: '1px solid #fecaca',
    borderRadius: 6,
    fontSize: '0.74rem',
    color: '#dc2626',
    fontWeight: 600,
    textAlign: 'center',
  },
  form: {
    marginTop: 10,
    padding: 12,
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 10,
  },
  hint: {
    fontSize: '0.74rem',
    color: '#64748b',
    marginBottom: 8,
    lineHeight: 1.5,
  },
  textarea: {
    width: '100%',
    padding: '8px 10px',
    border: '1px solid #cbd5e1',
    borderRadius: 6,
    fontSize: '0.85rem',
    resize: 'vertical',
    minHeight: 64,
    lineHeight: 1.5,
    fontFamily: 'inherit',
    color: '#0f172a',
    background: '#ffffff',
  },
  actions: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: 8,
    marginTop: 8,
  },
  cancelBtn: {
    padding: '6px 12px',
    background: '#ffffff',
    color: '#475569',
    border: '1px solid #e2e8f0',
    borderRadius: 6,
    fontSize: '0.82rem',
    cursor: 'pointer',
  },
  submitBtn: {
    padding: '6px 14px',
    background: 'linear-gradient(135deg,#f97316 0%,#ef4444 100%)',
    color: '#ffffff',
    border: 'none',
    borderRadius: 6,
    fontSize: '0.82rem',
    fontWeight: 600,
    cursor: 'pointer',
  },
  submitBtnDisabled: {
    background: '#e2e8f0',
    color: '#94a3b8',
    cursor: 'not-allowed',
  },
};
