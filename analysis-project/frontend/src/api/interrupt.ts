/**
 * POST /v2/ai/chat/interrupt client.
 *
 * <p>Single-request interrupt + supplement + auto-resume. The server
 * interrupts the in-flight call, waits for it to terminate, then starts
 * a new SSE stream with the supplement as the next user message. The
 * response body IS the resume stream (SseEmitter, same protocol as
 * /v2/ai/chat), plus the response header {@code X-Resume-Stream: true}.
 *
 * <p>See V2ChatInterruptController.java and the design doc
 * docs/rc2-to-rc5/interrupt-resume-single-endpoint-plan.md for details.
 *
 * <p>This function mirrors streamChat from chat.ts - it parses the SSE
 * response as a normalized event stream. The frontend ChatPanel should
 * close its current /v2/ai/chat reader and start consuming this stream
 * instead.
 */

import type { ChatEvent } from './chat';

export interface InterruptRequest {
  user_id: string;
  conversationId: string;
  supplement: string;
}

const PROCESS_EVENTS = new Set<string>([
  'agent_start',
  'tool_call_start',
  'tool_result_start',
  'tool_result_end',
  'subagent_exposed',
  'agent_end',
]);

export async function* interruptAndResume(
  req: InterruptRequest,
): AsyncGenerator<ChatEvent> {
  const res = await fetch('/v2/ai/chat/interrupt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok || !res.body) {
    throw new Error(`Interrupt failed: ${res.status} ${res.statusText}`);
  }

  // Header X-Resume-Stream: true signals this is the resume stream; the
  // caller should close the original /v2/ai/chat reader before consuming.
  // We don't enforce it here - the ChatPanel is responsible for the swap.

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const rawEvt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);

      const lines = rawEvt.split('\n');
      let eventName = 'message';
      let data = '';
      for (const line of lines) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (!data) continue;

      try {
        const json = JSON.parse(data);
        if (eventName === 'text_block_delta') {
          const chunk = typeof json.lineResult === 'string' ? json.lineResult : '';
          const fullText = typeof json.resultAll === 'string' ? json.resultAll : '';
          const source = json.source ?? null;
          yield { type: 'token', chunk, fullText, source };
        } else if (eventName === 'done') {
          const fullText = typeof json.resultAll === 'string' ? json.resultAll : '';
          const conversationId = typeof json.conversationId === 'string' ? json.conversationId : '';
          yield { type: 'done', fullText, conversationId };
        } else if (PROCESS_EVENTS.has(eventName)) {
          yield {
            type: 'process',
            process: {
              eventType: eventName as any,
              message: typeof json.lineResult === 'string' ? json.lineResult : '',
              source: json.source ?? null,
              toolCallId: json.toolCallId ?? undefined,
              toolCallName: json.toolCallName ?? undefined,
              toolCallState: json.toolCallState ?? undefined,
              subagentId: json.subagentId ?? undefined,
              subagentLabel: json.subagentLabel ?? undefined,
              agentNameRaw: json.agentNameRaw ?? undefined,
              agentRole: json.agentRole ?? undefined,
            },
          };
        }
      } catch {
        // skip malformed
      }
    }
  }
}