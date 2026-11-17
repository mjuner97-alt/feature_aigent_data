<script setup lang="ts">
/** 例会材料导航容器，根据管理权限展示配置和上传入口。 */

import { RouterLink, RouterView } from 'vue-router';
import { computed, onMounted, ref } from 'vue';
import { Document, FolderOpened, List, Setting } from '@element-plus/icons-vue';
import { pptAnomalyPermissions } from '../../api/pptAnomaly';
import './ppt-anomaly.css';

const nav = [
  { to: '/ppt-anomaly/keyword-config', label: '例会材料配置', icon: Setting },
  { to: '/ppt-anomaly/materials', label: '材料上传', icon: FolderOpened, productDepartmentOnly: true },
  { to: { path: '/ppt-anomaly/feedback-records', force: true }, label: '反馈记录', icon: List },
] as const;
const permissions = ref<Awaited<ReturnType<typeof pptAnomalyPermissions>>>();
const visibleNav = computed(() => nav.filter(item => !('productDepartmentOnly' in item) || permissions.value?.canManageMaterials));
onMounted(async () => {
  permissions.value = await pptAnomalyPermissions().catch(() => undefined);
});
</script>

<template>
  <div class="section-shell ppt-anomaly-shell">
    <aside class="nav" aria-label="例会材料识别导航">
      <div class="logo"><el-icon><Document /></el-icon>例会材料识别</div>
      <RouterLink v-for="item in visibleNav" :key="item.label" :to="item.to" class="nav-item">
        <el-icon><component :is="item.icon" /></el-icon>
        {{ item.label }}
      </RouterLink>
    </aside>
    <div class="content">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.section-shell { display: flex; height: 100%; }
.nav { box-sizing: border-box; width: 204px; flex: 0 0 auto; padding: 20px 14px; display: flex; flex-direction: column; gap: 6px; background: #142441; }
.logo, .nav-item { display: flex; align-items: center; gap: 10px; }
.logo { margin: 0 0 12px; padding: 0 4px 16px; border-bottom: 1px solid #ffffff20; color: #fff; font-weight: 700; white-space: nowrap; }
.logo .el-icon { width: 30px; height: 30px; border-radius: 8px; background: #315bc7; }
.nav-item { min-height: 40px; box-sizing: border-box; padding: 8px 12px; border-radius: 8px; color: #d4def1; text-decoration: none; font-size: 14px; }
.nav-item:hover { background: #ffffff14; color: #fff; }
.nav-item.router-link-exact-active { background: #2855c4; color: #fff; font-weight: 600; }
.nav-item:focus-visible { outline: 2px solid #adc7ff; outline-offset: 2px; }
.content { flex: 1; min-width: 0; min-height: 0; background: #f3f6fb; overflow: auto; }
@media (max-width: 640px) {
  .section-shell { flex-direction: column; }
  .nav { width: 100%; padding: 10px 12px; flex-direction: row; flex-wrap: wrap; }
  .logo { flex-basis: 100%; margin: 0; padding-bottom: 8px; }
}
</style>
