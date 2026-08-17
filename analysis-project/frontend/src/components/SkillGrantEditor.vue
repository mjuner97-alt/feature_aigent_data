<script setup lang="ts">
/**
 * Skill 私有可见性授权编辑器(可复用:创建/编辑表单 + 详情页 owner 面板)。
 *
 * 三块授权对象:人(USER,按姓名/统一认证号搜索)、部门(DEPARTMENT)、小组(GROUP=统计组)。
 * 添加后显示为可删除的 chips。仅 owner 可增删;非 owner 传入 editable=false 只读展示。
 * 首个授权由后端自动把 skill 切为 PRIVATE("要定向分享"即表达不想公开)。
 *
 * 两种模式:
 *  - skillId 有值: 每次增删立即调用后端(编辑/详情页 owner 面板)
 *  - skillId 为 null: 暂存到 pendingGrants(创建表单还没拿到 id),父组件保存拿到 id 后调用 commitPending 提交
 */
import { ref, watch, onMounted } from 'vue';
import { getGrants, addGrant, removeGrant, getPublishTargets, searchSkillUsers } from '../api/skill';
import type { SkillGrant, PublishTargetGroup } from '../types/skill';

const props = withDefaults(defineProps<{
  /** skillId;为 null 时进入"暂存"模式(创建表单还没拿到 id,先记录授权待保存后提交) */
  skillId: number | null;
  /** 是否可编辑(仅 owner)。false 时只读展示。 */
  editable?: boolean;
}>(), { editable: true });

const emit = defineEmits<{
  (e: 'changed'): void;   // 授权变更后通知父组件刷新(如详情页刷新 visibility)
}>();

const TYPE_LABEL: Record<string, string> = {
  USER: '人', DEPARTMENT: '部门', GROUP: '小组',
};

/** skillId 有值时,从 server 加载的已有授权 */
const effectiveGrants = ref<SkillGrant[]>([]);
/** skillId 为 null 时的暂存授权(创建表单场景) */
const pendingGrants = ref<SkillGrant[]>([]);
const loading = ref(false);
const error = ref('');

// —— 人(USER)搜索 ——
const userKeyword = ref('');
const userResults = ref<SkillUserOption[]>([]);
const searchingUsers = ref(false);
const userSearchError = ref('');

// —— 部门/小组选择(复用发布目标) ——
const orgGroups = ref<PublishTargetGroup[]>([]);
const activeType = ref<'DEPARTMENT' | 'GROUP'>('DEPARTMENT');
const selectedOrgKey = ref<string>('');
const orgsLoaded = ref(false);

interface SkillUserOption { userId: string; name: string; label: string; }

/** 当前有效授权列表(skillId 有值取 server,无值取暂存区);模板用此 */
const grants = ref<SkillGrant[]>([]);

function refreshGrantsView() {
  grants.value = props.skillId ? effectiveGrants.value : pendingGrants.value;
}

async function load() {
  if (props.skillId == null) return;
  loading.value = true;
  error.value = '';
  try {
    effectiveGrants.value = await getGrants(props.skillId);
    refreshGrantsView();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载授权失败';
  } finally {
    loading.value = false;
  }
}

async function ensureOrgs() {
  if (orgsLoaded.value) return;
  try {
    orgGroups.value = await getPublishTargets();
  } catch {
    // 组织加载失败不阻塞
  }
  orgsLoaded.value = true;
}

async function searchUsers() {
  const kw = userKeyword.value.trim();
  if (kw.length < 1) return;
  searchingUsers.value = true;
  userSearchError.value = '';
  try {
    const results = await searchSkillUsers(kw);
    userResults.value = results.map(r => ({
      userId: r.userId,
      name: r.name ?? r.userId,
      label: r.name ? `${r.name} (${r.userId})` : r.userId,
    }));
  } catch (e) {
    userSearchError.value = e instanceof Error ? e.message : '搜索失败';
    userResults.value = [];
  } finally {
    searchingUsers.value = false;
  }
}

async function addUser(opt: SkillUserOption) {
  if (!props.skillId) {
    pushPending({ grantType: 'USER', targetId: opt.userId, displayName: opt.name || opt.userId });
    userResults.value = [];
    userKeyword.value = '';
    refreshGrantsView();
    emit('changed');
    return;
  }
  try {
    await addGrant(props.skillId, 'USER', opt.userId);
    await load();
    emit('changed');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '授权失败';
  }
}

async function addOrg() {
  if (!selectedOrgKey.value) return;
  const [grantType, targetId] = selectedOrgKey.value.split(':');
  if (!grantType || !targetId) return;
  if (!props.skillId) {
    pushPending({ grantType, targetId, displayName: targetId });
    selectedOrgKey.value = '';
    refreshGrantsView();
    emit('changed');
    return;
  }
  try {
    await addGrant(props.skillId, grantType, targetId);
    await load();
    selectedOrgKey.value = '';
    emit('changed');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '授权失败';
  }
}

async function remove(grant: SkillGrant) {
  if (!props.editable) return;
  if (!props.skillId) {
    pendingGrants.value = pendingGrants.value.filter(
      g => !(g.grantType === grant.grantType && g.targetId === grant.targetId)
    );
    refreshGrantsView();
    emit('changed');
    return;
  }
  try {
    await removeGrant(props.skillId, grant.grantType, grant.targetId);
    await load();
    emit('changed');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '取消授权失败';
  }
}

const groupLabel = (t: string): string => TYPE_LABEL[t] ?? t;

// 暂存区去重追加
function pushPending(g: SkillGrant) {
  if (pendingGrants.value.some(x => x.grantType === g.grantType && x.targetId === g.targetId)) return;
  pendingGrants.value = [...pendingGrants.value, g];
}

/** 供父组件读取当前有效授权(skillId 有值取 server,无值取暂存区) */
function currentGrants(): SkillGrant[] {
  return props.skillId ? effectiveGrants.value : pendingGrants.value;
}

/** 供父组件保存后把暂存授权提交(拿到 skillId 后调用) */
async function commitPending(skillId: number) {
  for (const g of pendingGrants.value) {
    try { await addGrant(skillId, g.grantType, g.targetId); } catch { /* 单条失败不阻断 */ }
  }
  pendingGrants.value = [];
}

watch(() => props.skillId, (id) => {
  if (id && !effectiveGrants.value.length) load();
  if (!id) refreshGrantsView();
});

onMounted(() => {
  if (props.skillId) load();
  ensureOrgs();
});

defineExpose({ load, currentGrants, commitPending, hasGrants: () => currentGrants().length > 0 });
</script>

<template>
  <div class="grant-editor">
    <div class="header">
      <span class="title">谁可以看</span>
      <span v-if="loading" class="loading">加载中…</span>
    </div>
    <div v-if="error" class="error">{{ error }}</div>

    <!-- 已授权列表(只读展示 + owner 可删除) -->
    <div v-if="grants.length > 0" class="grant-list">
      <div v-for="g in grants" :key="`${g.grantType}:${g.targetId}`" class="grant-item">
        <span class="grant-label">{{ groupLabel(g.grantType) }}</span>
        <span class="grant-name">{{ g.displayName }}</span>
        <button v-if="editable" class="remove" @click="remove(g)" aria-label="移除授权">×</button>
      </div>
    </div>
    <div v-else-if="!editable" class="empty">无授权(仅创建人可见)</div>

    <!-- 增删操作(仅 owner 可编辑) -->
    <div v-if="editable" class="add-area">
      <div class="add-block">
        <div class="add-title">按人授权</div>
        <div class="user-row">
          <input v-model="userKeyword" placeholder="输入姓名或统一认证号" @keyup.enter="searchUsers" />
          <button class="search-btn" :disabled="searchingUsers" @click="searchUsers">
            {{ searchingUsers ? '搜…' : '搜索' }}
          </button>
        </div>
        <div v-if="userSearchError" class="mini-error">{{ userSearchError }}</div>
        <div v-if="userResults.length > 0" class="user-results">
          <button
            v-for="u in userResults"
            :key="u.userId"
            class="user-opt"
            @click="addUser(u)"
          >＋ {{ u.label }}</button>
        </div>
      </div>

      <div class="add-block">
        <div class="add-title">按部门/小组授权</div>
        <div class="org-row">
          <select v-model="activeType">
            <option value="DEPARTMENT">部门</option>
            <option value="GROUP">小组</option>
          </select>
          <select v-model="selectedOrgKey">
            <option value="">请选择{{ activeType === 'DEPARTMENT' ? '部门' : '小组' }}</option>
            <option
              v-for="t in (orgGroups.find(g => g.orgType === activeType)?.targets ?? [])"
              :key="t.orgId"
              :value="`${t.orgType}:${t.orgId}`"
            >{{ t.displayName }}</option>
          </select>
          <button class="search-btn" :disabled="!selectedOrgKey" @click="addOrg">授权</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.grant-editor { font-size: 13px; }
.header { display: flex; align-items: center; gap: 8px; }
.title { font-weight: 600; color: #475569; }
.loading { color: #94a3b8; font-size: 12px; }
.error { color: #dc2626; font-size: 13px; margin-top: 6px; }
.mini-error { color: #dc2626; font-size: 12px; margin-top: 4px; }

.grant-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.grant-item {
  display: inline-flex; align-items: center; gap: 6px;
  background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 4px;
  padding: 2px 8px; font-size: 12px; color: #1e40af;
}
.grant-label { font-weight: 600; }
.grant-name { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.remove { cursor: pointer; color: #1e40af; font-weight: 600; padding: 0 2px; border: none; background: transparent; }
.remove:hover { background: #dbeafe; color: #1e3a8a; border-radius: 2px; }
.empty { color: #94a3b8; font-size: 12px; margin-top: 6px; }

.add-area { display: flex; flex-direction: column; gap: 12px; margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e2e8f0; }
.add-block { display: flex; flex-direction: column; gap: 6px; }
.add-title { font-size: 12px; font-weight: 600; color: #64748b; }
.user-row, .org-row { display: flex; gap: 6px; align-items: center; }
.user-row input { flex: 1; padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 13px; }
.org-row select { padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 13px; }
.search-btn {
  padding: 4px 12px; border: 1px solid #3b82f6; background: #3b82f6; color: #fff;
  border-radius: 4px; font-size: 12px; cursor: pointer; white-space: nowrap;
}
.search-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.user-results { display: flex; flex-wrap: wrap; gap: 6px; max-height: 120px; overflow-y: auto; }
.user-opt {
  padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b;
  border-radius: 4px; font-size: 12px; cursor: pointer;
}
.user-opt:hover { border-color: #93c5fd; color: #2563eb; background: #eff6ff; }
</style>
