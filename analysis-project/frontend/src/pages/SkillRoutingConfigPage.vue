<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listSkillRouting, listSkillTags, saveSkillRouting, saveSkillTag, setSkillRoutingActive } from '../api/skillRouting';
import type { ToolRoutingTag } from '../types/toolRouting';
import type { SkillRoutingInput, SkillRoutingMetadata } from '../types/skillRouting';
import { DIRECT_EXEC_DIMENSION_KEYS } from '../types/skillRouting';
import { listDimensionKeys } from '../api/dimensionKey';
import { createBypassKeyword, deleteBypassKeyword, listBypassKeywords, listKeywordPriorities, reloadBypassKeywords, replaceKeywordPriorities, updateBypassKeyword } from '../api/skillRetrieval';
import type { SkillBypassKeywordRow, SkillKeywordPriorityRow } from '../api/skillRetrieval';
import { canEditConfig, isAdmin } from '../utils/auth';

type SkillTagType = 'DOMAIN' | 'TOPIC';

const rows = ref<SkillRoutingMetadata[]>([]);
const loading = ref(false);
const keyword = ref('');
const activeFilter = ref<string>('');
const currentPage = ref(1); const pageSize = ref(100); const total = ref(0);
// 我的/全部 范围切换: 管理员默认'全部', 普通用户默认'我的', 后端按 creator = 当前用户过滤
const scope = ref<'mine' | 'all'>(isAdmin() ? 'all' : 'mine');
const dialogVisible = ref(false);
const saving = ref(false);
const current = ref<SkillRoutingMetadata | null>(null);
const form = ref<SkillRoutingInput>(emptyInput());
const keywordsText = ref('');
const level1Text = ref('');
const level2Text = ref('');
const level3Text = ref('');
// 直接执行配置: script_id + param_mapping (key=脚本参数名, value=维度键)
const paramMappingRows = ref<{ param: string; dimKey: string }[]>([]);
// 参数映射维度键下拉: 动态加载维度键字典 (内置 8 键 + 用户新增键), 失败回退静态枚举
const dimensionKeyOptions = ref(DIRECT_EXEC_DIMENSION_KEYS.map(dk => ({ key: dk.key, label: dk.label })));
onMounted(async () => {
  try {
    const keys = await listDimensionKeys();
    if (keys.length) {
      const options = keys.filter(k => k.enabled)
        .map(k => ({ key: k.key, label: k.builtin ? k.label : `${k.label} (${k.key})` }));
      // timeRange 保留键透出 start/end 后缀形态 (后端 valuesFor/保存校验同源支持)
      if (keys.some(k => k.enabled && k.key === 'timeRange')) {
        options.push({ key: 'timeRange.start', label: '时间范围-开始 (timeRange.start)' });
        options.push({ key: 'timeRange.end', label: '时间范围-结束 (timeRange.end)' });
      }
      dimensionKeyOptions.value = options;
    }
  } catch { /* 字典接口不可用时回退静态枚举 */ }
});
const domainTags = ref<ToolRoutingTag[]>([]);
const topicTags = ref<ToolRoutingTag[]>([]);
const tagDialogVisible = ref(false);
const tagSaving = ref(false);
const tagType = ref<SkillTagType>('DOMAIN');
const tagName = ref('');
const tagDescription = ref('');
const currentUserId = localStorage.getItem('skill-user-id') || 'demo-user';
const router = useRouter();
// 生产环境 (app.env.production=true) 下非管理员只读, 即使是本人创建的条目
const canEdit = (row: SkillRoutingMetadata) => canEditConfig() && (!!row.creator && row.creator === currentUserId || isAdmin());

// ==================== 检索配置: 旁路词 (§8.2, 仅管理员) ====================
const bypassRows = ref<SkillBypassKeywordRow[]>([]);
const bypassLoading = ref(false);
const bypassDialogVisible = ref(false);
const bypassSaving = ref(false);
const bypassEditingId = ref<number | null>(null);
const bypassForm = ref({ groupName: '', keyword: '', enabled: true, remark: '' });

async function loadBypass() {
  bypassLoading.value = true;
  try { bypassRows.value = await listBypassKeywords(); }
  catch (e: any) { ElMessage.error(e.message || '加载旁路词失败'); }
  finally { bypassLoading.value = false; }
}
function openBypassCreate() {
  bypassEditingId.value = null;
  bypassForm.value = { groupName: '', keyword: '', enabled: true, remark: '' };
  bypassDialogVisible.value = true;
}
function openBypassEdit(row: SkillBypassKeywordRow) {
  bypassEditingId.value = row.id;
  bypassForm.value = { groupName: row.groupName, keyword: row.keyword, enabled: row.enabled, remark: row.remark || '' };
  bypassDialogVisible.value = true;
}
async function saveBypass() {
  if (!bypassForm.value.keyword.trim()) return ElMessage.warning('请输入旁路词');
  bypassSaving.value = true;
  try {
    const input = { ...bypassForm.value, keyword: bypassForm.value.keyword.trim() };
    if (bypassEditingId.value == null) await createBypassKeyword(input);
    else await updateBypassKeyword(bypassEditingId.value, input);
    bypassDialogVisible.value = false;
    await loadBypass();
    ElMessage.success('旁路词已保存');
  } catch (e: any) { ElMessage.error(e.message || '保存失败'); }
  finally { bypassSaving.value = false; }
}
async function removeBypass(row: SkillBypassKeywordRow) {
  try {
    await ElMessageBox.confirm(`确认停用旁路词「${row.keyword}」？（软删，可重新启用）`, '删除', { type: 'warning' });
  } catch { return; }
  try { await deleteBypassKeyword(row.id); await loadBypass(); ElMessage.success('已停用'); }
  catch (e: any) { ElMessage.error(e.message || '删除失败'); }
}
async function reloadBypass() {
  try { await reloadBypassKeywords(); ElMessage.success('快照已刷新'); }
  catch (e: any) { ElMessage.error(e.message || '刷新失败'); }
}

// ==================== 检索配置: 关键词优先级编辑 (§8.1) ====================
// 每行 = 一条配置行（一级词组 | 二级词组 | 三级词组, 格内顿号分隔）; ☰ 拖行 = 行优先级。
// 存储: parent_keyword 存行号 R1/R2…仅用于还原行, 匹配/排序只看 keyword + sort_order。
interface PriorityRow { l1: string; l2: string; l3: string }
const priorityRows = ref<PriorityRow[]>([]);
const priorityLoading = ref(false);
const prioritySaving = ref(false);
const dragRowFrom = ref(-1);

function onTabChange(name: string | number) {
  if (String(name) === 'retrieval') onRetrievalTabEnter();
}
function onRetrievalTabEnter() {
  loadBypass();
  loadPriorityRows();
}

async function loadPriorityRows() {
  priorityLoading.value = true;
  try {
    const list: SkillKeywordPriorityRow[] = await listKeywordPriorities();
    const order: string[] = [];
    const buckets = new Map<string, { l1: string[]; l2: string[]; l3: string[] }>();
    const ensure = (gid: string) => {
      let b = buckets.get(gid);
      if (!b) {
        b = { l1: [], l2: [], l3: [] };
        buckets.set(gid, b);
        order.push(gid);
      }
      return b;
    };
    for (const r of list.filter(r => r.level === 1)) {
      if (r.keyword) ensure(r.parentKeyword || '').l1.push(r.keyword);
    }
    for (const r of list.filter(r => r.level === 2)) {
      if (r.keyword) ensure(r.parentKeyword || '').l2.push(r.keyword);
    }
    for (const r of list.filter(r => r.level === 3)) {
      if (r.keyword) ensure(r.parentKeyword || '').l3.push(r.keyword);
    }
    priorityRows.value = order
      .map(gid => {
        const b = buckets.get(gid)!;
        return { l1: b.l1.join('、'), l2: b.l2.join('、'), l3: b.l3.join('、') };
      })
      .filter(r => r.l1 || r.l2 || r.l3);
  } catch (e: any) { ElMessage.error(e.message || '加载关键词优先级失败'); }
  finally { priorityLoading.value = false; }
}

function flattenPriority(): SkillKeywordPriorityRow[] {
  const out: SkillKeywordPriorityRow[] = [];
  const seen = new Set<string>();
  const emit = (level: number, gid: string, text: string) => {
    for (const w of text.split(/[、，,]/).map(s => s.trim()).filter(Boolean)) {
      const key = `${level}|${gid}|${w}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ id: null, level, parentKeyword: gid, keyword: w, sortOrder: null });
    }
  };
  priorityRows.value.forEach((r, i) => emit(1, `R${i + 1}`, r.l1));
  priorityRows.value.forEach((r, i) => emit(2, `R${i + 1}`, r.l2));
  priorityRows.value.forEach((r, i) => emit(3, `R${i + 1}`, r.l3));
  return out;
}

function movePriorityRow(from: number, to: number) {
  if (from === to || from < 0 || to < 0 || from >= priorityRows.value.length || to >= priorityRows.value.length) return;
  const [item] = priorityRows.value.splice(from, 1);
  priorityRows.value.splice(to, 0, item);
}
function onPriorityRowDrop(to: number) {
  if (dragRowFrom.value >= 0) movePriorityRow(dragRowFrom.value, to);
  dragRowFrom.value = -1;
}
function addPriorityRow() {
  priorityRows.value.push({ l1: '', l2: '', l3: '' });
}
function addPriorityRowBelow(row: number) {
  priorityRows.value.splice(row + 1, 0, { l1: '', l2: '', l3: '' });
}
async function removePriorityRow(row: number) {
  try {
    await ElMessageBox.confirm('确认删除该行？', '删除', { type: 'warning' });
  } catch { return; }
  priorityRows.value.splice(row, 1);
}
async function savePriority() {
  if (priorityRows.value.some(r => !r.l1.trim() && !r.l2.trim() && !r.l3.trim())) {
    return ElMessage.warning('存在空白行，请填写或删除');
  }
  prioritySaving.value = true;
  try {
    await replaceKeywordPriorities(flattenPriority());
    ElMessage.success('优先级已保存');
    await loadPriorityRows();
  } catch (e: any) { ElMessage.error(e.message || '保存失败'); }
  finally { prioritySaving.value = false; }
}

const domainOptions = computed(() => [...new Set([...domainTags.value.map(tag => tag.tagName), ...form.value.domainTags])]);

function emptyInput(): SkillRoutingInput {
  return { shortSummary: '', keywords: [], domainTags: [], topicTags: [], active: true, level1Keywords: [], level2Keywords: [], level3Keywords: [], scriptId: null, paramMapping: {} };
}
function tags(values: string[]): string { return (values || []).join(', '); }
// param_mapping 兜底: 后端个别路径可能回传 JSON 字符串, Object.entries(字符串) 会按字符下标炸开
function normalizeParamMapping(value: unknown): Record<string, string> {
  if (typeof value === 'string') {
    try { return JSON.parse(value) || {}; } catch { return {}; }
  }
  return (value as Record<string, string>) || {};
}
// 括号感知切分: 正则量词 {1,3} 内的逗号不作为分隔符
function split(value: string): string[] {
  const result: string[] = [];
  let buffer = ''; let braceDepth = 0;
  for (const ch of value) {
    if (ch === '{') braceDepth++;
    if (ch === '}') braceDepth = Math.max(0, braceDepth - 1);
    if (braceDepth === 0 && /[,，、\r\n]/.test(ch)) {
      if (buffer.trim()) result.push(buffer.trim());
      buffer = '';
    } else buffer += ch;
  }
  if (buffer.trim()) result.push(buffer.trim());
  return result;
}
function openEdit(row: SkillRoutingMetadata) {
  current.value = row;
  form.value = {
    shortSummary: row.shortSummary || '', keywords: [...row.keywords], domainTags: [...row.domainTags],
    topicTags: [...row.topicTags], active: row.active,
    level1Keywords: [...(row.level1Keywords || [])], level2Keywords: [...(row.level2Keywords || [])],
    level3Keywords: [...(row.level3Keywords || [])],
    scriptId: row.scriptId || null,
    paramMapping: { ...normalizeParamMapping(row.paramMapping) },
  };
  paramMappingRows.value = Object.entries(normalizeParamMapping(row.paramMapping)).map(([param, dimKey]) => ({ param, dimKey }));
  keywordsText.value = tags(form.value.keywords);
  level1Text.value = tags(form.value.level1Keywords);
  level2Text.value = tags(form.value.level2Keywords);
  level3Text.value = tags(form.value.level3Keywords);
  dialogVisible.value = true;
}
// ==================== 查看弹窗 (所有人可见, 只读) ====================
const viewVisible = ref(false);
const viewRow = ref<SkillRoutingMetadata | null>(null);
function openView(row: SkillRoutingMetadata) {
  viewRow.value = row;
  viewVisible.value = true;
}
async function load() {
  loading.value = true;
  try {
    const [skills, domains, topics] = await Promise.all([
      listSkillRouting(keyword.value || undefined,
      activeFilter.value === '' ? undefined : activeFilter.value === 'true',
      scope.value === 'mine', currentPage.value, pageSize.value),
      listSkillTags('DOMAIN'), listSkillTags('TOPIC'),
    ]);
    rows.value = skills.items; total.value = skills.total;
    domainTags.value = domains;
    topicTags.value = topics;
  }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
}
function changePage(page: number) { currentPage.value = page; load(); }
function changePageSize(size: number) { pageSize.value = size; currentPage.value = 1; load(); }
function openTag(type: SkillTagType) {
  tagType.value = type;
  tagName.value = '';
  tagDescription.value = '';
  tagDialogVisible.value = true;
}
async function createTag() {
  if (!tagName.value.trim()) return ElMessage.warning('请输入标签名称');
  tagSaving.value = true;
  try {
    await saveSkillTag(tagType.value, tagName.value.trim(), tagDescription.value.trim());
    tagDialogVisible.value = false;
    await load();
    ElMessage.success('标签已保存');
  } catch (e: any) { ElMessage.error(e.message || '保存失败'); }
  finally { tagSaving.value = false; }
}
async function save() {
  if (!current.value) return;
  saving.value = true;
  try {
    form.value.keywords = split(keywordsText.value);
    form.value.level1Keywords = split(level1Text.value);
    form.value.level2Keywords = split(level2Text.value);
    form.value.level3Keywords = split(level3Text.value);
    const mapping: Record<string, string> = {};
    for (const r of paramMappingRows.value) {
      if (r.param.trim() && r.dimKey) mapping[r.param.trim()] = r.dimKey;
    }
    form.value.paramMapping = mapping;
    const result = await saveSkillRouting(current.value.skillName, form.value);
    const index = rows.value.findIndex(r => r.skillName === result.skillName);
    if (index >= 0) rows.value[index] = { ...rows.value[index], ...result, configured: true };
    dialogVisible.value = false;
    ElMessage.success('保存成功');
  } catch (e: any) { ElMessageBox.alert(e.message || '保存失败', '操作失败', { type: 'error' }); }
  finally { saving.value = false; }
}
async function toggle(row: SkillRoutingMetadata) {
  const next = !row.active;
  try { const result = await setSkillRoutingActive(row.skillName, next); Object.assign(row, result); ElMessage.success(next ? '已启用' : '已停用'); }
  catch (e: any) { ElMessage.error(e.message || '操作失败'); }
}
watch([keyword, activeFilter, scope], () => { currentPage.value = 1; load(); });
load();
</script>

<template>
  <div class="page">
    <div class="header">
      <h2>Skill 配置</h2>
      <el-input v-model="keyword" placeholder="搜索名称 / 描述 / 创建人" clearable size="small" style="width: 240px" />
      <el-select v-model="activeFilter" placeholder="全部状态" clearable size="small" style="width: 120px">
        <el-option label="全部" value="" /><el-option label="已启用" value="true" /><el-option label="已停用" value="false" />
      </el-select>
      <span class="hint">仅配置路由元数据，Skill 正文请在 Skill 广场维护</span>
      <el-radio-group v-model="scope" size="small">
        <el-radio-button label="mine">我的</el-radio-button>
        <el-radio-button label="all">全部</el-radio-button>
      </el-radio-group>
    </div>
    <!-- element-plus tabs 没有 tab-enter 事件, 检索配置的数据加载挂在 el-tabs 的 tab-change 上 -->
    <el-tabs @tab-change="onTabChange">
      <el-tab-pane label="Skill 配置">
    <el-table :data="rows" v-loading="loading" stripe border size="small">
      <el-table-column prop="skillName" label="Skill 名称" width="260" show-overflow-tooltip />
      <el-table-column prop="description" label="已有描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="领域标签" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.domainTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="一级关键词" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.level1Keywords?.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="二级关键词" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.level2Keywords?.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="三级关键词" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.level3Keywords?.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="关键词" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.keywords.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="脚本 ID" width="160" show-overflow-tooltip><template #default="{ row }">{{ row.scriptId || '-' }}</template></el-table-column>
      <el-table-column label="参数映射" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ Object.entries(normalizeParamMapping(row.paramMapping)).map(([k, v]) => `${k}→${v}`).join(', ') || '-' }}</template></el-table-column>
      <el-table-column prop="creator" label="创建人" width="130" show-overflow-tooltip><template #default="{ row }">{{ row.creator || '-' }}</template></el-table-column>
      <el-table-column label="状态" width="80" align="center" fixed="right"><template #default="{ row }"><el-switch v-if="canEdit(row)" :model-value="row.active" size="small" @change="toggle(row)" /><span v-else>{{ row.active ? '已启用' : '已停用' }}</span></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><!-- 查看所有人可见; 配置仅本人 --><el-button size="small" @click="openView(row)">查看</el-button><el-button v-if="canEdit(row)" size="small" @click="openEdit(row)">配置</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changePageSize" />
      </el-tab-pane>
      <!-- 标签词典仅管理员可见/可新增 (数据加载保留, 配置弹窗下拉依赖词典接口) -->
      <el-tab-pane v-if="isAdmin()" label="标签词典">
        <div class="dictionary">
          <section><div class="section-head"><h3>领域字典</h3><el-button size="small" @click="openTag('DOMAIN')">新增</el-button></div><el-tag v-for="tag in domainTags" :key="tag.tagName" class="tag" type="success">{{ tag.tagName }}</el-tag><span v-if="!domainTags.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>主题词典</h3><el-button size="small" @click="openTag('TOPIC')">新增</el-button></div><el-tag v-for="tag in topicTags" :key="tag.tagName" class="tag" type="warning">{{ tag.tagName }}</el-tag><span v-if="!topicTags.length" class="empty">暂无标签</span></section>
        </div>
      </el-tab-pane>
      <!-- 检索旁路词 (§8.2): 命中即本轮 skill 检索置空, LLM 直接走工具层; 仅管理员 -->
      <el-tab-pane v-if="isAdmin()" name="retrieval" label="检索配置" lazy>
        <div class="section-head">
          <h3>检索旁路词</h3>
          <div>
            <el-button size="small" @click="reloadBypass">刷新快照</el-button>
            <el-button size="small" type="primary" @click="openBypassCreate">新增旁路词</el-button>
          </div>
        </div>
        <div class="mapping-hint" style="margin-bottom: 8px">
          问题命中任一启用旁路词 → 本轮 skill 检索置空，LLM 直接走工具层（分析类/表格类/质量板块等偏分析问法不再误入围 skill）。
          问题显式点名任一启用 skill 名时不旁路。词条支持正则（如 <code>(?&lt;!不包含)(?&lt;!不)(?&lt;!未)遗漏</code> 排除否定语境，
          <code>(?&lt;!数据)分析</code> 排除「数据分析」）；非法正则按字面包含处理。旁路词命中不影响 skill 直接执行参数提取。
        </div>
        <el-table :data="bypassRows" v-loading="bypassLoading" stripe border size="small">
          <el-table-column prop="groupName" label="分组" width="140" />
          <el-table-column prop="keyword" label="旁路词" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="90" align="center"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" @click="openBypassEdit(row)">编辑</el-button>
              <el-button size="small" type="danger" text @click="removeBypass(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="section-head" style="margin-top: 16px">
          <h3>关键词检索优先级</h3>
          <div>
            <el-button size="small" @click="addPriorityRow">+ 新增行</el-button>
            <el-button size="small" type="primary" :loading="prioritySaving" @click="savePriority">保存排序</el-button>
            <el-button size="small" @click="loadPriorityRows">重置</el-button>
          </div>
        </div>
        <div class="mapping-hint">
          每行 = 一条配置行：一级词组 | 二级词组 | 三级词组（格内多个词用顿号分隔，支持正则词形）。
          ☰ 拖行 = 行优先级（越上越高）；同一二级组可分多行挂不同三级组。层间漏斗 L3 &gt; L2 &gt; L1，无需给单个 skill 配分数。
        </div>
        <el-table :data="priorityRows" v-loading="priorityLoading" border size="small" style="margin-top: 8px" empty-text="暂无配置：点「+ 新增行」开始">
          <el-table-column label="拖曳" width="56" align="center">
            <template #default="{ $index }">
              <span class="grip row-grip" :draggable="true"
                    @dragstart="dragRowFrom = $index" @dragover.prevent @drop.prevent="onPriorityRowDrop($index)">☰</span>
            </template>
          </el-table-column>
          <el-table-column label="一级关键词" min-width="220">
            <template #default="{ row }">
              <el-input v-model="row.l1" size="small" placeholder="如 问题、缺陷、A号（顿号分隔，支持正则）" />
            </template>
          </el-table-column>
          <el-table-column label="二级关键词" min-width="200">
            <template #default="{ row }">
              <el-input v-model="row.l2" size="small" placeholder="如 未闭环、开口、在途（顿号分隔）" />
            </template>
          </el-table-column>
          <el-table-column label="三级关键词" min-width="200">
            <template #default="{ row }">
              <el-input v-model="row.l3" size="small" placeholder="如 严重、超7天（顿号分隔）" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center">
            <template #default="{ $index }">
              <div class="op-col">
                <el-button size="small" text type="primary" @click="addPriorityRowBelow($index)">新增行</el-button>
                <el-button size="small" type="danger" text @click="removePriorityRow($index)">删除行</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogVisible" :title="`配置 Skill: ${current?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="Skill 名称"><el-input :model-value="current?.skillName" disabled /></el-form-item>
        <el-form-item label="已有描述"><el-input :model-value="current?.description || '-'" type="textarea" :rows="3" disabled /></el-form-item>
        <el-form-item label="领域标签"><el-select v-model="form.domainTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in domainOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="一级关键词"><el-input v-model="level1Text" placeholder="最宽泛的入口词，如 问题；多个用顿号分隔，支持正则" /></el-form-item>
        <el-form-item label="二级关键词"><el-input v-model="level2Text" placeholder="在一级下钻，如 未闭环；支持正则如 未闭.{0,2}环" /></el-form-item>
        <el-form-item label="三级关键词"><el-input v-model="level3Text" placeholder="最具体一层，如 严重；支持正则" /></el-form-item>
        <el-form-item label="关键词"><el-input v-model="keywordsText" placeholder="叶层精确词，如 达标率、打分率；逗号、顿号或换行分隔，支持正则" /></el-form-item>
        <el-form-item label="脚本 ID"><el-input v-model="form.scriptId" placeholder="script_registry 脚本 ID，留空 = 不参与直接执行" maxlength="128" /></el-form-item>
        <el-form-item label="参数映射">
          <div class="param-mapping">
            <div v-for="(row, idx) in paramMappingRows" :key="idx" class="param-mapping-row">
              <el-input v-model="row.param" placeholder="脚本参数名，如 dept" maxlength="128" style="width: 180px" />
              <span class="arrow">→</span>
              <el-select v-model="row.dimKey" placeholder="维度键" style="width: 200px">
                <el-option v-for="dk in dimensionKeyOptions" :key="dk.key" :label="dk.label" :value="dk.key" />
              </el-select>
              <el-button size="small" text type="danger" @click="paramMappingRows.splice(idx, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="paramMappingRows.push({ param: '', dimKey: '' })">+ 添加映射</el-button>
            <div class="mapping-hint">配置后问题命中该 Skill 时绕过 LLM 直接执行脚本；参数名需与脚本 params_schema 一致</div>
          </div>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <!-- 查看弹窗 (只读, 所有人可见) -->
    <el-dialog v-model="viewVisible" :title="`查看 Skill: ${viewRow?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small" :disabled="true">
        <el-form-item label="Skill 名称"><el-input :model-value="viewRow?.skillName" /></el-form-item>
        <el-form-item label="已有描述"><el-input :model-value="viewRow?.description || '-'" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="领域标签"><el-input :model-value="viewRow?.domainTags.join('、') || '-'" /></el-form-item>
        <el-form-item label="一级关键词"><el-input :model-value="viewRow?.level1Keywords?.join('、') || '-'" /></el-form-item>
        <el-form-item label="二级关键词"><el-input :model-value="viewRow?.level2Keywords?.join('、') || '-'" /></el-form-item>
        <el-form-item label="三级关键词"><el-input :model-value="viewRow?.level3Keywords?.join('、') || '-'" /></el-form-item>
        <el-form-item label="关键词"><el-input :model-value="viewRow?.keywords.join('、') || '-'" /></el-form-item>
        <el-form-item label="脚本 ID"><el-input :model-value="viewRow?.scriptId || '-'" /></el-form-item>
        <el-form-item label="参数映射"><el-input :model-value="Object.entries(normalizeParamMapping(viewRow?.paramMapping)).map(([k, v]) => `${k}→${v}`).join(', ') || '-'" /></el-form-item>
        <el-form-item label="启用"><span>{{ viewRow?.active ? '已启用' : '已停用' }}</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="tagDialogVisible" :title="tagType === 'DOMAIN' ? '新增领域标签' : '新增业务主题'" width="480px">
      <el-form label-width="84px"><el-form-item label="标签名称" required><el-input v-model="tagName" maxlength="64" /></el-form-item><el-form-item label="说明"><el-input v-model="tagDescription" type="textarea" :rows="3" maxlength="500" /></el-form-item></el-form>
      <template #footer><el-button @click="tagDialogVisible = false">取消</el-button><el-button type="primary" :loading="tagSaving" @click="createTag">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.2rem; }
.hint { color: #64748b; font-size: 0.8rem; margin-left: auto; }
.muted { color: #94a3b8; }
.dictionary { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin-top: 4px; }
.dictionary section { background: #fff; border: 1px solid #e2e8f0; padding: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.section-head h3 { margin: 0; font-size: 1rem; }
.tag { margin: 0 8px 8px 0; }
.empty { color: #64748b; font-size: 0.8rem; }
.param-mapping { width: 100%; }
.param-mapping-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.param-mapping-row .arrow { color: #64748b; }
.mapping-hint { color: #94a3b8; font-size: 0.75rem; margin-top: 4px; }
.word-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.chip { display: inline-flex; align-items: center; padding: 1px 8px; border: 1px solid #cbd5e1; background: #f8fafc; }
.grip { color: #94a3b8; cursor: grab; }
.row-grip { font-size: 1rem; }
.op-col { display: flex; flex-direction: column; gap: 2px; align-items: center; }
.chip.l3 { font-size: 0.75rem; background: #f1f5f9; border-color: #e2e8f0; color: #475569; }
</style>
