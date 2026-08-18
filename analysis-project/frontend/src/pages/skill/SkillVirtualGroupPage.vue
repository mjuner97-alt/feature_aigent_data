<script setup lang="ts">
/**
 * 虚拟组管理页(/skills/virtual-groups)
 *
 * 虚拟组 = 组头(组名) + 成员 userid 列表(skill_virtual_group_def / skill_virtual_group 表)。
 * 私有 Skill 授权时按"虚拟组"授权,组内成员即时可见,不走审批。
 * 空组合法(建组后可先不填成员);被私有授权引用的组不可删除(后端校验)。
 */
import { ref, onMounted } from 'vue';
import {
  listVirtualGroups,
  createVirtualGroup,
  deleteVirtualGroup,
  addVirtualGroupMember,
  removeVirtualGroupMember,
} from '../../api/virtualGroup';
import { searchSkillUsers } from '../../api/skill';
import type { VirtualGroup } from '../../api/virtualGroup';
import type { SkillUserSearchItem } from '../../api/skill';

const groups = ref<VirtualGroup[]>([]);
const loading = ref(false);
const error = ref('');
const actionError = ref('');

// 建组
const newGroupName = ref('');
const creating = ref(false);
const firstMemberKeyword = ref('');
const firstMemberResults = ref<SkillUserSearchItem[]>([]);
const firstMember = ref<SkillUserSearchItem | null>(null);
const searchingFirstMember = ref(false);

// 成员搜索(每个组独立的搜索状态太重,这里共用一个展开态:当前展开加成员的组名)
const expandedGroup = ref('');
const memberKeyword = ref('');
const memberResults = ref<SkillUserSearchItem[]>([]);
const searchingMembers = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    groups.value = await listVirtualGroups();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载虚拟组失败';
  } finally {
    loading.value = false;
  }
}

async function createGroup() {
  actionError.value = '';
  const name = newGroupName.value.trim();
  if (!name) {
    actionError.value = '请输入虚拟组名';
    return;
  }
  creating.value = true;
  try {
    // 首个成员可选(空组合法,建组后可再通过"加成员"补)
    await createVirtualGroup(name, firstMember.value?.userId);
    newGroupName.value = '';
    firstMemberKeyword.value = '';
    firstMemberResults.value = [];
    firstMember.value = null;
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '建组失败';
  } finally {
    creating.value = false;
  }
}

async function searchFirstMember() {
  const kw = firstMemberKeyword.value.trim();
  if (kw.length < 1) return;
  searchingFirstMember.value = true;
  try {
    firstMemberResults.value = await searchSkillUsers(kw);
  } catch {
    firstMemberResults.value = [];
  } finally {
    searchingFirstMember.value = false;
  }
}

function selectFirstMember(u: SkillUserSearchItem) {
  firstMember.value = u;
  firstMemberKeyword.value = u.name ? `${u.name} (${u.userId})` : u.userId;
  firstMemberResults.value = [];
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

function toggleExpand(groupName: string) {
  if (expandedGroup.value === groupName) {
    expandedGroup.value = '';
  } else {
    expandedGroup.value = groupName;
    memberKeyword.value = '';
    memberResults.value = [];
  }
}

async function searchMembers() {
  const kw = memberKeyword.value.trim();
  if (kw.length < 1) return;
  searchingMembers.value = true;
  try {
    memberResults.value = await searchSkillUsers(kw);
  } catch {
    memberResults.value = [];
  } finally {
    searchingMembers.value = false;
  }
}

async function addMember(groupName: string, u: SkillUserSearchItem) {
  actionError.value = '';
  try {
    await addVirtualGroupMember(groupName, u.userId);
    memberResults.value = [];
    memberKeyword.value = '';
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '添加成员失败';
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

onMounted(load);
</script>

<template>
  <div class="vg-page">
    <div class="vg-header">
      <h2>虚拟组管理</h2>
      <p class="vg-tip">
        虚拟组 = 组名 + 成员列表。私有 Skill 授权时可选"虚拟组",组内成员即时可见、不走审批;
        被授权引用的组不可删除。
      </p>
    </div>

    <div class="vg-create">
      <div class="vg-create-fields">
        <input v-model="newGroupName" placeholder="输入虚拟组名" @keyup.enter="createGroup" />
        <div class="vg-first-member">
          <input v-model="firstMemberKeyword" placeholder="搜索首个成员(可选)" @keyup.enter="searchFirstMember" />
          <button class="btn ghost" :disabled="searchingFirstMember" @click="searchFirstMember">
            {{ searchingFirstMember ? '搜…' : '搜索成员' }}
          </button>
          <div v-if="firstMemberResults.length > 0" class="vg-results vg-first-results">
            <button v-for="u in firstMemberResults" :key="u.userId" class="vg-user-opt" @click="selectFirstMember(u)">
              {{ u.name ? `${u.name} (${u.userId})` : u.userId }}
            </button>
          </div>
        </div>
      </div>
      <div v-if="firstMember" class="vg-selected-member">首个成员：{{ firstMember.name }} ({{ firstMember.userId }})</div>
      <button class="btn primary" :disabled="creating" @click="createGroup">
        {{ creating ? '创建中…' : '建组' }}
      </button>
    </div>
    <div v-if="actionError" class="vg-error">{{ actionError }}</div>
    <div v-if="error" class="vg-error">{{ error }}</div>
    <div v-if="loading" class="vg-loading">加载中…</div>
    <div v-else-if="groups.length === 0 && !error" class="vg-empty">暂无虚拟组,先建一个吧。</div>

    <div class="vg-list">
      <div v-for="g in groups" :key="g.groupName" class="vg-card">
        <div class="vg-card-head">
          <span class="vg-name">{{ g.groupName }}</span>
          <span class="vg-count">{{ g.memberCount }} 人</span>
          <button class="btn ghost" @click="toggleExpand(g.groupName)">
            {{ expandedGroup === g.groupName ? '收起' : '加成员' }}
          </button>
          <button class="btn danger" @click="removeGroup(g)">删除组</button>
        </div>

        <div class="vg-members">
          <span v-for="m in g.members" :key="m.userId" class="vg-member">
            {{ m.name }}({{ m.userId }})
            <button class="vg-remove" aria-label="移除成员" @click="removeMember(g.groupName, m.userId)">×</button>
          </span>
          <span v-if="g.members.length === 0" class="vg-empty-member">暂无成员,点击"加成员"搜索添加</span>
        </div>

        <div v-if="expandedGroup === g.groupName" class="vg-add-member">
          <div class="vg-search-row">
            <input v-model="memberKeyword" placeholder="输入姓名或统一认证号" @keyup.enter="searchMembers" />
            <button class="btn primary" :disabled="searchingMembers" @click="searchMembers">
              {{ searchingMembers ? '搜…' : '搜索' }}
            </button>
          </div>
          <div v-if="memberResults.length > 0" class="vg-results">
            <button
              v-for="u in memberResults"
              :key="u.userId"
              class="vg-user-opt"
              @click="addMember(g.groupName, u)"
            >＋ {{ u.name ? `${u.name} (${u.userId})` : u.userId }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.vg-page { padding: 20px; max-width: 860px; margin: 0 auto; }
.vg-header h2 { margin: 0 0 4px; font-size: 20px; color: #0f172a; }
.vg-tip { margin: 0 0 16px; font-size: 12px; color: #94a3b8; }
.vg-create { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.vg-create-fields { display: flex; gap: 8px; flex: 1 1 420px; min-width: 280px; }
.vg-create input { flex: 1; padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; }
.vg-first-member { display: flex; gap: 6px; flex: 1; position: relative; }
.vg-first-member input { min-width: 0; }
.vg-first-results { position: absolute; z-index: 2; left: 0; right: 0; top: 40px; padding: 6px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; box-shadow: 0 4px 12px rgb(15 23 42 / 12%); }
.vg-selected-member { flex: 1 0 100%; color: #2563eb; font-size: 12px; margin-top: -3px; }
.vg-error { color: #dc2626; font-size: 13px; margin: 8px 0; }
.vg-loading, .vg-empty { color: #94a3b8; font-size: 13px; padding: 16px 0; }
.vg-list { display: flex; flex-direction: column; gap: 12px; }
.vg-card { border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px 14px; background: #fff; }
.vg-card-head { display: flex; align-items: center; gap: 10px; }
.vg-name { font-weight: 700; color: #1e293b; font-size: 15px; }
.vg-count { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 2px 8px; border-radius: 10px; }
.vg-card-head .btn { margin-left: auto; }
.vg-card-head .btn + .btn { margin-left: 0; }
.vg-members { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.vg-member {
  display: inline-flex; align-items: center; gap: 4px;
  background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 4px;
  padding: 2px 8px; font-size: 12px; color: #1e40af;
}
.vg-remove { border: none; background: transparent; color: #1e40af; font-weight: 700; cursor: pointer; padding: 0 2px; }
.vg-remove:hover { color: #dc2626; }
.vg-empty-member { font-size: 12px; color: #94a3b8; }
.vg-add-member { margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e2e8f0; }
.vg-search-row { display: flex; gap: 8px; }
.vg-search-row input { flex: 1; padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; }
.vg-results { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; max-height: 140px; overflow-y: auto; }
.vg-user-opt {
  padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b;
  border-radius: 4px; font-size: 12px; cursor: pointer;
}
.vg-user-opt:hover { border-color: #93c5fd; color: #2563eb; background: #eff6ff; }
.btn { padding: 6px 14px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 13px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.btn.danger { background: #fff; color: #dc2626; border-color: #fecaca; }
.btn.danger:hover { background: #fef2f2; }
</style>
