<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import MailRecipientDialog from '../components/MailRecipientDialog.vue';
import { searchSkillUsers } from '../api/skill';
import {
  listTeamConfigGroups, saveTeamConfigGroups, deleteTeamConfigGroup,
  listTeamConfigOptions, listLedgerPeopleOptions, listLedgerPeople,
  type TeamConfigGroup, type TeamConfigOption, type TeamRecipient,
} from '../api/teamConfig';

const rows = ref<TeamConfigGroup[]>([]), total = ref(0), page = ref(1), size = ref(20);
const keyword = ref(''), department = ref(''), team = ref('');
const options = ref<TeamConfigOption[]>([]), sourceOptions = ref<TeamConfigOption[]>([]);
const loading = ref(false), saving = ref(false), optionsLoading = ref(false);
const addingTeams = ref(false);
const expanded = ref(new Set<number>());
const editorOpen = ref(false), editing = ref(false), drafts = ref<TeamConfigGroup[]>([]);
const selectedDepartment = ref(''), selectedTeams = ref<string[]>([]), activeIndex = ref(0);
const recipientOpen = ref(false), recipientNames = ref<Record<string, string>>({});
const savedDraft = ref('');
const activeDraft = computed(() => drafts.value[activeIndex.value]);
const departments = computed(() => [...new Set(options.value.map(option => option.dev_dept))]);
const teams = computed(() => options.value.filter(option => option.dev_dept === department.value && option.stat_group).map(option => option.stat_group!));
const availableOptions = computed(() => {
  const result = new Map<string, TeamConfigOption>();
  for (const option of [...sourceOptions.value, ...options.value])
    result.set(JSON.stringify([option.dev_dept, option.stat_group || '']), option);
  return [...result.values()];
});
const sourceDepartments = computed(() => [...new Set(availableOptions.value.map(option => option.dev_dept))]);
const sourceTeams = computed(() => availableOptions.value.filter(option => option.dev_dept === selectedDepartment.value && option.stat_group).map(option => option.stat_group!));
const error = (value: unknown) => ElMessage.error(value instanceof Error ? value.message : '操作失败');
let listVersion = 0;
async function load() {
  const version = ++listVersion;
  loading.value = true;
  try {
    const result = await listTeamConfigGroups(keyword.value.trim(), page.value, size.value, department.value, team.value);
    if (version !== listVersion) return;
    rows.value = result.items;
    total.value = result.total;
    if (page.value > 1 && !rows.value.length) {
      page.value = Math.max(1, Math.ceil(total.value / size.value));
      await load();
    }
  } catch (value) { if (version === listVersion) error(value); }
  finally { if (version === listVersion) loading.value = false; }
}
function search() { page.value = 1; void load(); }
function changeDepartment() { team.value = ''; search(); }
async function loadOptions() {
  optionsLoading.value = true;
  const results = await Promise.allSettled([listTeamConfigOptions(), listLedgerPeopleOptions()]);
  if (results[0].status === 'fulfilled') options.value = results[0].value;
  else error(results[0].reason);
  if (results[1].status === 'fulfilled') sourceOptions.value = results[1].value;
  else error(results[1].reason);
  optionsLoading.value = false;
}
function toggle(row: TeamConfigGroup) {
  if (row.id == null) return;
  if (expanded.value.has(row.id)) expanded.value.delete(row.id);
  else expanded.value.add(row.id);
}
function label(person: TeamRecipient) {
  return person.name && person.name !== person.userId ? `${person.name}（${person.userId}）` : person.userId;
}
function beginCreate() {
  editing.value = false;
  drafts.value = [];
  activeIndex.value = 0;
  selectedDepartment.value = department.value;
  selectedTeams.value = [];
  savedDraft.value = JSON.stringify(drafts.value);
  editorOpen.value = true;
}
function beginEdit(row: TeamConfigGroup) {
  editing.value = true;
  drafts.value = [{ ...row, to: row.to.map(person => ({ ...person })), cc: row.cc.map(person => ({ ...person })) }];
  activeIndex.value = 0;
  savedDraft.value = JSON.stringify(drafts.value);
  editorOpen.value = true;
}
function alreadyAdded(name: string) {
  return drafts.value.some(row => row.department === selectedDepartment.value && row.team === name);
}
async function addTeams() {
  if (addingTeams.value || saving.value || !selectedDepartment.value || !selectedTeams.value.length) return;
  const departmentName = selectedDepartment.value;
  const teamNames = [...new Set(selectedTeams.value)].filter(name => !alreadyAdded(name));
  addingTeams.value = true;
  try {
    const groups = await Promise.all(teamNames.map(async name => {
      const result = await listTeamConfigGroups('', 1, 1, departmentName, name);
      const existing = result.items.find(row => row.department === departmentName && row.team === name);
      return existing
        ? { ...existing, to: existing.to.map(person => ({ ...person })), cc: existing.cc.map(person => ({ ...person })) }
        : { department: departmentName, team: name, to: [], cc: [] };
    }));
    drafts.value.push(...groups);
    activeIndex.value = Math.max(0, drafts.value.length - 1);
    selectedTeams.value = [];
  } catch (value) { error(value); }
  finally { addingTeams.value = false; }
}
function removeDraft(index: number) {
  drafts.value.splice(index, 1);
  activeIndex.value = Math.min(activeIndex.value, Math.max(0, drafts.value.length - 1));
}
function manageRecipients() {
  const row = activeDraft.value;
  if (!row) return;
  recipientNames.value = Object.fromEntries([...row.to, ...row.cc].map(person => [person.userId, person.name]));
  recipientOpen.value = true;
}
async function searchPeople(keyword: string) {
  const found = await searchSkillUsers(keyword);
  if (found.length) return found;
  // 公共台账的回退搜索同样允许跨部门、跨团队选人。
  const result = await listLedgerPeople(keyword, 1, 5);
  return result.items.map(person => ({ userId: person.user_id, name: person.user_name,
    department: [person.dev_dept, person.stat_group].filter(Boolean).join(' / '), positionRole: person.position_role }));
}
function confirmRecipients(value: { to: string[]; cc: string[]; names: Record<string, string> }) {
  const row = activeDraft.value;
  if (!row) return;
  const existing = new Map([...row.to, ...row.cc].map(person => [person.userId, person]));
  const person = (userId: string): TeamRecipient => ({ ...existing.get(userId), userId,
    name: value.names[userId] || existing.get(userId)?.name || userId, enabled: existing.get(userId)?.enabled ?? true });
  row.to = value.to.map(person);
  row.cc = value.cc.map(person);
  recipientOpen.value = false;
}
async function closeEditor(done?: () => void) {
  if (saving.value || addingTeams.value || recipientOpen.value) return;
  if (JSON.stringify(drafts.value) !== savedDraft.value) {
    try { await ElMessageBox.confirm('团队配置尚未保存，确认放弃本次修改？', '关闭配置', { type: 'warning' }); }
    catch { return; }
  }
  editorOpen.value = false;
  done?.();
}
async function save() {
  if (saving.value || addingTeams.value || !drafts.value.length || recipientOpen.value) return;
  saving.value = true;
  try {
    await saveTeamConfigGroups(drafts.value);
    editorOpen.value = false;
    ElMessage.success('团队配置已保存');
    await Promise.all([load(), loadOptions()]);
  } catch (value) { error(value); }
  finally { saving.value = false; }
}
async function remove(row: TeamConfigGroup) {
  if (row.id == null || row.version == null) return;
  try { await ElMessageBox.confirm(`确认删除“${row.department} / ${row.team || '部门默认名单'}”的团队配置及通知名单？`, '删除团队配置', { type: 'warning' }); }
  catch { return; }
  try {
    await deleteTeamConfigGroup(row.id, row.version);
    expanded.value.delete(row.id);
    ElMessage.success('团队配置已删除');
    await Promise.all([load(), loadOptions()]);
  } catch (value) { error(value); }
}
onMounted(() => { void load(); void loadOptions(); });
</script>

<template>
  <section class="team-config">
    <h2>长任务（团队配置）</h2>
    <p class="hint">按团队维护默认收件人和抄送人，支持跨部门添加团队及选择人员。保存后用于后续新增报告对象的默认名单，存量任务保留各自已保存的名单。</p>
    <div class="toolbar">
      <el-select v-model="department" class="org-filter" aria-label="部门" filterable clearable placeholder="全部部门（可搜索）" :loading="optionsLoading" @change="changeDepartment">
        <el-option v-for="name in departments" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="team" class="org-filter" aria-label="团队" filterable clearable :disabled="!department" :placeholder="department ? '全部团队（可搜索）' : '请先选择部门'" @change="search">
        <el-option v-for="name in teams" :key="name" :label="name" :value="name" />
      </el-select>
      <el-input v-model="keyword" class="keyword-filter" aria-label="关键词" clearable placeholder="搜索团队、姓名或统一认证号" @keyup.enter="search" @clear="search" />
      <button type="button" class="button" @click="search">查询</button>
      <button type="button" class="button primary" @click="beginCreate">新增团队</button>
    </div>
    <div v-loading="loading" class="table-wrap">
      <table class="team-table" aria-label="团队通知配置">
        <thead><tr><th class="expand-cell" /><th>部门</th><th>团队</th><th>收件人数</th><th>抄送人数</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <template v-for="row in rows" :key="row.id">
            <tr>
              <td class="expand-cell"><button type="button" class="expand-button" :aria-label="`${expanded.has(row.id!) ? '收起' : '展开'}${row.team || row.department}的通知名单`" :aria-expanded="expanded.has(row.id!)" @click="toggle(row)">{{ expanded.has(row.id!) ? '▾' : '▸' }}</button></td>
              <td>{{ row.department }}</td><td>{{ row.team || '部门默认名单' }}</td>
              <td>{{ row.to.length }} 人</td><td>{{ row.cc.length }} 人</td>
              <td class="actions"><button type="button" class="link-button" @click="beginEdit(row)">编辑</button><button type="button" class="link-button danger" @click="remove(row)">删除</button></td>
            </tr>
            <tr v-if="expanded.has(row.id!)" class="expanded-row"><td colspan="6">
              <div v-for="group in (['to', 'cc'] as const)" :key="group" class="recipient-line">
                <strong>{{ group === 'to' ? '收件人' : '抄送人' }}</strong>
                <div class="people"><span v-for="person in row[group]" :key="person.userId" class="person" :class="{ cc: group === 'cc', disabled: person.enabled === false }">{{ label(person) }}<small v-if="person.enabled === false">（停用）</small></span><span v-if="!row[group].length" class="empty">{{ group === 'to' ? '暂无收件人' : '暂无抄送人' }}</span></div>
              </div>
            </td></tr>
          </template>
          <tr v-if="!rows.length"><td colspan="6" class="empty-row">{{ loading ? '正在加载团队配置…' : '暂无团队配置，可点击“新增团队”添加' }}</td></tr>
        </tbody>
      </table>
    </div>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />

    <el-dialog v-model="editorOpen" :title="editing ? '编辑团队配置' : '新增团队配置'" width="min(1000px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving && !addingTeams && !recipientOpen" :show-close="!saving && !addingTeams" :before-close="closeEditor">
      <p class="hint">每个团队独立维护收件人和抄送人，可跨部门选择人员。完成后点击“保存”写入团队配置。</p>
      <div v-if="!editing" class="toolbar add-toolbar">
        <el-select v-model="selectedDepartment" aria-label="新增所属部门" filterable placeholder="选择部门" :loading="optionsLoading" :disabled="saving || addingTeams" @change="selectedTeams = []">
          <el-option v-for="name in sourceDepartments" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select v-model="selectedTeams" aria-label="新增团队" multiple filterable collapse-tags collapse-tags-tooltip placeholder="选择一个或多个团队" :disabled="!selectedDepartment || saving || addingTeams" :loading="optionsLoading">
          <el-option v-for="name in sourceTeams" :key="name" :label="name" :value="name" :disabled="alreadyAdded(name)" />
        </el-select>
        <button type="button" class="button" :disabled="!selectedTeams.length || saving || addingTeams" @click="addTeams">{{ addingTeams ? '读取配置中…' : '添加团队' }}</button>
      </div>
      <div v-if="drafts.length" class="editor-layout">
        <nav class="draft-list" aria-label="待保存团队">
          <button v-for="(row, index) in drafts" :key="JSON.stringify([row.department, row.team])" type="button" class="draft-button" :class="{ active: index === activeIndex }" :disabled="saving || addingTeams" @click="activeIndex = index"><small>{{ row.department }}</small><strong>{{ row.team || '部门默认名单' }}</strong></button>
        </nav>
        <section v-if="activeDraft" class="draft-detail">
          <header class="detail-heading"><div><h3>{{ activeDraft.team || '部门默认名单' }}</h3><span>{{ activeDraft.department }}</span></div><button v-if="!editing" type="button" class="link-button danger" :disabled="saving || addingTeams" @click="removeDraft(activeIndex)">移除团队</button></header>
          <p v-if="!editing && activeDraft.id != null" class="hint">已加载该团队保存的名单，可继续修改后保存。</p>
          <div class="recipient-heading"><span>收件人 {{ activeDraft.to.length }} 人 · 抄送人 {{ activeDraft.cc.length }} 人</span><button type="button" class="button" :disabled="saving || addingTeams" @click="manageRecipients">管理收件人</button></div>
          <div v-for="group in (['to', 'cc'] as const)" :key="group" class="recipient-line">
            <strong>{{ group === 'to' ? '收件人' : '抄送人' }}</strong>
            <div class="people"><span v-for="person in activeDraft[group]" :key="person.userId" class="person" :class="{ cc: group === 'cc', disabled: person.enabled === false }">{{ label(person) }}<small v-if="person.enabled === false">（停用）</small></span><span v-if="!activeDraft[group].length" class="empty">{{ group === 'to' ? '暂无收件人' : '暂无抄送人' }}</span></div>
          </div>
        </section>
      </div>
      <div v-else class="editor-empty">请选择部门和团队，点击“添加团队”。可切换部门继续添加。</div>
      <MailRecipientDialog v-if="activeDraft && recipientOpen" :open="recipientOpen" :to="activeDraft.to.map(person => person.userId)" :cc="activeDraft.cc.map(person => person.userId)" :names="recipientNames" :search-users="searchPeople" :teleport-disabled="true" save-label="保存" empty-to-hint="暂无收件人" @confirm="confirmRecipients" @update:open="recipientOpen = $event" />
      <template #footer><div class="editor-footer"><span>共 {{ drafts.length }} 个团队</span><button type="button" class="button" :disabled="saving || addingTeams" @click="closeEditor()">取消</button><button type="button" class="button primary" :disabled="!drafts.length || saving || addingTeams" @click="save">{{ saving ? '保存中…' : '保存' }}</button></div></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.team-config { background: white; padding: 24px; border-radius: 10px; }
h2 { margin: 0 0 12px; }
.hint { color: #64748b; font-size: 13px; line-height: 1.6; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 18px 0; }
.org-filter { width: 220px; }
.keyword-filter { flex: 1 1 260px; max-width: 440px; }
.button { min-height: 34px; padding: 7px 16px; border: 1px solid #dcdfe6; border-radius: 4px; background: white; color: #475569; cursor: pointer; font: inherit; font-size: 14px; }
.button:hover { color: #409eff; border-color: #a0cfff; }
.button.primary { background: #409eff; border-color: #409eff; color: white; }
button:disabled { cursor: not-allowed; opacity: .55; }
button:focus-visible { outline: 2px solid #409eff; outline-offset: 2px; }
.table-wrap { overflow-x: auto; }
.team-table { width: 100%; min-width: 640px; border-collapse: collapse; color: #606266; font-size: 14px; }
.team-table th, .team-table td { padding: 16px; text-align: left; border: 1px solid #ebeef5; }
.team-table th { background: #f8fafc; color: #64748b; font-weight: 600; }
.team-table .expand-cell { width: 42px; padding: 8px; text-align: center; }
.expand-button { background: none; border: 0; color: #64748b; font-size: 20px; cursor: pointer; width: 30px; height: 30px; }
.actions { width: 120px; white-space: nowrap; }
.link-button { background: none; border: 0; color: #409eff; cursor: pointer; font: inherit; font-size: 14px; padding: 4px 8px; }
.danger { color: #f56c6c; }
.expanded-row { background: #f8fafc; }
.recipient-line { display: flex; align-items: flex-start; gap: 18px; padding: 12px 0; }
.recipient-line > strong { flex: 0 0 56px; line-height: 28px; font-size: 13px; color: #64748b; font-weight: 500; }
.people { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.person { background: #ecf5ff; color: #337ecc; border-radius: 4px; padding: 4px 9px; font-size: 13px; overflow-wrap: anywhere; }
.person.cc { background: #f0f3f7; color: #53647c; }
.person.disabled { color: #909399; background: #f0f0f0; }
.empty { color: #94a3b8; font-size: 13px; line-height: 28px; }
.team-table .empty-row { text-align: center; padding: 48px 16px; color: #94a3b8; }
.el-pagination { margin-top: 20px; justify-content: flex-end; }
.add-toolbar > .el-select { width: 280px; }
.editor-layout { display: grid; grid-template-columns: 230px minmax(0, 1fr); min-height: 280px; border: 1px solid #e2e8f0; border-radius: 6px; overflow: hidden; }
.draft-list { background: #f8fafc; border-right: 1px solid #e2e8f0; max-height: 420px; overflow-y: auto; padding: 10px; }
.draft-button { display: flex; flex-direction: column; gap: 6px; width: 100%; padding: 12px; margin-bottom: 6px; text-align: left; background: transparent; border: 1px solid transparent; border-radius: 5px; color: #475569; cursor: pointer; overflow-wrap: anywhere; }
.draft-button small { color: #64748b; }
.draft-button.active { background: #ecf5ff; border-color: #b3d8ff; color: #337ecc; }
.draft-detail { padding: 20px; }
.detail-heading, .recipient-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-heading h3 { margin: 0 0 6px; font-size: 16px; }
.detail-heading span, .recipient-heading > span { color: #64748b; font-size: 13px; }
.recipient-heading { margin: 24px 0 8px; }
.editor-empty { padding: 64px 16px; background: #f8fafc; text-align: center; color: #94a3b8; }
.editor-footer { display: flex; gap: 12px; align-items: center; justify-content: flex-end; }
.editor-footer > span { margin-right: auto; color: #64748b; font-size: 13px; }
@media (max-width: 700px) {
  .team-config { padding: 16px; }
  .editor-layout { grid-template-columns: minmax(0, 1fr); }
  .draft-list { max-height: 160px; border-right: 0; border-bottom: 1px solid #e2e8f0; }
  .recipient-heading { flex-wrap: wrap; }
}
</style>
