<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { RouterLink, RouterView, useRouter } from 'vue-router';
import { listPendingPublishes } from '../api/skill';
import { getLoggedInUser, logout } from '../utils/auth';

const router = useRouter();
const user = ref(getLoggedInUser());

// 待审批数量(侧边导航红色徽章)
const pendingCount = ref(0);
async function loadPendingCount() {
  try {
    const list = await listPendingPublishes();
    pendingCount.value = list.length;
  } catch {
    pendingCount.value = 0;
  }
}

function handleLogout() {
  logout();
  router.push('/login');
}

onMounted(() => {
  user.value = getLoggedInUser();
  loadPendingCount();
});

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/approvals', label: '审批', badge: true },
];
</script>

<template>
  <div class="skill-shell">
    <aside class="nav">
      <div class="logo">Skill 广场</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">
        <span>{{ n.label }}</span>
        <span v-if="n.badge && pendingCount > 0" class="nav-badge">{{ pendingCount }}</span>
      </RouterLink>
    </aside>
    <main class="content">
      <div class="topbar">
        <div class="topbar-left">
          <span class="test-tag">当前用户</span>
        </div>
        <div class="user-area">
          <span class="user-name">{{ user?.name || '未登录' }}</span>
          <span v-if="user" class="user-org">{{ user.userId }}</span>
          <span class="user-avatar">👤</span>
          <button class="logout-btn" @click="handleLogout">退出</button>
        </div>
      </div>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.skill-shell { display: flex; min-height: 100vh; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.logo { font-weight: bold; color: #fff; margin-bottom: 12px; }
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; display: flex; align-items: center; justify-content: space-between; }
.nav-item.router-link-exact-active { background: #3b82f6; color: #fff; }
.nav-badge {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: #dc2626;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
  line-height: 18px;
}
.nav-item.router-link-exact-active .nav-badge { background: #fff; color: #dc2626; }
.content { flex: 1; padding: 16px; background: #f1f5f9; }
.topbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; padding: 8px 12px; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.test-tag { font-size: 11px; color: #6366f1; background: #e0e7ff; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.user-area { display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-radius: 6px; }
.user-name { font-size: 13px; color: #1e293b; font-weight: 500; }
.user-org { font-size: 11px; color: #64748b; }
.user-avatar { font-size: 18px; }
.logout-btn { padding: 4px 12px; font-size: 0.8rem; background: #f1f5f9; border: 1px solid #cbd5e1; border-radius: 4px; cursor: pointer; color: #475569; }
.logout-btn:hover { background: #e2e8f0; }
</style>
