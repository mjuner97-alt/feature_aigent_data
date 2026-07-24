<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router';

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/category', label: '分类浏览' },
];
</script>

<template>
  <div class="skill-shell">
    <aside class="nav">
      <div class="logo">Skill 广场</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">{{ n.label }}</RouterLink>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.skill-shell { display: flex; min-height: 100vh; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.logo { font-weight: bold; color: #fff; margin-bottom: 12px; }
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; }
.nav-item.router-link-active { background: #3b82f6; color: #fff; }
.content { flex: 1; padding: 16px; background: #f1f5f9; }
</style>
