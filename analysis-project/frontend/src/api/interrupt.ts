/**
 * POST /v2/ai/chat/interrupt client — interrupt-only, no resume.
 *
 * <p>Sends an interrupt request that:
 * <ol>
 *   <li>Triggers InterruptControl on the in-flight call</li>
 *   <li>Waits for the in-flight call to terminate</li>
 *   <li>Returns JSON confirmation { status: "interrupted", conversationId }</li>
 * </ol>
 *
 * <p>The frontend should:
 * <ol>
 *   <li>Call this endpoint to interrupt the current call</li>
 *   <li>Close the original /v2/ai/chat SSE stream</li>
 *   <li>Show the normal chat input for the user to type a follow-up message</li>
 *   <li>Send the follow-up as a regular /v2/ai/chat request to resume</li>
 * </ol>
 */

export interface InterruptRequest {
  user_id: string;
  conversationId: string;
}

export interface InterruptResponse {
  status: string;
  conversationId: string;
}

export async function triggerInterrupt(
  req: InterruptRequest,
): Promise<InterruptResponse> {
  const res = await fetch('/v2/ai/chat/interrupt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Interrupt failed: ${res.status} ${res.statusText}${text ? ' — ' + text : ''}`);
  }
  return res.json() as Promise<InterruptResponse>;
}