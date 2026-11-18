<script setup lang="ts">
/** 例会材料管理页，负责批次版本、上传解析、板块确认及原件下载。 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Search, UploadFilled, ZoomIn } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { confirmPptImport, deleteBatch, downloadVersion, listBatchSummaries, listPptPeople, listVersions, previewPptImport, previewPptImportImage, reviewPptImport, savePptImport, searchPptPeople } from '../../api/pptAnomaly';
import type { PptPerson, PptImportAssignment, PptRecognitionMode } from '../../api/pptAnomaly';
import type { PptBatchSummary, PptVersion } from '../../types/pptAnomaly';
import PptVersionAssignmentsDialog from '../../components/ppt/PptVersionAssignmentsDialog.vue';
import { getLoggedInUser } from '../../utils/auth';

const router = useRouter();
const titlePrefix = /^\s*(?:[一二三四五六七八九十百零]+[.．、]\s*|[0-9]+(?:(?:[.．][0-9]+)+[.．、\s]*|[.．、\s]+))/;
const batches = ref<PptBatchSummary[]>([]);
const histories = ref<Record<number, PptVersion[]>>({});
const historyLoading = ref<Record<number, boolean>>({});
const historyErrors = ref<Record<number, boolean>>({});
const historyBatch = ref<PptBatchSummary>();
const assignmentVersion = ref<{ id: number; versionNo: number | null }>();
const loading = ref(false);
const busy = ref(false);
const deletingBatchId = ref<number>();
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
const reviewing = computed(() => dialog.value && (reviewLoaded.value || !!reviewTarget.value));
const reviewHeading = ref<HTMLHeadingElement>();
watch(reviewing, active => {
  if (active) reviewHeading.value?.focus();
}, { flush: 'post' });
const reviewSections = ref<string[]>([]);
const configuredContacts = ref(new Map<string, string>());
const contactNames = ref(new Map<string, string>());
const reviewPeople = ref<PptPerson[]>([]);
type ReviewRow = {
  page: number;
  title: string;
  section: string;
  recognitionMode: PptRecognitionMode;
  skip: boolean;
  thumbnail?: string;
};
const reviewRows = ref<ReviewRow[]>([]);
const reviewPreviewUrl = ref('');
const reviewPreviewLoading = ref(false);
let reviewPreviewRequest: AbortController | undefined;
function closeReviewPreview() {
  reviewPreviewRequest?.abort();
  reviewPreviewRequest = undefined;
  reviewPreviewLoading.value = false;
  if (reviewPreviewUrl.value) URL.revokeObjectURL(reviewPreviewUrl.value);
  reviewPreviewUrl.value = '';
}
async function openReviewPreview(row: ReviewRow) {
  if (reviewPreviewLoading.value) return;
  closeReviewPreview();
  const request = new AbortController();
  reviewPreviewRequest = request;
  reviewPreviewLoading.value = true;
  try {
    const image = await previewPptImportImage({ file: reviewTarget.value ? undefined : file.value,
      versionId: reviewTarget.value, pageNo: row.page }, request.signal);
    if (!request.signal.aborted) reviewPreviewUrl.value = URL.createObjectURL(image);
  } catch (cause) {
    if (!request.signal.aborted) ElMessage.error(cause instanceof Error ? cause.message : '高清预览加载失败，请重试');
  } finally {
    if (reviewPreviewRequest === request) reviewPreviewLoading.value = false;
  }
}
watch([dialog, file, reviewTarget], closeReviewPreview);
onBeforeUnmount(closeReviewPreview);
type ReviewFilter = 'all' | 'pending' | 'ready' | 'untitled' | 'skipped';
const reviewFilter = ref<ReviewFilter>('all');
const reviewSearch = ref('');
const selectedPage = ref<number>();
const reviewList = ref<HTMLUListElement>();
const ownerChoices = ref(new Map<string, string>());
const savedTitleHandlers = ref(new Map<string, string[]>());
const handlerChoices = ref(new Map<string, string[]>());
const boardEditorOpen = ref(false);
const editingRow = ref<ReviewRow>();
const unassignedRows = computed(() => reviewRows.value.filter(row => !row.skip && !row.section));
const assigningUnassigned = ref(false);
const boardMode = ref<'existing' | 'board'>('existing');
const existingBoard = ref('');
const boardName = ref('');
const boardContact = ref('');
/** 每个选人控件独立管理请求，避免关闭另一个下拉框时清空正在搜索的结果。 */
function createPersonSearch() {
  const results = ref<PptPerson[]>();
  const loading = ref(false);
  const failed = ref(false);
  let requestId = 0;
  const options = computed(() => results.value ?? reviewPeople.value.slice(0, 5));
  async function search(keyword: string) {
    const id = ++requestId;
    const term = keyword.trim();
    results.value = term ? [] : undefined;
    loading.value = !!term;
    failed.value = false;
    if (!term) return;
    try {
      const people = await searchPptPeople(term);
      if (id !== requestId) return;
      results.value = people;
      const known = new Map(reviewPeople.value.map(person => [person.userId, person]));
      for (const person of people) known.set(person.userId, person);
      reviewPeople.value = [...known.values()];
    } catch {
      if (id === requestId) failed.value = true;
    } finally {
      if (id === requestId) loading.value = false;
    }
  }
  return { options, loading, failed, search };
}
const { options: matchingBoardContacts, loading: boardContactLoading,
  failed: boardContactSearchFailed, search: searchBoardContacts } = createPersonSearch();
const { options: matchingHandlers, loading: handlerLoading,
  failed: handlerSearchFailed, search: searchHandlers } = createPersonSearch();
const { options: matchingEditorContacts, loading: editorContactLoading,
  failed: editorContactSearchFailed, search: searchEditorContacts } = createPersonSearch();
const boardOptions = computed(() => [...new Set([
  ...reviewSections.value, ...reviewRows.value.map(row => row.section).filter(Boolean),
])]);
const draftSection = computed(() => boardMode.value === 'existing' ? existingBoard.value : boardName.value.trim());
const duplicateBoard = computed(() => boardMode.value === 'board' && boardOptions.value.includes(draftSection.value));
const draftContact = computed(() => configuredContacts.value.get(draftSection.value)
  || (reviewRows.value.some(row => row !== editingRow.value && row.section === draftSection.value)
    ? ownerChoices.value.get(draftSection.value) : undefined));
const canApplyBoard = computed(() => !!draftSection.value && !duplicateBoard.value
  && (boardMode.value === 'board' || boardOptions.value.includes(draftSection.value))
  && !!(draftContact.value || boardContact.value));
function boardContactId(section: string) {
  return configuredContacts.value.get(section) || ownerChoices.value.get(section);
}

function titleKey(title: string) {
  return title.replace(titlePrefix, '').trim();
}

function titleHandlerIds(title: string) {
  const key = titleKey(title);
  return [...new Set([...(savedTitleHandlers.value.get(key) ?? []), ...(handlerChoices.value.get(key) ?? [])])];
}

function handlerOptions(ids: string[]) {
  const options = new Map(matchingHandlers.value.map(person => [person.userId, person]));
  for (const id of ids) {
    const person = reviewPeople.value.find(item => item.userId === id);
    if (person && !options.has(id)) options.set(id, person);
  }
  return [...options.values()];
}

function pageStatus(row: ReviewRow) {
  if (row.skip) return { key: 'skipped', label: '已跳过' };
  if (!row.section) return { key: 'pending', label: '待分配板块' };
  if (!boardContactId(row.section)) return { key: 'pending', label: '待补联系人' };
  return { key: 'ready', label: '可识别' };
}

const pendingRows = computed(() => reviewRows.value.filter(row => pageStatus(row).key === 'pending'));
const missingContactCount = computed(() => pendingRows.value.filter(row => row.section && !boardContactId(row.section)).length);
const missingHandlerCount = computed(() => reviewRows.value.filter(row => !row.skip && titleKey(row.title) && !titleHandlerIds(row.title).length).length);
const canConfirm = computed(() => reviewLoaded.value && !pendingRows.value.length);
const reviewFilters = computed(() => {
  const skippedCount = reviewRows.value.filter(row => row.skip).length;
  return [
    { key: 'all', label: '全部', count: reviewRows.value.length },
    { key: 'pending', label: '待处理', count: pendingRows.value.length },
    { key: 'ready', label: '可识别', count: reviewRows.value.length - pendingRows.value.length - skippedCount },
    { key: 'untitled', label: '无标题', count: reviewRows.value.filter(row => !row.skip && !row.title.trim()).length },
    { key: 'skipped', label: '已跳过', count: skippedCount },
  ] satisfies { key: ReviewFilter; label: string; count: number }[];
});
const visibleReviewRows = computed(() => {
  const term = reviewSearch.value.trim().toLowerCase();
  const pageNumber = /^(?:p\s*|第\s*)?(\d+)(?:\s*页)?$/i.exec(term);
  return reviewRows.value.filter(row => {
    const included = reviewFilter.value === 'untitled' ? !row.skip && !row.title.trim()
      : reviewFilter.value === 'all' || reviewFilter.value === pageStatus(row).key;
    return included && (!term || (pageNumber ? row.page === Number(pageNumber[1])
      : row.title.toLowerCase().includes(term) || row.section.toLowerCase().includes(term)));
  });
});
const selectedReviewRow = computed(() => reviewRows.value.find(row => row.page === selectedPage.value));
const previousReviewRow = computed(() => {
  const preceding = visibleReviewRows.value.filter(row => row.page < (selectedPage.value ?? 0));
  return preceding[preceding.length - 1];
});
const nextReviewRow = computed(() => visibleReviewRows.value.find(row => row.page > (selectedPage.value ?? 0)));

watch([reviewFilter, reviewSearch], () => {
  if (!visibleReviewRows.value.some(row => row.page === selectedPage.value)) {
    selectedPage.value = visibleReviewRows.value[0]?.page;
  }
});

watch([selectedPage, reviewList, reviewFilter, reviewSearch], () => {
  const list = reviewList.value;
  const current = list?.querySelector<HTMLElement>('[aria-current="page"]');
  if (!list || !current) return;
  const viewport = list.getBoundingClientRect();
  const row = current.getBoundingClientRect();
  if (row.top < viewport.top) list.scrollTop += row.top - viewport.top;
  if (row.bottom > viewport.bottom) list.scrollTop += row.bottom - viewport.bottom;
}, { flush: 'post' });

/** 打开待归属页面的板块和联系人选择窗口。 */
function openBoardEditor(row: ReviewRow, assignUnassigned = false, createBoard = false) {
  editingRow.value = row;
  assigningUnassigned.value = assignUnassigned;
  const title = row.title.replace(titlePrefix, '').trim();
  const section = row.section || title;
  const exists = boardOptions.value.includes(section);
  boardMode.value = createBoard || (row.section && !exists) ? 'board' : 'existing';
  existingBoard.value = exists ? section : '';
  boardName.value = !createBoard && boardMode.value === 'board' ? row.section : '';
  boardContact.value = createBoard ? '' : ownerChoices.value.get(section) ?? '';
  void searchEditorContacts('');
  boardEditorOpen.value = true;
}

/** 将选定板块和联系人应用到当前待归属页面组。 */
function applyBoard() {
  const row = editingRow.value;
  if (!row || !canApplyBoard.value) return;
  const previousSection = row.section;
  const section = draftSection.value;
  if (!draftContact.value) ownerChoices.value.set(section, boardContact.value);
  const rows = assigningUnassigned.value ? unassignedRows.value : [row];
  for (const item of rows) item.section = section;
  if (previousSection !== section && !reviewRows.value.some(item => item.section === previousSection)) {
    ownerChoices.value.delete(previousSection);
  }
  boardEditorOpen.value = false;
}

/** 将板块联系人认证号转换为可读姓名。 */
function boardContactLabel(userId: string) {
  const name = contactNames.value.get(userId) || reviewPeople.value.find(person => person.userId === userId)?.name;
  return name && name !== userId ? `${name}（${userId}）` : userId;
}

/** 生成联系人姓名及认证号的展示文字。 */
function contactLabel(section: string | null) {
  if (!section) return '—';
  const id = boardContactId(section);
  if (!id) return '—';
  return boardContactLabel(id);
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
    const [review, people] = await Promise.all([load(), listPptPeople()]);
    const known = new Map(people.map(person => [person.userId, person]));
    const handlers = Object.entries(review.titleHandlers ?? {});
    for (const [, members] of handlers) {
      for (const person of members) {
        known.set(person.userId, { userId: person.userId, name: person.name, department: person.depts.join('、') });
      }
    }
    reviewPeople.value = [...known.values()];
    savedTitleHandlers.value = new Map(handlers.map(([title, members]) => [title, members.map(person => person.userId)]));
    handlerChoices.value = new Map();
    configuredContacts.value = new Map(Object.entries(review.contacts));
    contactNames.value = new Map(Object.entries(review.contactNames ?? {}));
    reviewSections.value = review.boards;
    reviewRows.value = review.pages.map(page => ({
      page: page.page,
      title: page.title ?? '',
      section: page.section ?? '',
      recognitionMode: page.mode === 'TEXT_ONLY' || page.mode === 'UPPER_TABLE_ONLY' ? page.mode : 'FULL',
      skip: page.excluded || (page.skipped && page.title === '目录'),
      thumbnail: review.thumbnails?.[page.page],
    }));
    ownerChoices.value = new Map();
    reviewSearch.value = '';
    reviewFilter.value = pendingRows.value.length ? 'pending' : 'all';
    selectedPage.value = (pendingRows.value[0] ?? reviewRows.value[0])?.page;
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
    const assignments: PptImportAssignment[] = reviewRows.value.map(row => {
      const section = row.section.trim();
      const owner = row.skip || configuredContacts.value.has(section) ? null : ownerChoices.value.get(section) || null;
      return { page: row.page, title: row.title, recognitionMode: row.recognitionMode,
        section, owner, rememberOwner: !!owner, skip: row.skip, remember: false,
        handlers: row.skip ? undefined : titleHandlerIds(row.title),
        createBoard: !row.skip && !reviewSections.value.includes(section) };
    });
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

function openVersionAssignments(id: number, versionNo: number | null) {
  assignmentVersion.value = { id, versionNo };
}

async function confirmDeleteBatch(batch: PptBatchSummary) {
  if (deletingBatchId.value !== undefined) return;
  deletingBatchId.value = batch.id;
  try {
    const confirmed = await ElMessageBox.confirm(
      `确定删除「${batch.name}」？该批次的所有上传版本、识别结果和反馈记录都将删除，且无法恢复。`,
      '删除批次',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    ).then(() => true, () => false);
    if (!confirmed) return;

    await deleteBatch(batch.id);
    batches.value = batches.value.filter(row => row.id !== batch.id);
    if (historyBatch.value?.id === batch.id) historyBatch.value = undefined;
    delete histories.value[batch.id];
    ElMessage.success('批次已删除');
    await refresh();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? `删除批次失败：${cause.message}` : '删除批次失败，请重试');
  } finally {
    deletingBatchId.value = undefined;
  }
}

onMounted(refresh);
</script>

<template>
  <div class="batch-page">
    <header v-if="!reviewing" class="page-header">
      <div>
        <h2>材料上传</h2>
        <p>按批次上传例会材料和查看历史版本。</p>
      </div>
      <el-button class="add-material" type="primary" :icon="Plus" :disabled="busy" @click="openUpload()">新增材料</el-button>
    </header>
    <el-alert v-if="!reviewing && error" :title="error" type="error" class="notice" @close="error = ''" />
    <section v-if="!reviewing" class="material-list" aria-labelledby="material-list-title">
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
        <el-table-column label="操作" min-width="350">
          <template #default="{ row }">
            <el-button v-if="row.versionId && row.status !== 'ANALYZED' && row.uploadedBy === getLoggedInUser()?.userId"
              link type="primary" :disabled="busy" @click="openPendingVersion(row.versionId)">配置并识别</el-button>
            <el-button v-if="row.versionId" link type="primary" @click="openHistory(row)">查看历史版本</el-button>
            <!-- 暂时隐藏批次列表中的调整经办人入口。
            <el-button v-if="row.versionId && row.status === 'ANALYZED'" link type="primary"
              @click="openVersionAssignments(row.versionId, row.versionNo)">调整经办人</el-button> -->
            <el-button v-if="row.status === 'ANALYZED'" link type="primary"
              @click="router.push({ path: '/ppt-anomaly/feedback-records', state: { pptFeedbackFilter: { batch: row.id } } })">反馈记录</el-button>
            <!-- 暂时隐藏生成结果入口。
            <el-button v-if="row.versionId" link type="primary"
              @click="router.push(`/ppt-anomaly/materials/${row.id}/result`)">生成结果</el-button>
            -->
            <el-button v-if="!row.versionId" link type="primary" :disabled="busy" @click="openUpload(row)">上传材料</el-button>
            <el-button link type="danger" :loading="deletingBatchId === row.id"
              :disabled="busy || deletingBatchId !== undefined" @click="confirmDeleteBatch(row)">删除批次</el-button>
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
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <div class="history-actions">
                <!-- 暂时隐藏历史版本中的调整经办人入口。
                <el-button v-if="row.status === 'ANALYZED'" link type="primary"
                  @click="openVersionAssignments(row.id, row.versionNo)">调整经办人</el-button> -->
                <el-button link type="primary" @click="download(row)">下载原件</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
    <el-image-viewer v-if="reviewPreviewUrl" :url-list="[reviewPreviewUrl]" teleported
      :z-index="4000" @close="closeReviewPreview" />
    <PptVersionAssignmentsDialog v-if="assignmentVersion" :key="assignmentVersion.id"
      :version-id="assignmentVersion.id" :version-no="assignmentVersion.versionNo"
      @close="assignmentVersion = undefined" />

    <el-dialog v-if="!reviewing" v-model="dialog" class="ppt-dialog" title="新增材料"
      width="min(640px, 94vw)" align-center append-to-body
      destroy-on-close :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy">
      <el-steps :active="0" finish-status="success" simple class="import-steps">
        <el-step title="上传 PPT" />
        <el-step title="核对页面" />
      </el-steps>
      <el-alert v-if="uploadError" :title="uploadError" type="error" :closable="false" class="notice" />
      <el-form class="material-upload-form" label-position="top" :disabled="busy" @submit.prevent="submit">
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
    </el-dialog>
    <section v-if="reviewing" aria-labelledby="ppt-review-heading">
      <header class="page-header">
        <div>
          <h2 id="ppt-review-heading" ref="reviewHeading" tabindex="-1">核对页面</h2>
          <p v-if="file">{{ file.name }}</p>
        </div>
      </header>
        <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" class="review-error" />
        <p v-if="!reviewLoaded && busy" role="status">正在解析页面标题…</p>
        <template v-if="reviewLoaded">
          <div class="review-toolbar">
            <div class="review-filters" role="group" aria-label="页面筛选">
              <button v-for="filter in reviewFilters" :key="filter.key" type="button" class="review-filter"
                :aria-pressed="reviewFilter === filter.key" @click="reviewFilter = filter.key">
                {{ filter.label }} <span>{{ filter.count }}</span>
              </button>
            </div>
            <el-button v-if="unassignedRows.length" :disabled="busy"
              @click="openBoardEditor(unassignedRows[0], true)">
              批量分配全部 {{ unassignedRows.length }} 个未归属页
            </el-button>
          </div>
          <div class="review-workspace">
            <section class="review-index" aria-label="PPT 页面列表">
              <div class="review-search">
                <el-input v-model="reviewSearch" :prefix-icon="Search" clearable
                  placeholder="搜索页码、标题或板块" aria-label="搜索页码、标题或板块" />
              </div>
              <ul v-if="visibleReviewRows.length" ref="reviewList" class="review-list">
                <li v-for="row in visibleReviewRows" :key="row.page">
                  <button type="button" class="review-page" :aria-current="row.page === selectedPage ? 'page' : undefined"
                    @click="selectedPage = row.page">
                    <span class="review-page-number">P{{ row.page }}</span>
                    <span class="review-page-text">
                      <strong :title="row.title">{{ row.title.trim() || '无标题' }}</strong>
                      <small :title="row.section">{{ row.section || '—' }}</small>
                    </span>
                    <span class="review-status" :data-status="pageStatus(row).key">{{ pageStatus(row).label }}</span>
                  </button>
                </li>
              </ul>
              <div v-else class="review-empty">
                <span>没有符合条件的页面</span>
                <el-button v-if="reviewFilter !== 'all'" link type="primary" @click="reviewFilter = 'all'">
                  在全部页面中查看
                </el-button>
              </div>
            </section>
            <section v-if="selectedReviewRow" class="review-detail" aria-label="当前页设置">
              <header class="review-detail-header">
                <h3>第 {{ selectedReviewRow.page }} 页 <small>/ {{ reviewRows.length }}</small></h3>
                <span class="review-status" :data-status="pageStatus(selectedReviewRow).key">
                  {{ pageStatus(selectedReviewRow).label }}
                </span>
              </header>
              <div class="review-page-content">
                <div class="review-preview">
                  <el-button class="review-zoom" :icon="ZoomIn" :loading="reviewPreviewLoading"
                    @click="openReviewPreview(selectedReviewRow)">放大查看</el-button>
                  <el-image v-if="selectedReviewRow.thumbnail" :key="selectedReviewRow.page"
                    class="review-image" :src="selectedReviewRow.thumbnail" fit="contain"
                    :alt="`PPT 第 ${selectedReviewRow.page} 页原页`"
                    @click="openReviewPreview(selectedReviewRow)" />
                  <span v-else>暂无原页预览</span>
                </div>
                <div class="review-fields">
                  <label class="review-field" for="ppt-review-title">
                    <span>页面标题</span>
                    <el-input id="ppt-review-title" v-model="selectedReviewRow.title" maxlength="512" placeholder="无标题，可留空"
                      :disabled="busy" />
                  </label>
                  <div class="review-field">
                    <span>识别范围</span>
                    <el-select v-model="selectedReviewRow.recognitionMode" :disabled="busy || selectedReviewRow.skip"
                      aria-label="识别范围">
                      <el-option label="全量识别" value="FULL" />
                      <el-option label="仅文字" value="TEXT_ONLY" />
                      <el-option label="仅上方表格" value="UPPER_TABLE_ONLY" />
                    </el-select>
                  </div>
                  <div class="review-field">
                    <div class="review-field-heading">
                      <label id="ppt-review-board-label">所属板块</label>
                      <el-button link type="primary" :disabled="busy || selectedReviewRow.skip"
                        @click="openBoardEditor(selectedReviewRow, false, true)">新建板块</el-button>
                    </div>
                    <el-select v-model="selectedReviewRow.section" filterable placeholder="选择已有板块"
                      aria-labelledby="ppt-review-board-label" :disabled="busy || selectedReviewRow.skip">
                      <el-option v-for="section in boardOptions" :key="section" :label="section" :value="section" />
                    </el-select>
                  </div>
                  <div v-if="selectedReviewRow.section && !selectedReviewRow.skip" :key="selectedReviewRow.section"
                    class="review-field review-contact">
                    <label id="ppt-review-contact-label">板块联系人</label>
                    <div v-if="configuredContacts.has(selectedReviewRow.section)" class="review-contact-name">
                      {{ contactLabel(selectedReviewRow.section) }}
                    </div>
                    <template v-else>
                      <el-select :model-value="ownerChoices.get(selectedReviewRow.section)" filterable remote
                        aria-labelledby="ppt-review-contact-label" placeholder="搜索姓名或用户 ID" :disabled="busy"
                        :remote-method="searchBoardContacts" :loading="boardContactLoading"
                        :no-data-text="boardContactSearchFailed ? '搜索失败，请重试' : '未找到联系人'"
                        @update:model-value="ownerChoices.set(selectedReviewRow.section, $event)"
                        @visible-change="(open: boolean) => { if (!open) searchBoardContacts(''); }">
                        <template #label="{ value }">{{ boardContactLabel(value) }}</template>
                        <el-option v-for="person in matchingBoardContacts" :key="person.userId"
                          :label="boardContactLabel(person.userId)" :value="person.userId" />
                      </el-select>
                      <small v-if="!boardContactId(selectedReviewRow.section)">该板块缺少联系人，请先选择一位。</small>
                    </template>
                  </div>
                  <div v-if="titleKey(selectedReviewRow.title) && !selectedReviewRow.skip" :key="`handlers-${titleKey(selectedReviewRow.title)}`"
                    class="review-field review-contact">
                    <label id="ppt-review-handlers-label">标题经办人</label>
                    <div v-if="savedTitleHandlers.get(titleKey(selectedReviewRow.title))?.length" class="review-contact-name">
                      {{ savedTitleHandlers.get(titleKey(selectedReviewRow.title))?.map(boardContactLabel).join('、') }}
                    </div>
                    <el-select :model-value="handlerChoices.get(titleKey(selectedReviewRow.title)) ?? []" multiple filterable remote
                      aria-labelledby="ppt-review-handlers-label" placeholder="搜索并补充经办人，可多选"
                      :disabled="busy" :remote-method="searchHandlers" :loading="handlerLoading"
                      :no-data-text="handlerSearchFailed ? '搜索失败，请重试' : '未找到经办人'"
                      @update:model-value="handlerChoices.set(titleKey(selectedReviewRow.title), $event)"
                      @visible-change="(open: boolean) => { if (!open) searchHandlers(''); }">
                      <el-option v-for="person in handlerOptions(titleHandlerIds(selectedReviewRow.title))" :key="person.userId"
                        :label="[boardContactLabel(person.userId), person.department].filter(Boolean).join(' · ')"
                        :value="person.userId" :disabled="!person.department" />
                    </el-select>
                    <small v-if="!titleHandlerIds(selectedReviewRow.title).length">暂未配置经办人，部门事项将保留为待分派。</small>
                    <p class="review-field-hint">按标题和部门分派。缺少同部门经办人的事项会保留为待分派。</p>
                  </div>
                </div>
              </div>
              <footer class="review-detail-footer">
                <el-checkbox v-model="selectedReviewRow.skip" :disabled="busy">跳过本页</el-checkbox>
                <div class="review-navigation">
                  <el-button size="small" :disabled="!previousReviewRow"
                    @click="selectedPage = previousReviewRow?.page">上一页</el-button>
                  <el-button size="small" :disabled="!nextReviewRow"
                    @click="selectedPage = nextReviewRow?.page">下一页</el-button>
                </div>
              </footer>
            </section>
            <div v-else class="review-empty">选择页面后可核对原页和识别设置</div>
          </div>
        </template>
        <div class="dialog-actions import-actions">
          <div v-if="reviewLoaded && pendingRows.length" class="review-blockers" role="status">
            <span v-if="unassignedRows.length">{{ unassignedRows.length }} 页待分配板块</span>
            <span v-if="missingContactCount">{{ missingContactCount }} 页待补联系人</span>
            <span v-if="missingHandlerCount">{{ missingHandlerCount }} 页未配置经办人，可先导入</span>
          </div>
          <el-button v-if="!reviewTarget" :disabled="busy" @click="reviewLoaded = false">上一步</el-button>
          <el-button :disabled="busy" @click="dialog = false">取消</el-button>
          <el-button v-if="reviewLoaded" type="primary" :loading="busy" :disabled="!canConfirm" @click="confirmReview">
            保存并识别
          </el-button>
        </div>
    </section>
    <el-dialog v-model="boardEditorOpen" :title="boardMode === 'board' ? '新建板块' : '分配板块'" class="ppt-dialog" width="min(480px, 92vw)"
      align-center append-to-body destroy-on-close :close-on-click-modal="false">
      <p class="board-editor-title">{{ assigningUnassigned ? '为未归属页面指定同一板块' : editingRow?.title || '无标题' }}</p>
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
        <el-form-item v-if="draftSection && !duplicateBoard" label="板块联系人" required>
          <span v-if="draftContact">{{ contactLabel(draftSection) }}</span>
          <el-select v-else v-model="boardContact" placeholder="搜索姓名或 userID" filterable remote
            :remote-method="searchEditorContacts" :loading="editorContactLoading"
            :no-data-text="editorContactSearchFailed ? '搜索失败，请重试' : '未找到联系人'"
            @visible-change="(open: boolean) => { if (!open) searchEditorContacts(''); }">
            <template #label="{ value }">
              {{ boardContactLabel(value) }}
            </template>
            <el-option v-for="person in matchingEditorContacts" :key="person.userId"
              :label="`${person.name}（${person.userId}）`" :value="person.userId" />
          </el-select>
        </el-form-item>
        <div class="dialog-actions">
          <el-button @click="boardEditorOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :disabled="!canApplyBoard">确定</el-button>
        </div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.history-actions { display: flex; flex-wrap: nowrap; align-items: center; gap: 8px; }
.history-actions :deep(.el-button) { flex: none; margin-left: 0; white-space: nowrap; }
.import-steps { margin-block: 12px; }
#ppt-review-heading:focus { outline: none; }
.review-error { margin-bottom: 12px; }
.review-toolbar, .review-detail-header, .review-detail-footer {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px;
}
.review-toolbar { margin-bottom: 12px; }
.review-filters { display: flex; flex-wrap: wrap; gap: 4px; }
.review-filter {
  display: inline-flex; align-items: center; gap: 5px; min-height: 34px; padding: 4px 8px; border: 1px solid transparent;
  border-radius: 6px; color: #455670; background: transparent; cursor: pointer; font: inherit; white-space: nowrap;
}
.review-filter span { font-size: 12px; font-variant-numeric: tabular-nums; }
.review-filter:hover { background: #f0f4fa; }
.review-filter[aria-pressed='true'] { border-color: #cbdaf7; color: #234ba9; background: #eaf1ff; font-weight: 600; }
.review-workspace {
  display: grid; grid-template-columns: clamp(220px, 18vw, 290px) minmax(0, 1fr);
  height: clamp(420px, 62dvh, 580px); border: 1px solid #dfe6f0; border-radius: 12px;
  background: #fff; overflow: hidden;
}
.review-index { display: grid; grid-template-rows: auto minmax(0, 1fr); min-height: 0; border-right: 1px solid #dfe6f0; }
.review-search { padding: 10px; border-bottom: 1px solid #e9eef6; }
.review-list { min-height: 0; margin: 0; padding: 0; list-style: none; overflow: auto; scrollbar-gutter: stable; }
.review-list li + li { border-top: 1px solid #ebeff5; }
.review-page {
  display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%;
  min-height: 49px; padding: 6px 10px; border: 0; border-left: 3px solid transparent; background: #fff;
  color: #243651; cursor: pointer; text-align: left; font: inherit;
}
.review-page:hover { background: #f4f7fb; }
.review-page[aria-current='page'] { border-left-color: #2855c4; background: #eaf1ff; }
.review-page:focus-visible, .review-filter:focus-visible { outline: 2px solid #2855c4; outline-offset: -2px; }
.review-page-number { color: #455670; font-size: 12px; font-variant-numeric: tabular-nums; }
.review-page-text { display: grid; gap: 3px; min-width: 0; }
.review-page-text > * { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.review-page-text small, .review-detail h3 small { color: #52627a; }
.review-status {
  justify-self: end; padding: 3px 6px; border-radius: 4px; color: #4a596e; background: #edf0f5;
  font-size: 12px; white-space: nowrap;
}
.review-status[data-status='pending'] { color: #805016; background: #fff1d8; }
.review-status[data-status='ready'] { color: #236344; background: #e7f4ec; }
.review-detail {
  display: grid; grid-template-rows: auto minmax(min-content, 1fr) auto; gap: 14px;
  min-width: 0; min-height: 0; padding: 16px; overflow: auto;
  container-type: inline-size;
}
.review-detail h3 { margin: 0; color: #243651; font-size: 16px; }
.review-detail h3 small { margin-left: 4px; font-weight: 400; }
.review-preview, .review-empty {
  display: grid; min-width: 0; place-content: center; justify-items: center; gap: 8px; padding: 12px;
  color: #52627a; text-align: center;
}
.review-page-content { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(260px, 1fr); gap: 16px; }
.review-preview { position: relative; min-height: 200px; border: 1px solid #e5ebf4; border-radius: 8px; background: #f3f6fb; }
.review-zoom { position: absolute; top: 8px; right: 8px; z-index: 1; }
.review-image { position: absolute; inset: 6px 0; width: min(calc(100% - 12px), 720px); margin-inline: auto; }
.review-fields { display: grid; align-content: start; gap: 14px; padding: 16px; border: 1px solid #e5ebf4; border-radius: 8px; }
.review-field { display: grid; align-content: start; gap: 6px; min-width: 0; }
.review-field > span, .review-field > label, .review-field-heading { color: #243651; font-size: 13px; font-weight: 600; }
.review-field-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.review-field :deep(.el-input__wrapper), .review-field :deep(.el-select__wrapper) { min-height: 36px; box-sizing: border-box; }
.review-contact-name { padding: 8px 11px; color: #243651; background: #f3f6fb; border-radius: 6px; overflow-wrap: anywhere; }
.review-contact small { color: #805016; font-size: 12px; }
.review-field-hint { margin: 0; color: #52627a; font-size: 12px; line-height: 1.5; }
.review-detail-footer { border-top: 1px solid #e9eef6; padding-top: 8px; }
.review-navigation { display: flex; gap: 6px; }
.review-navigation :deep(.el-button + .el-button) { margin-left: 0; }
.import-actions { flex-wrap: wrap; margin-top: 12px; }
.review-blockers { display: flex; flex-wrap: wrap; gap: 4px 12px; margin-right: auto; color: #805016; font-size: 13px; }
@container (max-width: 580px) {
  .review-page-content { grid-template-columns: minmax(0, 1fr); }
  .review-preview { min-height: 175px; }
}
@media (max-width: 700px) {
  .review-workspace { grid-template-columns: minmax(0, 1fr); height: auto; }
  .review-index { height: 215px; border-right: 0; border-bottom: 1px solid #dfe6f0; }
  .review-detail { padding-inline: 10px; }
  .review-blockers { flex-basis: 100%; }
}
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
