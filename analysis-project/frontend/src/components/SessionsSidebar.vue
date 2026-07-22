<template>
  <div :style="S.root">
    <div :style="S.headerRow">
      <button @click="handleNewChat" :style="S.newBtn">
        <span :style="{ fontSize: '1rem' }">＋</span> 新建对话
      </button>
    </div>
    <div :style="S.scroll">
      <div v-if="entries.length === 0" :style="S.muted">暂无会话。发送消息即可开始第一段对话。</div>
      <div
        v-for="e in entries"
        :key="e.id"
        :style="rowStyle(e.id)"
        @click="openSession(e)"
        @mouseenter="hoverId = e.id"
        @mouseleave="hoverId = null"
        :title="e.label"
      >
        <div :style="S.rowMain">
          <div :style="S.rowTitle">{{ e.label }}</div>
        </div>
        <div :style="S.rowMeta">
          <span>{{ relTime(e.lastActivityMs) }}</span>
          <button v-if="hoverId === e.id" @click.stop="handleDelete(e)" title="Delete" :style="S.deleteBtn">×</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { loadSessionEntries, saveSessionEntries, relTime, type SessionEntry } from '../utils/session';

const router = useRouter();
const route = useRoute();
const entries = ref<SessionEntry[]>(loadSessionEntries());
const hoverId = ref<string | null>(null);

function handleNewChat() {
  router.push('/chat');
}

function openSession(entry: SessionEntry) {
  router.push(`/chat?session=${encodeURIComponent(entry.id)}`);
}

function handleDelete(entry: SessionEntry) {
  if (!confirm(`Delete this conversation from the sidebar?\n\n"${entry.label}"\n\n(Note: server-side state is not affected.)`)) return;
  const list = loadSessionEntries().filter(e => e.id !== entry.id);
  saveSessionEntries(list);
  entries.value = list;
  const activeKey = (route.query.session as string) ?? null;
  if (activeKey === entry.id) router.push('/chat');
}

function rowStyle(id: string) {
  const activeKey = (route.query.session as string) ?? null;
  const isActive = id === activeKey && route.path === '/chat';
  const isHover = hoverId.value === id && !isActive;
  return {
    ...S.row,
    ...(isActive ? S.rowActive : isHover ? S.rowHover : {}),
  };
}

watch(
  () => [route.path, route.query.session],
  () => { entries.value = loadSessionEntries(); },
  { immediate: true },
);

const S = {
  root: { width: 240, flexShrink: 0, background: '#ffffff', borderRight: '1px solid #e2e8f0', display: 'flex', flexDirection: 'column', minHeight: 0 },
  headerRow: { padding: '14px 14px 10px', borderBottom: '1px solid #f1f5f9', flexShrink: 0 },
  newBtn: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, width: '100%',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)', color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 14px', fontSize: '0.92rem', fontWeight: 600,
    cursor: 'pointer', boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '10px 8px 16px' },
  muted: { padding: '8px 12px', fontSize: '0.85rem', color: '#94a3b8' },
  row: { display: 'flex', alignItems: 'flex-start', gap: 6, padding: '8px 10px', cursor: 'pointer', borderRadius: 8, marginBottom: 2, border: '1px solid transparent' },
  rowActive: { background: '#eef2ff', borderColor: '#c7d2fe' },
  rowHover: { background: '#f8fafc' },
  rowMain: { flex: 1, minWidth: 0 },
  rowTitle: { fontSize: '0.88rem', fontWeight: 500, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  rowMeta: { display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.72rem', color: '#94a3b8', flexShrink: 0 },
  deleteBtn: { background: 'transparent', border: 'none', cursor: 'pointer', color: '#94a3b8', fontSize: '1rem', padding: '0 4px', borderRadius: 4, lineHeight: 1 },
};
</script>
