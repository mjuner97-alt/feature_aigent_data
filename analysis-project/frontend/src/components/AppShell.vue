<template>
  <div :style="S.root">
    <SessionsSidebar />
    <div :style="S.main">
      <div :style="S.nav">
        <router-link to="/chat" :style="navStyle('/chat')">💬 对话</router-link>
        <router-link to="/dashboard" :style="navStyle('/dashboard')">📊 质量看板</router-link>
      </div>
      <div :style="S.body">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router';
import SessionsSidebar from './SessionsSidebar.vue';

const route = useRoute();
function navStyle(p: string) {
  const a = route.path === p;
  return { ...S.navItem, color: a ? '#6366f1' : '#64748b', borderBottomColor: a ? '#6366f1' : 'transparent' };
}

const S: any = {
  root: { display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 },
  nav: { display: 'flex', gap: 0, flexShrink: 0, background: '#ffffff', borderBottom: '1px solid #e2e8f0', padding: '0 16px' },
  navItem: { padding: '12px 20px', fontSize: '0.88rem', fontWeight: 600, textDecoration: 'none', cursor: 'pointer', borderBottom: '2px solid transparent' },
  body: { flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 },
};
</script>
