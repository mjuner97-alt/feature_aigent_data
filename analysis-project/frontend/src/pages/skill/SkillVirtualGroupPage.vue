<script setup lang="ts">
/**
 * 虚拟组管理页(/skills/virtual-groups)
 *
 * 两个 tab(结构对齐定时任务页):
 *  - 虚拟组管理:建组(Drawer)+ 组列表(表格)
 *  - 组成员管理:选组+搜人添加成员 + 成员列表(表格,组/关键字过滤)
 *
 * 虚拟组 = 组头(组名) + 成员 userid 列表(skill_virtual_group_def / skill_virtual_group 表)。
 * 私有 Skill 授权时按"虚拟组"授权,组内成员即时可见,不走审批。
 * 空组合法;被私有授权引用的组不可删除(后端校验)。
 */
import { computed, onMounted, ref } from 'vue';
import {
  listVirtualGroups,
  deleteVirtualGroup,
  addVirtualGroupMember,
  removeVirtualGroupMember,
} from '../../api/virtualGroup';
import { searchSkillUsers } from '../../api/skill';
import type { VirtualGroup } from '../../api/virtualGroup';
import type { SkillUserSearchItem } from '../../api/skill';
import SkillVirtualGroupFormDrawer from '../../components/SkillVirtualGroupFormDrawer.vue';

const activeTab = ref<'groups' | 'members'>('groups');
const groups = ref<VirtualGroup[]>([]);
const loading = ref(false);
const error = ref('');
const actionError = ref('');

// 虚拟组 tab:关键字过滤
const groupKeyword = ref('');

const filteredGroups = computed(() => {
  const kw = groupKeyword.value.trim().toLowerCase();
  if (!kw) return groups.value;
  return groups.value.filter(g =>
    g.groupName.toLowerCase().includes(kw)
    || g.members.some(m => m.userId.toLowerCase().includes(kw) || (m.name || '').toLowerCase().includes(kw)),
  );
});

// 建组 Drawer
const formOpen = ref(false);

// 组成员 tab:按组过滤 + 添加成员
const memberGroupFilter = ref('');
const memberKeyword = ref('');
const addTargetGroup = ref('');
const addKeyword = ref('');
const addResults = ref<SkillUserSearchItem[]>([]);
const searchingAdd = ref(false);
const addingUser = ref('');

/** 成员列表:拍平成行(组名/姓名/统一认证号),按过滤条件筛 */
const memberRows = computed(() => {
  const rows: { groupName: string; userId: string; name: string }[] = [];
  for (const g of groups.value) {
    if (memberGroupFilter.value && g.groupName !== memberGroupFilter.value) continue;
    for (const m of g.members) rows.push({ groupName: g.groupName, userId: m.userId, name: m.name });
  }
  const kw = memberKeyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter(r =>
    r.groupName.toLowerCase().includes(kw)
    || r.userId.toLowerCase().includes(kw)
    || (r.name || '').toLowerCase().includes(kw),
  );
});

/** 组名下拉选项(成员 tab 的组过滤 + 添加目标组共用) */
const groupNames = computed(() => groups.value.map(g => g.groupName));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    groups.value = await listVirtualGroups();
    // 组过滤目标被删时重置
    if (memberGroupFilter.value && !groupNames.value.includes(memberGroupFilter.value)) {
      memberGroupFilter.value = '';
    }
    if (addTargetGroup.value && !groupNames.value.includes(addTargetGroup.value)) {
      addTargetGroup.value = '';
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载虚拟组失败';
  } finally {
    loading.value = false;
  }
}

function switchTab(tab: 'groups' | 'members') {
  activeTab.value = tab;
  actionError.value = '';
}

/** 组列表"管理成员"入口:切到成员 tab 并锁定该组过滤 */
function openMembers(groupName: string) {
  memberGroupFilter.value = groupName;
  memberKeyword.value = '';
  addTargetGroup.value = groupName;
  addResults.value = [];
  addKeyword.value = '';
  switchTab('members');
}

async function removeGroup(g: VirtualGroup) {
  actionError.value = '';
  if (!window.confirm(`确定删除虚拟组"${g.groupName}"?(${g.memberCount} 名成员)`)) return;
  try {
    await deleteVirtualGroup(g.groupName);
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '删除失败';
  }
}

async function searchAdd() {
  const kw = addKeyword.value.trim();
  if (kw.length < 1) return;
  searchingAdd.value = true;
  try {
    addResults.value = await searchSkillUsers(kw);
  } catch {
    addResults.value = [];
  } finally {
    searchingAdd.value = false;
  }
}

async function addMember(u: SkillUserSearchItem) {
  actionError.value = '';
  if (!addTargetGroup.value) {
    actionError.value = '请先选择要添加到的虚拟组';
    return;
  }
  addingUser.value = u.userId;
  try {
    await addVirtualGroupMember(addTargetGroup.value, u.userId);
    addResults.value = [];
    addKeyword.value = '';
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '添加成员失败';
  } finally {
    addingUser.value = '';
  }
}

async function removeMember(groupName: string, userId: string) {
  actionError.value = '';
  try {
    await removeVirtualGroupMember(groupName, userId);
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '移除成员失败';
  }
}

/** 成员预览:前 3 个姓名,余下折叠;title 展示全量 */
function memberPreview(g: VirtualGroup): string {
  if (g.members.length === 0) return '暂无成员';
  const head = g.members.slice(0, 3).map(m => m.name || m.userId).join('、');
  return g.members.length > 3 ? `${head} 等 ${g.members.length} 人` : head;
}

function memberTitle(g: VirtualGroup): string {
  return g.members.map(m => `${m.name || m.userId}(${m.userId})`).join('\n') || '暂无成员';
}

onMounted(load);
</script>

<template>
  <div class="vg-page">
    <div class="page-header">
      <h2>{{ activeTab === 'groups' ? '虚拟组管理' : '组成员管理' }}</h2>
      <div class="header-actions">
        <template v-if="activeTab === 'groups'">
          <input v-model="groupKeyword" placeholder="搜索组名/成员…" class="search-input" />
          <button class="btn primary" @click="formOpen = true">+ 创建虚拟组</button>
        </template>
        <template v-else>
          <select v-model="memberGroupFilter" class="filter-select">
            <option value="">全部组</option>
            <option v-for="name in groupNames" :key="name" :value="name">{{ name }}</option>
          </select>
          <input v-model="memberKeyword" placeholder="搜索组名/成员…" class="search-input" />
        </template>
        <button class="btn ghost" @click="load">刷新</button>
      </div>
    </div>

    <div class="vg-tabs" role="tablist">
      <button class="vg-tab" :class="{ active: activeTab === 'groups' }" @click="switchTab('groups')">虚拟组管理</button>
      <button class="vg-tab" :class="{ active: activeTab === 'members' }" @click="switchTab('members')">组成员管理</button>
    </div>

    <p class="vg-tip">
      虚拟组 = 组名 + 成员列表。私有 Skill 授权时可选"虚拟组",组内成员即时可见、不走审批;
      被授权引用的组不可删除。
    </p>

    <div v-if="actionError" class="vg-error">{{ actionError }}</div>
    <div v-if="error" class="vg-error">{{ error }}</div>
    <div v-if="loading" class="vg-loading">加载中…</div>

    <template v-else-if="activeTab === 'groups'">
      <div v-if="filteredGroups.length === 0" class="vg-empty">
        <p>{{ groupKeyword ? '没有匹配的虚拟组' : '暂无虚拟组,点击「创建虚拟组」新增' }}</p>
      </div>
      <div v-else class="vg-table-wrap">
        <table class="vg-table">
          <thead>
            <tr>
              <th>组名</th>
              <th>成员数</th>
<!--              <th>成员</th>-->
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in filteredGroups" :key="g.groupName">
              <td><span class="col-name" :title="g.groupName">{{ g.groupName }}</span></td>
              <td><span class="count-badge">{{ g.memberCount }} 人</span></td>
<!--              <td class="col-members" :title="memberTitle(g)">{{ memberPreview(g) }}</td>-->
              <td class="col-actions">
                <button class="btn-action" @click="openMembers(g.groupName)">管理成员</button>
                <button class="btn-action danger" @click="removeGroup(g)">删除组</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else>
      <div class="vg-add-member">
        <select v-model="addTargetGroup" class="filter-select">
          <option value="" disabled>选择虚拟组</option>
          <option v-for="name in groupNames" :key="name" :value="name">{{ name }}</option>
        </select>
        <input v-model="addKeyword" placeholder="搜索姓名或统一认证号" class="search-input" @keyup.enter="searchAdd" />
        <button class="btn primary" :disabled="searchingAdd" @click="searchAdd">
          {{ searchingAdd ? '搜…' : '搜索' }}
        </button>
        <div v-if="addResults.length > 0" class="vg-results">
          <button
            v-for="u in addResults"
            :key="u.userId"
            class="user-opt"
            :disabled="addingUser === u.userId"
            @click="addMember(u)"
          >＋ {{ u.name ? `${u.name} (${u.userId})` : u.userId }}{{ addingUser === u.userId ? '(添加中…)' : '' }}</button>
        </div>
      </div>

      <div v-if="memberRows.length === 0" class="vg-empty">
        <p>暂无成员,先在上方选组并搜索添加</p>
      </div>
      <div v-else class="vg-table-wrap">
        <table class="vg-table">
          <thead>
            <tr>
              <th>组名</th>
              <th>姓名</th>
              <th>统一认证号</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in memberRows" :key="`${row.groupName}-${row.userId}`">
              <td><span class="col-name" :title="row.groupName">{{ row.groupName }}</span></td>
              <td>{{ row.name || '-' }}</td>
              <td class="selectable">{{ row.userId }}</td>
              <td class="col-actions">
                <button class="btn-action danger" @click="removeMember(row.groupName, row.userId)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <SkillVirtualGroupFormDrawer v-model:open="formOpen" @saved="load" />
  </div>
</template>

<style scoped>
.vg-page { padding: 4px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.search-input { padding: 7px 12px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; width: 220px; }
.filter-select { padding: 7px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; background: #fff; max-width: 220px; }
.btn { padding: 7px 16px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:hover { background: #2563eb; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.btn.ghost:hover { background: #f1f5f9; }

.vg-tabs { display: flex; gap: 24px; border-bottom: 1px solid #e2e8f0; margin-bottom: 12px; }
.vg-tab { border: 0; border-bottom: 2px solid transparent; background: transparent; color: #64748b; padding: 8px 2px 10px; font-size: 14px; cursor: pointer; }
.vg-tab.active { color: #2563eb; border-bottom-color: #2563eb; font-weight: 600; }
.vg-tip { margin: 0 0 12px; font-size: 12px; color: #94a3b8; }
.vg-error { margin-bottom: 12px; padding: 9px 12px; border: 1px solid #fecaca; border-radius: 5px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
.vg-loading, .vg-empty { color: #94a3b8; font-size: 14px; padding: 40px 0; text-align: center; background: #fff; border-radius: 8px; }

.vg-table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.vg-table { width: 100%; min-width: 720px; border-collapse: collapse; background: #fff; }
.vg-table th { background: #f8fafc; padding: 10px 12px; text-align: left; font-size: 13px; font-weight: 600; color: #475569; border-bottom: 1px solid #e2e8f0; white-space: nowrap; }
.vg-table td { padding: 10px 12px; font-size: 13px; color: #1e293b; border-bottom: 1px solid #f1f5f9; }
.vg-table tr:hover td { background: #f8fafc; }
.col-name { font-weight: 600; }
.col-members { max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475569; }
.count-badge { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 2px 8px; border-radius: 10px; }
.col-actions { display: flex; gap: 4px; white-space: nowrap; align-items: center; }
.selectable { user-select: text; cursor: text; }

.btn-action { padding: 4px 10px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 12px; cursor: pointer; background: #fff; color: #475569; }
.btn-action:hover { background: #f1f5f9; }
.btn-action.danger { color: #dc2626; border-color: #fecaca; }
.btn-action.danger:hover { background: #fef2f2; }

/* 成员 tab:添加成员行 */
.vg-add-member { display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap; margin-bottom: 14px; position: relative; }
.vg-add-member .search-input { width: 260px; }
.vg-results { display: flex; flex-wrap: wrap; gap: 6px; flex: 1 0 100%; max-height: 140px; overflow-y: auto; }
.user-opt { padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b; border-radius: 4px; font-size: 12px; cursor: pointer; }
.user-opt:hover { border-color: #93c5fd; color: #2563eb; background: #eff6ff; }
.user-opt:disabled { opacity: 0.6; cursor: not-allowed; }

@media (max-width: 900px) {
  .header-actions { width: 100%; flex-wrap: wrap; }
  .search-input { width: min(100%, 220px); }
}
</style>
