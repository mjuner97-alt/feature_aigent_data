<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createSkillFlow, getSkillFlow, updateSkillFlow, validateSkillFlow } from '../api/skillFlow';
import { getSkill, listSkills } from '../api/skill';
import { listAllEnabledEntries, startDebug, cancelDebug, subscribeDebug, getEntry } from '../api/scriptRegistry';
import type { ParamSchemaItem, ScriptDebugRun, ScriptRegistryListItem } from '../types/scriptRegistry';
import { listMetrics } from '../api/skillDependencyMetric';
import type { SkillListItem } from '../types/skill';
import type { SkillDependencyMetric } from '../types/skillJob';
import type { SkillFlow, SkillFlowInput, SkillFlowNode } from '../types/skillFlow';
import { buildOutline, defaultOutlineNumbering, flattenOutline, outlineNumberingPrefixes, validateOutlineRows, type OutlineRow } from '../utils/reportOutline';
import FlowNodeCard from './FlowNodeCard.vue';
import ScheduleRulesEditor from './ScheduleRulesEditor.vue';

const props = withDefaults(defineProps<{ open: boolean; editId: number | null; knownFlows: SkillFlow[]; page?: boolean }>(), { page: false });
const emit = defineEmits<{ (e: 'update:open', open: boolean): void; (e: 'saved'): void }>();
/** 编辑中的流程当前是否为公开状态(公开流程保存修改后会自动退出公开,需重新联系开发人员开通)。 */
const wasPublic = ref(false);
/** 加载时的触发关键词快照,保存时对比是否改动过关键词。 */
const originalKeywords = ref<string[]>([]);

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const skills = ref<SkillListItem[]>([]);
const scripts = ref<ScriptRegistryListItem[]>([]);
const metrics = ref<SkillDependencyMetric[]>([]);
const skillLoading = ref(false);
const scriptLoading = ref(false);
const metricLoading = ref(false);
let skillSearchSeq = 0;
let metricSearchSeq = 0;
const form = ref<SkillFlowInput>(emptyForm());
const outlineRows = ref<OutlineRow[]>([]);
const reportTitle = ref('');
/** 每行自动编号(与报告渲染同口径):一级 一、二、三;二级 1.1、1.2。 */
const outlineNumbers = computed(() => outlineNumberingPrefixes(outlineRows.value, form.value.reportOutline?.numbering || defaultOutlineNumbering()));
const baseline = ref('');
const isDirty = computed(() => JSON.stringify(form.value) !== baseline.value);
/** 拖拽排序:正在拖拽的卡片下标;dragover 时实时换位,drop/dragend 收尾。 */
const dragIndex = ref<number | null>(null);
function nextNodeKey(): string {
  const used = new Set(form.value.nodes.map(node => node.nodeKey));
  let index = form.value.nodes.length + 1;
  while (used.has(`node_${index}`)) index++;
  return `node_${index}`;
}

function emptyNode(nodeKey: string, sortOrder: number): SkillFlowNode {
  return { nodeKey, nodeName: '', nodeType: 'PYTHON', skillId: null, scriptId: null, scriptParams: {}, questionTemplate: '', metricIds: [], required: true, maxAttempts: 2, sortOrder };
}

function emptyForm(): SkillFlowInput {
  return { name: '', code: '', description: '', taskQuestion: '', summaryQuestionTemplate: '', enabled: true, scheduleRules: null, maxParallelism: 2, notifyEnabled: true, triggers: [], nodes: [], reportOutline: { numbering: {}, items: [] } };
}

const chineseStepLabels = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];
function stepLabel(index: number): string {
  return chineseStepLabels[index] || String(index + 1);
}

const isEdit = computed(() => props.editId != null);
const validationErrors = computed(() => {
  const errors: string[] = [];
  if (!form.value.name.trim()) errors.push('请填写流程名称');
  const sameName = props.knownFlows.find(flow => flow.id !== props.editId && flow.name.trim() === form.value.name.trim());
  if (sameName) errors.push(`流程名称「${form.value.name.trim()}」已存在`);
  if (!form.value.nodes.length) errors.push('至少配置一个 Skill 节点');
  const uniqueKeys = new Set<string>();
  form.value.nodes.forEach((node, index) => {
    const key = node.nodeKey.trim();
    if (!key) errors.push(`节点 ${index + 1} 缺少节点标识`);
    else if (uniqueKeys.has(key)) errors.push(`节点标识 ${key} 重复`);
    else uniqueKeys.add(key);
    if (node.nodeType === 'SKILL' || (!node.nodeType && node.skillId)) { if (!node.skillId) errors.push(`节点 ${key || index + 1} 未选择 Skill`); }
    else if (!node.scriptId?.trim()) errors.push(`节点 ${key || index + 1} 未选择 Python 脚本`);
    if (node.metricIds.length > 1) errors.push(`节点 ${key || index + 1} 只能依赖一个指标`);
  });
  errors.push(...validateOutlineRows(outlineRows.value, form.value.nodes.map(node => node.nodeKey.trim())));
  if (!form.value.triggers.length) errors.push('至少配置一个触发关键词');
  const seenKeywords = new Set<string>();
  form.value.triggers.forEach(trigger => {
    const keyword = trigger.keyword.trim().toLowerCase();
    if (!keyword) errors.push('触发关键词不能为空');
    else if (seenKeywords.has(keyword)) errors.push(`关键词 ${trigger.keyword} 重复`);
    else seenKeywords.add(keyword);
    const owner = props.knownFlows.find(flow => flow.id !== props.editId && flow.triggers.some(item => item.keyword.trim().toLowerCase() === keyword));
    if (owner) errors.push(`关键词 ${trigger.keyword} 已属于流程「${owner.name}」`);
  });
  return errors;
});

const preview = computed(() => form.value.nodes
    .map(node => node.nodeName?.trim() || node.scriptName || node.skillName || skills.value.find(item => item.id === node.skillId)?.name || '未配置节点')
    .join('、'));

function syncNodeSkillName(node: SkillFlowNode) {
  node.skillName = skills.value.find(skill => skill.id === node.skillId)?.name;
}

function setNodeMetric(node: SkillFlowNode, value: number | null) {
  node.metricIds = value ? [value] : [];
}

async function searchSkills(query: string) {
  const seq = ++skillSearchSeq;
  skillLoading.value = true;
  try {
    const result = await listSkills({ view: 'used', keyword: query.trim(), limit: 50 });
    if (seq === skillSearchSeq) {
      const selectedIds = new Set(form.value.nodes.map(node => node.skillId).filter((id): id is number => !!id));
      const retained = skills.value.filter(skill => selectedIds.has(skill.id) && !result.some(item => item.id === skill.id));
      skills.value = [...retained, ...result];
    }
  } catch {
    // Keep already selected options when a remote search fails. Dropping them
    // makes Element Plus fall back to displaying numeric skill IDs.
    if (seq === skillSearchSeq) {
      const selectedIds = new Set(form.value.nodes.map(node => node.skillId).filter((id): id is number => !!id));
      skills.value = skills.value.filter(skill => selectedIds.has(skill.id));
    }
  } finally {
    if (seq === skillSearchSeq) skillLoading.value = false;
  }
}

async function searchScripts(query = '') {
  scriptLoading.value = true;
  try { scripts.value = await listAllEnabledEntries(query); }
  catch { scripts.value = []; }
  finally { scriptLoading.value = false; }
}

/** 脚本参数定义缓存(键=注册表数字 id):选脚本后按 params_schema 渲染参数输入框,不再手写 JSON */
const scriptSchemas = ref<Record<number, ParamSchemaItem[]>>({});

async function ensureScriptSchema(scriptId: string | null | undefined) {
  if (!scriptId) return;
  const row = scripts.value.find(item => item.scriptId === scriptId);
  if (!row || scriptSchemas.value[row.id]) return;
  let items: ParamSchemaItem[] = [];
  try {
    const detail = await getEntry(row.id);
    const parsed = JSON.parse(detail.paramsSchema || '[]');
    if (Array.isArray(parsed)) items = parsed.filter((item: any) => item && typeof item.name === 'string');
  } catch { /* schema 解析失败按无参数处理,保存/试跑时由后端兜底校验 */ }
  scriptSchemas.value = { ...scriptSchemas.value, [row.id]: items };
}

function schemaFor(node: SkillFlowNode): ParamSchemaItem[] | null {
  const row = scripts.value.find(item => item.scriptId === node.scriptId);
  return row ? (scriptSchemas.value[row.id] ?? null) : null;
}

function onScriptSelected(node: SkillFlowNode) {
  node.scriptName = scripts.value.find(item => item.scriptId === node.scriptId)?.name;
  node.scriptParams = {};
  ensureScriptSchema(node.scriptId);
}

async function ensureSelectedSkills(skillIds: number[]) {
  const missing = [...new Set(skillIds)].filter(id => id > 0 && !skills.value.some(skill => skill.id === id));
  if (!missing.length) return;
  const loaded = await Promise.all(missing.map(async id => {
    // 本组件只用 id/name 做选项展示,Detail 里的多余字段不影响;直接按 ListItem 收敛类型
    try { return await getSkill(id) as unknown as SkillListItem; } catch { return null; }
  }));
  const additions = loaded.filter((skill): skill is SkillListItem => !!skill);
  if (additions.length) skills.value = [...additions, ...skills.value.filter(skill => !additions.some(item => item.id === skill.id))];
}

async function searchMetrics(query: string) {
  const seq = ++metricSearchSeq;
  metricLoading.value = true;
  try {
    const result = await listMetrics(query);
    if (seq === metricSearchSeq) metrics.value = result;
  } catch {
    if (seq === metricSearchSeq) metrics.value = [];
  } finally {
    if (seq === metricSearchSeq) metricLoading.value = false;
  }
}

function renumber() {
  form.value.nodes.forEach((node, order) => { node.sortOrder = order + 1; });
}

function addNode() {
  form.value.nodes.push(emptyNode(nextNodeKey(), form.value.nodes.length + 1));
}

function removeNode(index: number) {
  const [removed] = form.value.nodes.splice(index, 1);
  // 大纲里绑定了该节点的章节一并移除引用,避免残留失效 key
  outlineRows.value.forEach(row => {
    row.nodeKeys = row.nodeKeys.filter(key => key !== removed?.nodeKey);
  });
  renumber();
}

/** 节点标题(工具栏展示):名称 > 脚本名 > Skill 名 > 未配置。 */
function nodeTitle(node: SkillFlowNode): string {
  return node.nodeName?.trim() || node.scriptName || node.skillName || '未配置节点';
}

/** 未绑定到大纲章节的节点(留在执行节点区,保存前必须全部绑定)。 */
function isUnbound(node: SkillFlowNode): boolean {
  return !outlineRows.value.some(row => row.nodeKeys.includes(node.nodeKey));
}

const unboundNodes = computed(() => form.value.nodes.filter(node => isUnbound(node)));

/** 章节下内嵌的节点卡片(按章节内绑定顺序,可多个)。 */
function nodesForRow(row: OutlineRow): SkillFlowNode[] {
  return row.nodeKeys
      .map(key => form.value.nodes.find(node => node.nodeKey === key))
      .filter((node): node is SkillFlowNode => !!node);
}

/** 让 form.nodes 执行顺序跟随大纲文档顺序:已绑定节点按大纲序在前,未绑定节点殿后。 */
function syncNodesToOutline() {
  const bound: SkillFlowNode[] = [];
  outlineRows.value.forEach(row => row.nodeKeys.forEach(key => {
    const node = form.value.nodes.find(item => item.nodeKey === key);
    if (node && !bound.includes(node)) bound.push(node);
  }));
  form.value.nodes = [...bound, ...form.value.nodes.filter(node => isUnbound(node))];
  renumber();
}

/** 章节下添加一个全新 Python 节点并绑定到该章节(先写章节、章节下加节点)。 */
function addOutlineNode(row: OutlineRow) {
  const node = emptyNode(nextNodeKey(), form.value.nodes.length + 1);
  form.value.nodes.push(node);
  row.nodeKeys.push(node.nodeKey);
  syncNodesToOutline();
}

/** 把已有未绑定节点绑定到章节(追加,不影响章节内已有节点)。 */
function bindRowNode(row: OutlineRow, nodeKey: string) {
  if (nodeKey && !row.nodeKeys.includes(nodeKey)) {
    row.nodeKeys.push(nodeKey);
    syncNodesToOutline();
  }
}

/** 删除章节内节点，同时清理大纲绑定，避免删除后仍被校验为未绑定节点。 */
function removeChapterNode(row: OutlineRow, nodeKey: string) {
  row.nodeKeys = row.nodeKeys.filter(key => key !== nodeKey);
  form.value.nodes = form.value.nodes.filter(node => node.nodeKey !== nodeKey);
  outlineRows.value.forEach(item => { item.nodeKeys = item.nodeKeys.filter(key => key !== nodeKey); });
  renumber();
}

/** 章节内上移/下移节点。 */
function moveChapterNode(row: OutlineRow, nodeKey: string, direction: -1 | 1) {
  const index = row.nodeKeys.indexOf(nodeKey);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= row.nodeKeys.length) return;
  [row.nodeKeys[index], row.nodeKeys[target]] = [row.nodeKeys[target], row.nodeKeys[index]];
  syncNodesToOutline();
}

/** 章节内拖拽排序:dragover 目标卡片时把被拖节点插到它前面。 */
const dragNodeKey = ref<string | null>(null);

function chapterDragStart(nodeKey: string, event: DragEvent) {
  dragNodeKey.value = nodeKey;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', nodeKey);
  }
}

function chapterDragOver(row: OutlineRow, targetKey: string) {
  const from = dragNodeKey.value;
  if (!from || from === targetKey || !row.nodeKeys.includes(from)) return;
  const keys = row.nodeKeys.filter(key => key !== from);
  keys.splice(keys.indexOf(targetKey), 0, from);
  row.nodeKeys = keys;
  syncNodesToOutline();
}

function moveNode(index: number, direction: -1 | 1) {
  const destination = index + direction;
  if (destination < 0 || destination >= form.value.nodes.length) return;
  const nodes = form.value.nodes;
  [nodes[index], nodes[destination]] = [nodes[destination], nodes[index]];
  renumber();
}

/** 拖拽经过某张卡片时把被拖卡片实时换到该位置(所见即所得的排序预览)。 */
function onDragOver(index: number) {
  const from = dragIndex.value;
  if (from == null || from === index) return;
  const nodes = form.value.nodes;
  const [moved] = nodes.splice(from, 1);
  nodes.splice(index, 0, moved);
  dragIndex.value = index;
  renumber();
}

function onDragStart(index: number, event: DragEvent) {
  dragIndex.value = index;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', String(index));
  }
}

function finishDrag() {
  dragIndex.value = null;
  dragNodeKey.value = null;
}

/** 节点试跑:复用脚本注册页的调试运行接口,按节点选的脚本+参数 JSON 真实执行一次,提前验证参数。 */
const debugNodeKey = ref<string | null>(null);
const debugStarting = ref(false);
const debugRun = ref<ScriptDebugRun | null>(null);
let debugEvents: EventSource | null = null;
const DEBUG_TERMINAL = ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED'];

function debugInProgress(): boolean {
  return !!debugRun.value && !DEBUG_TERMINAL.includes(debugRun.value.status);
}

async function runNodeDebug(node: SkillFlowNode) {
  if (debugInProgress() || debugStarting.value) { ElMessage.warning('已有试跑在进行中，请先等待完成或停止'); return; }
  if (!node.scriptId?.trim()) { ElMessage.warning('请先选择 Python 脚本'); return; }
  // debug 接口要注册表数字 id,从已加载的脚本选项里按 scriptId 找
  const script = scripts.value.find(item => item.scriptId === node.scriptId);
  if (!script) { ElMessage.warning('脚本信息未加载，请重新选择脚本后再试跑'); return; }
  debugNodeKey.value = node.nodeKey;
  debugStarting.value = true;
  try {
    debugRun.value = await startDebug(script.id, node.scriptParams || {}, script.timeoutSeconds ?? 60);
    debugEvents?.close();
    debugEvents = subscribeDebug(debugRun.value.runId, {
      event: (event) => {
        if (!debugRun.value) return;
        debugRun.value.status = event.status;
        debugRun.value.exitCode = event.exitCode;
        debugRun.value.elapsedMs = event.elapsedMs;
        if (event.stdout) debugRun.value.stdout = event.stdout;
        if (event.stderr) debugRun.value.stderr = event.stderr;
      },
      error: () => { /* 终态由最后一个事件提供 */ },
      complete: () => {},
    });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启动试跑失败');
    debugNodeKey.value = null;
    debugRun.value = null;
  } finally {
    debugStarting.value = false;
  }
}

async function stopNodeDebug() {
  if (!debugInProgress()) return;
  try {
    await cancelDebug(debugRun.value!.runId);
    debugRun.value!.status = 'CANCELLED';
    debugEvents?.close();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停止试跑失败');
  }
}

function closeNodeDebug() {
  if (debugInProgress()) { ElMessage.warning('试跑还在进行中，请先停止'); return; }
  debugRun.value = null;
  debugNodeKey.value = null;
  debugEvents?.close();
}

onUnmounted(() => { debugEvents?.close(); });

function addTrigger() {
  form.value.triggers.push({ keyword: '', priority: 100, enabled: true });
}

function removeTrigger(index: number) {
  form.value.triggers.splice(index, 1);
}

async function loadOptions() {
  await Promise.all([searchScripts(''), searchMetrics('')]);
}

function normalizeFlow(flow: SkillFlow): SkillFlowInput {
  return {
    code: flow.code || '', name: flow.name || '', description: flow.description || '', taskQuestion: flow.taskQuestion || '',
    summaryQuestionTemplate: flow.summaryQuestionTemplate || '', enabled: flow.enabled !== false,
    scheduleRules: flow.scheduleRules ?? null,
    maxParallelism: 2, notifyEnabled: flow.notifyEnabled !== false,
    triggers: (flow.triggers || []).map(trigger => ({ ...trigger, enabled: trigger.enabled !== false })),
    nodes: (flow.nodes || []).map((node, index) => {
      let scriptParams: Record<string, unknown> = node.scriptParams || {};
      // The backend DTO exposes the persisted JSON as scriptParamsJson.
      // Decode it when reopening a flow so saved values are shown again.
      const persisted = (node as SkillFlowNode & { scriptParamsJson?: string }).scriptParamsJson;
      if (!node.scriptParams && persisted) {
        try { scriptParams = JSON.parse(persisted) || {}; } catch { scriptParams = {}; }
      }
      return { ...node, nodeType: node.nodeType || (node.scriptId ? 'PYTHON' : 'SKILL'), skillId: node.skillId ?? null, scriptId: node.scriptId ?? null, scriptParams, metricIds: (node.metricIds || []).slice(0, 1), required: node.required !== false, maxAttempts: 2, sortOrder: node.sortOrder || index + 1 };
    }),
    reportOutline: flow.reportOutline || { numbering: {}, items: [] },
  };
}

function syncOutlineRows() {
  outlineRows.value = flattenOutline(form.value.reportOutline);
  reportTitle.value = form.value.reportOutline?.title || '';
  // 旧流程可能只有节点没有大纲；给它们一个兼容章节，避免节点在新界面中不可见。
  if (!outlineRows.value.length && form.value.nodes.length) {
    outlineRows.value = [{ id: `outline_legacy_${Date.now()}`, title: '未配置章节', level: 1, nodeKeys: form.value.nodes.map(node => node.nodeKey) }];
  }
}

function addOutlineRow(after: OutlineRow | null, asChild = false) {
  const index = after ? outlineRows.value.indexOf(after) : outlineRows.value.length - 1;
  const level = after ? (asChild ? after.level + 1 : after.level) : 1;
  outlineRows.value.splice(index + 1, 0, { id: `outline_${Date.now()}_${Math.random().toString(16).slice(2)}`, title: '新章节', level, nodeKeys: [] });
}

function removeOutlineRow(row: OutlineRow) {
  const index = outlineRows.value.indexOf(row);
  if (index < 0) return;
  const level = row.level;
  let end = index + 1;
  while (end < outlineRows.value.length && outlineRows.value[end].level > level) end++;
  outlineRows.value.splice(index, end - index);
}

async function load() {
  loading.value = true;
  error.value = '';
  wasPublic.value = false;
  form.value = emptyForm();
  outlineRows.value = [];
  reportTitle.value = '';
  await loadOptions();
  if (props.editId != null) {
    try {
      const flow = await getSkillFlow(props.editId);
      await ensureSelectedSkills((flow.nodes || []).map(node => node.skillId ?? 0));
      wasPublic.value = flow.chatPublic === true;
      originalKeywords.value = (flow.triggers || []).map(trigger => trigger.keyword.trim().toLowerCase()).filter(Boolean);
    form.value = normalizeFlow(flow);
    (form.value.nodes || []).forEach(node => { if (node.scriptId) ensureScriptSchema(node.scriptId); });
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载流程失败';
    }
  }
  syncOutlineRows();
  baseline.value = JSON.stringify(form.value);
  loading.value = false;
}

/** 关键词集合相对加载时是否发生变化(增删改都算)。 */
function keywordsChanged(): boolean {
  const current = form.value.triggers.map(trigger => trigger.keyword.trim().toLowerCase()).filter(Boolean);
  const before = originalKeywords.value;
  return current.length !== before.length || current.some((keyword, index) => keyword !== before[index]);
}

async function save() {
  error.value = '';
  if (validationErrors.value.length) {
    error.value = validationErrors.value[0];
    await ElMessageBox.alert(validationErrors.value[0], '请检查填写内容', { type: 'warning' });
    return;
  }
  if (wasPublic.value && keywordsChanged()) {
    try {
      await ElMessageBox.confirm(
          '触发关键词已修改，保存后流程将自动退出公开状态，仅您自己可通过聊天触发；如需恢复公开请联系开发人员。是否继续保存？',
          '关键词已修改',
          { type: 'warning', confirmButtonText: '继续保存', cancelButtonText: '再检查一下' },
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    renumber();
    form.value.reportOutline = outlineRows.value.length
      ? buildOutline(outlineRows.value, form.value.reportOutline?.numbering || defaultOutlineNumbering(), reportTitle.value)
      : null;
    const saved = props.editId == null ? await createSkillFlow(form.value) : await updateSkillFlow(props.editId, form.value);
    await validateSkillFlow(saved.id);
    baseline.value = JSON.stringify(form.value);
    emit('saved');
    emit('update:open', false);
  } catch (e) {
    const message = e instanceof Error ? e.message : '保存流程失败';
    error.value = message;
    await ElMessageBox.alert(message, '保存失败', { type: 'error' });
  } finally {
    saving.value = false;
  }
}

watch(() => props.open, open => { if (open) load(); }, { immediate: true });
defineExpose({ isDirty });
</script>

<template>
  <Teleport to="body" :disabled="page">
    <div v-if="open" class="mask" :class="{ 'page-mode': page }" @click.self="!page && emit('update:open', false)">
      <section class="drawer" aria-label="长任务流程编辑器">
        <header class="drawer-header"><div><div class="eyebrow">长任务流程</div><h3>{{ isEdit ? '编辑长任务流程' : '创建长任务流程' }}</h3><p>配置触发条件与执行步骤，生成结构化汇总结果</p></div><button class="icon-button" title="关闭" aria-label="关闭" @click="emit('update:open', false)">×</button></header>
        <main class="drawer-body">
          <div v-if="loading" class="empty">加载中…</div>
          <template v-else>
            <section class="form-section wide section-card">
              <div class="section-heading"><h4>基本信息</h4></div>
              <label><span>流程名称 *</span><input v-model="form.name" placeholder="如 每日质量综合分析" /></label>
              <label><span>说明(非必填)</span><textarea v-model="form.description" rows="2" placeholder="说明该流程处理的业务问题" /></label>
              <div class="basic-row">
                <label class="toggle-row"><input v-model="form.enabled" type="checkbox" /><span>保存后启用流程</span></label>
              </div>
              <label><span>自动触发定时规则</span><ScheduleRulesEditor v-model="form.scheduleRules" /><small>所选星期内，依赖数据准备完成后立即自动触发；不选默认每天都执行</small></label>
            </section>

            <section class="form-section wide section-card">
              <div class="section-heading"><div><h4>触发关键词</h4><p>关键词在所有长任务流程中唯一；聊天只会触发您自己的流程（公开流程除外）。</p></div><button class="btn primary" @click="addTrigger">添加关键词</button></div>
              <div v-if="wasPublic" class="public-hint warning">该流程当前为公开状态（所有人的聊天都能触发）。保存修改后将自动退出公开、仅您自己可触发；如需恢复公开请联系开发人员。</div>
              <div v-else class="public-hint">如需将流程设为公开（所有人的聊天都能触发该流程），请联系开发人员开通。</div>
              <div v-if="!form.triggers.length" class="subtle-empty">未配置关键词，聊天不会触发这个流程。</div>
              <div v-for="(trigger, index) in form.triggers" :key="index" class="trigger-row"><input v-model="trigger.keyword" placeholder="输入触发关键词" /><label class="toggle-row"><input v-model="trigger.enabled" type="checkbox" /><span>启用</span></label><button class="icon-button danger" title="删除关键词" @click="removeTrigger(index)">×</button></div>
            </section>

            <section class="form-section wide section-card">
              <div class="section-heading"><div><h4>报告输出大纲</h4><p>先写章节，章节下可添加多个执行节点（拖拽 ⇕ 或 ↑↓ 调整章节内顺序）；序号由渲染器自动生成。</p></div><button class="btn primary" @click="addOutlineRow(null)">添加章节</button></div>
              <label class="report-title-field"><span>报告总标题</span><input v-model="reportTitle" placeholder="例如：月度经营分析报告" /><small>生成汇总时会作为整份报告的居中标题。</small></label>
              <div v-if="!outlineRows.length" class="subtle-empty">尚未配置大纲，报告将按执行节点顺序输出。</div>
              <template v-for="(row, rowIndex) in outlineRows" :key="row.id">
                <div class="outline-row" :style="{ marginLeft: `${Math.min(row.level - 1, 8) * 22}px` }">
                  <span class="outline-level" :title="`第 ${row.level} 级`">{{ outlineNumbers[rowIndex] || `L${row.level}` }}</span>
                  <input v-model="row.title" placeholder="章节标题" />
                  <button class="icon-button" title="添加同级" @click="addOutlineRow(row)">＋</button>
                  <button class="icon-button" title="添加子级" @click="addOutlineRow(row, true)">↳</button>
                  <button class="icon-button danger" title="删除本节及子级" @click="removeOutlineRow(row)">×</button>
                </div>
                <div class="outline-node-area" :style="{ marginLeft: `${Math.min(row.level, 8) * 22}px` }">
                  <FlowNodeCard v-for="(node, nodeIndex) in nodesForRow(row)" :key="node.nodeKey" class="chapter-node-card" :node="node" :title="nodeTitle(node)" :schema="schemaFor(node)" :scripts="scripts" :metrics="metrics" :script-loading="scriptLoading" :metric-loading="metricLoading" :debug-disabled="!node.scriptId || debugStarting || debugInProgress()" :dragging="dragNodeKey === node.nodeKey" :sortable="true" :is-first="nodeIndex === 0" :is-last="nodeIndex === nodesForRow(row).length - 1" remove-title="删除节点" @move="moveChapterNode(row, node.nodeKey, $event)" @remove="removeChapterNode(row, node.nodeKey)" @debug="runNodeDebug(node)" @drag-start="chapterDragStart(node.nodeKey, $event)" @drag-over="chapterDragOver(row, node.nodeKey)" @drag-end="finishDrag" @script-change="onScriptSelected(node)" @search-scripts="searchScripts" @search-metrics="searchMetrics" @set-metric="setNodeMetric(node, $event)" />
                  <div class="chapter-node-actions">
                    <button class="btn" title="新建一个 Python 节点并放到该章节" @click="addOutlineNode(row)">＋ 添加节点</button>
                    <select v-if="unboundNodes.length" :value="''" @change="bindRowNode(row, ($event.target as HTMLSelectElement).value)"><option value="" disabled>绑定已有未绑定节点…</option><option v-for="node in unboundNodes" :key="node.nodeKey" :value="node.nodeKey">{{ nodeTitle(node) }}（{{ node.nodeKey }}）</option></select>
                  </div>
                </div>
              </template>
            </section>

            <div v-if="validationErrors.length" class="validation"><strong>保存前需处理：</strong><span v-for="item in validationErrors" :key="item">{{ item }}</span></div>
            <div v-if="error" class="error">{{ error }}</div>
          </template>
        </main>
        <footer class="drawer-footer"><button class="btn" @click="emit('update:open', false)">取消</button><button class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存流程' }}</button></footer>
      </section>
      <div v-if="debugRun" class="debug-mask" @click.self="closeNodeDebug">
        <section class="debug-panel" aria-label="脚本试跑结果">
          <header class="debug-header">
            <div>
              <h4>脚本试跑</h4>
              <p>节点 {{ form.nodes.find(node => node.nodeKey === debugNodeKey)?.nodeKey || '' }} · {{ debugRun.scriptId }} · 走脚本注册的 params_schema 校验</p>
            </div>
            <button class="icon-button" title="关闭" aria-label="关闭" @click="closeNodeDebug">×</button>
          </header>
          <div class="debug-meta">
            <span>状态：<strong :class="{ ok: debugRun.status === 'SUCCESS', bad: ['FAILED', 'TIMEOUT'].includes(debugRun.status) }">{{ debugRun.status }}</strong></span>
            <span>退出码：{{ debugRun.exitCode ?? '-' }}</span>
            <span>耗时：{{ debugRun.elapsedMs ?? 0 }} ms</span>
          </div>
          <p v-if="debugRun.status === 'SUCCESS'" class="debug-ok-hint">试跑成功：脚本参数可用，与流程保存的参数一致即可放心保存。</p>
          <div class="debug-output">
            <div class="debug-output-title">stdout</div>
            <pre class="debug-pre">{{ debugRun.stdout || '(空)' }}</pre>
            <div class="debug-output-title">stderr / traceback</div>
            <pre class="debug-pre stderr">{{ debugRun.stderr || '(空)' }}</pre>
          </div>
          <footer class="debug-footer">
            <button class="btn danger" :disabled="!debugInProgress()" @click="stopNodeDebug">停止</button>
            <button class="btn" :disabled="debugInProgress()" @click="closeNodeDebug">关闭</button>
          </footer>
        </section>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgb(15 23 42 / 45%); }
.drawer { width: min(880px, 96vw); height: 100%; display: flex; flex-direction: column; background: #f5f7fb; box-shadow: -8px 0 24px rgb(15 23 42 / 12%); }
.mask.page-mode { position: static; min-height: 100%; justify-content: stretch; background: #f5f7fb; }
.page-mode .drawer { width: 100%; min-height: 100%; box-shadow: none; }
.page-mode .drawer-body { width: min(1100px, 100%); margin: 0 auto; box-sizing: border-box; }
.drawer-header, .drawer-footer, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.drawer-header { padding: 20px 28px 18px; border-bottom: 1px solid #e2e8f0; background: #fff; }.drawer-header h3 { margin: 2px 0 3px; color: #0f172a; font-size: 20px; }.drawer-header p { margin: 0; color: #64748b; font-size: 12px; }.eyebrow { color: #3b82f6; font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.drawer-body { flex: 1; overflow: auto; padding: 24px 28px 36px; }.drawer-footer { justify-content: flex-end; padding: 14px 28px; border-top: 1px solid #e2e8f0; background: #fff; }
.form-section { display: grid; gap: 14px; max-width: 620px; margin: 0 auto 18px; }.form-section.wide { max-width: none; }.section-card { padding: 20px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fff; box-shadow: 0 2px 8px rgb(15 23 42 / 3%); }.form-section label { display: grid; gap: 5px; }.form-section label > span, .section-heading h4 { color: #475569; font-size: 13px; font-weight: 600; }.section-heading h4 { color: #0f172a; font-size: 15px; margin: 0; }.section-heading p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
.basic-row { display: flex; gap: 20px; align-items: end; flex-wrap: wrap; }.basic-row > label:first-child { width: 180px; }
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #cbd5e1; border-radius: 6px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; font-size: 14px; } textarea { resize: vertical; }.toggle-row { display: flex !important; align-items: center; grid-template-columns: none !important; gap: 7px !important; color: #475569; font-size: 13px; }.toggle-row input { width: auto; }
.flow-preview { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 12px; border-left: 3px solid #3b82f6; background: #f8fafc; color: #475569; font-size: 13px; }.preview-nodes { color: #1d4ed8; }
.outline-row { display: grid; grid-template-columns: 34px minmax(180px, 1fr) auto auto auto; gap: 8px; align-items: center; padding: 8px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fbfdff; }.outline-row + .outline-row { margin-top: 7px; }.outline-level { color: #64748b; font-size: 11px; font-weight: 700; text-align: center; }.outline-row input, .outline-row select { min-width: 0; }
.section-heading { margin-top: 8px; }
.outline-node-area { display: grid; gap: 8px; margin-top: 6px; padding: 8px; border: 1px dashed #dbe4f0; border-radius: 8px; }
.outline-node-area + .outline-row, .outline-node-area + .outline-node-area { margin-top: 7px; }
.chapter-node-actions { display: flex; gap: 8px; align-items: center; }.chapter-node-actions select { width: auto; min-width: 200px; }
.outline-item { display: grid; grid-template-columns: 1fr 220px 30px; gap: 8px; align-items: center; margin-top: 8px; }
.trigger-row { display: grid; grid-template-columns: minmax(160px, 1fr) auto 30px; align-items: center; gap: 8px; }.public-hint { padding: 8px 12px; border-left: 3px solid #f59e0b; background: #fffbeb; color: #92400e; font-size: 12px; }.public-hint.warning { border-color: #dc2626; background: #fef2f2; color: #b91c1c; }.subtle-empty, .empty { color: #94a3b8; font-size: 13px; padding: 18px 0; }
.validation, .error { display: grid; gap: 4px; margin-top: 18px; padding: 10px 12px; border-left: 3px solid #f59e0b; background: #fffbeb; color: #92400e; font-size: 13px; }.error { border-color: #dc2626; background: #fef2f2; color: #b91c1c; }
.btn, .icon-button { border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn { padding: 7px 14px; }.btn.primary { border-color: #3b82f6; background: #3b82f6; color: #fff; }.btn.danger { border-color: #fecaca; color: #dc2626; }.btn:disabled, .icon-button:disabled { cursor: not-allowed; opacity: .45; }.icon-button { width: 28px; height: 28px; padding: 0; font-size: 18px; line-height: 1; }.icon-button.danger { color: #dc2626; border-color: #fecaca; }
.debug-mask { position: fixed; inset: 0; z-index: 1100; display: flex; align-items: center; justify-content: center; background: rgb(15 23 42 / 45%); }
.debug-panel { width: min(760px, 94vw); max-height: 86vh; display: flex; flex-direction: column; gap: 12px; padding: 20px; border-radius: 12px; background: #fff; box-shadow: 0 12px 40px rgb(15 23 42 / 25%); }
.debug-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.debug-header h4 { margin: 0; color: #0f172a; font-size: 16px; }.debug-header p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
.debug-meta { display: flex; flex-wrap: wrap; gap: 16px; color: #475569; font-size: 13px; }.debug-meta .ok { color: #16a34a; }.debug-meta .bad { color: #dc2626; }
.debug-ok-hint { margin: 0; padding: 8px 12px; border-left: 3px solid #16a34a; background: #f0fdf4; color: #15803d; font-size: 13px; }
.debug-output { flex: 1; min-height: 0; overflow: auto; display: grid; gap: 6px; align-content: start; }
.debug-output-title { color: #475569; font-size: 12px; font-weight: 600; }
.debug-pre { margin: 0; padding: 10px; border: 1px solid #e2e8f0; border-radius: 8px; background: #0f172a; color: #e2e8f0; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
.debug-footer { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 760px) { .drawer { width: 100vw; }.basic-row { grid-template-columns: 1fr; flex-direction: column; align-items: stretch; }.trigger-row { grid-template-columns: 1fr auto 30px; }.drawer-body { padding: 14px; } }
</style>



