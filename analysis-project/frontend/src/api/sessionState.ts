/**
 * GET /v2/ai/session/state polling client + React hook.
 *
 * <p>The frontend polls this every 2s to render PlanNotebook + state machine
 * panels. Polling was chosen over SSE state_changed events to avoid touching
 * V2ChatStreamServiceImpl.handleEvent (regression risk).
 *
 * <p>The hook exposes a refresh() function for immediate state fetches after
 * side-effects like interrupts, so the UI doesn't have to wait up to 2s.
 *
 * <p>See docs/Plan-Machie/plan-notebook-frontend-design.md §五.3 for the
 * polling strategy and the StateCache tradeoff.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type { SessionStateResponse } from '../types/sessionState';

const POLL_INTERVAL_MS = 2000;

export async function getSessionState(
  userId: string,
  conversationId: string,
): Promise<SessionStateResponse> {
  const url =
    `/v2/ai/session/state?userId=${encodeURIComponent(userId)}` +
    `&conversationId=${encodeURIComponent(conversationId)}`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Get state failed: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<SessionStateResponse>;
}

/**
 * Hook: poll /v2/ai/session/state every 2s. Returns [state, refresh].
 *
 * - state: null while loading or if conversationId is null
 * - refresh: call to immediately fetch state (e.g. after interrupt)
 *
 * Errors are swallowed (transient network blips shouldn't kill the panel);
 * the next tick will retry.
 *
 * @param userId          Stable user id ("" or null disables polling)
 * @param conversationId  Active session id (null disables polling)
 */
export function useSessionState(
  userId: string | null | undefined,
  conversationId: string | null | undefined,
): [SessionStateResponse | null, () => void] {
  const [state, setState] = useState<SessionStateResponse | null>(null);
  const cancelledRef = useRef(false);

  const tick = useCallback(async () => {
    if (!userId || !conversationId) return;
    try {
      const s = await getSessionState(userId, conversationId);
      if (!cancelledRef.current) setState(s);
    } catch {
      // swallow - keep previous state, retry on next tick
    }
  }, [userId, conversationId]);

  useEffect(() => {
    if (!userId || !conversationId) {
      setState(null);
      return;
    }
    cancelledRef.current = false;

    tick(); // initial fetch
    const id = setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelledRef.current = true;
      clearInterval(id);
    };
  }, [userId, conversationId, tick]);

  return [state, tick];
}