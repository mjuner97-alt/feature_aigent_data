<script setup lang="ts">
/**
 * SCRIPT 注册表 shell: 侧边栏 + 子列表.
 *
 * 子列表: SQL 注册 / python 脚本注册 (后续新增列表只需加 nav 项 + 子路由, 不引入字典).
 */
import { RouterLink, RouterView } from 'vue-router';

const nav = [
  { to: '/script-registry', label: 'SQL 注册' },
  { to: '/script-registry/python', label: 'python 脚本注册' },
  { to: '/script-registry/skills', label: 'Skill 配置' },
];
</script>

<template>
  <div class="script-shell">
    <aside class="nav">
      <div class="logo">SCRIPT 注册表</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">
        <span>{{ n.label }}</span>
      </RouterLink>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.script-shell { display: flex; height: 100%; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; flex-shrink: 0; }
.logo { font-weight: bold; color: #fff; margin-bottom: 12px; }
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; display: flex; align-items: center; justify-content: space-between; }
.nav-item.router-link-exact-active { background: #3b82f6; color: #fff; }
.content { flex: 1; padding: 16px; background: #f1f5f9; overflow: auto; }
</style>
