/**
 * InterruptButton - triggers interrupt on the in-flight call.
 *
 * Clicking the button sends POST /v2/ai/chat/interrupt which:
 * 1. Sets InterruptControl.flag = true on the in-flight call
 * 2. Waits for the call to terminate
 * 3. Returns JSON confirmation
 *
 * After interrupt, the user types their follow-up message in the normal
 * chat input and sends it via /v2/ai/chat to resume. No supplement textarea
 * needed here — the interrupt is decoupled from the resume.
 */

import React, { useState } from 'react';

interface Props {
  /** True when a /v2/ai/chat stream is currently in-flight. */
  enabled: boolean;
  /** Called when the user clicks interrupt. */
  onInterrupt: () => Promise<void> | void;
  /** Live InterruptControl.flag from /v2/ai/session/state polling. */
  interruptFlag: boolean;
}

export default function InterruptButton({ enabled, onInterrupt, interruptFlag }: Props) {
  const [submitting, setSubmitting] = useState(false);

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
        title={enabled ? '中断当前调用，然后输入补充信息续跑' : '当前无在跑调用可中断'}
      >
        {submitting ? '中断中…' : '✋ 中断'}{!enabled ? ' (无在跑调用)' : ''}
      </button>

      {interruptFlag && (
        <div style={S.hint}>
          ⚠ 已中断 — 在下方输入框输入补充信息并发送即可续跑
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
  hint: {
    marginTop: 8,
    padding: '6px 10px',
    background: '#fffbeb',
    border: '1px solid #fde68a',
    borderRadius: 6,
    fontSize: '0.74rem',
    color: '#92400e',
    fontWeight: 500,
    textAlign: 'center',
    lineHeight: 1.5,
  },
};