<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listTeamConfig, listTeamConfigOptions, listLedgerPeople, listLedgerPeopleOptions, addTeamConfigFromLedger, deleteTeamConfig, updateTeamConfigRecipientType, type TeamConfig, type TeamConfigOption, type LedgerPerson } from '../api/teamConfig';

const rows = ref<TeamConfig[]>([]), total = ref(0), page = ref(1), size = ref(20), keyword = ref('');
const department = ref(''), team = ref(''), filterOptions = ref<TeamConfigOption[]>([]), optionsLoading = ref(false);
const departments = computed(() => [...new Set(filterOptions.value.map(option => option.dev_dept))]);
const teams = computed(() => [...new Set(filterOptions.value
  .filter(option => option.dev_dept === department.value)
  .map(option => option.stat_group).filter((name): name is string => !!name))]);
const loading = ref(false), saving = ref(false), pickerOpen = ref(false);
const selectedPeople = ref<LedgerPerson[]>([]);
const pickerSession = ref(0);
const recipientSaving = ref(new Set<number>());
const people = ref<LedgerPerson[]>([]), personTotal = ref(0), personPage = ref(1), personSize = ref(20), personKeyword = ref(''), peopleLoading = ref(false);
const personDepartment = ref(''), personTeam = ref(''), personOptions = ref<TeamConfigOption[]>([]), personOptionsLoading = ref(false);
const personDepartments = computed(() => [...new Set(personOptions.value.map(option => option.dev_dept))]);
const personTeams = computed(() => [...new Set(personOptions.value
  .filter(option => option.dev_dept === personDepartment.value)
  .map(option => option.stat_group).filter((name): name is string => !!name))]);
const error = (e: unknown) => ElMessage.error(e instanceof Error ? e.message : '操作失败');
let listVersion = 0, peopleVersion = 0;
async function load() {
  const version = ++listVersion;
  loading.value = true;
  try {
    const result = await listTeamConfig(keyword.value.trim(), page.value, size.value, department.value, team.value);
    if (version !== listVersion) return;
    rows.value = result.items; total.value = result.total;
    if (page.value > 1 && !rows.value.length) { page.value = Math.max(1, Math.ceil(total.value / size.value)); await load(); }
  } catch (e) { if (version === listVersion) error(e); }
  finally { if (version === listVersion) loading.value = false; }
}
function search() { page.value = 1; void load(); }
function changeDepartment() { team.value = ''; search(); }
async function loadOptions() {
  optionsLoading.value = true;
  try { filterOptions.value = await listTeamConfigOptions(); }
  catch (e) { error(e); }
  finally { optionsLoading.value = false; }
}
async function loadPeople() {
  const version = ++peopleVersion;
  peopleLoading.value = true;
  try {
    const result = await listLedgerPeople(personKeyword.value.trim(), personPage.value, personSize.value, personDepartment.value, personTeam.value);
    if (version === peopleVersion) { people.value = result.items; personTotal.value = result.total; }
  } catch (e) { if (version === peopleVersion) error(e); }
  finally { if (version === peopleVersion) peopleLoading.value = false; }
}
function searchPeople() { personPage.value = 1; void loadPeople(); }
function changePersonDepartment() { personTeam.value = ''; searchPeople(); }
async function loadPersonOptions() {
  personOptionsLoading.value = true;
  try { personOptions.value = await listLedgerPeopleOptions(); }
  catch (e) { error(e); }
  finally { personOptionsLoading.value = false; }
}
function openPicker() {
  selectedPeople.value = [];
  pickerSession.value++;
  personDepartment.value = department.value;
  personTeam.value = team.value;
  personKeyword.value = '';
  people.value = [];
  pickerOpen.value = true;
  searchPeople();
  void loadPersonOptions();
}
function selectPeople(selection: LedgerPerson[]) { selectedPeople.value = selection; }
async function addSelected() {
  if (saving.value || !selectedPeople.value.length) return;
  saving.value = true;
  try {
    const result = await addTeamConfigFromLedger(selectedPeople.value.map(person => person.user_id));
    pickerOpen.value = false;
    ElMessage.success(`已新增 ${result.added} 人${result.skipped ? `，${result.skipped} 人已存在，已跳过` : ''}`);
    await Promise.all([load(), loadOptions()]);
  }
  catch (e) { error(e); } finally { saving.value = false; }
}
async function remove(row: TeamConfig) {
  try { await ElMessageBox.confirm(`确认删除 ${row.dev_dept} / ${row.stat_group || '部门'} 的 ${row.user_name} 配置？`, '删除团队参数', { type: 'warning' }); }
  catch { return; }
  try { await deleteTeamConfig(row.id!); ElMessage.success('删除成功'); await Promise.all([load(), loadOptions()]); } catch (e) { error(e); }
}
async function changeRecipientType(row: TeamConfig, type: string) {
  if (row.id == null || type === row.recipient_type || recipientSaving.value.has(row.id)) return;
  recipientSaving.value.add(row.id);
  try {
    const label = (value: string) => value === 'TO' ? '收件人' : '抄送人';
    try {
      await ElMessageBox.confirm(
        `确认将“${row.dev_dept}${row.stat_group ? ` / ${row.stat_group}` : ''}”下的 ${row.user_name}（${row.user_id}）从“${label(row.recipient_type)}”改为“${label(type)}”？此变更仅用于后续新增报告对象，已保存的报告名单需在“管理收件人”中调整。`,
        '确认修改通知类型',
        { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消', closeOnClickModal: false },
      );
    } catch { return; }
    const updated = await updateTeamConfigRecipientType(row.id, type);
    Object.assign(row, updated);
    ElMessage.success('通知类型已更新，用于后续新增报告对象');
  } catch (e) { error(e); }
  finally { recipientSaving.value.delete(row.id); }
}
onMounted(() => { void load(); void loadOptions(); });
</script>

<template>
  <section class="team-config">
    <h2>长任务（团队配置）</h2>
    <p class="hint">从人员台账批量新增团队报告默认人员，可直接调整通知类型。分管领导默认抄送、排序为 0，其他人员默认收件人、排序为 1；具体通知名单与顺序可在团队报告的“管理收件人”中调整，已保存的任务配置保留独立参数。</p>
    <div class="toolbar">
      <el-select v-model="department" class="org-filter" aria-label="部门" filterable clearable placeholder="全部部门（可搜索）" :loading="optionsLoading" @change="changeDepartment">
        <el-option v-for="name in departments" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="team" class="org-filter" aria-label="团队" filterable clearable :disabled="!department" :placeholder="department ? '全部团队（可搜索）' : '请先选择部门'" :loading="optionsLoading" @change="search">
        <el-option v-for="name in teams" :key="name" :label="name" :value="name" />
      </el-select>
      <el-input v-model="keyword" class="keyword-filter" aria-label="关键词" clearable placeholder="搜索姓名 / userID（统一认证号）" @keyup.enter="search" @clear="search" />
      <el-button @click="search">查询</el-button><el-button type="primary" @click="openPicker">新增</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="dev_dept" label="部门" min-width="170" />
      <el-table-column prop="stat_group" label="团队" min-width="170" />
      <el-table-column prop="user_name" label="姓名" width="100" />
      <el-table-column prop="user_id" label="统一认证号" min-width="130" />
      <el-table-column prop="position_role" label="岗位角色" min-width="120" />
      <el-table-column label="通知类型" width="135"><template #default="{ row }"><el-select :model-value="row.recipient_type" :aria-label="`${row.user_name}的通知类型`" :loading="recipientSaving.has(row.id)" :disabled="recipientSaving.has(row.id)" @change="changeRecipientType(row, $event)"><el-option label="收件人" value="TO"/><el-option label="抄送人" value="CC"/></el-select></template></el-table-column>
      <el-table-column label="状态" width="85"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="85" fixed="right"><template #default="{ row }"><el-button link type="danger" :disabled="recipientSaving.has(row.id)" @click="remove(row)">删除</el-button></template></el-table-column>
      <template #empty>暂无团队参数，可点击“新增”从人员台账选择</template>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />

    <el-dialog v-model="pickerOpen" title="从人员台账新增" width="1000px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <p class="hint">可跨页多选人员，部门与团队信息自动取自最新有效台账。已有人员会自动跳过。具体通知顺序请在团队报告的“管理收件人”中调整。</p>
      <div class="toolbar">
        <el-select v-model="personDepartment" class="org-filter" aria-label="台账部门" filterable clearable placeholder="全部部门（可搜索）" :loading="personOptionsLoading" @change="changePersonDepartment">
          <el-option v-for="name in personDepartments" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select v-model="personTeam" class="org-filter" aria-label="台账团队" filterable clearable :disabled="!personDepartment" :placeholder="personDepartment ? '全部团队（可搜索）' : '请先选择部门'" :loading="personOptionsLoading" @change="searchPeople">
          <el-option v-for="name in personTeams" :key="name" :label="name" :value="name" />
        </el-select>
        <el-input v-model="personKeyword" class="keyword-filter" aria-label="台账关键词" clearable placeholder="搜索姓名 / userID（统一认证号）" @keyup.enter="searchPeople" @clear="searchPeople" />
        <el-button @click="searchPeople">查询</el-button>
      </div>
      <el-table :key="pickerSession" aria-label="人员台账" v-loading="peopleLoading" :data="people" row-key="user_id" border @selection-change="selectPeople">
        <el-table-column type="selection" width="45" :reserve-selection="true" :selectable="() => !saving" />
        <el-table-column prop="user_name" label="姓名" width="100" /><el-table-column prop="user_id" label="统一认证号" min-width="120" />
        <el-table-column prop="dev_dept" label="部门" min-width="160" /><el-table-column prop="stat_group" label="团队" min-width="160" />
        <el-table-column prop="position_role" label="岗位角色" min-width="120" /><el-table-column prop="stat_month" label="统计月份" width="110" />
      </el-table>
      <el-pagination v-model:current-page="personPage" v-model:page-size="personSize" :total="personTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadPeople" @size-change="searchPeople" />
      <template #footer><div class="picker-footer"><span>已选择 {{ selectedPeople.length }} 人</span><el-button :disabled="saving" @click="pickerOpen = false">取消</el-button><el-button type="primary" :disabled="!selectedPeople.length || selectedPeople.length > 500" :loading="saving" @click="addSelected">确认新增</el-button></div></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.team-config { background: white; padding: 24px; border-radius: 10px; }
h2 { margin: 0 0 12px; }
.hint { color: #64748b; font-size: 13px; line-height: 1.6; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 18px 0; }
.toolbar .org-filter { width: 220px; }
.toolbar .el-input { width: 440px; max-width: 100%; }
.toolbar .keyword-filter { flex: 1 1 280px; max-width: 440px; }
.toolbar > .el-button { margin-left: 0; }
.el-pagination { margin-top: 20px; justify-content: flex-end; }
.picker-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.picker-footer > span { margin-right: auto; color: #64748b; font-size: 13px; }
.picker-footer > .el-button { margin-left: 0; }
</style>
