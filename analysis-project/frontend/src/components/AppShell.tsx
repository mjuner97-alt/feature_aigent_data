/**
 * AppShell - top-level layout with SessionsSidebar + outlet.
 *
 * Simplified from dataagent: no agent-fetch, no EditTierGate context. The
 * outlet (ChatPage) handles its own state.
 */

import React from 'react';
import { Outlet } from 'react-router-dom';
import SessionsSidebar from './SessionsSidebar';

export default function AppShell() {
  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' }}>
      <SessionsSidebar />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <Outlet />
      </div>
    </div>
  );
}