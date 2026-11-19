<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listPptReportConfigurations, savePptReportRecipients, searchPptReportPeople,
  type PptReportConfiguration, type PptReportRecipients } from '../../api/pptAnomaly';
import PptListFilterBar from './PptListFilterBar.vue';
import MailRecipientDialog from '../MailRecipientDialog.vue';

const rows = ref<PptReportConfiguration[]>([]);
const department = ref('');
const visibleRows = computed(() => rows.value.filter(row => !department.value || row.department === department.value));
const loading = ref(false);
const editing = ref(false);
const editingRow = ref<PptReportConfiguration>();
const saving = ref(false);
const error = ref('');
const saveError = ref('');
const groups = { to: '默认收件人', cc: '默认抄送人' } as const;
const previewLimit = 2;
const detailOpen = ref(false);
const detail = ref<{ row: PptReportConfiguration; group: keyof typeof groups }>();
const detailKeyword = ref('');
const detailPeople = computed(() => {
  if (!detail.value) return [];
  const { row, group } = detail.value;
  const term = detailKeyword.value.trim().toLowerCase();
  return row.recipients[group]
    .map(id => ({ id, name: row.recipients.names[id] || id }))
    .filter(({ id, name }) => !term || name.toLowerCase().includes(term) || id.toLowerCase().includes(term));
});

function recipientLabel(recipients: PptReportRecipients, id: string) {
  const name = recipients.names[id];
  return name && name !== id ? `${name}（${id}）` : id;
}

function showRecipients(row: PptReportConfiguration, group: keyof typeof groups) {
  detail.value = { row, group };
  detailKeyword.value = '';
  detailOpen.value = true;
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    rows.value = await listPptReportConfigurations();
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '读取部门配置失败';
  } finally {
    loading.value = false;
  }
}

function edit(row: PptReportConfiguration) {
  editingRow.value = row;
  saveError.value = '';
  editing.value = true;
}

async function save(value: PptReportRecipients) {
  const row = editingRow.value;
  if (!row?.editable || saving.value) return;
  saving.value = true;
  saveError.value = '';
  try {
    await savePptReportRecipients(row.department, value);
    row.recipients = value;
    editing.value = false;
    ElMessage.success('报告通知配置已保存');
  } catch (failure) {
    saveError.value = failure instanceof Error ? failure.message : '保存失败，请重试';
  } finally {
    saving.value = false;
  }
}

function searchPeople(keyword: string) {
  return editingRow.value ? searchPptReportPeople(editingRow.value.department, keyword) : Promise.resolve([]);
}

onMounted(load);
</script>

<template>
  <section class="report-config" v-loading="loading">
    <PptListFilterBar>
      <el-select v-model="department" filterable clearable aria-label="筛选部门"
        placeholder="全部部门" class="ppt-filter-select">
        <el-option v-for="row in rows" :key="row.department" :label="row.department" :value="row.department" />
      </el-select>
    </PptListFilterBar>
    <el-alert v-if="error" type="error" :title="error" :closable="false">
      <el-button link type="primary" @click="load">重试</el-button>
    </el-alert>
    <el-table v-else :data="visibleRows" row-key="department" empty-text="暂无可查看的部门">
      <el-table-column prop="department" label="部门" min-width="140" show-overflow-tooltip />
      <el-table-column v-for="group in (['to', 'cc'] as const)" :key="group"
        :label="groups[group]" min-width="230">
        <template #default="{ row }">
          <div v-if="row.recipients[group].length" class="recipients">
            <el-tag v-for="id in row.recipients[group].slice(0, previewLimit)" :key="id"
              class="recipient-tag" :type="group === 'cc' ? 'info' : 'primary'"
              :title="recipientLabel(row.recipients, id)">
              <span class="recipient-label">{{ recipientLabel(row.recipients, id) }}</span>
            </el-tag>
            <el-button v-if="row.recipients[group].length > previewLimit" link type="primary"
              :aria-label="`查看${row.department}全部${groups[group]}，共 ${row.recipients[group].length} 人`"
              @click="showRecipients(row, group)">查看全部（{{ row.recipients[group].length }}）</el-button>
          </div>
          <span v-else class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="115">
        <template #default="{ row }">
          <el-button v-if="row.editable" link type="primary" @click="edit(row)">管理收件人</el-button>
          <span v-else class="muted">仅查看</span>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="detailOpen" append-to-body width="min(580px, 94vw)"
      :title="detail ? `${detail.row.department} · ${groups[detail.group]}` : '通知名单'">
      <div v-if="detail" class="recipient-detail">
        <div class="recipient-detail-toolbar">
          <span class="muted">共 {{ detail.row.recipients[detail.group].length }} 人</span>
          <el-input v-model="detailKeyword" clearable aria-label="搜索名单" placeholder="搜索姓名或统一认证号" />
        </div>
        <el-table :data="detailPeople" row-key="id" max-height="min(360px, 45vh)"
          empty-text="未找到匹配人员">
          <el-table-column prop="name" label="姓名" min-width="110" show-overflow-tooltip />
          <el-table-column prop="id" label="统一认证号" min-width="160" show-overflow-tooltip />
        </el-table>
      </div>
      <template #footer><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-dialog>
    <MailRecipientDialog v-if="editingRow" v-model:open="editing" mode="config" v-bind="editingRow.recipients"
      :description="`为${editingRow.department}设置默认收件人和抄送人，可从任意部门选人。名单用于详情页的手动通知。`"
      :search-users="searchPeople" :submitting="saving" :error="saveError" @confirm="save" />
  </section>
</template>

<style scoped>
.report-config { display: grid; gap: 12px; padding: 8px 0 16px; }
.recipients { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; }
.recipient-tag { max-width: 100%; min-width: 0; }
.recipient-tag :deep(.el-tag__content) { min-width: 0; }
.recipient-label { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recipient-detail { display: grid; gap: 12px; }
.recipient-detail-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.recipient-detail-toolbar .el-input { width: min(240px, 70%); }
.muted { color: #64748b; }
</style>
