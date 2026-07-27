<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { RouterLink, RouterView, useRouter } from 'vue-router';
import { currentUserId, getUserInfo } from '../api/skill';
import type { UserInfo } from '../api/skill';

const USERS = [
  { id: 'user_001',     label: '张三(user_001)' },
  { id: 'user_002',     label: '李四(user_002)' },
  { id: 'user_003',     label: '王五(user_003)' },
  { id: 'approver_001', label: '审批人A(approver_001)' },
  { id: 'approver_002', label: '审批人B(approver_002)' },
  { id: 'approver_003', label: '审批人C(approver_003)' },
  { id: 'demo-user',    label: '访客(demo-user)' },
];

const router = useRouter();
const current = ref(currentUserId());
const userInfo = ref<UserInfo | null>(null);
const showDropdown = ref(false);

const currentLabel = computed(() => {
  const u = USERS.find(u => u.id === current.value);
  return u ? u.label : current.value;
});

const orgSummary = computed(() => {
  if (!userInfo.value || userInfo.value.orgs.length === 0) return '';
  return userInfo.value.orgs.map(o => o.orgName).join('/');
});

async function loadUserInfo() {
  try {
    userInfo.value = await getUserInfo(current.value);
  } catch {
    userInfo.value = null;
  }
}

function switchUser(id: string) {
  localStorage.setItem('skill-user-id', id);
  current.value = id;
  showDropdown.value = false;
  router.go(0);
}

function toggleDropdown() {
  showDropdown.value = !showDropdown.value;
}

onMounted(loadUserInfo);

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/approvals', label: '待我审批' },
];
</script>

<template>
  <div class="skill-shell">
    <aside class="nav">
      <div class="logo">Skill 广场</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">{{ n.label }}</RouterLink>
    </aside>
    <main class="content">
      <div class="topbar">
        <div class="topbar-left">
          <span class="test-tag">测试身份</span>
        </div>
        <div class="user-area" @click="toggleDropdown">
          <span class="user-name">{{ currentLabel }}</span>
          <span v-if="orgSummary" class="user-org">{{ orgSummary }}</span>
          <span class="user-avatar">👤</span>
          <div v-if="showDropdown" class="user-dropdown">
            <div v-for="u in USERS" :key="u.id" class="dropdown-item"
                 :class="{ active: u.id === current }"
                 @click.stop="switchUser(u.id)">
              {{ u.label }}
            </div>
          </div>
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
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; }
.nav-item.router-link-active { background: #3b82f6; color: #fff; }
.content { flex: 1; padding: 16px; background: #f1f5f9; }
.topbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; padding: 8px 12px; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.test-tag { font-size: 11px; color: #f59e0b; background: #fef3c7; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.user-area { position: relative; cursor: pointer; display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-radius: 6px; }
.user-area:hover { background: #f1f5f9; }
.user-name { font-size: 13px; color: #1e293b; font-weight: 500; }
.user-org { font-size: 11px; color: #64748b; }
.user-avatar { font-size: 18px; }
.user-dropdown { position: absolute; top: 100%; right: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); min-width: 200px; z-index: 100; margin-top: 4px; }
.dropdown-item { padding: 8px 12px; font-size: 13px; cursor: pointer; color: #1e293b; }
.dropdown-item:hover { background: #f1f5f9; }
.dropdown-item.active { background: #dbeafe; color: #2563eb; font-weight: 600; }
</style>
