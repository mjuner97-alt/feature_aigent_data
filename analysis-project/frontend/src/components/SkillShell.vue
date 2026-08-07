<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { RouterLink, RouterView } from 'vue-router';
import { listPendingPublishes } from '../api/skill';

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

onMounted(() => {
  loadPendingCount();
});

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/approvals', label: '审批', badge: true },
  { to: '/skills/jobs', label: '定时任务' },
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
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.skill-shell { display: flex; height: 100%; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; flex-shrink: 0; }
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
.content { flex: 1; padding: 16px; background: #f1f5f9; overflow: auto; }
</style>
