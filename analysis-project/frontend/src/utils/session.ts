/**
 * Shared session utilities: localStorage-based conversation + user ID management.
 */

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
  } catch { return []; }
}

function saveEntries(list: SessionEntry[]) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(list)); } catch { /* ignore */ }
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
  if (list.length > 30) list.length = 30;
  saveEntries(list);
}

export function getOrCreateUserId(): string {
  let uid = localStorage.getItem(USERID_KEY);
  if (!uid) {
    uid = `user-${Math.random().toString(36).slice(2, 10)}`;
    try { localStorage.setItem(USERID_KEY, uid); } catch { /* ignore */ }
  }
  return uid;
}

export function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export function loadSessionEntries(): SessionEntry[] {
  return loadEntries();
}

export function saveSessionEntries(list: SessionEntry[]) {
  saveEntries(list);
}

export type { SessionEntry };
