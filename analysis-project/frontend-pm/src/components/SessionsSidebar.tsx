/**
 * Simplified SessionsSidebar - localStorage-only conversation list.
 *
 * <p>The v2 backend has no "list sessions" REST endpoint, so we manage the
 * recent conversation ids purely in the browser. Each entry is a {id, label,
 * createdAtMs} tuple; the label is the first user prompt (truncated).
 *
 * <p>The active conversation is tracked via the URL ?session= param (set by
 * ChatPanel when the user sends the first message). This sidebar just lists
 * what we know + lets the user switch or create new.
 */

import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';

interface SessionEntry {
  id: string;
  label: string;
  createdAtMs: number;
  lastActivityMs: number;
}

const STORAGE_KEY = 'analysis_project_sessions';
const USERID_KEY = 'analysis_project_userid';

function loadEntries(): SessionEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveEntries(list: SessionEntry[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  } catch {
    // ignore quota errors
  }
}

export function rememberSession(id: string, label: string) {
  const list = loadEntries();
  const existing = list.findIndex(e => e.id === id);
  const entry: SessionEntry = {
    id,
    label: label.slice(0, 60) || '(empty)',
    createdAtMs: existing >= 0 ? list[existing].createdAtMs : Date.now(),
    lastActivityMs: Date.now(),
  };
  if (existing >= 0) list[existing] = entry;
  else list.unshift(entry);
  // Cap at 30 entries to avoid unbounded growth
  if (list.length > 30) list.length = 30;
  saveEntries(list);
}

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export function getOrCreateUserId(): string {
  let uid = localStorage.getItem(USERID_KEY);
  if (!uid) {
    uid = `user-${Math.random().toString(36).slice(2, 10)}`;
    try { localStorage.setItem(USERID_KEY, uid); } catch { /* ignore */ }
  }
  return uid;
}

export default function SessionsSidebar() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const activeKey = searchParams.get('session');
  const [entries, setEntries] = useState<SessionEntry[]>([]);

  // Refresh from localStorage on mount + when location changes (new chat created)
  useEffect(() => {
    setEntries(loadEntries());
  }, [location.pathname, location.search]);

  function handleNewChat() {
    navigate('/chat');  // no ?session= means brand-new conversation
  }

  function openSession(entry: SessionEntry) {
    navigate(`/chat?session=${encodeURIComponent(entry.id)}`);
  }

  async function handleDelete(entry: SessionEntry, ev: React.MouseEvent) {
    ev.stopPropagation();
    if (!confirm(`Delete this conversation from the sidebar?\n\n"${entry.label}"\n\n(Note: server-side state is not affected.)`)) return;
    const list = loadEntries().filter(e => e.id !== entry.id);
    saveEntries(list);
    setEntries(list);
    if (activeKey === entry.id) navigate('/chat');
  }

  return (
    <div style={S.root}>
      <div style={S.headerRow}>
        <button onClick={handleNewChat} style={S.newBtn}>
          <span style={{ fontSize: '1rem' }}>＋</span> 新建对话
        </button>
      </div>

      <div style={S.scroll}>
        {entries.length === 0 && (
          <div style={S.muted}>暂无会话。发送消息即可开始第一段对话。</div>
        )}

        {entries.map(e => {
          const isActive = e.id === activeKey && location.pathname === '/chat';
          return (
            <SessionRow
              key={e.id}
              entry={e}
              active={isActive}
              onOpen={() => openSession(e)}
              onDelete={ev => handleDelete(e, ev)}
            />
          );
        })}
      </div>
    </div>
  );
}

interface RowProps {
  entry: SessionEntry;
  active: boolean;
  onOpen: () => void;
  onDelete: (e: React.MouseEvent) => void;
}

function SessionRow({ entry, active, onOpen, onDelete }: RowProps) {
  const [hover, setHover] = useState(false);
  return (
    <div
      onClick={onOpen}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        ...S.row,
        ...(active ? S.rowActive : hover ? S.rowHover : {}),
      }}
      title={entry.label}
    >
      <div style={S.rowMain}>
        <div style={S.rowTitle}>{entry.label}</div>
      </div>
      <div style={S.rowMeta}>
        <span>{relTime(entry.lastActivityMs)}</span>
        {hover && (
          <button onClick={onDelete} title="Delete" style={S.deleteBtn}>×</button>
        )}
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 240, flexShrink: 0,
    background: '#ffffff', borderRight: '1px solid #e2e8f0',
    display: 'flex', flexDirection: 'column', minHeight: 0,
  },
  headerRow: {
    padding: '14px 14px 10px', borderBottom: '1px solid #f1f5f9', flexShrink: 0,
  },
  newBtn: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, width: '100%',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 14px', fontSize: '0.92rem', fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '10px 8px 16px' },
  muted: { padding: '8px 12px', fontSize: '0.85rem', color: '#94a3b8' },
  row: {
    display: 'flex', alignItems: 'flex-start', gap: 6,
    padding: '8px 10px', cursor: 'pointer',
    borderRadius: 8, marginBottom: 2,
    border: '1px solid transparent',
  },
  rowActive: { background: '#eef2ff', borderColor: '#c7d2fe' },
  rowHover: { background: '#f8fafc' },
  rowMain: { flex: 1, minWidth: 0 },
  rowTitle: {
    fontSize: '0.88rem', fontWeight: 500, color: '#0f172a',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  rowMeta: {
    display: 'flex', alignItems: 'center', gap: 4,
    fontSize: '0.72rem', color: '#94a3b8', flexShrink: 0,
  },
  deleteBtn: {
    background: 'transparent', border: 'none', cursor: 'pointer',
    color: '#94a3b8', fontSize: '1rem', padding: '0 4px',
    borderRadius: 4, lineHeight: 1,
  },
};