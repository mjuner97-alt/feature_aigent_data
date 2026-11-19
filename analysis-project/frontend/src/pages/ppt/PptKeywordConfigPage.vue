<script setup lang="ts">
/** 例会识别配置页，维护部门关键词、标题规则及板块联系人。 */

import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { pptAnomalyPermissions, searchPptPeople, type PptPerson } from '../../api/pptAnomaly';
import { Search } from '@element-plus/icons-vue';
import PptListFilterBar from '../../components/ppt/PptListFilterBar.vue';
import PptFeedbackSupervisorConfig from '../../components/ppt/PptFeedbackSupervisorConfig.vue';
import PptReportNotificationConfig from '../../components/ppt/PptReportNotificationConfig.vue';

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

const tabs = [
  { key: 'department', label: '部门关键词', name: '匹配关键词', target: '部门' },
  { key: 'section', label: '标题配置', name: '匹配标题', target: '所属板块' },
  { key: 'board', label: '板块配置', name: '板块', target: '用户 ID' },
] as const;
const modes: Record<string, string> = {
  FULL: '全量识别', TEXT_ONLY: '仅文字', UPPER_TABLE_ONLY: '仅上方表格', NO_SCAN: '全不扫',
};
type ScanRule = { name: string; keywords: string; mode: string; upperLimitRatio?: number; firstRow?: number; lastRow?: number };
const rulesOpen = ref(false);
const rulesTitle = ref('');
const scanRules = ref<ScanRule[]>([]);
const rulesLoading = ref(false);
const rulesReady = ref(false);
async function openScanRules(title: string) {
  rulesTitle.value = title;
  scanRules.value = [];
  rulesReady.value = false;
  rulesOpen.value = true;
  rulesLoading.value = true;
  try {
    scanRules.value = await (await request('/scan-rules?' + new URLSearchParams({ title }))).json();
    rulesReady.value = true;
  } catch (error) { ElMessage.error(String(error)); }
  finally { rulesLoading.value = false; }
}
async function saveScanRules() {
  if (rulesLoading.value || !rulesReady.value) return;
  rulesLoading.value = true;
  try {
    await request('/scan-rules?' + new URLSearchParams({ title: rulesTitle.value }), {
      method: 'PUT', body: JSON.stringify(scanRules.value),
    });
    rulesOpen.value = false;
    ElMessage.success('扫描规则已保存，仅影响后续导入');
  } catch (error) { ElMessage.error(String(error)); }
  finally { rulesLoading.value = false; }
}
const route = useRoute();
const router = useRouter();
const requestedTab = () => route.query.tab === 'section' ? 'section'
  : route.query.tab === 'report-notification' ? 'report-notification'
    : route.query.tab === 'feedback-supervisors' ? 'feedback-supervisors' : 'department';
const canManageKeywords = ref(false);
const active = ref<string>('report-notification');
onMounted(async () => {
  canManageKeywords.value = (await pptAnomalyPermissions().catch(() => undefined))?.canManageMaterials === true;
  if (canManageKeywords.value) active.value = requestedTab();
});
watch(() => route.query.tab, () => {
  if (canManageKeywords.value) active.value = requestedTab();
});
const editKind = ref('department');
const isTitleEditor = computed(() => editKind.value === 'section');
const tab = computed(() => tabs.find(item => item.key === editKind.value) ?? tabs[0]);
const keyword = ref('');
const mode = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const rows = ref<Entry[]>([]);
const titleRows = ref<TitleRow[]>([]);
const loading = ref(false);
const people = ref<PptPerson[]>([]);
const knownBoardContacts = ref(new Map<string, string>());
const peopleLoading = ref(false);
const sections = ref<BoardRow[]>([]);
const boardRows = ref<BoardRow[]>([]);
const boardForm = ref({ originalName: '', name: '', owner: '', create: true });
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
const base = '/api/ppt-anomaly/keyword-config';

/** 为配置请求补充认证信息并统一处理响应错误。 */
async function request(path: string, init?: RequestInit) {
  const response = await fetch(base + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-User-Id': localStorage.getItem('skill-user-id') || '' },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `请求失败（${response.status}）`);
  }
  return response;
}

/** 读取当前配置分类及分页筛选结果。 */
async function load() {
  const id = ++requestId;
  if (!canManageKeywords.value || active.value === 'report-notification' || active.value === 'feedback-supervisors') {
    loading.value = false;
    return;
  }
  loading.value = true;
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
    else rows.value = result.items;
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
  applied.keyword = keyword.value.trim();
  applied.target = mode.value;
  if (page.value === 1) void load();
  else page.value = 1;
}

watch(active, () => {
  keyword.value = '';
  mode.value = '';
  rows.value = [];
  titleRows.value = [];
  boardRows.value = [];
  total.value = 0;
  search();
}, { immediate: true });
watch([page, size], () => void load());

const titleForm = ref({ originalName: undefined as string | undefined, name: '', section: '',
  handlerUserIds: [] as string[],
  scope: 'FULL', upperLimitRatio: undefined as number | undefined,
  firstRow: undefined as number | undefined, lastRow: undefined as number | undefined });
const selectedBoard = computed(() => sections.value.find(board => board.name === titleForm.value.section));
const knownTitleHandlers = ref(new Map<string, string>());
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

/** 格式化材料或配置的维护时间。 */
function dateText(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) ?? '';
}

/** 读取标题相关配置中可用于展示的创建时间。 */
function titleCreated(row: TitleRow) {
  return row.section ? row.section.createdAt : row.scope?.createdAt;
}

/** 读取标题相关配置最近一次维护时间。 */
function titleUpdated(row: TitleRow) {
  return [row.section?.updatedAt, row.scope?.updatedAt]
    .filter((value): value is string => !!value).sort().pop();
}

/** 打开配置编辑窗口并初始化表单。 */
function edit(entry?: Entry) {
  editKind.value = 'department';
  form.value = entry ? { ...entry } : { name: '', target: '' };
  editorOpen.value = true;
}

/** 加载并编辑标题的板块、解析范围及联系人。 */
async function editTitle(row?: TitleRow) {
  editKind.value = 'section';
  titleForm.value = {
    originalName: row?.name, name: row?.name ?? '', section: row?.section?.target ?? '',
    handlerUserIds: [],
    scope: row?.scope?.target && modes[row.scope.target] ? row.scope.target : 'FULL',
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
  const names = row?.ownerName?.split('、') ?? [];
  knownBoardContacts.value = new Map(ids.map((id, index) => [id, names[index] ?? id]));
  editorOpen.value = true;
  void searchPeople('');
}

/** 跳转到指定标题的经办人维护页面。 */
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

/** 提交关键词、标题或板块编辑表单并刷新对应列表。 */
async function save() {
  if (saving.value) return;
  const entry = form.value;
  const isTitle = editKind.value === 'section';
  const isBoard = editKind.value === 'board';
  if (isBoard && !boardForm.value.owner?.trim()) {
    ElMessage.warning('请选择板块联系人');
    return;
  }
  const valid = isTitle ? titleForm.value.name.trim() && titleForm.value.section.trim()
    : isBoard ? boardForm.value.name.trim() : entry.name.trim() && entry.target.trim();
  if (!valid) {
    ElMessage.warning('请填写完整配置');
    return;
  }
  const payload = isTitle ? titleForm.value : isBoard ? boardForm.value : entry;
  saving.value = true;
  try {
    const path = isTitle ? '/titles' : isBoard ? '/boards' : '/department' + (entry.id ? '/' + entry.id : '');
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
  try {
    await ElMessageBox.confirm(`删除「${entry.name}」？`, '删除配置', { type: 'warning', customClass: 'ppt-confirm-box' });
  } catch {
    return;
  }
  try {
    await request(kind === 'board' ? '/boards?' + new URLSearchParams({ name: entry.name })
      : kind === 'section' ? '/titles?' + new URLSearchParams({ name: entry.name })
      : `/department/${(entry as Entry).id}`, { method: 'DELETE' });
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
        <template v-if="canManageKeywords">
          <el-tab-pane v-for="item in tabs" :key="item.key" :name="item.key" :label="item.label" />
        </template>
        <el-tab-pane v-if="canManageKeywords" name="feedback-supervisors" label="反馈督办人配置" />
        <el-tab-pane name="report-notification" label="报告通知配置" />
      </el-tabs>
      <PptReportNotificationConfig v-if="active === 'report-notification'" />
      <PptFeedbackSupervisorConfig v-else-if="canManageKeywords && active === 'feedback-supervisors'" />
      <el-config-provider v-else-if="canManageKeywords" size="small">
        <form class="config-filters" @submit.prevent="search">
          <PptListFilterBar>
            <el-input v-model="keyword" class="ppt-filter-search" :prefix-icon="Search" clearable aria-label="搜索配置"
              :placeholder="active === 'section' ? '搜索页面标题或所属板块' : active === 'board' ? '搜索板块或负责人' : '搜索匹配关键词或部门'" />
            <el-select v-if="active === 'section'" v-model="mode" class="ppt-filter-select" clearable
              placeholder="全部识别范围" aria-label="识别范围">
              <el-option v-for="(label, key) in modes" :key="key" :label="label" :value="key" />
            </el-select>
            <el-button native-type="submit">筛选</el-button>
            <el-button type="primary" @click="active === 'section' ? editTitle() : active === 'board' ? editBoard() : edit()">新增</el-button>
          </PptListFilterBar>
        </form>
        <el-table v-if="active === 'section'" v-loading="loading" :data="titleRows" row-key="name">
          <el-table-column prop="name" label="页面标题" min-width="124" show-overflow-tooltip />
          <el-table-column label="识别范围" min-width="80">
            <template #default="{ row }">{{ modes[row.scope?.target ?? 'FULL'] ?? modes.FULL }}</template>
          </el-table-column>
          <el-table-column label="区域限制" width="108" align="center">
            <template #default="{ row }">
              <span v-if="row.scope?.target === 'UPPER_TABLE_ONLY'">页高前 {{ (row.scope.upperLimitRatio ?? 0.5) * 100 }}% · 第 {{ row.scope.firstRow ?? 1 }} 行至 {{ row.scope.lastRow ?? '末行' }}</span>
              <span v-else aria-label="无区域限制">—</span>
            </template>
          </el-table-column>
          <el-table-column label="所属板块" min-width="76" show-overflow-tooltip>
            <template #default="{ row }">{{ row.section?.target }}</template>
          </el-table-column>
          <el-table-column label="板块联系人" min-width="112" show-overflow-tooltip>
            <template #default="{ row }">{{ row.contact ? contactLabel(row.contact.target, row.ownerName) : '—' }}</template>
          </el-table-column>
          <el-table-column label="新增时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(titleCreated(row)) }}</template>
          </el-table-column>
          <el-table-column label="编辑时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(titleUpdated(row) ?? titleCreated(row)) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="224" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="editTitle(row)">编辑</el-button>
                <el-button link type="primary" @click="openScanRules(row.name)">扫描规则</el-button>
                <el-button link type="primary" @click="openHandlers(row.name)">设置经办人</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
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
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="editBoard(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-table v-else v-loading="loading" :data="rows" row-key="id">
          <el-table-column prop="name" label="匹配关键词" min-width="180" />
          <el-table-column prop="target" label="部门" min-width="160" />
          <el-table-column label="新增时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="编辑时间" width="168" class-name="config-date">
            <template #default="{ row }">{{ dateText(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="edit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" />
      </el-config-provider>
    </el-card>
    <el-dialog v-model="rulesOpen" :title="`${rulesTitle} · 关键词扫描规则`" width="min(760px, 94vw)"
      append-to-body :close-on-click-modal="false" :show-close="!rulesLoading" :close-on-press-escape="!rulesLoading">
      <p>从整页正文和表格中匹配关键词，不依赖页码。每行一个关键词，全部包含才命中；未命中使用标题默认范围，多条命中需在上传时确认。</p>
      <div v-loading="rulesLoading">
        <el-card v-for="(rule, index) in scanRules" :key="index" shadow="never" style="margin-bottom: 12px">
          <el-form label-position="top" :disabled="rulesLoading">
            <el-form-item label="规则名称" required><el-input v-model="rule.name" maxlength="255" /></el-form-item>
            <el-form-item label="特征关键词（每行一个，全部命中）" required>
              <el-input v-model="rule.keywords" type="textarea" :rows="3" maxlength="8192" />
            </el-form-item>
            <el-form-item label="扫描方式" required><el-select v-model="rule.mode">
              <el-option v-for="(label, key) in modes" :key="key" :label="label" :value="key" />
            </el-select></el-form-item>
            <template v-if="rule.mode === 'UPPER_TABLE_ONLY'">
              <el-form-item label="表格顶边分界线比例（默认 0.5）"><el-input-number v-model="rule.upperLimitRatio" :min="0.01" :max="1" :step="0.05" /></el-form-item>
              <el-form-item label="起始行（默认 1）"><el-input-number v-model="rule.firstRow" :min="1" /></el-form-item>
              <el-form-item label="结束行（留空到末行）"><el-input-number v-model="rule.lastRow" :min="1" /></el-form-item>
            </template>
            <el-button type="danger" link @click="scanRules.splice(index, 1)">删除规则</el-button>
          </el-form>
        </el-card>
        <el-button :disabled="rulesLoading || !rulesReady || scanRules.length >= 50"
          @click="scanRules.push({ name: '', keywords: '', mode: 'FULL' })">新增规则</el-button>
      </div>
      <template #footer>
        <el-button :disabled="rulesLoading" @click="rulesOpen = false">取消</el-button>
        <el-button type="primary" :loading="rulesLoading" :disabled="!rulesReady" @click="saveScanRules">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="editorOpen"
      :title="`${(isTitleEditor ? titleForm.originalName !== undefined : editKind === 'board' ? !boardForm.create : form.id) ? '编辑' : '新增'}${tab.label}`"
      class="ppt-dialog"
      :width="isTitleEditor ? 'min(680px, 94vw)' : 'min(540px, 94vw)'"
      align-center append-to-body destroy-on-close
      :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false"
    >
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="editKind === 'section'">
          <el-form-item label="页面标题" required><el-input v-model="titleForm.name" maxlength="512" /></el-form-item>
          <el-form-item label="默认识别范围（关键词规则未命中时使用）" required>
            <el-select v-model="titleForm.scope">
              <el-option v-for="(label, key) in modes" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <template v-if="titleForm.scope === 'UPPER_TABLE_ONLY'">
            <el-form-item label="表格顶边分界线比例（默认 0.5）"><el-input-number v-model="titleForm.upperLimitRatio" :min="0.01" :max="1" :step="0.05" /></el-form-item>
            <el-form-item label="起始行（默认第 1 行）"><el-input-number v-model="titleForm.firstRow" :min="1" /></el-form-item>
            <el-form-item label="结束行（留空读到末行）"><el-input-number v-model="titleForm.lastRow" :min="1" /></el-form-item>
          </template>
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
            <el-select v-model="titleForm.handlerUserIds" multiple filterable remote :remote-method="searchPeople"
              :loading="peopleLoading" placeholder="搜索姓名或用户 ID，可稍后补充" @change="rememberTitleHandlers">
              <el-option v-for="[id, label] in titleHandlerOptions" :key="id" :value="id" :label="label" />
            </el-select>
          </el-form-item>
          <el-button v-if="sectionsReady && !sections.length" link type="primary" @click="editorOpen = false; active = 'board'">前往板块配置</el-button>
        </template>
        <template v-else-if="editKind === 'board'">
          <el-form-item label="板块名称" required><el-input v-model="boardForm.name" maxlength="512" /></el-form-item>
          <el-form-item label="板块联系人" required>
            <el-select :model-value="boardForm.owner ? boardForm.owner.split(',') : []" multiple filterable remote clearable
              :remote-method="searchPeople" :loading="peopleLoading" placeholder="搜索并选择一位或多位联系人"
              @update:model-value="selectBoardOwners">
              <el-option v-for="[id, name] in boardOwnerOptions" :key="id" :value="id" :label="contactLabel(id, name)" />
            </el-select>
            <small>材料内容的咨询对象。</small>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="匹配关键词" required><el-input v-model="form.name" maxlength="512" /></el-form-item>
          <el-form-item label="部门" required><el-input v-model="form.target" maxlength="2048" /></el-form-item>
        </template>
        <div class="editor-actions">
          <el-button :disabled="saving" @click="editorOpen = false">取消</el-button>
          <el-button native-type="submit" type="primary" :loading="saving">保存</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.config-page { padding: clamp(12px, 1.5vw, 20px); }
h2 { margin: 0 0 14px; font-size: 22px; color: #142441; }
.config-page :deep(.el-card) { border: 1px solid #dfe6f0; border-radius: 14px; box-shadow: 0 4px 16px #22375c0a; }
.config-page :deep(.config-card-body) { padding: 12px; }
.config-page :deep(.el-tabs__header) { margin-bottom: 10px; }
.config-page :deep(.el-tabs__item) { height: 32px; font-size: 13px; }
.config-page :deep(.el-table .el-table__cell) { padding-block: 6px; }
.config-page :deep(.el-table .cell) { padding-inline: 8px; }
.config-page :deep(.config-date .cell) { white-space: nowrap; font-variant-numeric: tabular-nums; }
.config-filters { --ppt-filter-width: 144px; margin-bottom: 10px; }
.row-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.row-actions .el-button { flex-shrink: 0; margin: 0; }
.el-pagination { margin-top: 10px; justify-content: flex-end; flex-wrap: wrap; }
.editor-actions { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #e3eaf4; }
</style>
