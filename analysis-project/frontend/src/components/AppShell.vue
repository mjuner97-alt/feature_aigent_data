<template>
  <div :style="S.root">
    <div :style="S.main">
      <div :style="S.nav">
        <router-link to="/chat" :style="navStyle('/chat')">📋 会话历史</router-link>
        <router-link to="/skills" :style="navStyle('/skills')">🧩 Skill 广场</router-link>
        <div :style="S.navRight">
          <span v-if="user" :style="S.userName">{{ user.name }} ({{ user.userId }})</span>
          <button :style="S.logoutBtn" @click="handleLogout">退出</button>
        </div>
      </div>
      <div :style="S.body">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getLoggedInUser, logout } from '../utils/auth';

const route = useRoute();
const router = useRouter();
const user = ref(getLoggedInUser());

function navStyle(p: string) {
  const active = p === '/chat'
    ? route.path === '/chat' || route.path.startsWith('/chat/')
    : route.path === p || route.path.startsWith(p + '/');
  return { ...S.navItem, color: active ? '#6366f1' : '#64748b', borderBottomColor: active ? '#6366f1' : 'transparent' };
}

function handleLogout() {
  logout();
  router.push('/login');
}

onMounted(() => {
  user.value = getLoggedInUser();
});

const S: any = {
  root: { display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 },
  nav: { display: 'flex', gap: 0, flexShrink: 0, background: '#ffffff', borderBottom: '1px solid #e2e8f0', padding: '0 16px', alignItems: 'center' },
  navItem: { padding: '12px 20px', fontSize: '0.88rem', fontWeight: 600, textDecoration: 'none', cursor: 'pointer', borderBottom: '2px solid transparent' },
  navRight: { marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '12px' },
  userName: { fontSize: '0.82rem', color: '#475569' },
  logoutBtn: { padding: '4px 12px', fontSize: '0.8rem', background: '#f1f5f9', border: '1px solid #cbd5e1', borderRadius: '4px', cursor: 'pointer', color: '#475569' },
  body: { flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 },
};
</script>
