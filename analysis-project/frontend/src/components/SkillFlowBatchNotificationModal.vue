<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  previewSkillFlowBatchNotification,
  sendSkillFlowBatchNotification,
  type BatchNotificationPreview,
  type BatchNotificationSelection,
} from '../api/skillFlow';
import { searchSkillUsers } from '../api/skill';
import MailRecipientDialog from './MailRecipientDialog.vue';

const props = defineProps<{ open: boolean; selection: BatchNotificationSelection | null }>();
const emit = defineEmits<{
  (event: 'update:open', value: boolean): void;
  (event: 'sent', result: { sentExecutionIds: number[]; failedExecutionIds: number[] }): void;
}>();
const preview = ref<BatchNotificationPreview | null>(null);
const loading = ref(false);
const sending = ref(false);
const error = ref('');
const reportFilter = ref('');
const currentPage = ref(1);
const pageSize = ref(10);
const recipientOverrides = ref<Record<number, { to: string[]; cc: string[] }>>({});
const editingId = ref<number | null>(null);
const editorOpen = ref(false);
const editorTo = ref<string[]>([]);
const editorCc = ref<string[]>([]);
const editorNames = ref<Record<string, string>>({});
let generation = 0;

const displayedItems = computed(() => {
  const keyword = reportFilter.value.trim().toLocaleLowerCase('zh-CN');
  return [...(preview.value?.items ?? [])]
    .filter((item) => !keyword || item.reportName.toLocaleLowerCase('zh-CN').includes(keyword))
    .sort((left, right) => left.reportName.localeCompare(right.reportName, 'zh-CN', { numeric: true, sensitivity: 'base' }));
});
const totalPages = computed(() => Math.max(1, Math.ceil(displayedItems.value.length / pageSize.value)));
const paginatedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return displayedItems.value.slice(start, start + pageSize.value);
});

watch(reportFilter, () => { currentPage.value = 1; });
watch(pageSize, () => { currentPage.value = 1; });
watch(totalPages, (value) => {
  if (currentPage.value > value) currentPage.value = value;
});

function changePageSize(event: Event) {
  pageSize.value = Number((event.target as HTMLSelectElement).value);
}

function overrideList(ids?: number[]) {
  const allowed = ids ? new Set(ids) : null;
  return Object.entries(recipientOverrides.value)
    .map(([executionId, value]) => ({ executionId: Number(executionId), ...value }))
    .filter((value) => !allowed || allowed.has(value.executionId));
}

async function load(reset = true) {
  const selection = props.selection;
  const current = ++generation;
  preview.value = null;
  error.value = '';
  if (reset) {
    recipientOverrides.value = {};
    reportFilter.value = '';
    currentPage.value = 1;
  }
  if (!props.open || !selection) return;
  loading.value = true;
  try {
    const result = await previewSkillFlowBatchNotification({ ...selection, recipientOverrides: overrideList() });
    if (current === generation) preview.value = result;
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '预览批量通知失败';
  } finally {
    if (current === generation) loading.value = false;
  }
}

watch(() => [props.open, props.selection] as const, () => void load(), { immediate: true });

function close() {
  if (sending.value) return;
  generation++;
  emit('update:open', false);
}

async function send() {
  if (!props.selection || !preview.value?.sendableCount || sending.value) return;
  sending.value = true;
  error.value = '';
  try {
    // Freeze the previewed set so reports that finish after the preview are never added silently.
    const confirmedSelection: BatchNotificationSelection = {
      flowId: props.selection.flowId,
      batchId: props.selection.batchId,
      selectionMode: 'INCLUDE',
      includedExecutionIds: preview.value.items.filter((item) => item.sendable).map((item) => item.executionId),
      excludedExecutionIds: [],
    };
    confirmedSelection.recipientOverrides = overrideList(confirmedSelection.includedExecutionIds);
    const result = await sendSkillFlowBatchNotification(confirmedSelection);
    emit('sent', result);
    emit('update:open', false);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '批量发送通知失败';
  } finally {
    sending.value = false;
  }
}

function editRecipients(item: BatchNotificationPreview['items'][number]) {
  editingId.value = item.executionId;
  editorTo.value = item.to.map((person) => person.userId);
  editorCc.value = item.cc.map((person) => person.userId);
  editorNames.value = Object.fromEntries([...item.to, ...item.cc].map((person) => [person.userId, person.name]));
  editorOpen.value = true;
}

async function saveRecipients(value: { to: string[]; cc: string[]; names: Record<string, string> }) {
  if (editingId.value == null) return;
  recipientOverrides.value = {
    ...recipientOverrides.value,
    [editingId.value]: { to: [...value.to], cc: [...value.cc] },
  };
  editorNames.value = { ...value.names };
  editorOpen.value = false;
  await load(false);
}

function people(items: Array<{ userId: string; name: string; valid: boolean }>) {
  return items
    .map((item) => `${item.name === item.userId ? item.userId : `${item.name}(${item.userId})`}${item.valid ? '' : '（无效）'}`)
    .join('、') || '-';
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="batch-mask" @click.self="close">
      <section class="batch-dialog" role="dialog" aria-modal="true" aria-label="预览批量发送" :aria-busy="loading || sending">
        <header><h3>预览批量发送</h3><button type="button" aria-label="关闭" :disabled="sending" @click="close">×</button></header>
        <div class="batch-content">
          <div v-if="loading" class="state">正在读取收件人…</div>
          <div v-else-if="error && !preview" class="state error" role="alert">{{ error }}</div>
          <template v-else-if="preview">
            <div class="filter-row">
              <input v-model="reportFilter" type="search" aria-label="筛选报告名称" placeholder="筛选报告名称" />
            </div>
            <div class="preview-table-wrap"><table><thead><tr><th>序号</th><th>报告</th><th>收件人</th><th>抄送人</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="(item, index) in paginatedItems" :key="item.executionId">
                  <td>{{ (currentPage - 1) * pageSize + index + 1 }}</td><td>{{ item.reportName }}</td><td :title="people(item.to)">{{ people(item.to) }}</td><td :title="people(item.cc)">{{ people(item.cc) }}</td><td><button type="button" class="adjust-button" @click="editRecipients(item)">调整名单</button></td>
                </tr>
                <tr v-if="!displayedItems.length"><td class="empty-result" colspan="5">没有匹配的报告</td></tr>
              </tbody></table></div>
            <div v-if="displayedItems.length" class="pagination">
              <span>共 {{ displayedItems.length }} 条</span>
              <label>每页
                <select :value="pageSize" aria-label="每页显示数量" @change="changePageSize">
                  <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option>
                </select>
                条
              </label>
              <button type="button" :disabled="currentPage <= 1" @click="currentPage--">上一页</button>
              <span>{{ currentPage }} / {{ totalPages }}</span>
              <button type="button" :disabled="currentPage >= totalPages" @click="currentPage++">下一页</button>
            </div>
            <p v-if="error" class="error" role="alert">{{ error }}</p>
          </template>
        </div>
        <footer><button type="button" :disabled="sending" @click="close">取消</button><button type="button" :disabled="loading || sending || !preview?.sendableCount" @click="send">{{ sending ? '发送中…' : `确认发送（${preview?.sendableCount || 0}）` }}</button></footer>
      </section>
    </div>
    <MailRecipientDialog
      v-if="editorOpen"
      v-model:open="editorOpen"
      mode="batch"
      :to="editorTo"
      :cc="editorCc"
      :names="editorNames"
      :search-users="searchSkillUsers"
      @confirm="saveRecipients"
    />
  </Teleport>
</template>

<style scoped>
.batch-mask{position:fixed;inset:0;z-index:2100;display:flex;align-items:center;justify-content:center;padding:28px;background:#0f172a75;backdrop-filter:blur(3px)}
.batch-dialog{display:flex;flex-direction:column;width:min(1080px,100%);max-height:calc(100vh - 56px);overflow:hidden;border:1px solid #e2e8f0;border-radius:16px;background:#fff;box-shadow:0 24px 80px #0f172a33;color:#1e293b}
.batch-dialog header,.batch-dialog footer{display:flex;align-items:center;padding:18px 24px}.batch-dialog header{justify-content:space-between;border-bottom:1px solid #e2e8f0}.batch-dialog h3{margin:0;font-size:20px}.batch-dialog button{padding:7px 13px;border:1px solid #cbd5e1;border-radius:7px;background:#fff;cursor:pointer}.batch-dialog header button{border:0;font-size:22px}.batch-dialog button:disabled{opacity:.45;cursor:default}.batch-content{min-height:180px;overflow:auto;padding:20px 24px}.state{padding:56px;text-align:center;color:#64748b}.filter-row{display:flex;justify-content:flex-end;margin-bottom:12px}.filter-row input{width:min(320px,100%);padding:8px 11px;border:1px solid #cbd5e1;border-radius:7px;color:#1e293b;font:inherit;outline:none}.filter-row input:focus{border-color:#2563eb;box-shadow:0 0 0 3px #2563eb1a}.preview-table-wrap{overflow:auto;border:1px solid #e2e8f0;border-radius:9px}.preview-table-wrap table{width:100%;border-collapse:collapse;table-layout:fixed}.preview-table-wrap th,.preview-table-wrap td{padding:11px 12px;border-bottom:1px solid #edf2f7;text-align:left;font-size:13px}.preview-table-wrap th{background:#f8fafc;color:#475569}.preview-table-wrap th:first-child{width:64px}.preview-table-wrap th:nth-child(2){width:25%}.preview-table-wrap th:last-child{width:90px}.preview-table-wrap td{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.preview-table-wrap tr:last-child td{border-bottom:0}.preview-table-wrap .empty-result{text-align:center;color:#64748b}.batch-dialog .adjust-button{padding:4px 8px;border-color:#bfdbfe;color:#2563eb}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:12px;color:#64748b;font-size:13px}.pagination label{display:flex;align-items:center;gap:5px}.pagination select{padding:5px 7px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#1e293b}.pagination button{padding:5px 9px}.error{color:#b91c1c}.batch-dialog footer{justify-content:flex-end;gap:10px;border-top:1px solid #e2e8f0;background:#fafcff}.batch-dialog footer button:last-child{border-color:#2563eb;background:#2563eb;color:#fff}
@media(max-width:700px){.batch-mask{padding:12px}.batch-dialog{max-height:calc(100vh - 24px)}.batch-content{padding:16px}.preview-table-wrap table{min-width:760px}}
</style>
