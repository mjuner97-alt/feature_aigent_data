<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getPptReportRecipients, listPptReportDepartments, savePptReportRecipients,
  searchPptReportPeople, type PptReportRecipients } from '../../api/pptAnomaly';
import MailRecipientDialog from '../MailRecipientDialog.vue';
import RecipientGroupSummary from '../RecipientGroupSummary.vue';

const departments = ref<Awaited<ReturnType<typeof listPptReportDepartments>>>([]);
const department = ref('');
const recipients = ref<PptReportRecipients>();
const loadingDepartments = ref(false);
const loading = ref(false);
const editing = ref(false);
const saving = ref(false);
const error = ref('');
const saveError = ref('');
const editable = computed(() => departments.value.some(item => item.name === department.value && item.editable));
let requestId = 0;

async function loadDepartments() {
  loadingDepartments.value = true;
  error.value = '';
  try {
    departments.value = await listPptReportDepartments();
    department.value = departments.value.find(item => item.editable)?.name ?? departments.value[0]?.name ?? '';
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '读取部门失败';
  } finally {
    loadingDepartments.value = false;
  }
}

async function loadRecipients() {
  const id = ++requestId;
  recipients.value = undefined;
  error.value = '';
  if (!department.value) return;
  loading.value = true;
  try {
    const result = await getPptReportRecipients(department.value);
    if (id === requestId) recipients.value = result;
  } catch (failure) {
    if (id === requestId) error.value = failure instanceof Error ? failure.message : '读取名单失败';
  } finally {
    if (id === requestId) loading.value = false;
  }
}

async function save(value: PptReportRecipients) {
  if (!editable.value || saving.value) return;
  saving.value = true;
  saveError.value = '';
  try {
    await savePptReportRecipients(department.value, value);
    recipients.value = value;
    editing.value = false;
    ElMessage.success('报告通知配置已保存');
  } catch (failure) {
    saveError.value = failure instanceof Error ? failure.message : '保存失败，请重试';
  } finally {
    saving.value = false;
  }
}

watch(department, loadRecipients);
onMounted(loadDepartments);
</script>

<template>
  <section class="report-config" v-loading="loadingDepartments">
    <label for="ppt-report-department">部门</label>
    <el-select id="ppt-report-department" v-model="department" filterable placeholder="搜索部门"
      :disabled="editing || saving" class="department-picker">
      <el-option v-for="item in departments" :key="item.name" :label="item.name" :value="item.name" />
    </el-select>
    <el-alert v-if="error" type="error" :title="error" :closable="false">
      <el-button link type="primary" @click="department ? loadRecipients() : loadDepartments()">重试</el-button>
    </el-alert>
    <el-empty v-else-if="!loadingDepartments && !department" description="暂无可访问的部门" />
    <div v-else-if="department" v-loading="loading" class="recipient-settings">
      <template v-if="recipients">
        <header>
          <div class="recipient-heading">
            <strong>{{ department }} · 默认名单</strong>
            <small>收件人 {{ recipients.to.length }} 人 · 抄送 {{ recipients.cc.length }} 人</small>
          </div>
          <el-button v-if="editable" type="primary" @click="saveError = ''; editing = true">管理收件人</el-button>
          <span v-else class="muted">仅查看</span>
        </header>
        <div class="recipient-groups">
          <RecipientGroupSummary v-bind="recipients" />
        </div>
        <MailRecipientDialog v-model:open="editing" mode="config" v-bind="recipients"
          :description="`选择${department}的默认收件人和抄送人，保存后用于详情页的手动通知。`"
          :search-users="keyword => searchPptReportPeople(department, keyword)"
          :submitting="saving" :error="saveError" @confirm="save" />
      </template>
    </div>
  </section>
</template>

<style scoped>
.report-config { display: grid; gap: 12px; padding: 8px 0 16px; }
.report-config > label { font-size: 14px; font-weight: 600; }
.department-picker { width: min(360px, 100%); }
.recipient-settings { min-height: 132px; border: 1px solid #dce7f5; border-radius: 10px; padding: 18px; background: linear-gradient(120deg, #f8fbff, #fff); }
header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid #e7eef7; }
.recipient-heading { display: grid; gap: 4px; }
.recipient-heading small { color: #7b8ba1; font-size: 12px; }
.recipient-groups { display: grid; gap: 14px; margin-top: 14px; }
.muted { color: #64748b; font-size: 13px; font-weight: 400; }
</style>
