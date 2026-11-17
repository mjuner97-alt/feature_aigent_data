<script setup lang="ts">
/** 例会材料管理页，负责批次版本、上传解析、板块确认及原件下载。 */

import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Search, UploadFilled } from '@element-plus/icons-vue';
import { confirmPptImport, downloadVersion, listBatchSummaries, listPptDemoPeople, listVersions, previewPptImport, reviewPptImport, savePptImport, searchPptPeople } from '../../api/pptAnomaly';
import type { PptDemoPerson, PptImportAssignment, PptImportPage } from '../../api/pptAnomaly';
import type { PptBatchSummary, PptVersion } from '../../types/pptAnomaly';
import { getLoggedInUser } from '../../utils/auth';

const router = useRouter();
const titlePrefix = /^\s*[0-9]+(?:(?:[.．][0-9]+)+[.．、\s]*|[.．、\s]+)/;
const batches = ref<PptBatchSummary[]>([]);
const histories = ref<Record<number, PptVersion[]>>({});
const historyLoading = ref<Record<number, boolean>>({});
const historyErrors = ref<Record<number, boolean>>({});
const historyBatch = ref<PptBatchSummary>();
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const query = ref('');
const dialog = ref(false);
const uploadError = ref('');
const mode = ref<'new' | 'existing'>('new');
const fixedBatch = ref(false);
const batchId = ref<number>();
const batchName = ref('');
const batchSearch = ref('');
const file = ref<File>();
const reviewTarget = ref<number>();
const reviewError = ref('');
const reviewLoaded = ref(false);
const reviewing = computed(() => reviewLoaded.value || !!reviewTarget.value);
const reviewedPages = ref<PptImportPage[]>([]);
const reviewSections = ref<string[]>([]);
const configuredContacts = ref(new Map<string, string>());
const reviewPeople = ref<PptDemoPerson[]>([]);
/** 导入确认时按标题聚合的待归属页面组。 */
type ReviewRow = { pages: PptImportPage[]; section: string };
const reviewRows = ref<ReviewRow[]>([]);
const ownerChoices = ref(new Map<string, string>());
const boardEditorOpen = ref(false);
const editingRow = ref<ReviewRow>();
const boardMode = ref<'existing' | 'board'>('existing');
const existingBoard = ref('');
const boardName = ref('');
const boardContact = ref('');
const boardContactResults = ref<PptDemoPerson[]>();
const boardContactLoading = ref(false);
const boardContactSearchFailed = ref(false);
let boardContactRequestId = 0;
const matchingBoardContacts = computed(() => boardContactResults.value ?? reviewPeople.value.slice(0, 5));

/** 搜索板块可选联系人并处理搜索状态。 */
async function searchBoardContacts(keyword: string) {
  const id = ++boardContactRequestId;
  const term = keyword.trim();
  boardContactResults.value = term ? [] : undefined;
  boardContactLoading.value = !!term;
  boardContactSearchFailed.value = false;
  if (!term) return;
  try {
    const people = await searchPptPeople(term);
    if (id === boardContactRequestId) boardContactResults.value = people;
  } catch {
    if (id === boardContactRequestId) boardContactSearchFailed.value = true;
  } finally {
    if (id === boardContactRequestId) boardContactLoading.value = false;
  }
}
const boardOptions = computed(() => [...new Set([
  ...reviewSections.value,
  ...reviewRows.value.filter(row => row !== editingRow.value).map(row => row.section).filter(Boolean),
])]);
const draftSection = computed(() => boardMode.value === 'existing' ? existingBoard.value : boardName.value.trim());
const duplicateBoard = computed(() => boardMode.value === 'board' && boardOptions.value.includes(draftSection.value));
const draftContact = computed(() => configuredContacts.value.get(draftSection.value)
  || (reviewRows.value.some(row => row !== editingRow.value && row.section === draftSection.value)
    ? ownerChoices.value.get(draftSection.value) : undefined));
const canApplyBoard = computed(() => !!draftSection.value && !duplicateBoard.value
  && (boardMode.value === 'board' || boardOptions.value.includes(draftSection.value)));
const canConfirm = computed(() => reviewLoaded.value && reviewRows.value.every(row => !!row.section));

/** 打开待归属页面的板块和联系人选择窗口。 */
function openBoardEditor(row: ReviewRow) {
  editingRow.value = row;
  const title = row.pages[0].title?.replace(titlePrefix, '').trim().slice(0, 512).trim() ?? '';
  const section = row.section || title;
  const exists = boardOptions.value.includes(section);
  boardMode.value = !row.section || exists ? 'existing' : 'board';
  existingBoard.value = exists ? section : '';
  boardName.value = boardMode.value === 'board' ? row.section : '';
  boardContact.value = ownerChoices.value.get(section) ?? '';
  void searchBoardContacts('');
  boardEditorOpen.value = true;
}

/** 将选定板块和联系人应用到当前待归属页面组。 */
function applyBoard() {
  const row = editingRow.value;
  if (!row || !canApplyBoard.value) return;
  const previousSection = row.section;
  const section = draftSection.value;
  if (!draftContact.value) ownerChoices.value.set(section, boardContact.value);
  row.section = section;
  if (previousSection !== section && !reviewRows.value.some(item => item.section === previousSection)) {
    ownerChoices.value.delete(previousSection);
  }
  boardEditorOpen.value = false;
}

/** 将板块联系人认证号转换为可读姓名。 */
function boardContactLabel(userId: string) {
  const person = reviewPeople.value.find(person => person.userId === userId);
  return person ? `${person.name}（${person.userId}）` : userId;
}

/** 生成联系人姓名及认证号的展示文字。 */
function contactLabel(section: string | null) {
  if (!section) return '—';
  const id = configuredContacts.value.get(section) || ownerChoices.value.get(section);
  if (!id) return '—';
  const person = reviewPeople.value.find(person => person.userId === id);
  return person ? `${person.name}（${person.department}）` : id;
}

const selectedBatch = computed(() => batches.value.find(batch => batch.id === batchId.value));
/** 按批次名称和文件信息判断是否符合搜索条件。 */
function matches(batch: PptBatchSummary, search: string) {
  const term = search.trim().toLowerCase();
  return batch.name.toLowerCase().includes(term) || !!batch.fileNames?.toLowerCase().includes(term);
}
const matchingBatches = computed(() => batches.value.filter(batch => matches(batch, batchSearch.value)));
const visible = computed(() => batches.value.filter(batch => matches(batch, query.value)));
const canSubmit = computed(() => !!file.value && (mode.value === 'new' ? !!batchName.value.trim() : batchId.value !== undefined));
/** 将材料上传时间格式化为分钟精度，空时间显示占位符。 */
const dateText = (value?: string | null) => value?.replace('T', ' ').slice(0, 16) || '—';

/** 刷新材料批次摘要并更新页面加载状态。 */
async function refresh() {
  loading.value = true;
  try {
    batches.value = await listBatchSummaries();
  } catch {
    error.value ||= '材料列表加载失败，请重试';
  } finally {
    loading.value = false;
  }
}

/** 加载指定批次的历史版本。 */
async function loadHistory(id: number) {
  if (historyLoading.value[id]) return;
  historyLoading.value[id] = true;
  historyErrors.value[id] = false;
  try {
    histories.value[id] = await listVersions(id);
  } catch {
    historyErrors.value[id] = true;
  } finally {
    historyLoading.value[id] = false;
  }
}

/** 展开批次历史版本列表。 */
function openHistory(batch: PptBatchSummary) {
  historyBatch.value = batch;
  void loadHistory(batch.id);
}

/** 初始化新批次或已有批次的材料上传窗口。 */
function openUpload(batch?: PptBatchSummary) {
  reviewTarget.value = undefined;
  reviewLoaded.value = false;
  uploadError.value = '';
  file.value = undefined;
  batchName.value = '';
  batchSearch.value = '';
  batchId.value = batch?.id;
  fixedBatch.value = !!batch;
  mode.value = batch ? 'existing' : 'new';
  dialog.value = true;
}


/** 加载解析预览并组合待确认的页面板块归属。 */
async function loadReview(load: () => ReturnType<typeof reviewPptImport>) {
  reviewLoaded.value = false;
  reviewError.value = '';
  try {
    const [review, people] = await Promise.all([load(), listPptDemoPeople()]);
    reviewPeople.value = people;
    configuredContacts.value = new Map(Object.entries(review.contacts));
    reviewSections.value = review.boards;
    reviewedPages.value = review.pages.filter(page => page.section !== null);
    const byTitle = new Map<string, ReviewRow>();
    const rows: ReviewRow[] = [];
    for (const page of review.pages) {
      if (page.section !== null) continue;
      const title = page.title?.replace(titlePrefix, '').trim();
      const existing = title ? byTitle.get(title) : undefined;
      if (existing) {
        existing.pages.push(page);
        continue;
      }
      const row: ReviewRow = { pages: [page], section: '' };
      if (title) byTitle.set(title, row);
      rows.push(row);
    }
    reviewRows.value = rows;
    ownerChoices.value = new Map();
    reviewLoaded.value = true;
  } catch (cause) {
    reviewError.value = cause instanceof Error && cause.message !== 'Failed to fetch'
      ? cause.message : '无法读取页面归属或联系人，请检查 PPT 文件及网络连接。';
  }
}

/** 保存已确认的页面归属并识别材料，成功后刷新批次列表。 */
async function confirmReview() {
  if (busy.value || !canConfirm.value) return;
  const target = reviewTarget.value;
  const selectedFile = file.value;
  const selectedBatchId = batchId.value;
  busy.value = true;
  reviewError.value = '';
  try {
    const assignments: PptImportAssignment[] = reviewRows.value.flatMap(row => row.pages.map(page => {
      const section = row.section.trim();
      const owner = configuredContacts.value.has(section) ? null : ownerChoices.value.get(section) || null;
      return { page: page.page, section, owner, rememberOwner: !!owner, skip: false,
        remember: !!page.title?.replace(titlePrefix, '').trim(), createBoard: !reviewSections.value.includes(section) };
    }));
    if (target) {
      await confirmPptImport(target, assignments);
    } else if (selectedFile && (mode.value === 'new' || selectedBatchId !== undefined)) {
      const batch = mode.value === 'new' ? { batchName: batchName.value.trim() } : { batchId: selectedBatchId! };
      await savePptImport(selectedFile, batch, assignments);
    } else {
      return;
    }
    query.value = '';
    await refresh();
    dialog.value = false;
  } catch (cause) {
    reviewError.value = cause instanceof Error && cause.message !== 'Failed to fetch'
      ? cause.message : '保存失败，请检查网络连接后再保存。';
  } finally {
    busy.value = false;
  }
}

/** 将所选 PPT 提交解析预览并进入导入确认步骤。 */
async function submit() {
  const selectedFile = file.value;
  if (!canSubmit.value || !selectedFile || busy.value) return;
  busy.value = true;
  uploadError.value = '';
  try {
    await loadReview(() => previewPptImport(selectedFile, mode.value === 'existing' ? batchId.value : undefined));
    if (!reviewLoaded.value) uploadError.value = reviewError.value;
  } finally {
    busy.value = false;
  }
}

/** 打开已上传但尚未识别版本的导入确认窗口。 */
async function openPendingVersion(id: number) {
  if (busy.value) return;
  reviewTarget.value = id;
  dialog.value = true;
  busy.value = true;
  try {
    await loadReview(() => reviewPptImport(id));
  } finally {
    busy.value = false;
  }
}

/** 下载所选历史版本的 PPT 原件。 */
async function download(version: PptVersion) {
  try {
    await downloadVersion(version);
  } catch {
    error.value = '原文件下载失败';
  }
}

function openVersionFindings(versionId: number) {
  router.push({ path: '/ppt-anomaly/overview', hash: `#version=${versionId}&scope=all` });
}

onMounted(refresh);
</script>

<template>
  <div class="batch-page">
    <header class="page-header">
      <div>
        <h2>材料上传</h2>
        <p>按批次上传例会材料，查看识别结果和历史版本。</p>
      </div>
      <el-button class="add-material" type="primary" :icon="Plus" :disabled="busy" @click="openUpload()">新增材料</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" class="notice" @close="error = ''" />
    <section class="material-list" aria-labelledby="material-list-title">
      <div class="filters">
        <h3 id="material-list-title">材料批次</h3>
        <el-input v-model="query" :prefix-icon="Search" clearable placeholder="搜索批次名称或文件名" aria-label="搜索批次名称或文件名" />
        <el-button v-if="error" :loading="loading" @click="error = ''; refresh()">刷新列表</el-button>
      </div>
      <el-table v-loading="loading" :data="visible" row-key="id"
        :empty-text="batches.length ? '没有匹配的材料' : '暂无材料，点击新增材料开始上传'">
        <el-table-column prop="name" label="批次名称" min-width="155" show-overflow-tooltip />
        <el-table-column label="最新文件" min-width="215" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fileName || '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="155">
          <template #default="{ row }">{{ dateText(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="330">
          <template #default="{ row }">
            <el-button v-if="row.versionId && row.status !== 'ANALYZED' && row.uploadedBy === getLoggedInUser()?.userId"
              link type="primary" :disabled="busy" @click="openPendingVersion(row.versionId)">配置并识别</el-button>
            <el-button v-if="row.versionId" link type="primary" @click="openHistory(row)">查看历史版本</el-button>
            <el-button v-if="row.status === 'ANALYZED'" link type="primary"
              @click="openVersionFindings(row.versionId)">识别结果</el-button>
            <el-button v-if="row.status === 'ANALYZED'" link type="primary"
              @click="router.push({ path: '/ppt-anomaly/feedback-records', state: { pptFeedbackFilter: { batch: row.id } } })">反馈记录</el-button>
            <!-- 暂时隐藏生成结果入口。
            <el-button v-if="row.versionId" link type="primary"
              @click="router.push(`/ppt-anomaly/materials/${row.id}/result`)">生成结果</el-button>
            -->
            <el-button v-if="!row.versionId" link type="primary" :disabled="busy" @click="openUpload(row)">上传材料</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <el-drawer :model-value="!!historyBatch" title="历史版本" size="min(680px, 100%)"
      @update:model-value="(open: boolean) => { if (!open) historyBatch = undefined }">
      <template v-if="historyBatch">
        <p>{{ historyBatch.name }}</p>
        <el-button v-if="historyErrors[historyBatch.id]" @click="loadHistory(historyBatch.id)">重新加载历史版本</el-button>
        <el-table v-else v-loading="historyLoading[historyBatch.id]"
          :data="histories[historyBatch.id] || []" row-key="id" empty-text="暂无上传版本">
          <el-table-column label="版本" width="110">
            <template #default="{ row, $index }">
              v{{ row.versionNo }}
              <el-tag v-if="$index === 0" size="small" effect="plain">最新</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="原文件" min-width="140" show-overflow-tooltip />
          <el-table-column label="上传时间" width="145">
            <template #default="{ row }">{{ dateText(row.uploadedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ANALYZED'" link type="primary" @click="openVersionFindings(row.id)">识别结果</el-button>
              <el-button link type="primary" @click="download(row)">下载原件</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="dialog" class="ppt-dialog" :title="reviewTarget ? '确认板块' : '新增材料'" width="min(860px, 94vw)" align-center append-to-body
      destroy-on-close :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy && !boardEditorOpen">
      <el-steps :active="reviewing ? 1 : 0" finish-status="success" simple class="import-steps">
        <el-step title="上传 PPT" />
        <el-step title="确认板块" />
      </el-steps>
      <el-alert v-if="!reviewing && uploadError" :title="uploadError" type="error" :closable="false" class="notice" />
      <el-form v-if="!reviewing" class="material-upload-form" label-position="top" :disabled="busy" @submit.prevent="submit">
        <el-form-item v-if="!fixedBatch" label="批次归属">
          <el-radio-group v-model="mode">
            <el-radio-button value="new">新建批次</el-radio-button>
            <el-radio-button value="existing" :disabled="!batches.length">加入已有批次</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="mode === 'new'" label="批次名称">
          <el-input v-model="batchName" placeholder="例如 11 月第 2 周例会" maxlength="255" />
        </el-form-item>
        <el-form-item v-else label="材料批次">
          <strong v-if="fixedBatch">{{ selectedBatch?.name || batchName }}</strong>
          <el-select v-else v-model="batchId" filterable placeholder="搜索批次名称或文件名"
            :filter-method="(term: string) => batchSearch = term" @visible-change="batchSearch = ''">
            <el-option v-for="batch in matchingBatches" :key="batch.id" :label="batch.name" :value="batch.id" />
          </el-select>
          <div v-if="selectedBatch?.fileName" class="last-upload">最近上传 {{ selectedBatch.fileName }} · {{ dateText(selectedBatch.uploadedAt) }}</div>
        </el-form-item>
        <el-form-item label="PPT 文件">
          <div class="file-picker" :class="{ 'has-file': file }">
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="file-picker-body">
              <input type="file" accept=".pptx" aria-label="PPT 文件" aria-describedby="ppt-file-hint" :disabled="busy"
                @change="file = ($event.target as HTMLInputElement).files?.[0]" />
              <div id="ppt-file-hint" class="file-hint">{{ file?.name || '支持 .pptx 格式' }}</div>
            </div>
          </div>
        </el-form-item>
        <div class="dialog-actions">
          <el-button :disabled="busy" @click="dialog = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="!canSubmit">解析 PPT</el-button>
        </div>
      </el-form>
      <template v-else>
      <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" />
      <p v-if="!reviewLoaded && busy" role="status">正在解析页面标题…</p>
      <template v-if="reviewLoaded">
      <section v-if="reviewRows.length" aria-labelledby="pending-board-heading">
        <h3 id="pending-board-heading" class="import-review-heading">待确认板块的页面</h3>
        <ul class="pending-pages">
          <li v-for="row in reviewRows" :key="row.pages[0].page" class="pending-page">
            <div class="import-page-title">
              <small>第 {{ row.pages.map(page => page.page).join('、') }} 页</small>
              <strong>{{ row.pages[0].title || '无标题' }}</strong>
              <small v-if="row.section">{{ row.section }} · {{ contactLabel(row.section) }}</small>
            </div>
            <el-button :type="row.section ? undefined : 'primary'" :plain="!row.section" :disabled="busy"
              :aria-label="`${row.section ? '编辑板块' : '加入板块'}，第 ${row.pages.map(page => page.page).join('、')} 页`"
              @click="openBoardEditor(row)">{{ row.section ? '编辑板块' : '加入板块' }}</el-button>
          </li>
        </ul>
      </section>
      <h3 v-if="reviewedPages.length" class="import-review-heading">已匹配板块的页面</h3>
      <el-table v-if="reviewedPages.length" :data="reviewedPages" max-height="220">
        <el-table-column prop="page" label="页码" width="70" />
        <el-table-column label="页面标题"><template #default="{ row }">{{ row.title || '无标题' }}</template></el-table-column>
        <el-table-column prop="section" label="板块" />
        <el-table-column label="板块联系人"><template #default="{ row }">{{ contactLabel(row.section) }}</template></el-table-column>
      </el-table>
      </template>
      <div class="dialog-actions import-actions">
        <el-button v-if="!reviewTarget" :disabled="busy" @click="reviewLoaded = false">上一步</el-button>
        <el-button :disabled="busy" @click="dialog = false">取消</el-button>
        <el-button v-if="reviewLoaded" type="primary" :loading="busy" :disabled="!canConfirm" @click="confirmReview">
          保存并识别
        </el-button>
      </div>
      </template>
    <el-dialog v-model="boardEditorOpen" :title="editingRow?.section ? '编辑板块' : '加入板块'" class="ppt-dialog" width="min(480px, 92vw)"
      align-center append-to-body destroy-on-close :close-on-click-modal="false">
      <p class="board-editor-title">{{ editingRow?.pages[0].title || '无标题' }}</p>
      <el-form label-position="top" @submit.prevent="applyBoard">
        <el-form-item label="板块归属">
          <el-radio-group v-model="boardMode" @change="boardContact = ''">
            <el-radio-button value="existing">选择已有板块</el-radio-button>
            <el-radio-button value="board">新建板块</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="boardMode === 'existing'" label="已有板块">
          <el-select v-model="existingBoard" placeholder="选择板块" no-data-text="暂无已有板块" filterable @change="boardContact = ''">
            <el-option v-for="section in boardOptions" :key="section" :value="section" :label="section" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="新板块名称" :error="duplicateBoard ? '板块已存在，请选择已有板块' : undefined">
          <el-input v-model="boardName" maxlength="512" placeholder="输入板块名称" @input="boardContact = ''" />
        </el-form-item>
        <el-form-item v-if="draftSection && !duplicateBoard" label="板块联系人（选填）">
          <span v-if="draftContact">{{ contactLabel(draftSection) }}</span>
          <el-select v-else v-model="boardContact" placeholder="搜索姓名或 userID" filterable remote
            :remote-method="searchBoardContacts" :loading="boardContactLoading"
            :no-data-text="boardContactSearchFailed ? '搜索失败，请重试' : '未找到联系人'"
            @visible-change="(open: boolean) => { if (!open) searchBoardContacts(''); }">
            <template #label="{ value }">
              {{ boardContactLabel(value) }}
            </template>
            <el-option v-for="person in matchingBoardContacts" :key="person.userId"
              :label="`${person.name}（${person.userId}）`" :value="person.userId" />
          </el-select>
        </el-form-item>
        <div class="dialog-actions">
          <el-button @click="boardEditorOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :disabled="!canApplyBoard">确定</el-button>
        </div>
      </el-form>
    </el-dialog>
    </el-dialog>
  </div>
</template>

<style scoped>
.import-steps, .import-actions { margin-block: 12px; }
.import-review-heading { margin: 16px 0 8px; color: #243651; font-size: 14px; }
.pending-pages { margin: 0; padding: 0; list-style: none; border: 1px solid #dfe6f0; border-radius: 10px; }
.pending-page { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; }
.pending-page + .pending-page { border-top: 1px solid #e9eef6; }
.pending-page .el-button { flex-shrink: 0; }
.import-page-title { min-width: 0; display: grid; gap: 3px; overflow-wrap: anywhere; color: #243651; }
.import-page-title small { color: #52627a; }
.board-editor-title { margin: 0 0 16px; color: #243651; font-weight: 600; overflow-wrap: anywhere; }
.batch-page { --el-color-primary: #2855c4; padding: clamp(16px, 3vw, 36px); min-height: 100%; box-sizing: border-box; background: #f3f6fb; }
.page-header, .dialog-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-header { margin-bottom: 24px; flex-wrap: wrap; }
.page-header h2 { margin: 0; font-size: 28px; color: #142441; }
.page-header p { margin: 6px 0 0; color: #52627a; font-size: 14px; line-height: 1.6; }
.add-material { min-height: 40px; padding-inline: 16px; font-weight: 600; }
.add-material:not(:disabled) { box-shadow: 0 4px 10px #2855c42b; }
.batch-page :deep(.el-button) { border-radius: 8px; }
.material-list { background: #fff; border: 1px solid #dfe6f0; border-radius: 14px; overflow: hidden; box-shadow: 0 4px 16px #22375c0a; }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 16px 20px; }
.filters h3 { margin: 0 auto 0 0; color: #243651; font-size: 16px; }
.filters .el-input { width: min(100%, 270px); }
.filters :deep(.el-input__wrapper) { border-radius: 8px; min-height: 36px; }
.material-list :deep(.el-table__cell) { padding-block: 11px; }
.material-list :deep(.el-button.is-link) { padding: 5px 7px; }
.material-list :deep(.el-button.is-link + .el-button.is-link) { margin-left: 2px; }
.material-list :deep(.el-button.is-link:not(:disabled):hover) { background: #e9f0ff; color: #2049b0; }
.notice { margin-bottom: 16px; }
.last-upload { width: 100%; margin-top: 6px; color: #667085; overflow-wrap: anywhere; }
.dialog-actions { justify-content: flex-end; padding-top: 16px; border-top: 1px solid #e9eef6; }
.dialog-actions :deep(.el-button) { min-height: 38px; margin-left: 0; border-radius: 8px; }
.material-upload-form :deep(.el-form-item) { margin-bottom: 22px; }
.file-picker { box-sizing: border-box; display: flex; align-items: center; gap: 12px; width: 100%; padding: clamp(14px, 2.5vw, 22px); border: 1px dashed #a9bde2; border-radius: 10px; background: #f5f8ff; }
.file-picker:focus-within, .file-picker.has-file { border-color: #2855c4; }
.file-picker-body { flex: 1; min-width: 0; }
.upload-icon { width: 36px; height: 36px; flex: 0 0 36px; border-radius: 9px; color: #2855c4; background: #e6eeff; font-size: 19px; }
.file-picker input { display: block; width: 100%; color: #314661; font: inherit; }
.file-picker input::file-selector-button { margin-right: 8px; padding: 5px 10px; border: 1px solid #c3d2ee; border-radius: 6px; background: #fff; color: #2049b0; cursor: pointer; font: inherit; line-height: 1.35; }
.file-picker input:not(:disabled)::file-selector-button:hover { background: #e6eeff; }
.file-picker input:disabled::file-selector-button { cursor: not-allowed; opacity: .65; }
.file-hint { margin-top: 4px; color: #52627a; font-size: 12px; line-height: 1.5; }
@media (max-width: 640px) {
  .filters h3 { flex-basis: 100%; }
  .filters .el-input { width: 100%; }
  .page-header h2 { font-size: 24px; }
}
</style>
