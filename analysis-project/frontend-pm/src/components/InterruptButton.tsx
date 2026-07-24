/**
 * InterruptButton - step 1 of two-step interrupt+resume.
 *
 * Disabled when no in-flight call (chatHandle.busy === false). When clicked,
 * calls onInterrupt() which POSTs /v2/ai/chat/interrupt (no supplement) to
 * set InterruptControl.flag = true and terminate the in-flight call.
 *
 * Step 2 (resume) is handled by the normal input box - the user types a
 * follow-up message and sends it via /v2/ai/chat to continue.
 *
 * When InterruptControl.flag is true (server-side), the panel shows a red
 * "interrupted" indicator below the button.
 */

import React from 'react';

interface Props {
  /** True when a /v2/ai/chat stream is currently in-flight. */
  enabled: boolean;
  /** Called when the user clicks the interrupt button. */
  onInterrupt: () => Promise<void> | void;
  /** Live InterruptControl.flag from /v2/ai/session/state polling. */
  interruptFlag: boolean;
}

export default function InterruptButton({ enabled, onInterrupt, interruptFlag }: Props) {
  const [submitting, setSubmitting] = React.useState(false);

  async function handleClick() {
    if (!enabled || submitting) return;
    setSubmitting(true);
    try {
      await onInterrupt();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.root}>
      <button
        style={{
          ...S.button,
          ...(enabled ? S.buttonEnabled : S.buttonDisabled),
        }}
        onClick={handleClick}
        disabled={!enabled || submitting}
        title={enabled ? '中断当前调用，随后在输入框补充信息续跑' : '当前无在跑调用可中断'}
      >
        {submitting ? '中断中…' : '✋ 中断并补充'}
        {!enabled && !submitting && ' (无在跑调用)'}
      </button>

      {interruptFlag && (
        <div style={S.flagIndicator}>
          ⚠ InterruptControl.flag = true（服务端已收到中断信号，请到输入框补充信息续跑）
        </div>
      )}

      {enabled && (
        <div style={S.hint}>
          点按钮 = 中断当前调用。<br />
          中断生效后到下方输入框输入补充信息，回车发送即续跑。
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
  hint: {
    marginTop: 8,
    fontSize: '0.74rem',
    color: '#64748b',
    lineHeight: 1.5,
  },
};
