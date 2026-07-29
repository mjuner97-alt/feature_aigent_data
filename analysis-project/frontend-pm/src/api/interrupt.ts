/**
 * POST /v2/ai/chat/interrupt client (two-step design).
 *
 * <p>Step 1: POST {user_id, conversationId} to set InterruptControl.flag = true
 * and mark the in-flight call for termination. Returns JSON
 * {status: "interrupted", conversationId}.
 *
 * <p>Step 2 (handled by ChatPanel.sendMessage, not here): the user types a
 * follow-up message in the normal input box and sends it via /v2/ai/chat to
 * resume. The framework's beforeAgentExecution reloads state (with the recovery
 * msg appended from handleInterrupt), interruptControl.reset() clears the
 * flag, and the LLM continues with the new user input + saved history.
 *
 * <p>See V2ChatInterruptController.java for the backend design.
 */

export interface InterruptRequest {
  user_id: string;
  conversationId: string;
}

export interface InterruptResponse {
  status: 'interrupted';
  conversationId: string;
}

export async function triggerInterrupt(req: InterruptRequest): Promise<InterruptResponse> {
  const res = await fetch('/v2/ai/chat/interrupt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Interrupt failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as InterruptResponse;
}
