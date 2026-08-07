/**
 * GET /v2/ai/session/state polling client + Vue composable.
 *
 * <p>The frontend polls this every 2s to render PlanNotebook + state machine
 * panels. Polling was chosen over SSE state_changed events to avoid touching
 * V2ChatStreamServiceImpl.handleEvent (regression risk).
 *
 * <p>The composable exposes a refresh() function for immediate state fetches after
 * side-effects like interrupts, so the UI doesn't have to wait up to 2s.
 */

import { ref, watch, onUnmounted, type Ref } from 'vue';
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
 * Composable: poll /v2/ai/session/state every 2s. Returns { state, refresh }.
 *
 * - state: ref(null) while loading or if conversationId is null
 * - refresh: call to immediately fetch state (e.g. after interrupt)
 *
 * Errors are swallowed (transient network blips shouldn't kill the panel);
 * the next tick will retry.
 *
 * @param userIdRef          Ref containing the stable user id (null disables polling)
 * @param conversationIdRef  Ref containing the active session id (null disables polling)
 */
export function useSessionState(
  userIdRef: Ref<string | null | undefined>,
  conversationIdRef: Ref<string | null | undefined>,
) {
  const state = ref<SessionStateResponse | null>(null);
  let cancelled = false;
  let timerId: ReturnType<typeof setInterval> | null = null;

  async function tick() {
    const uid = userIdRef.value;
    const cid = conversationIdRef.value;
    if (!uid || !cid) return;
    try {
      const s = await getSessionState(uid, cid);
      if (!cancelled) state.value = s;
    } catch {
      // swallow — keep previous state, retry on next tick
    }
  }

  function startPolling() {
    stopPolling();
    cancelled = false;
    tick();
    timerId = setInterval(tick, POLL_INTERVAL_MS);
  }

  function stopPolling() {
    cancelled = true;
    if (timerId !== null) {
      clearInterval(timerId);
      timerId = null;
    }
  }

  watch(
    [userIdRef, conversationIdRef],
    ([uid, cid]) => {
      if (uid && cid) {
        startPolling();
      } else {
        state.value = null;
        stopPolling();
      }
    },
    { immediate: true },
  );

  onUnmounted(() => stopPolling());

  return { state, refresh: tick };
}
