<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { createSkillFlow, getSkillFlow, updateSkillFlow, validateSkillFlow } from '../api/skillFlow';
import { listSkills } from '../api/skill';
import { listMetrics } from '../api/skillDependencyMetric';
import type { SkillListItem } from '../types/skill';
import type { SkillDependencyMetric } from '../types/skillJob';
import type { SkillFlow, SkillFlowInput, SkillFlowNode } from '../types/skillFlow';

const props = defineProps<{ open: boolean; editId: number | null; knownFlows: SkillFlow[] }>();
const emit = defineEmits<{ (e: 'update:open', open: boolean): void; (e: 'saved'): void }>();

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const skills = ref<SkillListItem[]>([]);
const metrics = ref<SkillDependencyMetric[]>([]);
const skillLoading = ref(false);
const metricLoading = ref(false);
let skillSearchSeq = 0;
let metricSearchSeq = 0;
const form = ref<SkillFlowInput>(emptyForm());
/** 拖拽排序:正在拖拽的卡片下标;dragover 时实时换位,drop/dragend 收尾。 */
const dragIndex = ref<number | null>(null);
function nextNodeKey(): string {
  const used = new Set(form.value.nodes.map(node => node.nodeKey));
  let index = form.value.nodes.length + 1;
  while (used.has(`node_${index}`)) index++;
  return `node_${index}`;
}

function emptyNode(nodeKey: string, sortOrder: number): SkillFlowNode {
  return { nodeKey, skillId: null, questionTemplate: '', metricIds: [], required: true, maxAttempts: 4, sortOrder };
}

function emptyForm(): SkillFlowInput {
  return { name: '', code: '', description: '', taskQuestion: '', summaryQuestionTemplate: '', enabled: true, maxParallelism: 2, notifyEnabled: true, triggers: [], nodes: [emptyNode('node_1', 1)] };
}

const isEdit = computed(() => props.editId != null);
const validationErrors = computed(() => {
  const errors: string[] = [];
  if (!form.value.name.trim()) errors.push('请填写流程名称');
  if (!form.value.taskQuestion.trim()) errors.push('请填写任务问题');
  const sameName = props.knownFlows.find(flow => flow.id !== props.editId && flow.name.trim() === form.value.name.trim());
  if (sameName) errors.push(`流程名称「${form.value.name.trim()}」已存在`);
  if (!form.value.nodes.length) errors.push('至少配置一个 Skill 节点');
  const uniqueKeys = new Set<string>();
  form.value.nodes.forEach((node, index) => {
    const key = node.nodeKey.trim();
    if (!key) errors.push(`节点 ${index + 1} 缺少节点标识`);
    else if (uniqueKeys.has(key)) errors.push(`节点标识 ${key} 重复`);
    else uniqueKeys.add(key);
    if (!node.skillId) errors.push(`节点 ${key || index + 1} 未选择 Skill`);
    if (node.metricIds.length > 1) errors.push(`节点 ${key || index + 1} 只能依赖一个指标`);
  });
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
  .map(node => skills.value.find(item => item.id === node.skillId)?.name || '未配置 Skill')
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
    if (seq === skillSearchSeq) skills.value = result;
  } catch {
    if (seq === skillSearchSeq) skills.value = [];
  } finally {
    if (seq === skillSearchSeq) skillLoading.value = false;
  }
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
  form.value.nodes.splice(index, 1);
  renumber();
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
}

function addTrigger() {
  form.value.triggers.push({ keyword: '', priority: 100, enabled: true });
}

function removeTrigger(index: number) {
  form.value.triggers.splice(index, 1);
}

async function loadOptions() {
  await Promise.all([searchSkills(''), searchMetrics('')]);
}

function normalizeFlow(flow: SkillFlow): SkillFlowInput {
  return {
    code: flow.code || '', name: flow.name || '', description: flow.description || '', taskQuestion: flow.taskQuestion || '',
    summaryQuestionTemplate: flow.summaryQuestionTemplate || '', enabled: flow.enabled !== false,
    maxParallelism: 2, notifyEnabled: flow.notifyEnabled !== false,
    triggers: (flow.triggers || []).map(trigger => ({ ...trigger, enabled: trigger.enabled !== false })),
    nodes: (flow.nodes || []).map((node, index) => ({ ...node, skillId: node.skillId ?? null, metricIds: (node.metricIds || []).slice(0, 1), required: node.required !== false, maxAttempts: node.maxAttempts || 4, sortOrder: node.sortOrder || index + 1 })),
  };
}

async function load() {
  loading.value = true;
  error.value = '';
  form.value = emptyForm();
  await loadOptions();
  if (props.editId != null) {
    try {
      form.value = normalizeFlow(await getSkillFlow(props.editId));
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载流程失败';
    }
  }
  loading.value = false;
}

async function save() {
  error.value = '';
  if (validationErrors.value.length) {
    error.value = validationErrors.value[0];
    await ElMessageBox.alert(validationErrors.value[0], '请检查填写内容', { type: 'warning' });
    return;
  }
  saving.value = true;
  try {
    renumber();
    const saved = props.editId == null ? await createSkillFlow(form.value) : await updateSkillFlow(props.editId, form.value);
    await validateSkillFlow(saved.id);
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

watch(() => props.open, open => { if (open) load(); });
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="mask" @click.self="emit('update:open', false)">
      <section class="drawer" aria-label="长任务流程编辑器">
        <header class="drawer-header"><h3>{{ isEdit ? '编辑长任务流程' : '创建长任务流程' }}</h3><button class="icon-button" title="关闭" aria-label="关闭" @click="emit('update:open', false)">×</button></header>
        <main class="drawer-body">
          <div v-if="loading" class="empty">加载中…</div>
          <template v-else>
            <section class="form-section">
              <div class="section-heading"><h4>基本信息</h4></div>
              <label><span>流程名称 *</span><input v-model="form.name" placeholder="如 每日质量综合分析" /></label>
              <label><span>任务问题 *</span><textarea v-model="form.taskQuestion" rows="3" placeholder="如 分析今日质量指标并生成综合报告" /></label>
              <label><span>说明(非必填)</span><textarea v-model="form.description" rows="2" placeholder="说明该流程处理的业务问题" /></label>
              <div class="basic-row">
                <label class="toggle-row"><input v-model="form.enabled" type="checkbox" /><span>保存后启用流程</span></label>
              </div>
            </section>

            <section class="form-section wide">
              <div class="section-heading"><div><h4>触发关键词</h4><p>关键词在所有长任务流程中唯一。</p></div><button class="btn primary" @click="addTrigger">添加关键词</button></div>
              <div v-if="!form.triggers.length" class="subtle-empty">未配置关键词，聊天不会触发这个流程。</div>
              <div v-for="(trigger, index) in form.triggers" :key="index" class="trigger-row"><input v-model="trigger.keyword" placeholder="输入触发关键词" /><label class="toggle-row"><input v-model="trigger.enabled" type="checkbox" /><span>启用</span></label><button class="icon-button danger" title="删除关键词" @click="removeTrigger(index)">×</button></div>
              <label class="toggle-row"><input v-model="form.notifyEnabled" type="checkbox" /><span>汇总完成后通知触发用户</span></label>
            </section>

            <section class="form-section wide">
              <div class="section-heading"><div><h4>Skill 卡片</h4><p>拖拽 ⇕ 调整顺序；卡片从上到下的顺序就是最终报告的拼接顺序，Skill 之间并行执行、互不依赖。</p></div><button class="btn primary" @click="addNode">添加 Skill</button></div>
              <div v-for="(node, index) in form.nodes" :key="node.nodeKey" class="node-card" :class="{ dragging: dragIndex === index }" @dragover.prevent="onDragOver(index)" @drop.prevent="finishDrag">
                <div class="node-toolbar">
                  <span class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="onDragStart(index, $event)" @dragend="finishDrag">⇕</span>
                  <strong>{{ index + 1 }}. {{ node.skillName || '未选择 Skill' }}</strong>
                  <div>
                    <button class="icon-button" title="上移" :disabled="index === 0" @click="moveNode(index, -1)">↑</button>
                    <button class="icon-button" title="下移" :disabled="index === form.nodes.length - 1" @click="moveNode(index, 1)">↓</button>
                    <button class="icon-button danger" title="删除卡片" :disabled="form.nodes.length === 1" @click="removeNode(index)">×</button>
                  </div>
                </div>
                <div class="node-grid">
                  <label><span>Skill *</span><el-select v-model="node.skillId" filterable remote reserve-keyword :remote-method="searchSkills" :loading="skillLoading" placeholder="请选择 Skill" clearable style="width: 100%" @change="syncNodeSkillName(node)"><el-option v-for="skill in skills" :key="skill.id" :value="skill.id" :label="skill.name" /></el-select></label>
                  <label><span>依赖指标</span><el-select :model-value="node.metricIds[0] ?? null" filterable remote reserve-keyword :remote-method="searchMetrics" :loading="metricLoading" placeholder="无需依赖指标" clearable style="width: 100%" @change="setNodeMetric(node, $event)"><el-option v-for="metric in metrics" :key="metric.id" :value="metric.id" :label="`${metric.name} (${metric.code})`" /></el-select></label>
                </div>
              </div>
              <div class="flow-preview"><strong>开始（等待全部指标）</strong><span>-></span><span class="preview-nodes">{{ preview || '请添加 Skill' }}</span><span>全部并行</span><span>-></span><strong>结束（结果按卡片顺序拼接）</strong></div>
            </section>

            <div v-if="validationErrors.length" class="validation"><strong>保存前需处理：</strong><span v-for="item in validationErrors" :key="item">{{ item }}</span></div>
            <div v-if="error" class="error">{{ error }}</div>
          </template>
        </main>
        <footer class="drawer-footer"><button class="btn" @click="emit('update:open', false)">取消</button><button class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存流程' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgb(15 23 42 / 45%); }
.drawer { width: min(880px, 96vw); height: 100%; display: flex; flex-direction: column; background: #fff; box-shadow: -8px 0 24px rgb(15 23 42 / 12%); }
.drawer-header, .drawer-footer, .section-heading, .node-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.drawer-header { padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }.drawer-header h3 { margin: 0; color: #0f172a; font-size: 18px; }
.drawer-body { flex: 1; overflow: auto; padding: 20px; }.drawer-footer { justify-content: flex-end; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.form-section { display: grid; gap: 14px; max-width: 620px; margin-bottom: 26px; }.form-section.wide { max-width: none; }.form-section label { display: grid; gap: 5px; }.form-section label > span, .section-heading h4 { color: #475569; font-size: 13px; font-weight: 600; }.section-heading h4 { color: #0f172a; font-size: 15px; margin: 0; }.section-heading p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
.basic-row { display: flex; gap: 20px; align-items: end; flex-wrap: wrap; }.basic-row > label:first-child { width: 180px; }
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #cbd5e1; border-radius: 6px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; font-size: 14px; } textarea { resize: vertical; }.toggle-row { display: flex !important; align-items: center; grid-template-columns: none !important; gap: 7px !important; color: #475569; font-size: 13px; }.toggle-row input { width: auto; }
.flow-preview { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 12px; border-left: 3px solid #3b82f6; background: #f8fafc; color: #475569; font-size: 13px; }.preview-nodes { color: #1d4ed8; }
.section-heading { margin-top: 8px; }
.node-card { display: grid; gap: 10px; padding: 14px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }.node-card + .node-card { margin-top: 10px; }.node-card.dragging { border-color: #3b82f6; background: #eff6ff; }
.node-toolbar strong { color: #0f172a; font-size: 14px; flex: 1; }.node-toolbar > div { display: flex; gap: 4px; }
.drag-handle { cursor: grab; color: #94a3b8; font-size: 18px; padding: 0 4px; user-select: none; }.drag-handle:active { cursor: grabbing; }
.node-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
.trigger-row { display: grid; grid-template-columns: minmax(160px, 1fr) auto 30px; align-items: center; gap: 8px; }.subtle-empty, .empty { color: #94a3b8; font-size: 13px; padding: 18px 0; }
.validation, .error { display: grid; gap: 4px; margin-top: 18px; padding: 10px 12px; border-left: 3px solid #f59e0b; background: #fffbeb; color: #92400e; font-size: 13px; }.error { border-color: #dc2626; background: #fef2f2; color: #b91c1c; }
.btn, .icon-button { border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn { padding: 7px 14px; }.btn.primary { border-color: #3b82f6; background: #3b82f6; color: #fff; }.btn:disabled, .icon-button:disabled { cursor: not-allowed; opacity: .45; }.icon-button { width: 28px; height: 28px; padding: 0; font-size: 18px; line-height: 1; }.icon-button.danger { color: #dc2626; border-color: #fecaca; }
@media (max-width: 760px) { .drawer { width: 100vw; }.node-grid, .basic-row { grid-template-columns: 1fr; flex-direction: column; align-items: stretch; }.trigger-row { grid-template-columns: 1fr auto 30px; }.drawer-body { padding: 14px; } }
</style>
