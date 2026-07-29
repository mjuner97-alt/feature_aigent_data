/**
 * Entry point - just /chat + redirect.
 *
 * Slimmed from dataagent: no login, no admin, no configure routes. The v2
 * backend's sandbox-windows profile doesn't require auth; the frontend is
 * a single-page chat UI with state panels.
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

import AppShell from './components/AppShell';
import ChatPage from './pages/ChatPage';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<ChatPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);