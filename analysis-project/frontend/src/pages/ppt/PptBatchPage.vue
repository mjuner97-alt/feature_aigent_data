<script setup lang="ts">
/** 例会材料管理页，提供批次、上传确认、全局默认配置维护和原件下载。 */

import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Search, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { Options } from '@popperjs/core';
import { confirmPptImport, deleteBatch, downloadVersion, getPptTitleRecognitionSettings, listBatchSummaries, listPptMaterialTypes, listPptPeople, listVersions, previewPptImport, previewPptImportImage, previewPptImportRecognition, reviewPptImport, savePptImport, savePptTitleRecognitionSettings, searchPptPeople } from '../../api/pptAnomaly';
import type { PptPerson, PptImportAssignment, PptImportRecognition, PptImportRecognitionPreview, PptImportTitleMatch, PptRecognitionMode, PptRecognitionScope, PptTitleScanRule } from '../../api/pptAnomaly';
import { isPptRecognitionMode, pptAnomalyPermissions, pptManualRecognitionOptions, pptRecognitionModes } from '../../api/pptAnomaly';
import type { PptBatchSummary, PptVersion } from '../../types/pptAnomaly';
import PptListFilterBar from '../../components/ppt/PptListFilterBar.vue';
import PptSlideViewer from '../../components/ppt/PptSlideViewer.vue';
import PptBoardContactList from '../../components/ppt/PptBoardContactList.vue';
import PptPreviewPdfDialog from '../../components/ppt/PptPreviewPdfDialog.vue';
import PptRecognitionSettings from '../../components/ppt/PptRecognitionSettings.vue';
import PptTitleHandlerReuseDialog from '../../components/ppt/PptTitleHandlerReuseDialog.vue';
import PptVersionAssignmentsDialog from '../../components/ppt/PptVersionAssignmentsDialog.vue';
import { getLoggedInUser } from '../../utils/auth';

const router = useRouter();
// 全局默认配置和当前版本分派使用各自的权限判断。
const canMaintainDefaults = ref(false);
const titlePrefix = /^\s*(?:[一二三四五六七八九十百零]+[.．、]\s*|[0-9]+(?:(?:[.．][0-9]+)+[.．、\s]*|[.．、\s]+))/;
const batches = ref<PptBatchSummary[]>([]);
const histories = ref<Record<number, PptVersion[]>>({});
const historyLoading = ref<Record<number, boolean>>({});
const historyErrors = ref<Record<number, boolean>>({});
const historyBatch = ref<PptBatchSummary>();
const assignmentVersion = ref<{ id: number; versionNo: number | null }>();
const pdfVersion = ref<PptVersion>();
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
const materialTypes = ref<{ name: string }[]>([]);
const materialType = ref('');
const versionName = ref<'初稿' | '终稿'>('初稿');
const batchSearch = ref('');
const materialTypeFilter = ref('');
const file = ref<File>();
const previewPdf = ref<File>();
const previewPdfInput = ref<HTMLInputElement>();
function clearPreviewPdf() {
  previewPdf.value = undefined;
  if (previewPdfInput.value) previewPdfInput.value.value = '';
}
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
type RecognitionMethod = 'default' | 'rules';
type ReviewRow = {
  page: number;
  originalTitle: string;
  title: string;
  section: string;
  defaultRecognitionMode: PptRecognitionMode | '';
  recognitionMethod: RecognitionMethod;
  recognition?: PptImportRecognition | null;
  refreshedRecognition?: Map<string, PptImportRecognitionPreview>;
  pendingRecognition?: Set<string>;
  skip: boolean;
  titleMatch?: PptImportTitleMatch;
  titleCandidateIndex: number;
};
const reviewRows = ref<ReviewRow[]>([]);
const reviewPreviewUrl = ref('');
const reviewPreviewLoading = ref(false);
const reviewPreviewError = ref('');
const reviewPreviewCache = new Map<number, string>();
let reviewPreviewRequest: AbortController | undefined;
function resetReviewPreviews() {
  reviewPreviewRequest?.abort();
  reviewPreviewRequest = undefined;
  reviewPreviewLoading.value = false;
  reviewPreviewError.value = '';
  reviewPreviewUrl.value = '';
  for (const url of reviewPreviewCache.values()) URL.revokeObjectURL(url);
  reviewPreviewCache.clear();
}
/** 当前页只请求一次清晰原图，页面内展示和查看大图复用同一图片。 */
async function loadReviewPreview(row?: ReviewRow) {
  reviewPreviewRequest?.abort();
  reviewPreviewRequest = undefined;
  reviewPreviewLoading.value = false;
  reviewPreviewError.value = '';
  reviewPreviewUrl.value = row ? reviewPreviewCache.get(row.page) ?? '' : '';
  if (!row || !dialog.value || !reviewLoaded.value || reviewPreviewUrl.value) return;
  const request = new AbortController();
  reviewPreviewRequest = request;
  reviewPreviewLoading.value = true;
  try {
    const image = await previewPptImportImage({ file: reviewTarget.value ? undefined : file.value,
      previewPdf: reviewTarget.value ? undefined : previewPdf.value,
      versionId: reviewTarget.value, pageNo: row.page }, request.signal);
    if (!request.signal.aborted) {
      const url = URL.createObjectURL(image);
      reviewPreviewCache.set(row.page, url);
      reviewPreviewUrl.value = url;
    }
  } catch {
    if (!request.signal.aborted) reviewPreviewError.value = '原页预览暂时不可用，可继续确认并识别。';
  } finally {
    if (reviewPreviewRequest === request) reviewPreviewLoading.value = false;
  }
}
watch([dialog, file, previewPdf, reviewTarget], resetReviewPreviews);
onBeforeUnmount(resetReviewPreviews);

type ReviewFilter = 'all' | 'pending' | 'ready' | 'untitled' | 'skipped';
const reviewFilter = ref<ReviewFilter>('all');
const reviewSearch = ref('');
const selectedPage = ref<number>();
const reviewList = ref<HTMLUListElement>();
const ownerChoices = ref(new Map<string, string>());
const savedTitleHandlers = ref(new Map<string, string[]>());
const handlerChoices = ref(new Map<string, string[]>());
const handlerListOpen = ref(false);
const handlerListTitle = ref('');
const handlerListKey = computed(() => titleKey(handlerListTitle.value));
const handlerListIds = computed(() => titleHandlerIds(handlerListTitle.value));
const handlerListDepartments = computed(() => new Map(reviewPeople.value.map(person => [person.userId, person.department])));
const reuseHandlersOpen = ref(false);
const reuseHandlerTitle = ref('');
const reuseExistingIds = computed(() => new Set(titleHandlerIds(reuseHandlerTitle.value)));
const boardEditorOpen = ref(false);
const editingRow = ref<ReviewRow>();
const unassignedRows = computed(() => reviewRows.value.filter(row => !row.skip && !row.section));
const assigningUnassigned = ref(false);
const boardMode = ref<'existing' | 'board'>('existing');
const existingBoard = ref('');
const boardName = ref('');
const boardContactIds = ref<string[]>([]);
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
  && (!!draftContact.value || boardContactIds.value.length > 0));
function boardContactId(section: string) {
  return configuredContacts.value.get(section) || ownerChoices.value.get(section);
}

function titleKey(title: string) {
  const value = title.trim();
  return savedTitleHandlers.value.has(value) ? value : value.replace(titlePrefix, '').trim();
}

function titleHandlerIds(title: string) {
  const key = titleKey(title);
  return [...new Set([...(savedTitleHandlers.value.get(key) ?? []), ...(handlerChoices.value.get(key) ?? [])])];
}

function openHandlerList(title: string) {
  handlerListTitle.value = title;
  handlerListOpen.value = true;
}

function removeDraftHandler(userId: string) {
  if (busy.value) return;
  const title = handlerListKey.value;
  handlerChoices.value.set(title, (handlerChoices.value.get(title) ?? []).filter(id => id !== userId));
}

const handlerDropdownOffset = 6;
const handlerDropdownMaxHeight = 224;
const handlerDropdownOptions: Partial<Options> = {
  modifiers: [
    { name: 'flip', enabled: false },
    {
      name: 'fitHandlerDropdown',
      enabled: true,
      phase: 'beforeMain',
      fn({ state }) {
        const reference = state.elements.reference;
        const anchor = reference.getBoundingClientRect();
        const panel = 'closest' in reference ? reference.closest('.review-detail')?.getBoundingClientRect() : undefined;
        const top = Math.max(0, panel?.top ?? 0);
        const bottom = Math.min(document.documentElement.clientHeight, panel?.bottom ?? Infinity);
        const gutter = handlerDropdownOffset + 6;
        const above = anchor.top - top - gutter;
        const below = bottom - anchor.bottom - gutter;
        const list = state.elements.popper.querySelector('.el-select-dropdown__wrap');
        const desiredHeight = Math.min(handlerDropdownMaxHeight, list?.scrollHeight ?? state.rects.popper.height);
        const openAbove = below < desiredHeight && above > below;
        const placement = openAbove ? 'top-start' : 'bottom-start';
        if (state.placement !== placement) {
          state.placement = placement;
          state.reset = true;
          return;
        }
        state.elements.popper.style.setProperty('--handler-dropdown-height',
          `${Math.max(0, Math.floor(Math.min(handlerDropdownMaxHeight, openAbove ? above : below)))}px`);
      },
    },
  ],
};

function openHandlerReuse(title: string) {
  if (busy.value) return;
  reuseHandlerTitle.value = titleKey(title);
  reuseHandlersOpen.value = true;
}

function addReusedHandlers(_sourceTitle: string, people: PptPerson[]) {
  const title = reuseHandlerTitle.value;
  if (!title || busy.value) return;
  const existing = reuseExistingIds.value;
  const known = new Map(reviewPeople.value.map(person => [person.userId, person]));
  const additions = new Set<string>();
  for (const person of people) {
    if (existing.has(person.userId)) continue;
    additions.add(person.userId);
    known.set(person.userId, person);
  }
  reviewPeople.value = [...known.values()];
  handlerChoices.value.set(title, [...(handlerChoices.value.get(title) ?? []), ...additions]);
  reuseHandlersOpen.value = false;
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
  if (row.pendingRecognition?.has(row.title.trim())) return { key: 'pending', label: '待重新匹配规则' };
  if (row.skip) return { key: 'skipped', label: '已跳过' };
  if (!reviewRecognitionMode(row)) return { key: 'pending', label: '待确认扫描规则' };
  if (!row.section) return { key: 'pending', label: '待分配板块' };
  if (!boardContactId(row.section)) return { key: 'pending', label: '待补联系人' };
  return { key: 'ready', label: '可识别' };
}

function selectableRecognitionMode(mode: string): PptRecognitionMode | '' {
  return isPptRecognitionMode(mode) ? mode : mode === 'RULE_CONFLICT' ? '' : 'FULL';
}

function reviewRecognitionMode(row: ReviewRow) {
  return row.recognitionMethod === 'rules' && row.recognition
    ? selectableRecognitionMode(row.recognition.ruleMode) : row.defaultRecognitionMode;
}

function reviewRecognitionSummary(row: ReviewRow) {
  if (row.pendingRecognition?.has(row.title.trim())) return '待重新匹配当前页';
  const mode = reviewRecognitionMode(row);
  const method = row.recognitionMethod === 'rules' ? '规则识别' : '默认识别';
  return `${method} · ${mode ? pptRecognitionModes[mode] : '待确认范围'}`;
}

function setRecognitionMethod(row: ReviewRow, method: RecognitionMethod) {
  row.recognitionMethod = method;
  setReviewSkip(row, reviewRecognitionMode(row) === 'NO_SCAN');
}

function changeReviewTitle(row: ReviewRow, title: string) {
  if (row.title.trim() !== title.trim()) {
    const refreshed = row.refreshedRecognition?.get(title.trim());
    if (refreshed) {
      applyRefreshedRecognition(row, refreshed);
    } else if (row.recognition) {
      row.recognition = undefined;
      row.recognitionMethod = 'default';
      row.defaultRecognitionMode = 'FULL';
      setReviewSkip(row, false);
    }
  }
  row.title = title;
}

function selectTitleCandidate(row: ReviewRow, index: number) {
  const match = row.titleMatch;
  if (!match) return;
  const candidate = match.candidates[index];
  row.titleCandidateIndex = index;
  row.title = candidate?.title ?? match.originalTitle;
  row.section = candidate?.section ?? '';
  const refreshed = row.refreshedRecognition?.get(row.title.trim());
  if (refreshed) {
    applyRefreshedRecognition(row, refreshed);
    return;
  }
  row.recognition = candidate?.recognition;
  row.defaultRecognitionMode = selectableRecognitionMode(row.recognition?.defaultMode ?? candidate?.mode ?? 'FULL');
  setRecognitionMethod(row, row.recognition ? 'rules' : 'default');
}

const recognitionEditorOpen = ref(false);
const recognitionTarget = shallowRef<{ title: string }>();
const recognitionEditor = ref<InstanceType<typeof PptRecognitionSettings>>();
const recognitionScope = ref<PptRecognitionScope>({ scope: 'FULL' });
const initialScanRules = ref<PptTitleScanRule[]>();
const recognitionLoading = ref(false);
const recognitionError = ref('');
const recognitionSaved = ref(false);

function applyRefreshedRecognition(row: ReviewRow, result: PptImportRecognitionPreview) {
  const keepSkipped = row.skip && reviewRecognitionMode(row) !== 'NO_SCAN';
  row.recognition = result.recognition;
  row.defaultRecognitionMode = selectableRecognitionMode(result.defaultMode);
  row.recognitionMethod = result.recognition ? 'rules' : 'default';
  row.skip = keepSkipped || reviewRecognitionMode(row) === 'NO_SCAN';
}

function openRecognitionEditor(row: ReviewRow) {
  const title = row.title.trim();
  if (busy.value || !title || !canMaintainDefaults.value) return;
  recognitionTarget.value = { title };
  recognitionSaved.value = row.pendingRecognition?.has(title) === true;
  initialScanRules.value = undefined;
  recognitionLoading.value = false;
  recognitionError.value = '';
  recognitionEditorOpen.value = true;
  if (!recognitionSaved.value) void loadRecognitionSettings();
}

function resolveRecognitionConflict(row: ReviewRow, mode: unknown) {
  if (typeof mode !== 'string' || !isPptRecognitionMode(mode)) return;
  row.defaultRecognitionMode = mode;
  setRecognitionMethod(row, 'default');
}

async function loadRecognitionSettings() {
  const target = recognitionTarget.value;
  if (!target) return;
  initialScanRules.value = undefined;
  recognitionError.value = '';
  recognitionLoading.value = true;
  try {
    const { scanRules, ...scope } = await getPptTitleRecognitionSettings(target.title);
    if (recognitionTarget.value !== target || !recognitionEditorOpen.value) return;
    recognitionScope.value = scope;
    initialScanRules.value = scanRules;
  } catch (cause) {
    if (recognitionTarget.value === target) recognitionError.value = cause instanceof Error ? cause.message : '无法加载识别规则';
  } finally {
    if (recognitionTarget.value === target) recognitionLoading.value = false;
  }
}

async function refreshPageRecognition(row: ReviewRow, title: string) {
  const result = await previewPptImportRecognition({
    file: reviewTarget.value ? undefined : file.value, versionId: reviewTarget.value,
    pageNo: row.page, title,
  });
  row.pendingRecognition?.delete(title);
  (row.refreshedRecognition ??= new Map()).set(title, result);
  applyRefreshedRecognition(row, result);
}

async function refreshTitleRecognition(title: string) {
  const failedPages: number[] = [];
  for (const row of reviewRows.value) {
    if (row.title.trim() !== title || !row.pendingRecognition?.has(title)) continue;
    try {
      await refreshPageRecognition(row, title);
    } catch {
      failedPages.push(row.page);
    }
  }
  return failedPages;
}

async function saveRecognitionSettings() {
  const target = recognitionTarget.value;
  if (!target || busy.value) return;
  const rules = recognitionSaved.value ? undefined : recognitionEditor.value?.prepareSave()?.rules;
  if (!recognitionSaved.value && !rules) return;
  busy.value = true;
  recognitionError.value = '';
  try {
    if (rules) {
      await savePptTitleRecognitionSettings(target.title, { ...recognitionScope.value, scanRules: rules });
      recognitionSaved.value = true;
      // 标题配置变更后，该标题的旧匹配结果需要重新计算。
      for (const row of reviewRows.value) {
        row.refreshedRecognition?.delete(target.title);
        (row.pendingRecognition ??= new Set()).add(target.title);
      }
    }
    const failedPages = await refreshTitleRecognition(target.title);
    if (failedPages.length) {
      recognitionError.value = `全局设置已保存，第 ${failedPages.join('、')} 页匹配失败，请重试。`;
      return;
    }
    recognitionEditorOpen.value = false;
    ElMessage.success('全局标题识别设置已保存，同标题页面已重新匹配');
  } catch (cause) {
    recognitionError.value = cause instanceof Error ? cause.message : '无法保存识别设置，请重试';
  } finally {
    busy.value = false;
  }
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
      : row.title.toLowerCase().includes(term) || row.section.toLowerCase().includes(term)
        || row.originalTitle.toLowerCase().includes(term)));
  });
});
const selectedReviewRow = computed(() => visibleReviewRows.value.find(row => row.page === selectedPage.value));
const selectedHandlerTitle = computed(() => titleKey(selectedReviewRow.value?.title ?? ''));
const savedHandlersForReview = computed(() => savedTitleHandlers.value.get(selectedHandlerTitle.value) ?? []);
const draftHandlersForReview = computed(() => handlerChoices.value.get(selectedHandlerTitle.value) ?? []);
const selectedBoardContacts = computed(() => configuredContacts.value.get(selectedReviewRow.value?.section ?? ''));
const selectedContactIds = computed(() => ownerChoices.value.get(selectedReviewRow.value?.section ?? '')?.split(',') ?? []);
watch([dialog, file, previewPdf, reviewTarget, reviewLoaded, () => selectedReviewRow.value?.page], () => {
  void loadReviewPreview(selectedReviewRow.value);
});
const previousReviewRow = computed(() => {
  const preceding = visibleReviewRows.value.filter(row => row.page < (selectedPage.value ?? 0));
  return preceding[preceding.length - 1];
});
const nextReviewRow = computed(() => visibleReviewRows.value.find(row => row.page > (selectedPage.value ?? 0)));

/** 跳过当前页后，优先预览下一张符合当前筛选且未跳过的页面。 */
function setReviewSkip(row: ReviewRow, skip: boolean) {
  if (row.skip === skip) return;
  row.skip = skip;
  if (!reviewLoaded.value || selectedPage.value !== row.page) return;

  const visible = visibleReviewRows.value;
  const stillVisible = visible.some(item => item.page === row.page);
  if (!skip && stillVisible) return;
  const candidates = skip ? visible.filter(item => !item.skip) : visible;
  const adjacent = candidates.find(item => item.page > row.page) ?? candidates[candidates.length - 1];
  selectedPage.value = adjacent?.page ?? (stillVisible ? row.page : undefined);
}

watch([visibleReviewRows, reviewFilter, reviewSearch], ([rows, filter, search], [, previousFilter, previousSearch]) => {
  const page = selectedPage.value;
  if (rows.some(row => row.page === page)) return;
  if (page === undefined || filter !== previousFilter || search !== previousSearch) {
    selectedPage.value = rows[0]?.page;
    return;
  }
  // 当前页因配置变更离开目录时，优先接着处理后面的页面。
  selectedPage.value = rows.find(row => row.page > page)?.page ?? rows[rows.length - 1]?.page;
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

/** 打开板块和联系人选择窗口，具备全局维护权限时可新建板块。 */
function openBoardEditor(row: ReviewRow, assignUnassigned = false, createBoard = false) {
  if (createBoard && !canMaintainDefaults.value) return;
  editingRow.value = row;
  assigningUnassigned.value = assignUnassigned;
  const title = row.title.replace(titlePrefix, '').trim();
  const section = row.section || title;
  const exists = boardOptions.value.includes(section);
  boardMode.value = canMaintainDefaults.value && (createBoard || (row.section && !exists)) ? 'board' : 'existing';
  existingBoard.value = exists ? section : '';
  boardName.value = !createBoard && boardMode.value === 'board' ? row.section : '';
  boardContactIds.value = createBoard ? [] : ownerChoices.value.get(section)?.split(',') ?? [];
  void searchEditorContacts('');
  boardEditorOpen.value = true;
}

/** 将选定板块和联系人应用到当前待归属页面组。 */
function applyBoard() {
  const row = editingRow.value;
  if (!row || !canApplyBoard.value) return;
  const previousSection = row.section;
  const section = draftSection.value;
  if (!draftContact.value) ownerChoices.value.set(section, boardContactIds.value.join(','));
  const rows = assigningUnassigned.value ? unassignedRows.value : [row];
  for (const item of rows) item.section = section;
  if (previousSection !== section && !reviewRows.value.some(item => item.section === previousSection)) {
    ownerChoices.value.delete(previousSection);
  }
  boardEditorOpen.value = false;
}

/** 将板块联系人认证号转换为可读姓名。 */
function boardContactName(userId: string) {
  return contactNames.value.get(userId) || reviewPeople.value.find(person => person.userId === userId)?.name || userId;
}

function boardContactLabel(userId: string) {
  const name = boardContactName(userId);
  return name === userId ? userId : `${name}（${userId}）`;
}

function selectReviewContacts(ids: string[]) {
  const row = selectedReviewRow.value;
  if (!row) return;
  if (ids.length) ownerChoices.value.set(row.section, ids.join(','));
  else ownerChoices.value.delete(row.section);
}

/** 已保存的名单来自接口，本次导入中新选的人员来自搜索结果。 */
function boardContactNames(userIds: string) {
  return contactNames.value.get(userIds) ?? userIds.split(',').map(boardContactName).join('、');
}

const selectedBatch = computed(() => batches.value.find(batch => batch.id === batchId.value));
/** 选择已有批次时，导入类型必须跟随批次保存的材料类型。 */
watch([mode, batchId], () => {
  if (mode.value === 'existing') {
    materialType.value = selectedBatch.value?.materialType || '';
  } else {
    materialType.value = '';
  }
});
/** 按批次名称和文件信息判断是否符合搜索条件。 */
function matches(batch: PptBatchSummary, search: string) {
  const term = search.trim().toLowerCase();
  return batch.name.toLowerCase().includes(term) || !!batch.fileNames?.toLowerCase().includes(term);
}
const matchingBatches = computed(() => batches.value.filter(batch => matches(batch, batchSearch.value)));
const visible = computed(() => batches.value.filter(batch => matches(batch, query.value)
  && (!materialTypeFilter.value || batch.materialType === materialTypeFilter.value)));
const canSubmit = computed(() => !!file.value && !!materialType.value && (mode.value === 'new' ? !!batchName.value.trim() : batchId.value !== undefined));
const batchNameTaken = computed(() => mode.value === 'new' && !!batchName.value.trim()
  && batches.value.some(batch => batch.name.trim() === batchName.value.trim()));
/** 将材料上传时间格式化为分钟精度，空时间显示占位符。 */
const dateText = (value?: string | null) => value?.replace('T', ' ').slice(0, 16) || '—';
/** 兼容历史数据中将默认稿件类型保存为 v1 的记录。 */
function versionTypeLabel(versionNo: number | null | undefined, versionName?: string | null) {
  const name = versionName?.trim();
  if (name && name !== `v${versionNo}`) return name;
  return versionNo === 1 ? '初稿' : '';
}

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
async function loadMaterialTypes() {
  materialTypes.value = await listPptMaterialTypes().catch(() => []);
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
  previewPdf.value = undefined;
  batchName.value = '';
  versionName.value = '初稿';
  batchSearch.value = '';
  batchId.value = batch?.id;
  materialType.value = batch?.materialType || '';
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
    reviewRows.value = review.pages.map(page => {
      const titleMatch = review.titleMatches?.[page.page];
      const titleCandidateIndex = titleMatch?.candidates.findIndex(candidate => candidate.title === page.title) ?? -1;
      const recognition = review.recognitionByPage?.[page.page] ?? titleMatch?.candidates[titleCandidateIndex]?.recognition;
      const useRules = recognition != null && recognition.ruleMode === page.mode;
      const row: ReviewRow = {
        page: page.page,
        originalTitle: titleMatch?.originalTitle ?? page.title ?? '',
        title: page.title ?? '',
        section: page.section ?? '',
        defaultRecognitionMode: selectableRecognitionMode((useRules ? recognition?.defaultMode : page.mode) ?? 'FULL'),
        recognitionMethod: useRules ? 'rules' : 'default',
        recognition,
        skip: page.excluded || page.mode === 'NO_SCAN' || (page.skipped && page.title === '目录'),
        titleMatch,
        titleCandidateIndex,
      };
      if (titleMatch && !page.excluded) {
        if (page.mode === 'OUT_OF_SCOPE') selectTitleCandidate(row, 0);
        else if (titleCandidateIndex >= 0) row.title = titleMatch.candidates[titleCandidateIndex].title;
      }
      return row;
    });
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

/** 保存当前版本分派及有权限维护的全局配置，完成识别后刷新批次列表。 */
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
      // 页面归属随版本保存，新建板块和记住默认联系人需要全局维护权限。
      return { page: row.page, title: row.title, recognitionMode: reviewRecognitionMode(row) || (row.skip ? 'NO_SCAN' : undefined),
        useDefaultRecognition: row.recognition && row.recognitionMethod === 'default' ? true : undefined,
        refreshRecognition: reviewTarget.value && row.refreshedRecognition?.has(row.title.trim()) ? true : undefined,
        section, owner, rememberOwner: canMaintainDefaults.value && !!owner, skip: row.skip, remember: false,
        handlers: row.skip ? undefined : titleHandlerIds(row.title),
        createBoard: canMaintainDefaults.value && !row.skip && !reviewSections.value.includes(section) };
    });
    if (target) {
      await confirmPptImport(target, assignments);
    } else if (selectedFile && (mode.value === 'new' || selectedBatchId !== undefined)) {
      const batch = mode.value === 'new' ? { batchName: batchName.value.trim() } : { batchId: selectedBatchId! };
      await savePptImport(selectedFile, batch, assignments, previewPdf.value, versionName.value, materialType.value);
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
  if (batchNameTaken.value) return;
  busy.value = true;
  uploadError.value = '';
  try {
    await loadReview(() => previewPptImport(selectedFile, mode.value === 'existing' ? batchId.value : undefined, previewPdf.value, materialType.value));
    if (!reviewLoaded.value) uploadError.value = reviewError.value;
  } finally {
    busy.value = false;
  }
}

/** 打开已上传但尚未识别版本的导入确认窗口。 */
async function openPendingVersion(id: number) {
  if (busy.value) return;
  reviewTarget.value = id;
  file.value = undefined;
  previewPdf.value = undefined;
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

async function confirmDeleteBatch(batch: PptBatchSummary) {
  if (deletingBatchId.value !== undefined) return;
  deletingBatchId.value = batch.id;
  try {
    const confirmed = await ElMessageBox.confirm(
      `确定删除「${batch.name}」？该批次的所有上传版本、识别结果和补充说明都将删除，且无法恢复。`,
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
onMounted(loadMaterialTypes);
/** 从配置权限接口获取导入中的全局维护权限。 */
onMounted(async () => {
  canMaintainDefaults.value = (await pptAnomalyPermissions().catch(() => undefined))?.canManageBoardsAndTitles === true;
});
</script>

<template>
  <div class="batch-page" :class="{ 'is-reviewing': reviewing }">
    <header v-if="!reviewing" class="page-header upload-header">
      <div>
        <h2>材料上传</h2>
        <p>按批次上传例会材料和查看历史版本。</p>
      </div>
      <PptListFilterBar class="upload-header-actions">
        <el-button v-if="error" :loading="loading" @click="error = ''; refresh()">刷新列表</el-button>
        <el-input v-model="query" class="ppt-filter-search" :prefix-icon="Search" clearable
          placeholder="搜索批次名称或文件名" aria-label="搜索批次名称或文件名" />
      <el-select v-model="materialTypeFilter" clearable placeholder="材料类型" class="ppt-filter-select">
        <el-option v-for="item in materialTypes" :key="item.name" :label="item.name" :value="item.name" />
      </el-select>
        <el-button class="add-material" type="primary" :icon="Plus" :disabled="busy" @click="openUpload()">新增材料</el-button>
      </PptListFilterBar>
    </header>
    <el-alert v-if="!reviewing && error" :title="error" type="error" class="notice" @close="error = ''" />
    <section v-if="!reviewing" class="material-list" aria-label="材料批次">
      <el-table v-loading="loading" :data="visible" row-key="id"
        :empty-text="batches.length ? '没有匹配的材料' : '暂无材料，点击新增材料开始上传'">
        <el-table-column prop="name" label="批次名称" min-width="155" show-overflow-tooltip />
        <el-table-column prop="materialType" label="材料类型" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialType || '—' }}</template>
        </el-table-column>
        <el-table-column label="最新文件" min-width="215" show-overflow-tooltip>
          <template #default="{ row }">{{ [row.fileName, versionTypeLabel(row.versionNo, row.versionName)].filter(Boolean).join(' · ') || '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="155">
          <template #default="{ row }">{{ dateText(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="350">
          <template #default="{ row }">
            <el-button v-if="row.versionId && row.status !== 'ANALYZED'"
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
    <el-drawer :model-value="!!historyBatch" title="历史版本" size="min(860px, 100%)"
      @update:model-value="(open: boolean) => { if (!open) historyBatch = undefined }">
      <template v-if="historyBatch">
        <div class="history-summary">
          <span>材料批次</span>
          <strong>{{ historyBatch.name }}</strong>
        </div>
        <div v-if="historyErrors[historyBatch.id]" class="history-error">
          <el-alert title="历史版本加载失败" type="error" :closable="false" show-icon />
          <el-button @click="loadHistory(historyBatch.id)">重新加载</el-button>
        </div>
        <el-table v-else v-loading="historyLoading[historyBatch.id]" class="history-table" stripe
          :data="histories[historyBatch.id] || []" row-key="id" empty-text="暂无上传版本">
          <el-table-column label="版本" width="150">
            <template #default="{ row, $index }">
              <div class="history-version">
                <strong>{{ [versionTypeLabel(row.versionNo, row.versionName), `v${row.versionNo}`].filter(Boolean).join(' ') }}</strong>
                <el-tag v-if="$index === 0" size="small" effect="light">最新</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="原文件" min-width="140" show-overflow-tooltip />
          <el-table-column label="上传时间" width="155">
            <template #default="{ row }">{{ dateText(row.uploadedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="188">
            <template #default="{ row }">
              <div class="history-actions">
                <!-- 暂时隐藏历史版本中的调整经办人入口。
                <el-button v-if="row.status === 'ANALYZED'" link type="primary"
                  @click="openVersionAssignments(row.id, row.versionNo)">调整经办人</el-button> -->
                <el-button link type="primary" @click="download(row)">下载原件</el-button>
                <el-button v-if="row.uploadedBy === getLoggedInUser()?.userId" link type="primary"
                  @click="pdfVersion = row">上传预览 PDF</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
    <PptPreviewPdfDialog v-if="pdfVersion" :key="pdfVersion.id" :version="pdfVersion" @close="pdfVersion = undefined" />
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
        <el-form-item label="材料类型" required>
          <el-select v-model="materialType" filterable clearable
            :disabled="mode === 'existing' && !!selectedBatch?.materialType" placeholder="选择材料类型">
            <el-option v-for="item in materialTypes" :key="item.name" :label="item.name" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'new'" label="批次名称"
          :error="batchNameTaken ? '批次名称已存在，请更换名称或选择已有批次' : ''">
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
        <el-form-item label="稿件类型">
          <el-radio-group v-model="versionName" aria-label="稿件类型">
            <el-radio-button value="初稿">初稿</el-radio-button>
            <el-radio-button value="终稿">终稿</el-radio-button>
          </el-radio-group>
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
        <el-form-item label="预览 PDF（可选）">
          <div>
            <input ref="previewPdfInput" type="file" accept=".pdf,application/pdf" aria-label="预览 PDF（可选）" :disabled="busy"
              @change="previewPdf = ($event.target as HTMLInputElement).files?.[0]" />
            <p class="file-hint">请从同一份 PPT 导出完整 PDF，页数和顺序须一致，最大 50 MB。PDF 用于预览，PPT 用于识别。</p>
            <el-button v-if="previewPdf" link type="primary" :disabled="busy" @click="clearPreviewPdf">取消 PDF，使用 PPT 预览</el-button>
          </div>
        </el-form-item>
        <div class="dialog-actions">
          <el-button :disabled="busy" @click="dialog = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="!canSubmit || batchNameTaken">解析 PPT</el-button>
        </div>
      </el-form>
    </el-dialog>
    <section v-if="reviewing" class="review-screen" aria-labelledby="ppt-review-heading">
      <header class="page-header">
        <div>
          <h2 id="ppt-review-heading" ref="reviewHeading" tabindex="-1">核对页面</h2>
          <p v-if="file">{{ file.name }}</p>
          <p v-if="previewPdf">预览来源：{{ previewPdf.name }}</p>
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
                      <strong :title="row.originalTitle">{{ row.originalTitle.trim() || '无标题' }}</strong>
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
            <section v-if="selectedReviewRow" :key="selectedReviewRow.page" class="review-detail" aria-label="当前页设置">
              <header class="review-detail-header">
                <h3>第 {{ selectedReviewRow.page }} 页 <small>/ {{ reviewRows.length }}</small></h3>
                <span class="review-status" :data-status="pageStatus(selectedReviewRow).key">
                  {{ pageStatus(selectedReviewRow).label }}
                </span>
              </header>
              <div class="review-page-content">
                <div class="review-preview">
                  <p v-if="reviewPreviewLoading" role="status">正在加载原页预览…</p>
                  <div v-else-if="reviewPreviewError" role="status">
                    {{ reviewPreviewError }}
                    <el-button link type="primary" @click="loadReviewPreview(selectedReviewRow)">重试预览</el-button>
                  </div>
                  <PptSlideViewer v-else plain :page-no="selectedReviewRow.page" :unavailable="true"
                    :slide="reviewPreviewUrl ? { id: selectedReviewRow.page, pageNo: selectedReviewRow.page,
                      imageUrl: reviewPreviewUrl, renderStatus: 'READY' } : undefined" />
                </div>
                <div class="review-fields">
                  <div v-if="selectedReviewRow.titleMatch" class="review-field">
                    <label id="ppt-review-title-match-label">匹配标题</label>
                    <small>原始标题：{{ selectedReviewRow.titleMatch.originalTitle }}</small>
                    <el-select :model-value="selectedReviewRow.titleCandidateIndex" :disabled="busy"
                      aria-labelledby="ppt-review-title-match-label"
                      @update:model-value="(index: number) => selectTitleCandidate(selectedReviewRow!, index)">
                      <el-option v-for="(candidate, index) in selectedReviewRow.titleMatch.candidates" :key="candidate.title"
                        :label="[candidate.title, candidate.section].filter(Boolean).join(' · ')" :value="index" />
                      <el-option label="保留原始标题，手动配置" :value="-1" />
                    </el-select>
                  </div>
                  <label v-if="selectedReviewRow.titleCandidateIndex < 0" class="review-field" for="ppt-review-title">
                    <span>页面标题</span>
                    <el-input id="ppt-review-title" :model-value="selectedReviewRow.title" maxlength="512" placeholder="无标题，可留空"
                      :disabled="busy" @update:model-value="changeReviewTitle(selectedReviewRow, $event)" />
                  </label>
                  <div class="review-field">
                    <div class="review-field-heading">
                      <span>识别方式</span>
                      <el-button v-if="canMaintainDefaults" type="primary" plain size="small"
                        :disabled="busy || !selectedReviewRow.title.trim()"
                        @click="openRecognitionEditor(selectedReviewRow)">
                        {{ selectedReviewRow.pendingRecognition?.has(selectedReviewRow.title.trim()) ? '重新匹配规则' : '配置识别方式' }}
                      </el-button>
                    </div>
                    <p class="review-field-hint">{{ reviewRecognitionSummary(selectedReviewRow) }}</p>
                    <el-select v-if="selectedReviewRow.recognition?.ruleMode === 'RULE_CONFLICT' && !selectedReviewRow.skip
                      && !selectedReviewRow.pendingRecognition?.has(selectedReviewRow.title.trim())"
                      :model-value="selectedReviewRow.recognitionMethod === 'default' ? selectedReviewRow.defaultRecognitionMode : ''"
                      :disabled="busy" placeholder="多条规则命中，请选择本页识别范围" aria-label="规则冲突时的识别范围"
                      @update:model-value="resolveRecognitionConflict(selectedReviewRow, $event)">
                      <el-option v-for="[value, label] in pptManualRecognitionOptions" :key="value" :label="label" :value="value" />
                    </el-select>
                  </div>
                  <div class="review-field">
                    <div class="review-field-heading">
                      <label id="ppt-review-board-label">所属板块</label>
                      <el-button v-if="canMaintainDefaults" link type="primary" :disabled="busy || selectedReviewRow.skip"
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
                    <PptBoardContactList v-if="selectedBoardContacts" :user-ids="selectedBoardContacts"
                      :names="boardContactNames(selectedBoardContacts)" />
                    <template v-else>
                      <el-select :model-value="selectedContactIds" class="board-contact-picker" multiple filterable remote
                        :reserve-keyword="false"
                        aria-labelledby="ppt-review-contact-label" placeholder="搜索姓名或认证号，可多选" :disabled="busy"
                        :remote-method="searchBoardContacts" :loading="boardContactLoading"
                        :no-data-text="boardContactSearchFailed ? '搜索失败，请重试' : '未找到联系人'"
                        @update:model-value="selectReviewContacts"
                        @visible-change="(open: boolean) => { if (!open) searchBoardContacts(''); }">
                        <template #label="{ value }">{{ boardContactLabel(value) }}</template>
                        <el-option v-for="person in matchingBoardContacts" :key="person.userId"
                          :label="boardContactLabel(person.userId)" :value="person.userId" />
                      </el-select>
                      <p v-if="selectedContactIds.length" class="review-field-hint" aria-live="polite">已选择 {{ selectedContactIds.length }} 位联系人</p>
                      <small v-else>请至少选择一位板块联系人。</small>
                    </template>
                  </div>
                  <div v-if="titleKey(selectedReviewRow.title) && !selectedReviewRow.skip" :key="`handlers-${titleKey(selectedReviewRow.title)}`"
                    class="review-field review-contact review-title-handlers">
                    <div class="review-field-heading">
                      <label id="ppt-review-handlers-label">标题经办人</label>
                      <el-button link type="primary" :disabled="busy" @click="openHandlerReuse(selectedReviewRow.title)">
                        复用其他标题经办人
                      </el-button>
                    </div>
                    <div v-if="savedHandlersForReview.length" class="review-saved-handlers">
                      <span>已配置</span>
                      <strong :title="boardContactLabel(savedHandlersForReview[0])">{{ boardContactName(savedHandlersForReview[0]) }}</strong>
                      <button v-if="savedHandlersForReview.length > 1" type="button" class="review-handler-count"
                        :aria-label="`查看全部 ${savedHandlersForReview.length} 位已配置经办人`" aria-haspopup="dialog"
                        @click="openHandlerList(selectedReviewRow.title)">+{{ savedHandlersForReview.length - 1 }}</button>
                    </div>
                    <el-select :model-value="draftHandlersForReview" class="review-handler-picker" multiple filterable remote remote-show-suffix
                      fit-input-width :reserve-keyword="false" :offset="handlerDropdownOffset"
                      popper-class="ppt-review-handler-dropdown" :popper-options="handlerDropdownOptions"
                      aria-labelledby="ppt-review-handlers-label" placeholder="搜索并补充经办人，可多选"
                      :disabled="busy" :remote-method="searchHandlers" :loading="handlerLoading"
                      :no-data-text="handlerSearchFailed ? '搜索失败，请重试' : '未找到经办人'"
                      @update:model-value="handlerChoices.set(selectedHandlerTitle, $event)"
                      @visible-change="(open: boolean) => { if (!open) searchHandlers(''); }">
                      <template #tag>
                        <div v-if="draftHandlersForReview.length" class="review-handler-selection">
                          <button type="button" class="review-handler-count review-handler-first" aria-haspopup="dialog"
                            :title="boardContactLabel(draftHandlersForReview[0])"
                            :aria-label="`查看全部 ${draftHandlersForReview.length} 位补选经办人`" :disabled="busy"
                            @click.stop="openHandlerList(selectedReviewRow.title)">{{ boardContactName(draftHandlersForReview[0]) }}</button>
                          <button v-if="draftHandlersForReview.length > 1" type="button" class="review-handler-count"
                            :aria-label="`还有 ${draftHandlersForReview.length - 1} 位补选经办人，查看完整名单`"
                            aria-haspopup="dialog" :disabled="busy"
                            @click.stop="openHandlerList(selectedReviewRow.title)">+{{ draftHandlersForReview.length - 1 }}</button>
                        </div>
                      </template>
                      <el-option v-for="person in handlerOptions(titleHandlerIds(selectedReviewRow.title))" :key="person.userId"
                        :label="person.name || person.userId" :value="person.userId"
                        :disabled="!person.department || savedHandlersForReview.includes(person.userId)">
                        <div class="review-handler-option" :title="[boardContactLabel(person.userId), person.department].filter(Boolean).join(' · ')">
                          <strong>{{ person.name || person.userId }}</strong>
                          <span>{{ [person.department, person.userId].filter(Boolean).join(' · ') }}</span>
                        </div>
                      </el-option>
                    </el-select>
                    <small v-if="!titleHandlerIds(selectedReviewRow.title).length">暂未配置经办人，部门事项将保留为待分派。</small>
                    <p class="review-field-hint">按标题和部门分派。缺少同部门经办人的事项会保留为待分派。</p>
                  </div>
                </div>
              </div>
              <footer class="review-detail-footer">
                <el-checkbox :model-value="selectedReviewRow.skip" :disabled="busy"
                  @update:model-value="setReviewSkip(selectedReviewRow, $event === true)">跳过本页</el-checkbox>
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
    <el-dialog v-model="handlerListOpen" title="标题经办人" class="ppt-dialog" width="min(520px, 94vw)"
      align-center append-to-body :close-on-click-modal="false">
      <div class="review-handler-list-content">
        <ul v-if="handlerListIds.length" class="review-handler-list">
          <li v-for="id in handlerListIds" :key="id">
            <div class="review-handler-list-person">
              <span>{{ boardContactLabel(id) }}</span>
              <span class="review-handler-list-department">{{ handlerListDepartments.get(id) || '部门未登记' }}</span>
            </div>
            <span v-if="savedTitleHandlers.get(handlerListKey)?.includes(id)" class="review-handler-list-status">已配置</span>
            <el-button v-else link type="danger" :disabled="busy" :aria-label="`移除 ${boardContactName(id)}`"
              @click="removeDraftHandler(id)">移除</el-button>
          </li>
        </ul>
        <p v-else role="status">暂无经办人。</p>
      </div>
      <template #footer><el-button @click="handlerListOpen = false">关闭</el-button></template>
    </el-dialog>
    <PptTitleHandlerReuseDialog v-model="reuseHandlersOpen" :title="reuseHandlerTitle" :existing-ids="reuseExistingIds"
      :saving="busy" note="将来源标题的经办人追加到当前标题，已有人员自动去重。名单随导入一起提交。"
      @apply="addReusedHandlers" />
    <el-dialog v-model="recognitionEditorOpen" title="配置识别方式" class="ppt-dialog" width="min(680px, 94vw)"
      align-center append-to-body destroy-on-close :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy">
      <el-form label-position="top" :disabled="busy || recognitionSaved" @submit.prevent="saveRecognitionSettings">
        <el-form-item label="页面标题">{{ recognitionTarget?.title }}</el-form-item>
        <p v-if="!recognitionSaved" class="review-field-hint">设置对该标题全局生效，保存后会重新匹配本次导入中的同标题页面。</p>
        <p v-else-if="!recognitionError" class="review-field-hint">全局设置已保存，可以重新匹配尚未刷新的页面。</p>
        <p v-if="recognitionLoading" role="status">正在加载识别设置…</p>
        <el-alert v-if="recognitionError" :title="recognitionError" type="error" :closable="false" />
        <el-button v-if="recognitionError && !initialScanRules && !recognitionSaved" link type="primary" @click="loadRecognitionSettings">重新加载识别设置</el-button>
        <PptRecognitionSettings v-if="recognitionEditorOpen && initialScanRules" ref="recognitionEditor"
          v-model="recognitionScope" :initial-rules="initialScanRules" :editable="!recognitionSaved" />
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="recognitionEditorOpen = false">{{ recognitionSaved ? '关闭' : '取消' }}</el-button>
        <el-button type="primary" :disabled="!initialScanRules && !recognitionSaved" :loading="busy" @click="saveRecognitionSettings">
          {{ recognitionSaved ? '重新匹配同标题页面' : '保存全局设置' }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="boardEditorOpen" :title="boardMode === 'board' ? '新建板块' : '分配板块'" class="ppt-dialog" width="min(480px, 92vw)"
      align-center append-to-body destroy-on-close :close-on-click-modal="false">
      <p class="board-editor-title">{{ assigningUnassigned ? '为未归属页面指定同一板块' : editingRow?.title || '无标题' }}</p>
      <el-form label-position="top" @submit.prevent="applyBoard">
        <el-form-item label="板块归属">
          <el-radio-group v-model="boardMode" @change="boardContactIds = []">
            <el-radio-button value="existing">选择已有板块</el-radio-button>
            <el-radio-button v-if="canMaintainDefaults" value="board">新建板块</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="boardMode === 'existing'" label="已有板块">
          <el-select v-model="existingBoard" placeholder="选择板块" no-data-text="暂无已有板块" filterable @change="boardContactIds = []">
            <el-option v-for="section in boardOptions" :key="section" :value="section" :label="section" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="新板块名称" :error="duplicateBoard ? '板块已存在，请选择已有板块' : undefined">
          <el-input v-model="boardName" maxlength="512" placeholder="输入板块名称" />
        </el-form-item>
        <el-form-item v-if="draftSection && !duplicateBoard" label="板块联系人" required>
          <PptBoardContactList v-if="draftContact" :user-ids="draftContact" :names="boardContactNames(draftContact)" />
          <el-select v-else v-model="boardContactIds" class="board-contact-picker" multiple
            :reserve-keyword="false"
            placeholder="搜索姓名或认证号，可多选" filterable remote
            :remote-method="searchEditorContacts" :loading="editorContactLoading"
            :no-data-text="editorContactSearchFailed ? '搜索失败，请重试' : '未找到联系人'"
            @visible-change="(open: boolean) => { if (!open) searchEditorContacts(''); }">
            <template #label="{ value }">
              {{ boardContactLabel(value) }}
            </template>
            <el-option v-for="person in matchingEditorContacts" :key="person.userId"
              :label="boardContactLabel(person.userId)" :value="person.userId" />
          </el-select>
          <p v-if="!draftContact" class="board-contact-hint" aria-live="polite">
            {{ boardContactIds.length ? `已选择 ${boardContactIds.length} 位联系人` : '至少选择一位联系人。' }}
          </p>
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
.history-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 16px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
}
.history-summary span { color: var(--el-text-color-secondary); font-size: 13px; }
.history-error { display: flex; flex-wrap: wrap; gap: 12px; }
.history-table { border: 1px solid var(--el-border-color-light); border-radius: 8px; }
.history-version, .history-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
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
  border: 1px solid #dfe6f0; border-radius: 12px; background: #fff; overflow: hidden;
}
.review-index { display: grid; grid-template-rows: auto minmax(0, 1fr); min-height: 0; border-right: 1px solid #dfe6f0; }
.review-search { padding: 10px; border-bottom: 1px solid #e9eef6; }
.review-list {
  min-height: 0; margin: 0; padding: 0; list-style: none; overflow: auto;
  overscroll-behavior-y: contain; scrollbar-gutter: stable;
}
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

.review-preview :deep(img) { max-height: 400px; }
.review-fields { display: grid; align-content: start; gap: 14px; padding: 16px; border: 1px solid #e5ebf4; border-radius: 8px; }
.review-field { display: grid; align-content: start; gap: 6px; min-width: 0; }
.review-field > span, .review-field > label, .review-field-heading { color: #243651; font-size: 13px; font-weight: 600; }
.review-required::before { content: '*'; color: var(--el-color-danger); margin-right: 4px; }
.review-field-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.review-field :deep(.el-input__wrapper), .review-field :deep(.el-select__wrapper) { min-height: 36px; box-sizing: border-box; }
.review-contact-name { padding: 8px 11px; color: #243651; background: #f3f6fb; border-radius: 6px; overflow-wrap: anywhere; }
.review-contact small { color: #805016; font-size: 12px; }
.review-title-handlers { grid-template-columns: minmax(0, 1fr); }
.review-title-handlers .review-field-heading { flex-wrap: wrap; }
.review-saved-handlers, .review-handler-selection {
  display: flex; align-items: center; gap: 6px; min-width: 0;
}
.review-saved-handlers { padding: 5px 9px; border-radius: 6px; background: #f3f6fb; color: #52627a; }
.review-saved-handlers > span { flex-shrink: 0; }
.review-saved-handlers strong, .review-handler-first { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.review-handler-count {
  padding: 2px 6px; border: 0; border-radius: 4px; color: #234ba9;
  background: #eaf1ff; cursor: pointer; font: inherit; white-space: nowrap;
}
.review-handler-count:not(.review-handler-first) { flex-shrink: 0; }
.review-handler-count:hover, .review-handler-count:focus-visible { background: #dce8ff; }
.review-handler-count:disabled { cursor: default; }
.review-handler-picker :deep(.el-select__selection) { flex-wrap: nowrap; }
.review-handler-picker :deep(.el-select__input-wrapper) { flex: 1; min-width: 48px; }
.review-handler-picker :deep(.review-handler-selection + .el-select__input-wrapper.is-hidden) {
  position: static; opacity: 1; z-index: auto;
}
.review-handler-selection { max-width: calc(100% - 54px); }
:global(.ppt-review-handler-dropdown .el-select-dropdown__wrap) {
  max-height: var(--handler-dropdown-height, 224px);
}
:global(.ppt-review-handler-dropdown .el-select-dropdown__item) {
  display: flex; align-items: center; height: 44px; padding: 0 36px 0 12px; line-height: normal;
}
:global(.ppt-review-handler-dropdown .el-select-dropdown__item.is-selected::after) { right: 12px; }
.review-handler-option { display: grid; gap: 2px; min-width: 0; }
.review-handler-option > * { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.review-handler-option span, .review-handler-list-status, .review-handler-list-department { color: #52627a; font-size: 12px; }
.review-handler-list-content > p { margin: 0; color: #52627a; }
.review-handler-list {
  max-height: min(320px, 48dvh); overflow: auto; margin: 0; padding: 0; list-style: none;
}
.review-handler-list li {
  display: flex; justify-content: space-between; align-items: center; gap: 12px;
  min-height: 36px; padding: 6px 8px; border-bottom: 1px solid #e9eef6;
}
.review-handler-list-person { display: grid; gap: 2px; min-width: 0; overflow-wrap: anywhere; }
.review-handler-list-status { flex-shrink: 0; }
.board-contact-picker :deep(.el-tag) { max-width: 100%; height: auto; min-height: 24px; padding-block: 3px; }
.board-contact-picker :deep(.el-tag__content) { white-space: normal; overflow-wrap: anywhere; }
.board-contact-hint { width: 100%; margin: 5px 0 0; color: #52627a; font-size: 12px; line-height: 1.5; }
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
  @media not all and (max-width: 700px) {
  .batch-page.is-reviewing, .review-screen { height: 100%; }
  .review-screen { display: flex; flex-direction: column; }
  .review-screen > :not(.review-workspace) { flex: none; }
  .review-workspace {
    flex: 1 1 0; grid-template-rows: minmax(0, 1fr);
    min-height: 160px;
    max-height: min(62vh, 580px);
    max-height: min(62dvh, 580px);
  }
}
@media (max-width: 700px) {
  .review-workspace { grid-template-columns: minmax(0, 1fr); }
  .review-index { height: 215px; border-right: 0; border-bottom: 1px solid #dfe6f0; }
  .review-detail { padding-inline: 10px; }
  .review-blockers { flex-basis: 100%; }
}
.board-editor-title { margin: 0 0 16px; color: #243651; font-weight: 600; overflow-wrap: anywhere; }
.batch-page { --el-color-primary: #2855c4; padding: clamp(16px, 3vw, 36px); min-height: 100%; box-sizing: border-box; background: #f3f6fb; }
.batch-page:not(.is-reviewing) { padding: clamp(12px, 1.5vw, 20px); }
.page-header, .dialog-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-header { margin-bottom: 24px; flex-wrap: wrap; }
.page-header h2 { margin: 0; font-size: 28px; color: #142441; }
.page-header p { margin: 6px 0 0; color: #52627a; font-size: 14px; line-height: 1.6; }
.page-header.upload-header { margin-bottom: 14px; gap: 8px 12px; }
.page-header.upload-header h2 { font-size: 22px; line-height: 1.35; }
.page-header.upload-header p { margin-top: 3px; font-size: 13px; line-height: 1.4; }
.upload-header-actions { margin-inline-start: auto; max-width: 100%; }
.add-material { min-height: 32px; padding-inline: 12px; }
.batch-page :deep(.el-button) { border-radius: 8px; }
.material-list { background: #fff; border: 1px solid #dfe6f0; border-radius: 14px; overflow: hidden; box-shadow: 0 4px 16px #22375c0a; }
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
  .page-header h2 { font-size: 24px; }
}
</style>
