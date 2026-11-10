<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listTeamConfig, listLedgerPeople, saveTeamConfig, deleteTeamConfig, type TeamConfig, type LedgerPerson } from '../api/teamConfig';

const rows = ref<TeamConfig[]>([]), total = ref(0), page = ref(1), size = ref(20), keyword = ref('');
const loading = ref(false), saving = ref(false), editOpen = ref(false), pickerOpen = ref(false);
const people = ref<LedgerPerson[]>([]), personTotal = ref(0), personPage = ref(1), personSize = ref(20), personKeyword = ref(''), peopleLoading = ref(false);
const blank = (): TeamConfig => ({ dev_dept: '', stat_group: '', user_id: '', user_name: '', position_role: '', recipient_type: 'TO', sort_order: 0, enabled: true, remark: '', report_keyword: '', notify_contact_name: '', notify_contact_phone: '' });
const form = reactive<TeamConfig>(blank());
const error = (e: unknown) => ElMessage.error(e instanceof Error ? e.message : '操作失败');
let listVersion = 0, peopleVersion = 0;
async function load() {
  const version = ++listVersion;
  loading.value = true;
  try {
    const result = await listTeamConfig(keyword.value.trim(), page.value, size.value);
    if (version !== listVersion) return;
    rows.value = result.items; total.value = result.total;
    if (page.value > 1 && !rows.value.length) { page.value = Math.max(1, Math.ceil(total.value / size.value)); await load(); }
  } catch (e) { if (version === listVersion) error(e); }
  finally { if (version === listVersion) loading.value = false; }
}
function search() { page.value = 1; void load(); }
function edit(row?: TeamConfig) {
  delete form.id;
  Object.assign(form, blank(), row || {});
  editOpen.value = true;
}
async function loadPeople() {
  const version = ++peopleVersion;
  peopleLoading.value = true;
  try {
    const result = await listLedgerPeople(personKeyword.value.trim(), personPage.value, personSize.value);
    if (version === peopleVersion) { people.value = result.items; personTotal.value = result.total; }
  } catch (e) { if (version === peopleVersion) error(e); }
  finally { if (version === peopleVersion) peopleLoading.value = false; }
}
function searchPeople() { personPage.value = 1; void loadPeople(); }
function openPicker() { pickerOpen.value = true; searchPeople(); }
function choose(person: LedgerPerson) {
  Object.assign(form, { dev_dept: person.dev_dept || '', stat_group: person.stat_group || '', user_id: person.user_id, user_name: person.user_name || '', position_role: person.position_role || '' });
  pickerOpen.value = false;
}
async function save() {
  if (![form.dev_dept, form.user_id, form.user_name].every(v => v?.trim())) { ElMessage.warning('请填写部门、姓名和统一认证号'); return; }
  saving.value = true;
  try { await saveTeamConfig({ ...form }); editOpen.value = false; ElMessage.success('保存成功'); await load(); }
  catch (e) { error(e); } finally { saving.value = false; }
}
async function remove(row: TeamConfig) {
  try { await ElMessageBox.confirm(`确认删除 ${row.dev_dept} / ${row.stat_group || '部门'} 的 ${row.user_name} 配置？`, '删除团队参数', { type: 'warning' }); }
  catch { return; }
  try { await deleteTeamConfig(row.id!); ElMessage.success('删除成功'); await load(); } catch (e) { error(e); }
}
onMounted(load);
</script>

<template>
  <section class="team-config">
    <h2>长任务（团队配置）</h2>
    <p class="hint">维护团队报告的默认人员参数。变更用于后续新增报告对象，已保存的任务配置保留独立参数。</p>
    <div class="toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索部门、团队、姓名或统一认证号" @keyup.enter="search" @clear="search" />
      <el-button @click="search">查询</el-button><el-button type="primary" @click="edit()">新增</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="dev_dept" label="部门" min-width="170" />
      <el-table-column prop="stat_group" label="团队" min-width="170" />
      <el-table-column prop="user_name" label="姓名" width="100" />
      <el-table-column prop="user_id" label="统一认证号" min-width="130" />
      <el-table-column prop="position_role" label="岗位角色" min-width="120" />
      <el-table-column label="通知类型" width="100"><template #default="{ row }">{{ row.recipient_type === 'TO' ? '收件人' : '抄送人' }}</template></el-table-column>
      <el-table-column prop="sort_order" label="排序" width="75" />
      <el-table-column label="状态" width="85"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      <template #empty>暂无团队参数，可点击“新增”从人员台账选择</template>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />

    <el-dialog v-model="editOpen" :title="form.id == null ? '新增团队参数' : '编辑团队参数'" width="700px" :close-on-click-modal="false">
      <el-button @click="openPicker">从人员台账选择</el-button>
      <el-form label-width="120px" class="editor" @submit.prevent="save">
        <el-form-item label="部门" required><el-input v-model="form.dev_dept" maxlength="200" /></el-form-item>
        <el-form-item label="团队"><el-input v-model="form.stat_group" maxlength="200" placeholder="部门报告可留空" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="form.user_name" maxlength="200" /></el-form-item>
        <el-form-item label="统一认证号" required><el-input v-model="form.user_id" maxlength="128" /></el-form-item>
        <el-form-item label="岗位角色"><el-input v-model="form.position_role" maxlength="100" /></el-form-item>
        <el-form-item label="通知类型"><el-radio-group v-model="form.recipient_type"><el-radio value="TO">收件人</el-radio><el-radio value="CC">抄送人</el-radio></el-radio-group></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort_order" :min="0" :max="2147483647" :precision="0" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="editOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="pickerOpen" title="从人员台账选择" width="1000px" append-to-body>
      <p class="hint">团队对应人员台账的统计组。仅显示最新统计月份的有效人员，按统一认证号去重；认证号精确匹配优先，同名人员请结合认证号、部门和团队选择。</p>
      <div class="toolbar"><el-input v-model="personKeyword" clearable placeholder="搜索姓名、统一认证号、部门或团队" @keyup.enter="searchPeople" @clear="searchPeople" /><el-button @click="searchPeople">查询</el-button></div>
      <el-table v-loading="peopleLoading" :data="people" border>
        <el-table-column prop="user_name" label="姓名" width="100" /><el-table-column prop="user_id" label="统一认证号" min-width="120" />
        <el-table-column prop="dev_dept" label="部门" min-width="160" /><el-table-column prop="stat_group" label="团队" min-width="160" />
        <el-table-column prop="position_role" label="岗位角色" min-width="120" /><el-table-column prop="stat_month" label="统计月份" width="110" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click="choose(row)">选择</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="personPage" v-model:page-size="personSize" :total="personTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadPeople" @size-change="searchPeople" />
    </el-dialog>
  </section>
</template>

<style scoped>
.team-config { background: white; padding: 24px; border-radius: 10px; }
h2 { margin: 0 0 12px; }
.hint { color: #64748b; font-size: 13px; line-height: 1.6; }
.toolbar { display: flex; gap: 12px; margin: 18px 0; }
.toolbar .el-input { max-width: 440px; }
.el-pagination { margin-top: 20px; justify-content: flex-end; }
.editor { margin-top: 20px; }
</style>
