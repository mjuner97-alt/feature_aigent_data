<script setup lang="ts">
/** 例会材料配置页，展示全部配置标签，并按权限提供维护入口。 */

import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { isPptRecognitionMode, listPptMaterialIgnoreRules, listPptReportDepartments, savePptMaterialIgnoreRules, pptAnomalyPermissions, pptManualRecognitionOptions, pptRecognitionModes as modes, searchPptPeople, type PptMaterialIgnoreRule, type PptPerson, type PptRecognitionScope } from '../../api/pptAnomaly';
import { updatePptNotificationSwitches, type PptFeedbackReminderSettings, type PptReminderWindow } from '../../api/pptAnomaly';
import { Clock, Document, Download, Plus, Search, UploadFilled } from '@element-plus/icons-vue';
import PptListFilterBar from '../../components/ppt/PptListFilterBar.vue';
import PptFeedbackSupervisorConfig from '../../components/ppt/PptFeedbackSupervisorConfig.vue';
import PptRecognitionSettings from '../../components/ppt/PptRecognitionSettings.vue';
import PptReportNotificationConfig from '../../components/ppt/PptReportNotificationConfig.vue';
import PptTitleHandlerReuseDialog from '../../components/ppt/PptTitleHandlerReuseDialog.vue';

/** 关键词或解析范围配置的编辑数据。 */
interface Entry {
  id?: number;
  name: string;
  target: string;
  upperLimitRatio?: number;
  firstRow?: number;
  lastRow?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** 标题归属、解析范围及联系人组合数据。 */
type TitleRow = { name: string; section?: Entry; scope?: Entry; contact?: Entry; ownerName?: string };

/** 板块默认联系人及关联标题数量。 */
type BoardRow = { name: string; owner: string | null; ownerName: string | null; titleCount: number };

// 标签顺序对所有登录用户一致。
const tabs = [
  { key: 'material-type', label: '材料类型配置' },
  { key: 'board', label: '板块配置' },
  { key: 'section', label: '标题配置' },
  { key: 'department', label: '部门关键字配置' },
  { key: 'report-notification', label: '报告通知配置' },
  { key: 'feedback-supervisors', label: '组织督办人配置' },
] as const;
const visibleTabs = tabs;
function titleScopeLabel(scope?: Entry) {
  return scope && isPptRecognitionMode(scope.target) ? modes[scope.target] : modes.FULL;
}
const route = useRoute();
const router = useRouter();
/** 从查询参数打开可见标签，无匹配项时显示首个可见标签。 */
const requestedTab = () => visibleTabs.find(item => item.key === route.query.tab)?.key ?? visibleTabs[0].key;
const active = ref<string>(requestedTab());
const permissions = ref<Awaited<ReturnType<typeof pptAnomalyPermissions>>>();
const editKind = ref('department');
const departmentFilter = ref('');
const departmentFilterReady = ref(false);
const directoryDepartments = ref<string[]>([]);
const departmentOptions = computed(() => [...new Set([
  ...directoryDepartments.value, ...(permissions.value?.departments ?? []),
])].sort((first, second) => first.localeCompare(second, 'zh-CN')));
const editableDepartments = computed(() => permissions.value?.editableDepartments ?? []);
// 将后端的管理员配置权限用于页面操作入口。
const canManageAllConfigurations = computed(() => permissions.value?.canManageAllConfigurations === true);
/** 判断当前用户能否维护指定配置类别或某一部门的关键字记录。 */
function canMaintain(kind: string, department?: string) {
  if (kind === 'material-type') return canManageAllConfigurations.value || permissions.value?.canManageMaterials === true;
  // 部门关键字的新增入口以是否有可维护部门为准，已有记录按归属部门判断。
  if (kind === 'department') {
    if (department === undefined) return canManageAllConfigurations.value || editableDepartments.value.length > 0;
    const target = department.trim();
    return !!target && (canManageAllConfigurations.value || editableDepartments.value.includes(target));
  }
  return permissions.value?.canManageBoardsAndTitles === true;
}
const canMaintainActive = computed(() => canMaintain(active.value));
const canMaintainEditor = computed(() => canMaintain(editKind.value));
/** 获取最新维护权限，督办关系变更时重新查询。 */
async function loadPermissions() {
  const current = await pptAnomalyPermissions().catch(() => undefined);
  permissions.value = current;
  if (departmentFilterReady.value) return;
  const ownDepartments = current?.departments ?? [];
  departmentFilter.value = ownDepartments.includes('杭州产品部') ? '' : (ownDepartments[0] ?? '');
  departmentFilterReady.value = true;
  if (active.value === 'department') search();
}
const reminderSettings = ref<Partial<Record<number, PptFeedbackReminderSettings>>>({});
const savingReminderRows = reactive(new Set<number>());
const defaultReminderSettings: PptFeedbackReminderSettings = {
  enabled: true,
  cutoff: '18:00',
  intervalMinutes: 60,
  weeklyStartMinute: null,
  weeklyEndMinute: null,
  notificationEnabled: true,
};

onMounted(loadPermissions);

async function updateNotificationSwitch(id: number, field: 'enabled' | 'notificationEnabled', value: boolean) {
  if (savingReminderRows.has(id) || !canMaintain('material-type')) return;
  const current = reminderSettings.value[id];
  if (current && (current[field] ?? true) === value) return;

  savingReminderRows.add(id);
  reminderSettings.value[id] = { ...(current ?? defaultReminderSettings), [field]: value };
  try {
    await updatePptNotificationSwitches(id, { [field]: value });
    if (active.value === 'material-type') await load();
  } catch (error) {
    reminderSettings.value[id] = current;
    ElMessage.error(String(error));
  } finally {
    savingReminderRows.delete(id);
  }
}
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function clockText(minute: number) {
  const withinDay = minute % 1440;
  return [Math.floor(withinDay / 60), withinDay % 60].map(value => String(value).padStart(2, '0')).join(':');
}

function reminderWindows(settings: PptFeedbackReminderSettings): PptReminderWindow[] {
  if (settings.windows?.length) return settings.windows;
  const { weeklyStartMinute: start, weeklyEndMinute: end, cutoff } = settings;
  if (start != null && end != null) return [{ daily: false, startMinute: start, endMinute: end }];
  return [{ daily: true, startMinute: 600, endMinute: weekMinute(0, cutoff) }];
}

function scheduleText({ daily, startMinute, endMinute }: PptReminderWindow) {
  if (daily) return `每天 ${clockText(startMinute)} – ${endMinute < startMinute ? '次日 ' : ''}${clockText(endMinute)}`;
  const start = `${weekdays[Math.floor(startMinute / 1440)]} ${clockText(startMinute)}`;
  const end = endMinute < startMinute ? '当周结束' : `${weekdays[Math.floor(endMinute / 1440)]} ${clockText(endMinute)}`;
  return `${start} – ${end}`;
}

function weekMinute(day: number, time: string) {
  const [hour, minute] = time.split(':').map(Number);
  return day * 1440 + hour * 60 + minute;
}

function notificationWindowsOverlap(windows: PptReminderWindow[]) {
  const sorted = [...windows].sort((left, right) => left.startMinute - right.startMinute);
  return sorted.some((range, index) => index > 0 && range.startMinute < sorted[index - 1].endMinute);
}

watch(() => route.query.tab, () => {
  active.value = requestedTab();
});
const isTitleEditor = computed(() => editKind.value === 'section');
const tab = computed(() => tabs.find(item => item.key === editKind.value) ?? tabs[0]);
const keyword = ref('');
const mode = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const rows = ref<Entry[]>([]);
const showConfigActions = computed(() => active.value === 'department'
  ? rows.value.some(row => canMaintain('department', row.target))
  : canMaintainActive.value);
/** 管理员编辑已有的关键字时，可保留该记录的历史部门。 */
const keywordDepartmentOptions = computed(() => {
  if (!canManageAllConfigurations.value) return editableDepartments.value;
  const configured = rows.value.map(row => row.target.trim()).filter(Boolean);
  return [...new Set([...editableDepartments.value, ...configured])];
});
const titleRows = ref<TitleRow[]>([]);
const loading = ref(false);
const people = ref<PptPerson[]>([]);
const knownBoardContacts = ref(new Map<string, string>());
const peopleLoading = ref(false);
const sections = ref<BoardRow[]>([]);
const boardRows = ref<BoardRow[]>([]);
const boardForm = ref({ originalName: '', name: '', owner: '', create: true });
const boardOwnerIds = ref<string[]>([]);
const sectionsLoading = ref(false);
const sectionsReady = ref(false);
let peopleRequestId = 0;

/** 生成联系人姓名及认证号的展示文字。 */
function contactLabel(userId: string, name?: string) {
  const ids = userId.split(',').filter(Boolean);
  const names = name?.split('、') ?? [];
  return ids.map((id, index) => names[index] && names[index] !== id ? `${names[index]}（${id}）` : id).join('、');
}
const boardOwnerOptions = computed(() => {
  const options = new Map(knownBoardContacts.value);
  for (const person of people.value) options.set(person.userId, person.name);
  return [...options];
});
function selectBoardOwners(ids: string[]) {
  const options = new Map(boardOwnerOptions.value);
  boardOwnerIds.value = [...ids];
  knownBoardContacts.value = new Map(ids.map(id => [id, options.get(id) ?? id]));
  boardForm.value.owner = ids.join(',');
}

/** 远程搜索可选联系人并处理加载状态。 */
async function searchPeople(keyword: string) {
  const id = ++peopleRequestId;
  const term = keyword.trim();
  people.value = [];
  peopleLoading.value = !!term;
  if (!term) return;
  try {
    const result = await searchPptPeople(term);
    if (id === peopleRequestId && editorOpen.value) people.value = result;
  } catch (error) {
    if (id === peopleRequestId && editorOpen.value) ElMessage.error(String(error));
  } finally {
    if (id === peopleRequestId) peopleLoading.value = false;
  }
}
const saving = ref(false);
const editorOpen = ref(false);
const form = ref<Entry>({ name: '', target: '' });
const applied = reactive({ keyword: '', target: '' });
let requestId = 0;
let titleSearchTimer: ReturnType<typeof setTimeout> | undefined;
const base = '/api/ppt-anomaly/keyword-config';
const importOpen = ref(false);
const importing = ref(false);
const importFile = ref<File>();
const importError = ref('');
const ruleOpen = ref(false);
const ruleMaterialType = ref('');
const ignoreRules = ref<PptMaterialIgnoreRule[]>([]);
const newRule = ref<PptMaterialIgnoreRule>({ ruleType: 'KEYWORD', ruleValue: '', ruleScope: 'PAGE', enabled: true });
const materialEditorRules = ref<PptMaterialIgnoreRule[]>([]);
const materialEditorRulesState = ref<'ready' | 'loading' | 'failed'>('ready');
let materialEditorRulesRequest = 0;
const materialEditorNewRule = ref<PptMaterialIgnoreRule>({ ruleType: 'KEYWORD', ruleValue: '', ruleScope: 'PAGE', enabled: true });
const materialEditorReminder = reactive({ ...defaultReminderSettings, notificationEnabled: true });
const materialEditorWeekly = ref(false);
type ReminderWindowForm = { id: number; startDay: number; startTime: string; endDay: number; endTime: string };
const materialEditorWindows = ref<ReminderWindowForm[]>([]);
const materialEditorWindowValues = computed(() => materialEditorWindows.value.map(window => ({
  daily: !materialEditorWeekly.value,
  startMinute: weekMinute(materialEditorWeekly.value ? window.startDay : 0, window.startTime || ''),
  endMinute: weekMinute(materialEditorWeekly.value ? window.endDay : 0, window.endTime || ''),
})));
const windowTimeErrors = computed(() => materialEditorWindowValues.value.map(({ startMinute, endMinute }) => {
  if (!Number.isInteger(startMinute) || !Number.isInteger(endMinute)) return '请填写完整通知时段';
  return startMinute >= endMinute ? '开始时间必须早于结束时间' : '';
}));
let reminderWindowId = 0;

function addReminderWindow() {
  const error = windowTimeErrors.value.find(Boolean);
  if (error) {
    ElMessage.warning(error);
    return;
  }
  materialEditorWindows.value.push({ id: ++reminderWindowId, startDay: 0, startTime: '10:00', endDay: 0, endTime: '18:00' });
}
function addIgnoreRule() {
  if (!newRule.value.ruleValue.trim()) return;
  ignoreRules.value.push({ ...newRule.value, ruleValue: newRule.value.ruleValue.trim(), ruleScope: newRule.value.ruleType === 'CHAPTER' ? 'SECTION' : 'PAGE' });
  newRule.value = { ruleType: 'KEYWORD', ruleValue: '', ruleScope: 'PAGE', enabled: true };
}
async function saveIgnoreRules() {
  await savePptMaterialIgnoreRules(ruleMaterialType.value, ignoreRules.value);
  ElMessage.success('忽略规则已保存');
  ruleOpen.value = false;
}
function addMaterialEditorRule() {
  if (!materialEditorNewRule.value.ruleValue.trim()) return;
  materialEditorRules.value.push({ ...materialEditorNewRule.value, ruleValue: materialEditorNewRule.value.ruleValue.trim(), ruleScope: materialEditorNewRule.value.ruleType === 'CHAPTER' ? 'SECTION' : 'PAGE' });
  materialEditorNewRule.value = { ruleType: 'KEYWORD', ruleValue: '', ruleScope: 'PAGE', enabled: true };
}

function resetMaterialEditorReminder() {
  Object.assign(materialEditorReminder, defaultReminderSettings);
  materialEditorWeekly.value = false;
  materialEditorWindows.value = [];
  addReminderWindow();
}

function materialEditorReminderPayload(): PptFeedbackReminderSettings {
  return {
    ...materialEditorReminder,
    weeklyStartMinute: null,
    weeklyEndMinute: null,
    windows: materialEditorWindowValues.value,
  };
}

/** 为配置请求补充认证信息并统一处理响应错误。 */
async function request(path: string, init?: RequestInit) {
  const headers = new Headers(init?.headers);
  headers.set('X-User-Id', localStorage.getItem('skill-user-id') || '');
  if (typeof init?.body === 'string') headers.set('Content-Type', 'application/json');
  const response = await fetch(base + path, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `请求失败（${response.status}）`);
  }
  return response;
}

/** 打开部门关键字导入窗口并清空上一次文件。 */
function openDepartmentImport() {
  importFile.value = undefined;
  importError.value = '';
  importOpen.value = true;
}

function selectDepartmentFile(event: Event) {
  const input = event.target as HTMLInputElement;
  importFile.value = input.files?.[0];
  importError.value = '';
}

/** 上传部门关键字工作表，由服务器校验并写入整批数据。 */
async function importDepartmentKeywords() {
  const file = importFile.value;
  if (!file || importing.value) return;
  if (!/\.xlsx?$/i.test(file.name) || !file.size) {
    importError.value = '请选择非空的 .xlsx 或 .xls 文件';
    return;
  }
  const body = new FormData();
  body.append('file', file);
  importing.value = true;
  importError.value = '';
  try {
    const response = await request('/department/import', { method: 'POST', body });
    const result: { imported: number } = await response.json();
    importOpen.value = false;
    ElMessage.success(`成功导入 ${result.imported} 条部门关键词`);
    if (page.value === 1) await load();
    else page.value = 1;
  } catch (error) {
    importError.value = error instanceof Error ? error.message : '导入失败，请重试';
  } finally {
    importing.value = false;
  }
}

/** 读取当前配置分类及分页筛选结果。 */
async function load() {
  const id = ++requestId;
  // 报告通知和组织督办人由各自组件加载数据。
  if (active.value === 'report-notification' || active.value === 'feedback-supervisors') {
    loading.value = false;
    return;
  }
  loading.value = true;
  if (active.value === 'department' && !departmentFilterReady.value) return;
  try {
    if (active.value === 'board') {
      const boards: BoardRow[] = await (await request('/section-options')).json();
      if (id !== requestId) return;
      const term = applied.keyword.toLowerCase();
      const matches = boards.filter(row => [row.name, row.owner, row.ownerName].some(value => value?.toLowerCase().includes(term)));
      const start = (page.value - 1) * size.value;
      boardRows.value = matches.slice(start, start + size.value);
      total.value = matches.length;
      return;
    }
    const query = new URLSearchParams({ ...applied, page: String(page.value), size: String(size.value) });
    const result = await (await request(`/${active.value === 'section' ? 'titles' : active.value}?${query}`)).json();
    if (id !== requestId) return;
    if (active.value === 'section') titleRows.value = result.items;
    else {
      rows.value = result.items;
      if (active.value === 'material-type') {
        const settings: (PptFeedbackReminderSettings & { id: number })[] = result.reminderSettings ?? [];
        reminderSettings.value = Object.fromEntries(settings.map(({ id, ...values }) => [
          id, savingReminderRows.has(id) ? reminderSettings.value[id] : { ...values, windows: result.reminderWindows?.[id] ?? [] },
        ]));
      }
    }
    total.value = result.total;
  } catch (error) {
    if (id === requestId) {
      rows.value = [];
      titleRows.value = [];
      total.value = 0;
      ElMessage.error(String(error));
    }
  } finally {
    if (id === requestId) loading.value = false;
  }
}

/** 按当前筛选条件查询页面数据。 */
function search() {
  clearTimeout(titleSearchTimer);
  applied.keyword = keyword.value.trim();
  applied.target = (active.value === 'department' ? departmentFilter.value : mode.value) || '';
  if (page.value === 1) void load();
  else page.value = 1;
}

function scheduleTitleSearch() {
  if (active.value !== 'section') return;
  clearTimeout(titleSearchTimer);
  titleSearchTimer = setTimeout(search, 250);
}

onUnmounted(() => clearTimeout(titleSearchTimer));

watch(active, () => {
  keyword.value = '';
  mode.value = '';
  rows.value = [];
  titleRows.value = [];
  boardRows.value = [];
  total.value = 0;
  if (active.value === 'department' && !directoryDepartments.value.length) {
    void listPptReportDepartments().then(options => {
      directoryDepartments.value = options.map(item => item.name);
    }).catch(() => undefined);
  }
  search();
}, { immediate: true });
watch([page, size], () => void load());

const titleForm = ref({ originalName: undefined as string | undefined, name: '', section: '',
  handlerUserIds: [] as string[] });
const recognitionScope = ref<PptRecognitionScope>({ scope: 'FULL' });
const recognitionEditor = ref<InstanceType<typeof PptRecognitionSettings>>();
const selectedBoard = computed(() => sections.value.find(board => board.name === titleForm.value.section));
const knownTitleHandlers = ref(new Map<string, string>());
const titleHandlerReuseOpen = ref(false);
const titleHandlerReuseError = ref('');
const titleHandlerOptions = computed(() => {
  const options = new Map(titleForm.value.handlerUserIds.map(id => [id, knownTitleHandlers.value.get(id) ?? id]));
  for (const person of people.value) {
    options.set(person.userId, [contactLabel(person.userId, person.name), person.department].filter(Boolean).join(' · '));
  }
  return [...options];
});

function rememberTitleHandlers(ids: string[]) {
  const options = new Map(titleHandlerOptions.value);
  knownTitleHandlers.value = new Map(ids.map(id => [id, options.get(id) ?? id]));
}

function openTitleHandlerReuse() {
  titleHandlerReuseError.value = '';
  titleHandlerReuseOpen.value = true;
}

function applyTitleHandlerReuse(_sourceTitle: string, peopleToAdd: PptPerson[]) {
  const merged = new Map(titleForm.value.handlerUserIds.map(id => [id, knownTitleHandlers.value.get(id) ?? id]));
  for (const person of peopleToAdd) {
    merged.set(person.userId, [contactLabel(person.userId, person.name), person.department].filter(Boolean).join(' · '));
  }
  titleForm.value.handlerUserIds = [...merged.keys()];
  knownTitleHandlers.value = merged;
  titleHandlerReuseOpen.value = false;
}

/** 格式化材料或配置的维护时间。 */
function dateText(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) ?? '';
}

/** 读取标题相关配置最近一次维护时间。 */
function titleUpdated(row: TitleRow) {
  return [row.section?.updatedAt, row.scope?.updatedAt]
    .filter((value): value is string => !!value).sort().pop();
}

/** 打开配置编辑窗口并初始化表单。 */
function edit(entry?: Entry) {
  if (!canMaintain(active.value, active.value === 'department' ? entry?.target : undefined)) return;
  editKind.value = active.value;
  form.value = entry ? { ...entry } : { name: '', target: '' };
  materialEditorRules.value = [];
  materialEditorRulesState.value = 'ready';
  const rulesRequest = ++materialEditorRulesRequest;
  materialEditorNewRule.value = { ruleType: 'KEYWORD', ruleValue: '', ruleScope: 'PAGE', enabled: true };
  resetMaterialEditorReminder();
  if (active.value === 'material-type' && entry?.id != null) {
    const settings = reminderSettings.value[entry.id];
    if (settings) {
      Object.assign(materialEditorReminder, settings, { notificationEnabled: settings.notificationEnabled !== false });
      const windows = reminderWindows(settings);
      materialEditorWeekly.value = !windows[0].daily;
      materialEditorWindows.value = windows.map(({ startMinute, endMinute }) => ({
        id: ++reminderWindowId,
        startDay: Math.floor(startMinute / 1440), startTime: clockText(startMinute),
        endDay: Math.floor(endMinute / 1440), endTime: clockText(endMinute),
      }));
    }
  }
  if (active.value === 'material-type' && entry) {
    materialEditorRulesState.value = 'loading';
    void listPptMaterialIgnoreRules(entry.name).then(rules => {
      if (rulesRequest !== materialEditorRulesRequest) return;
      materialEditorRules.value = rules;
      materialEditorRulesState.value = 'ready';
    }).catch(() => {
      if (rulesRequest === materialEditorRulesRequest) materialEditorRulesState.value = 'failed';
    });
  }
  editorOpen.value = true;
}

/** 初始化标题配置弹窗，供查看或维护板块、解析范围和经办人。 */
async function editTitle(row?: TitleRow) {
  editKind.value = 'section';
  titleForm.value = {
    originalName: row?.name, name: row?.name ?? '', section: row?.section?.target ?? '',
    handlerUserIds: [],
  };
  titleHandlerReuseOpen.value = false;
  titleHandlerReuseError.value = '';
  recognitionScope.value = {
    scope: row?.scope?.target && isPptRecognitionMode(row.scope.target) ? row.scope.target : 'FULL',
    upperLimitRatio: row?.scope?.upperLimitRatio, firstRow: row?.scope?.firstRow, lastRow: row?.scope?.lastRow,
  };
  editorOpen.value = true;
  sectionsReady.value = false;
  if (!row) {
    knownTitleHandlers.value.clear();
    void searchPeople('');
  }
  await loadSections();
}

/** 打开正式板块及默认联系人的编辑窗口。 */
function editBoard(row?: BoardRow) {
  editKind.value = 'board';
  boardForm.value = { originalName: row?.name ?? '', name: row?.name ?? '', owner: row?.owner ?? '', create: !row };
  const ids = row?.owner?.split(',') ?? [];
  boardOwnerIds.value = ids;
  const names = row?.ownerName?.split('、') ?? [];
  knownBoardContacts.value = new Map(ids.map((id, index) => [id, names[index] ?? id]));
  editorOpen.value = true;
  void searchPeople('');
}

/** 打开指定标题的经办人列表，列表页根据权限提供维护操作。 */
function openHandlers(title: string) {
  void router.push({ path: '/ppt-anomaly/keyword-config/title-handlers', state: { pptHandlerTitle: title } });
}

/** 刷新正式板块及默认联系人选项。 */
async function loadSections() {
  sectionsLoading.value = true;
  try {
    sections.value = await (await request('/section-options')).json();
    sectionsReady.value = true;
  } catch (error) {
    ElMessage.error(String(error));
  } finally {
    sectionsLoading.value = false;
  }
}

/** 提交当前材料类型、关键字、标题或板块的表单，并刷新对应列表。 */
async function save() {
  if (saving.value || !canMaintainEditor.value) return;
  const entry = form.value;
  const isTitle = editKind.value === 'section';
  const isBoard = editKind.value === 'board';
  const isMaterialType = editKind.value === 'material-type';
  if (isBoard && !boardForm.value.owner?.trim()) {
    ElMessage.warning('请选择板块联系人');
    return;
  }
  const reminder = isMaterialType ? materialEditorReminderPayload() : undefined;
  if (reminder) {
    const error = windowTimeErrors.value.find(Boolean);
    if (error) {
      ElMessage.warning(error);
      return;
    }
    if (reminder.windows && notificationWindowsOverlap(reminder.windows)) {
      ElMessage.warning('通知时段不能重叠，请调整后再保存');
      return;
    }
    if (!Number.isInteger(reminder.intervalMinutes) || reminder.intervalMinutes < 1) {
      ElMessage.warning('提醒间隔须为正整数分钟');
      return;
    }
  }
  // 材料类型只要求名称，部门关键字还需选中有权维护的目标部门。
  const valid = isTitle ? titleForm.value.name.trim() && titleForm.value.section.trim()
    : isBoard ? boardForm.value.name.trim() : entry.name.trim() && (isMaterialType || canMaintain('department', entry.target));
  if (!valid) {
    ElMessage.warning('请填写完整配置');
    return;
  }
  const recognition = isTitle ? recognitionEditor.value?.prepareSave() : undefined;
  if (isTitle && !recognition) return;
  const payload = isTitle ? { ...titleForm.value, ...recognitionScope.value, scanRules: recognition?.changedRules }
    : isBoard ? boardForm.value : isMaterialType
      ? { name: entry.name, reminder, ignoreRules: materialEditorRulesState.value === 'ready' ? materialEditorRules.value : undefined }
      : entry;
  saving.value = true;
  try {
    const path = isTitle ? '/titles' : isBoard ? '/boards'
      : `/${isMaterialType ? 'material-types' : editKind.value}` + (entry.id ? '/' + entry.id : '');
    await request(path, {
      method: !isTitle && !isBoard && entry.id ? 'PUT' : 'POST', body: JSON.stringify(payload),
    });
    editorOpen.value = false;
    ElMessage.success('已保存');
    await load();
  } catch (error) {
    ElMessage.error(String(error));
  } finally {
    saving.value = false;
  }
}

/** 确认并删除所选配置或标题。 */
async function remove(entry: Entry | TitleRow | BoardRow) {
  const kind = active.value;
  // 部门关键字使用被删除记录的归属部门校验操作入口。
  if (!canMaintain(kind, kind === 'department' ? (entry as Entry).target : undefined)) return;
  try {
    await ElMessageBox.confirm(`删除「${entry.name}」？`, '删除配置', { type: 'warning', customClass: 'ppt-confirm-box' });
  } catch {
    return;
  }
  try {
    await request(kind === 'board' ? '/boards?' + new URLSearchParams({ name: entry.name })
      : kind === 'section' ? '/titles?' + new URLSearchParams({ name: entry.name })
      : `/${kind}/${(entry as Entry).id}`, { method: 'DELETE' });
    if (active.value !== kind) return;
    if ((kind === 'board' ? boardRows.value.length : kind === 'section' ? titleRows.value.length : rows.value.length) === 1 && page.value > 1) page.value--;
    else await load();
  } catch (error) {
    ElMessage.error(String(error));
  }
}
</script>

<template>
  <section class="config-page">
    <h2>例会材料配置</h2>
    <el-card shadow="never" body-class="config-card-body">
      <el-tabs v-model="active">
        <el-tab-pane v-for="item in visibleTabs" :key="item.key" :name="item.key" :label="item.label" />
      </el-tabs>
      <!-- 督办关系增减可能改变当前账号的可维护部门。 -->
      <PptReportNotificationConfig v-if="active === 'report-notification'" />
      <PptFeedbackSupervisorConfig v-else-if="active === 'feedback-supervisors'"
        :can-manage-all-configurations="canManageAllConfigurations" @changed="loadPermissions" />
      <el-config-provider v-else size="small">
        <form class="config-filters" @submit.prevent="search">
          <PptListFilterBar>
            <el-input v-model="keyword" class="ppt-filter-search" :prefix-icon="Search" clearable aria-label="搜索配置"
              @input="scheduleTitleSearch"
              :placeholder="active === 'section' ? '搜索页面标题或所属板块' : active === 'board' ? '搜索板块或负责人'
                : active === 'material-type' ? '搜索材料类型' : '搜索匹配关键词'" />
            <el-select v-if="active === 'department'" v-model="departmentFilter" class="ppt-filter-select"
              filterable clearable :disabled="!departmentFilterReady" placeholder="全部部门" aria-label="筛选部门" @change="search">
              <el-option v-for="name in departmentOptions" :key="name" :label="name" :value="name" />
            </el-select>
            <el-select v-if="active === 'section'" v-model="mode" class="ppt-filter-select" clearable
              placeholder="全部识别范围" aria-label="识别范围" @change="search">
              <el-option v-for="[key, label] in pptManualRecognitionOptions" :key="key" :label="label" :value="key" />
            </el-select>
            <el-button v-if="active !== 'section'" native-type="submit">筛选</el-button>
            <el-button v-if="active === 'department' && canMaintainActive" @click="openDepartmentImport">导入</el-button>
            <el-button v-if="canMaintainActive" type="primary" @click="active === 'section' ? editTitle() : active === 'board' ? editBoard() : edit()">新增</el-button>
          </PptListFilterBar>
        </form>
        <el-table v-if="active === 'section'" v-loading="loading" :data="titleRows" row-key="name">
          <el-table-column prop="name" label="页面标题" min-width="124" show-overflow-tooltip />
          <el-table-column label="识别范围" min-width="140">
            <template #default="{ row }">{{ titleScopeLabel(row.scope) }}</template>
          </el-table-column>
          <el-table-column label="所属板块" min-width="76" show-overflow-tooltip>
            <template #default="{ row }">{{ row.section?.target }}</template>
          </el-table-column>
          <el-table-column label="板块联系人" min-width="112" show-overflow-tooltip>
            <template #default="{ row }">{{ row.contact ? contactLabel(row.contact.target, row.ownerName) : '—' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(titleUpdated(row)) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="168" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="editTitle(row)">{{ canMaintainActive ? '编辑' : '查看' }}</el-button>
                <el-button link type="primary" @click="openHandlers(row.name)">{{ canMaintainActive ? '设置经办人' : '查看经办人' }}</el-button>
                <el-button v-if="canMaintainActive" link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-table v-else-if="active === 'board'" v-loading="loading" :data="boardRows" row-key="name">
          <el-table-column prop="name" label="板块名称" min-width="180" />
          <el-table-column label="板块联系人" min-width="180">
            <template #default="{ row }">{{ row.owner ? contactLabel(row.owner, row.ownerName) : '—' }}</template>
          </el-table-column>
          <el-table-column prop="titleCount" label="关联标题数" width="120" />
          <el-table-column v-if="canMaintainActive" label="操作" width="110">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="editBoard(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-table v-else v-loading="loading" :data="rows" row-key="id">
          <el-table-column prop="name" :label="active === 'material-type' ? '材料类型' : '匹配关键词'" min-width="180" />
          <el-table-column v-if="active === 'material-type'" label="通知时段" min-width="286">
            <template #default="{ row }">
              <div v-if="reminderSettings[row.id]">
                <div v-for="window in reminderWindows(reminderSettings[row.id]!)" :key="`${window.startMinute}-${window.endMinute}`" class="schedule-display">
                  <el-icon><Clock /></el-icon> {{ scheduleText(window) }}
                </div>
                <span class="schedule-interval-summary">
                  {{ reminderSettings[row.id]?.intervalMinutes }} 分钟/次
                </span>
              </div>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="active === 'material-type'" label="通知总开关" width="108" align="center">
            <template #default="{ row }">
              <el-switch v-if="reminderSettings[row.id]" :model-value="reminderSettings[row.id]?.notificationEnabled !== false"
                :loading="savingReminderRows.has(row.id)" :disabled="!canMaintainActive"
                :aria-label="`${row.name}通知总开关`"
                @change="(value: unknown) => updateNotificationSwitch(row.id, 'notificationEnabled', value === true)" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="active === 'material-type'" label="自动逾期提醒" width="118" align="center">
            <template #default="{ row }">
              <el-switch v-if="reminderSettings[row.id]" :model-value="reminderSettings[row.id]?.enabled"
                :loading="savingReminderRows.has(row.id)" :disabled="!canMaintainActive || reminderSettings[row.id]?.notificationEnabled === false"
                :aria-label="`${row.name}自动逾期提醒`"
                @change="(value: unknown) => updateNotificationSwitch(row.id, 'enabled', value === true)" />
              <el-button v-else-if="canMaintainActive" link type="primary" :loading="savingReminderRows.has(row.id)"
                :aria-label="`${row.name}设置自动逾期提醒`" @click="updateNotificationSwitch(row.id, 'enabled', true)">—</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="active === 'department'" prop="target" label="部门" min-width="160" />
          <el-table-column label="更新时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column v-if="showConfigActions" label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <div v-if="active !== 'department' || canMaintain('department', row.target)" class="row-actions">
                <el-button link type="primary" @click="edit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
              <span v-else class="unavailable-action">—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" />
      </el-config-provider>
    </el-card>
    <el-dialog v-model="importOpen" title="导入部门关键词" width="min(540px, 94vw)"
      class="ppt-dialog department-import-dialog"
      align-center append-to-body destroy-on-close :close-on-click-modal="false"
      :show-close="!importing" :close-on-press-escape="!importing">
      <div class="department-import-content">
        <div class="department-import-template">
          <el-link :href="`${base}/department/template`" :icon="Download" type="primary">下载 Excel 模板</el-link>
          <p>在第一个工作表填写「匹配关键词」和「部门」，每行一条。已有关键词会提示冲突。</p>
        </div>
        <label class="department-file-picker"
          :class="{ 'is-selected': importFile, 'is-importing': importing }">
          <input type="file" accept=".xlsx,.xls"
            :aria-label="importFile ? '更换部门关键词 Excel 文件' : '选择部门关键词 Excel 文件'"
            aria-describedby="department-import-file-detail" :disabled="importing" @change="selectDepartmentFile" />
          <el-icon class="department-file-icon" aria-hidden="true">
            <Document v-if="importFile" />
            <UploadFilled v-else />
          </el-icon>
          <strong>{{ importFile ? '更换文件' : '选择 Excel 文件' }}</strong>
          <span id="department-import-file-detail" class="department-file-detail" aria-live="polite">
            {{ importFile?.name || '支持 .xlsx 和 .xls 格式' }}
          </span>
        </label>
        <el-alert v-if="importError" :title="importError" type="error" :closable="false" />
      </div>
      <template #footer>
        <el-button :disabled="importing" @click="importOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="importDepartmentKeywords">确认导入</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="ruleOpen" title="材料忽略规则" class="ignore-rule-dialog" width="min(760px, 94vw)" append-to-body destroy-on-close>
      <div class="rule-dialog-intro">
        <div class="rule-dialog-title">{{ ruleMaterialType }}</div>
        <p>章节规则从匹配到的章节标题开始，持续忽略到下一章节。关键词规则只忽略命中的页面。</p>
      </div>
      <div class="rule-add-row">
        <el-select v-model="newRule.ruleType" style="width: 150px">
          <el-option label="关键词" value="KEYWORD" />
          <el-option label="章节编号" value="CHAPTER" />
        </el-select>
        <el-input v-model="newRule.ruleValue" :placeholder="newRule.ruleType === 'CHAPTER' ? '例如 2 或 3' : '例如 目录'" />
        <el-button type="primary" @click="addIgnoreRule">添加</el-button>
      </div>
      <el-table class="ignore-rule-table" :data="ignoreRules" row-key="id" empty-text="暂无忽略规则">
        <el-table-column label="规则类型" width="120"><template #default="{ row }">{{ row.ruleType === 'CHAPTER' ? '章节编号' : '关键词' }}</template></el-table-column>
        <el-table-column prop="ruleValue" label="匹配内容" />
        <el-table-column label="忽略范围" width="150"><template #default="{ row }">{{ row.ruleScope === 'SECTION' ? '整个章节' : '当前页' }}</template></el-table-column>
        <el-table-column label="操作" width="88"><template #default="{ $index }"><el-button class="ignore-rule-delete" link type="danger" @click="ignoreRules.splice($index, 1)">删除</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="ruleOpen = false">取消</el-button><el-button type="primary" @click="saveIgnoreRules">保存</el-button></template>
    </el-dialog>
    <el-dialog
      v-model="editorOpen"
      :title="`${!canMaintainEditor ? '查看' : (isTitleEditor ? titleForm.originalName !== undefined : editKind === 'board' ? !boardForm.create : form.id) ? '编辑' : '新增'}${tab.label}`"
      class="ppt-dialog"
      :class="{ 'material-type-editor': editKind === 'material-type' }"
      :width="editKind === 'material-type' ? 'min(760px, 94vw)' : isTitleEditor ? 'min(680px, 94vw)' : 'min(540px, 94vw)'"
      align-center append-to-body destroy-on-close
      :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false"
    >
      <el-form :id="editKind === 'material-type' ? 'material-type-form' : undefined"
        label-position="top" :disabled="saving || !canMaintainEditor" @submit.prevent="save">
        <template v-if="editKind === 'section'">
          <el-form-item label="页面标题" required><el-input v-model="titleForm.name" maxlength="512" /></el-form-item>
          <PptRecognitionSettings v-if="editorOpen" ref="recognitionEditor" v-model="recognitionScope"
            :original-title="titleForm.originalName" :editable="canMaintainEditor" />
          <el-form-item label="所属板块" required>
            <el-select v-if="sectionsReady || sectionsLoading" v-model="titleForm.section" filterable
              :disabled="sectionsLoading" :loading="sectionsLoading" placeholder="搜索或选择已有板块">
              <el-option v-for="section in sections" :key="section.name" :value="section.name" :label="section.name" />
            </el-select>
            <el-button v-else @click="loadSections">重新加载板块</el-button>
          </el-form-item>
          <el-form-item v-if="sectionsReady && titleForm.section" label="板块联系人">
            <span aria-live="polite">{{ selectedBoard?.owner ? contactLabel(selectedBoard.owner, selectedBoard.ownerName ?? undefined) : '—' }}</span>
          </el-form-item>
          <el-form-item v-if="titleForm.originalName === undefined" label="标题经办人">
            <div class="title-handler-editor">
              <el-select v-model="titleForm.handlerUserIds" multiple filterable remote :reserve-keyword="false" :remote-method="searchPeople"
                :loading="peopleLoading" placeholder="搜索姓名或用户 ID，可稍后补充" @change="rememberTitleHandlers">
                <el-option v-for="[id, label] in titleHandlerOptions" :key="id" :value="id" :label="label" />
              </el-select>
              <el-button class="title-handler-reuse-button" plain type="primary" @click="openTitleHandlerReuse">复用其他标题经办人</el-button>
            </div>
            <small class="title-handler-hint">可直接搜索添加，也可从已有标题追加经办人，人员会随标题一起保存。</small>
          </el-form-item>
          <el-button v-if="canMaintainEditor && sectionsReady && !sections.length" link type="primary" @click="editorOpen = false; active = 'board'">前往板块配置</el-button>
        </template>
        <template v-else-if="editKind === 'board'">
          <el-form-item label="板块名称" required><el-input v-model="boardForm.name" maxlength="512" /></el-form-item>
          <el-form-item label="板块联系人" required>
            <el-select :model-value="boardOwnerIds" multiple filterable remote clearable :reserve-keyword="false"
              :remote-method="searchPeople" :loading="peopleLoading" placeholder="搜索并选择一位或多位联系人"
              @update:model-value="selectBoardOwners">
              <el-option v-for="[id, name] in boardOwnerOptions" :key="id" :value="id" :label="contactLabel(id, name)" />
            </el-select>
            <small>材料内容的咨询对象。</small>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item :label="editKind === 'material-type' ? '材料类型名称' : '匹配关键词'" required>
            <el-input v-model="form.name" maxlength="512" />
          </el-form-item>
          <template v-if="editKind === 'material-type'">
            <div class="inline-rule-header">
              <div>
                <strong>忽略规则</strong>
                <span>可选，创建后也可以单独维护</span>
              </div>
              <el-tag size="small" type="info">{{ materialEditorRules.length }} 条</el-tag>
            </div>
            <div v-if="materialEditorRulesState === 'ready'" class="inline-rule-add">
              <el-select v-model="materialEditorNewRule.ruleType" aria-label="规则类型">
                <el-option label="关键词" value="KEYWORD" />
                <el-option label="章节编号" value="CHAPTER" />
              </el-select>
              <el-input v-model="materialEditorNewRule.ruleValue"
                :placeholder="materialEditorNewRule.ruleType === 'CHAPTER' ? '例如 2 或 3' : '例如 目录'"
                @keyup.enter="addMaterialEditorRule" />
              <el-button type="primary" plain @click="addMaterialEditorRule">添加</el-button>
            </div>
            <div v-if="materialEditorRules.length" class="inline-rule-list">
              <div v-for="(rule, index) in materialEditorRules" :key="`${rule.ruleType}-${rule.ruleValue}-${index}`" class="inline-rule-item">
                <el-tag size="small" effect="plain">{{ rule.ruleType === 'CHAPTER' ? '章节' : '关键词' }}</el-tag>
                <span class="inline-rule-value">{{ rule.ruleValue }}</span>
                <span class="inline-rule-scope">{{ rule.ruleScope === 'SECTION' ? '整个章节' : '当前页' }}</span>
                <el-button link type="danger" @click="materialEditorRules.splice(index, 1)">删除</el-button>
              </div>
            </div>
            <div v-else class="inline-rule-empty">{{ materialEditorRulesState === 'ready' ? '暂未设置忽略规则，上传时会完整识别。'
              : materialEditorRulesState === 'loading' ? '正在加载忽略规则…' : '忽略规则加载失败，可重新打开编辑窗口。' }}</div>
            <div class="material-reminder-editor">
              <div class="notification-heading">
                <div>
                  <strong>通知设置</strong>
                  <span>总开关控制此类型的自动和手动邮件</span>
                </div>
                <el-switch v-model="materialEditorReminder.notificationEnabled" aria-label="通知总开关"
                  inline-prompt active-text="开启" inactive-text="关闭" />
              </div>
              <div class="reminder-editor-fields">
                <div class="notification-period">
                  <div>
                    <strong>通知时段</strong>
                    <small>仅在报告上传当周发送，按北京时间计算</small>
                  </div>
                  <el-radio-group v-model="materialEditorWeekly" aria-label="通知时段设置方式">
                    <el-radio-button :value="false">当周每天</el-radio-button>
                    <el-radio-button :value="true">按星期设置</el-radio-button>
                  </el-radio-group>
                </div>
                <div class="reminder-windows">
                  <div v-for="(window, index) in materialEditorWindows" :key="window.id" class="reminder-window"
                    :class="{ 'has-weekday': materialEditorWeekly, 'can-remove': materialEditorWindows.length > 1,
                      'invalid-notice-window': windowTimeErrors[index] }">
                    <div class="schedule-endpoint">
                      <span>开始</span>
                      <el-select v-if="materialEditorWeekly" v-model="window.startDay" :aria-label="`时段 ${index + 1} 开始星期`">
                        <el-option v-for="(day, dayIndex) in weekdays" :key="day" :label="day" :value="dayIndex" />
                      </el-select>
                      <el-time-picker v-model="window.startTime" format="HH:mm" value-format="HH:mm" :clearable="false" :aria-label="`时段 ${index + 1} 开始时间`" />
                    </div>
                    <div class="schedule-endpoint">
                      <span>结束</span>
                      <el-select v-if="materialEditorWeekly" v-model="window.endDay" :aria-label="`时段 ${index + 1} 结束星期`">
                        <el-option v-for="(day, dayIndex) in weekdays" :key="day" :label="day" :value="dayIndex" />
                      </el-select>
                      <el-time-picker v-model="window.endTime" format="HH:mm" value-format="HH:mm" :clearable="false" :aria-label="`时段 ${index + 1} 结束时间`" />
                    </div>
                    <el-button v-if="materialEditorWindows.length > 1" class="remove-notice-window" link type="danger"
                      :aria-label="`删除时段 ${index + 1}`" @click="materialEditorWindows.splice(index, 1)">删除</el-button>
                    <small v-if="windowTimeErrors[index]" class="notice-window-error" role="status">{{ windowTimeErrors[index] }}</small>
                  </div>
                </div>
                <div class="notification-period-actions">
                  <el-button plain type="primary" :icon="Plus" @click="addReminderWindow">添加时段</el-button>
                  <div class="schedule-interval-field">
                    <label for="material-editor-reminder-interval">提醒间隔</label>
                    <el-input-number id="material-editor-reminder-interval" v-model="materialEditorReminder.intervalMinutes" :min="1" :max="2147483647" :precision="0" class="reminder-interval" />
                    <span>分钟</span>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <el-form-item v-if="editKind === 'department'" label="部门" required>
            <el-select v-model="form.target" filterable placeholder="选择可维护的部门">
              <el-option v-for="name in keywordDepartmentOptions" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>
        </template>
        <div v-if="editKind !== 'material-type'" class="editor-actions" :class="{ 'title-editor-actions': isTitleEditor }">
          <el-button :disabled="saving" @click="editorOpen = false">{{ canMaintainEditor ? '取消' : '关闭' }}</el-button>
          <el-button v-if="canMaintainEditor" native-type="submit" type="primary" :loading="saving" :disabled="isTitleEditor && recognitionEditor?.loading">保存</el-button>
        </div>
      </el-form>
      <template v-if="editKind === 'material-type'" #footer>
        <el-button :disabled="saving" @click="editorOpen = false">{{ canMaintainEditor ? '取消' : '关闭' }}</el-button>
        <el-button v-if="canMaintainEditor" form="material-type-form" native-type="submit" type="primary" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
    <PptTitleHandlerReuseDialog v-model="titleHandlerReuseOpen" title="" :existing-ids="new Set(titleForm.handlerUserIds)"
      :saving="saving" :error="titleHandlerReuseError"
      note="选择一个已配置经办人的标题，将其人员追加到当前新标题。已有人员会自动去重。"
      @apply="applyTitleHandlerReuse" @select="titleHandlerReuseError = ''" />
  </section>
</template>

<style scoped>
.unavailable-action { color: #64748b; }
.config-page { padding: clamp(16px, 2.2vw, 32px); background: #f4f7fb; min-height: 100%; }
h2 { margin: 0 0 14px; font-size: 22px; color: #142441; }
.config-page :deep(.el-card) { border: 1px solid #dfe6f0; border-radius: 14px; box-shadow: 0 4px 16px #22375c0a; }
.config-page :deep(.config-card-body) { padding: clamp(14px, 1.6vw, 24px); }
.config-page :deep(.el-tabs__header) { margin-bottom: 16px; }
.config-page :deep(.el-tabs__item) { height: 42px; font-size: 14px; }
.config-page :deep(.el-table .el-table__cell) { padding-block: 11px; }
.config-page :deep(.el-table .cell) { padding-inline: 12px; }
.config-page :deep(.config-date .cell) { white-space: nowrap; font-variant-numeric: tabular-nums; }
.config-filters { --ppt-filter-width: 186px; margin-bottom: 14px; }
.row-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.row-actions .el-button { flex-shrink: 0; margin: 0; }
.title-handler-editor { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: start; width: 100%; }
.title-handler-reuse-button { min-height: 40px; }
.title-handler-hint { display: block; margin-top: 6px; color: #7b8799; font-size: 12px; line-height: 1.5; }
@media (max-width: 640px) { .title-handler-editor { grid-template-columns: 1fr; } .title-handler-reuse-button { justify-self: start; } }
.inline-rule-header { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 10px; padding-top: 14px; border-top: 1px solid #edf1f7; color: #253858; }
.inline-rule-header > div { display: flex; flex-direction: column; gap: 3px; }
.inline-rule-header span { color: #64748b; font-size: 12px; font-weight: 400; }
.inline-rule-add { display: grid; grid-template-columns: 118px minmax(0, 1fr) auto; gap: 8px; align-items: center; }
.inline-rule-list { display: flex; flex-direction: column; gap: 6px; margin-top: 10px; max-height: 150px; overflow-y: auto; }
.inline-rule-item { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; gap: 8px; align-items: center; min-height: 34px; padding: 5px 8px; border: 1px solid #e7ecf4; border-radius: 7px; background: #f8faff; }
.inline-rule-value { overflow: hidden; color: #2f405e; text-overflow: ellipsis; white-space: nowrap; }
.inline-rule-scope { color: #7b8799; font-size: 12px; }
.inline-rule-empty { margin-top: 10px; padding: 8px 12px; border: 1px dashed #d8e0ed; border-radius: 8px; color: #64748b; font-size: 12px; text-align: center; background: #fbfcfe; }
:global(.material-type-editor.el-dialog .el-dialog__footer) {
  flex-shrink: 0;
  padding: 12px var(--ppt-dialog-gutter);
  border-top: 1px solid #e3eaf4;
  background: #fff;
}
.material-reminder-editor {
  margin-top: 18px;
  border: 1px solid #dce6f3;
  border-radius: 12px;
  background: #f7faff;
}
.notification-heading,
.notification-period,
.notification-period-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}
.notification-heading {
  padding: 12px 16px;
  border-bottom: 1px solid #dce6f3;
  color: #243858;
}
.notification-heading > div,
.notification-period > div:first-child {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.notification-heading span,
.notification-period small {
  color: #52647d;
  font-size: 12px;
  line-height: 1.5;
}
.notification-heading :deep(.el-switch) { flex: none; }
.reminder-editor-fields { display: grid; gap: 12px; padding: 14px 16px; }
.notification-period { flex-wrap: wrap; color: #243858; }
.reminder-windows { display: grid; gap: 8px; }
.reminder-window {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: center;
  gap: 8px 16px;
  padding: 10px 12px;
  border: 1px solid #dce6f3;
  border-radius: 8px;
  background: #fff;
}
.reminder-window.can-remove { grid-template-columns: repeat(2, minmax(0, 1fr)) auto; }
.invalid-notice-window { border-color: var(--el-color-danger-light-5); }
.notice-window-error { grid-column: 1 / -1; color: var(--el-color-danger-dark-2); font-size: 12px; }
.notification-period-actions { flex-wrap: wrap; }
.rule-hint { margin: -4px 0 14px; color: #7b8799; font-size: 13px; line-height: 1.6; }
.ignore-rule-button { display: inline-flex; align-items: center; gap: 5px; border-radius: 6px; font-weight: 600; }
.ignore-rule-button:hover { background: #eff6ff; }
.ignore-rule-dialog :deep(.el-dialog__body) { padding-top: 8px; }
.rule-dialog-intro { padding: 12px 14px; margin-bottom: 16px; border: 1px solid #dbeafe; border-radius: 8px; background: linear-gradient(135deg, #f8fbff, #eff6ff); }
.rule-dialog-title { color: #1e3a8a; font-size: 14px; font-weight: 700; }
.rule-dialog-intro p { margin: 5px 0 0; color: #64748b; font-size: 13px; line-height: 1.55; }
.rule-add-row { display: grid; grid-template-columns: 140px minmax(0, 1fr) auto; gap: 10px; align-items: center; margin-bottom: 16px; }
.ignore-rule-table { border: 1px solid #e5eaf2; border-radius: 8px; overflow: hidden; }
.ignore-rule-table :deep(.el-table__header th) { background: #f7f9fc; color: #52627a; font-weight: 700; }
.ignore-rule-table :deep(.el-table__row:hover > td) { background: #f8fbff; }
.ignore-rule-delete { font-weight: 600; }
@media (max-width: 560px) { .rule-add-row { grid-template-columns: 1fr; } .inline-rule-item { grid-template-columns: auto minmax(0, 1fr) auto; } .inline-rule-item .el-button { grid-column: 3; } }
@media (max-width: 400px) { .inline-rule-add { grid-template-columns: minmax(0, 1fr) auto; } .inline-rule-add :deep(.el-select) { grid-column: 1 / -1; } }
.el-pagination { margin-top: 10px; justify-content: flex-end; flex-wrap: wrap; }
.editor-actions { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #e3eaf4; }
.title-editor-actions { position: sticky; bottom: -20px; z-index: 1; padding-bottom: 10px; background: var(--el-dialog-bg-color, #fff); }
.department-import-content { display: grid; gap: 16px; }
.department-import-template {
  padding: 12px 14px;
  border: 1px solid #e3eaf4;
  border-radius: 10px;
  background: #f5f8fd;
}
.department-import-template .el-link { font-weight: 600; }
.department-import-template p { margin: 6px 0 0; color: #52647c; line-height: 1.6; }
.department-file-picker {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  justify-items: center;
  gap: 6px;
  padding: 18px 14px;
  border: 1px dashed #a6b8d3;
  border-radius: 10px;
  text-align: center;
  color: var(--el-color-primary);
  background: #fafcff;
}
.department-file-picker input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}
.department-file-picker:focus-within { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.department-file-picker:not(.is-importing):hover,
.department-file-picker.is-selected { border-color: var(--el-color-primary); background: #eff5ff; }
.department-file-picker.is-importing input { cursor: wait; }
.department-file-icon {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  background: #e6eeff;
  font-size: 21px;
}
.department-file-detail { min-width: 0; max-width: 100%; color: #52647c; overflow-wrap: anywhere; }
:global(.department-import-dialog .el-dialog__footer) {
  flex-shrink: 0;
  padding: 12px var(--ppt-dialog-gutter) 16px;
  border-top: 1px solid #e3eaf4;
}
.config-page :deep(.el-table) {
  border: 1px solid #e2eaf5;
  border-radius: 10px;
  --el-table-header-bg-color: #f0f5fc;
  --el-table-row-hover-bg-color: #f5f9ff;
}

.schedule-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 9px;
  color: #1e50a4;
  background: #eef5ff;
  border: 1px solid #dce8fb;
  border-radius: 7px;
  font: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

.schedule-trigger:hover,
.schedule-trigger:focus-visible {
  border-color: #81a9e8;
  background: #e1edff;
}
.schedule-empty-trigger { min-width: 42px; justify-content: center; }

:global(.el-popper.ppt-schedule-popper) {
  max-width: calc(100vw - 24px);
  padding: 0;
  border: 1px solid #dce6f3;
  border-radius: 12px;
  box-shadow: 0 12px 32px #16355724, 0 2px 6px #16355710;
}

.schedule-editor {
  overflow-y: auto;
  overscroll-behavior: contain;
  border-radius: 11px;
}

.schedule-fields {
  display: grid;
  gap: 10px;
  padding: 12px 16px;
}

.schedule-endpoint {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}
.has-weekday .schedule-endpoint { grid-template-columns: auto 76px minmax(0, 1fr); }
.schedule-endpoint > span { color: #435673; font-size: 13px; white-space: nowrap; }
.schedule-endpoint :deep(.el-date-editor),
.schedule-endpoint :deep(.el-select) { min-width: 0; width: 100%; }
@media (max-width: 660px) {
  .reminder-window,
  .reminder-window.can-remove { grid-template-columns: minmax(0, 1fr); }
  .remove-notice-window { justify-self: end; }
}
@media (max-width: 400px) {
  .notification-heading,
  .reminder-editor-fields { padding-inline: 10px; }
  .has-weekday .schedule-endpoint { grid-template-columns: auto 66px minmax(0, 1fr); }
}

.schedule-actions {
  display: flex;
  justify-content: flex-end;
  padding: 10px 16px;
  border-top: 1px solid #e8eef7;
  background: #f7faff;
}

.schedule-interval-summary {
  margin-left: 3px;
  padding-left: 8px;
  border-left: 1px solid #d0dff3;
  color: #486387;
  white-space: nowrap;
}

.schedule-editor-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e8eef7;
  background: #f7faff;
  color: #162b49;
}

.schedule-editor-header > span,
.schedule-interval-field > span {
  color: #536884;
  font-size: 12px;
}

.schedule-interval-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.schedule-interval-field > label {
  color: #334965;
  white-space: nowrap;
}

.reminder-interval {
  width: 106px;
}
</style>
