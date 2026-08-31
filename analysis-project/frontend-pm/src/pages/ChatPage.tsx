/**
 * ChatPage - three-column layout:
 *   [SessionsSidebar] [ChatPanel] [StateColumn]
 *
 * Holds the active userId + conversationId state, passes them to ChatPanel and
 * to the state panel hook (useSessionState polls /v2/ai/session/state every 2s).
 *
 * The InterruptButton in the right column calls the ChatPanel's interrupt handle;
 * the state panel reflects the live PlanMode/Task/Permission/Interrupt snapshots.
 */

import React, { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import ChatPanel from '../components/ChatPanel';
import { getOrCreateUserId, rememberSession } from '../components/SessionsSidebar';

export default function ChatPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  // userId resolution: URL ?userId= > localStorage random id. URL param takes
  // precedence so the operator can manually switch identity for multi-user
  // testing (e.g. /chat?userId=alice vs /chat?userId=bob) without clearing
  // localStorage. When no URL param is present, fall back to the persisted
  // random id so each browser keeps a stable identity across reloads.
  const urlUserId = searchParams.get('userId');
  const userId = urlUserId || getOrCreateUserId();

  // conversationId: URL ?session= > state (minted by ChatPanel on first send)
  const [conversationId, setConversationId] = useState<string | null>(
    searchParams.get('session'),
  );


  function handleConversationId(id: string) {
    setConversationId(id);
    const next = new URLSearchParams(searchParams);
    if (next.get('session') !== id) {
      next.set('session', id);
      setSearchParams(next, { replace: true });
    }
  }

  function handleUserMessage(text: string) {
    if (conversationId) rememberSession(conversationId, text);
    else {
      // First send: conversationId not yet known (ChatPanel will mint it). Defer
      // remembering until handleConversationId fires - see useEffect below.
      pendingRememberRef.current = text;
    }
  }
  const pendingRememberRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    if (conversationId && pendingRememberRef.current) {
      rememberSession(conversationId, pendingRememberRef.current);
      pendingRememberRef.current = null;
    }
  }, [conversationId]);

  return (
    <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
      {/* Center: chat */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <ChatPanel
          userId={userId}
          conversationId={conversationId}
          onConversationId={handleConversationId}
          onUserMessage={handleUserMessage}
        />
      </div>
    </div>
  );
}
