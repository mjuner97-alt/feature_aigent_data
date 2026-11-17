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
  { to: '/skills/virtual-groups', label: '虚拟组管理' },
  // 测试期间临时隐藏，保留路由以便恢复。
  // { to: '/skills/report-processes', label: '报告流程' },
];
</script>

<template>
  <div class="section-shell">
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

<style scoped src="./section-shell.css"></style>
<style scoped>
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
</style>
