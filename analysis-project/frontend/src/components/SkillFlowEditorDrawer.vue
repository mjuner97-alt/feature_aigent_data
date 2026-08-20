<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { createSkillFlow, getSkillFlow, updateSkillFlow, validateSkillFlow } from '../api/skillFlow';
import { listSkills } from '../api/skill';
import { listMetrics } from '../api/skillDependencyMetric';
import type { SkillListItem } from '../types/skill';
import type { SkillDependencyMetric } from '../types/skillJob';
import type { SkillFlow, SkillFlowInput, SkillFlowNode, SkillFlowTrigger } from '../types/skillFlow';

const props = defineProps<{ open: boolean; editId: number | null; knownFlows: SkillFlow[] }>();
const emit = defineEmits<{ (e: 'update:open', open: boolean): void; (e: 'saved'): void }>();

type Step = 1 | 2 | 3;
const step = ref<Step>(1);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const skills = ref<SkillListItem[]>([]);
const metrics = ref<SkillDependencyMetric[]>([]);
const form = ref<SkillFlowInput>(emptyForm());

function emptyNode(index: number): SkillFlowNode {
  return { nodeKey: `node_${index + 1}`, skillId: null, questionTemplate: '', metricIds: [], dependsOn: [], required: true, maxAttempts: 4, sortOrder: index + 1 };
}

function emptyForm(): SkillFlowInput {
  return { name: '', code: '', description: '', summaryQuestionTemplate: '', enabled: true, maxParallelism: 4, notifyEnabled: true, triggers: [], nodes: [emptyNode(0)] };
}

const isEdit = computed(() => props.editId != null);
const nodeKeys = computed(() => form.value.nodes.map(node => node.nodeKey.trim()).filter(Boolean));
const allowedNodeVariables = new Set(['server_date', 'original_question', 'flow_name', 'skill_name', 'upstream_results']);
const allowedSummaryVariables = new Set(['server_date', 'original_question', 'flow_name', 'all_results']);
const metricsInUse = computed(() => {
  const usages = new Map<number, string[]>();
  for (const node of form.value.nodes) {
    for (const metricId of node.metricIds) {
      const users = usages.get(metricId) ?? [];
      users.push(node.skillName || node.nodeKey || '未命名节点');
      usages.set(metricId, users);
    }
  }
  return [...usages.entries()].map(([metricId, skillsUsing]) => ({ metric: metrics.value.find(item => item.id === metricId), skillsUsing }));
});

function cycleError(nodes: SkillFlowNode[]): string | null {
  const graph = new Map(nodes.map(node => [node.nodeKey.trim(), node.dependsOn]));
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const visit = (key: string): boolean => {
    if (visiting.has(key)) return true;
    if (visited.has(key)) return false;
    visiting.add(key);
    const cycle = (graph.get(key) ?? []).some(parent => graph.has(parent) && visit(parent));
    visiting.delete(key);
    visited.add(key);
    return cycle;
  };
  return [...graph.keys()].some(visit) ? '前置 Skill 不能形成环' : null;
}

function unknownTemplateVariable(template: string, allowed: Set<string>): string | null {
  const matches = template.matchAll(/\{([^}]+)\}/g);
  for (const match of matches) {
    if (!allowed.has(match[1].trim())) return `{${match[1]}}`;
  }
  return null;
}

const validationErrors = computed(() => {
  const errors: string[] = [];
  if (!form.value.name.trim()) errors.push('请填写流程名称');
  if (form.value.maxParallelism < 1) errors.push('最大并发数至少为 1');
  if (!form.value.nodes.length) errors.push('至少配置一个 Skill 节点');
  const uniqueKeys = new Set<string>();
  form.value.nodes.forEach((node, index) => {
    const key = node.nodeKey.trim();
    if (!key) errors.push(`节点 ${index + 1} 缺少节点标识`);
    else if (uniqueKeys.has(key)) errors.push(`节点标识 ${key} 重复`);
    else uniqueKeys.add(key);
    if (!node.skillId) errors.push(`节点 ${key || index + 1} 未选择 Skill`);
    if (!node.questionTemplate.trim()) errors.push(`节点 ${key || index + 1} 未填写问题模板`);
    const invalidVariable = unknownTemplateVariable(node.questionTemplate, allowedNodeVariables);
    if (invalidVariable) errors.push(`节点 ${key || index + 1} 使用了无效变量 ${invalidVariable}`);
    if (node.maxAttempts < 1) errors.push(`节点 ${key || index + 1} 最大尝试次数至少为 1`);
  });
  form.value.nodes.forEach(node => node.dependsOn.forEach(parent => {
    if (!uniqueKeys.has(parent)) errors.push(`节点 ${node.nodeKey || '未命名'} 引用了不存在的前置节点 ${parent}`);
  }));
  const cycle = cycleError(form.value.nodes);
  if (cycle) errors.push(cycle);
  if (!form.value.triggers.length) errors.push('至少配置一个触发关键词');
  if (!form.value.summaryQuestionTemplate.trim()) errors.push('请填写最终汇总问题模板');
  const summaryInvalidVariable = unknownTemplateVariable(form.value.summaryQuestionTemplate, allowedSummaryVariables);
  if (summaryInvalidVariable) errors.push(`最终汇总问题模板使用了无效变量 ${summaryInvalidVariable}`);
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

const preview = computed(() => {
  const nodes = form.value.nodes;
  return nodes.map(node => {
    const skill = skills.value.find(item => item.id === node.skillId)?.name || node.nodeKey || '未配置 Skill';
    return node.dependsOn.length ? `${node.dependsOn.join(' + ')} -> ${skill}` : skill;
  }).join('    ');
});

function syncNodeSkillName(node: SkillFlowNode) {
  node.skillName = skills.value.find(skill => skill.id === node.skillId)?.name;
}

function addNode() {
  form.value.nodes.push(emptyNode(form.value.nodes.length));
}

function removeNode(index: number) {
  const removedKey = form.value.nodes[index].nodeKey;
  form.value.nodes.splice(index, 1);
  form.value.nodes.forEach(node => { node.dependsOn = node.dependsOn.filter(key => key !== removedKey); });
}

function moveNode(index: number, direction: -1 | 1) {
  const destination = index + direction;
  if (destination < 0 || destination >= form.value.nodes.length) return;
  const nodes = form.value.nodes;
  [nodes[index], nodes[destination]] = [nodes[destination], nodes[index]];
  nodes.forEach((node, order) => { node.sortOrder = order + 1; });
}

function addTrigger() {
  form.value.triggers.push({ keyword: '', priority: 100, enabled: true });
}

function removeTrigger(index: number) {
  form.value.triggers.splice(index, 1);
}

function insertVariable(variable: string, target: 'summary' | number) {
  if (target === 'summary') form.value.summaryQuestionTemplate += variable;
  else form.value.nodes[target].questionTemplate += variable;
}

async function loadOptions() {
  const [skillResult, metricResult] = await Promise.allSettled([
    listSkills({ view: 'used', limit: 200 }),
    listMetrics(),
  ]);
  skills.value = skillResult.status === 'fulfilled' ? skillResult.value : [];
  metrics.value = metricResult.status === 'fulfilled' ? metricResult.value : [];
}

function normalizeFlow(flow: SkillFlow): SkillFlowInput {
  return {
    code: flow.code || '', name: flow.name || '', description: flow.description || '',
    summaryQuestionTemplate: flow.summaryQuestionTemplate || '', enabled: flow.enabled !== false,
    maxParallelism: flow.maxParallelism || 4, notifyEnabled: flow.notifyEnabled !== false,
    triggers: (flow.triggers || []).map(trigger => ({ ...trigger, enabled: trigger.enabled !== false })),
    nodes: (flow.nodes || []).map((node, index) => ({ ...node, skillId: node.skillId ?? null, metricIds: node.metricIds || [], dependsOn: node.dependsOn || [], required: node.required !== false, maxAttempts: node.maxAttempts || 4, sortOrder: node.sortOrder || index + 1 })),
  };
}

function currentStepError(current: Step): string | null {
  if (current === 1) {
    if (!form.value.name.trim()) return '请填写流程名称';
    if (form.value.maxParallelism < 1) return '最大并发数至少为 1';
    return null;
  }
  if (current === 2) {
    if (!form.value.nodes.length) return '至少配置一个 Skill 节点';
    const keys = new Set<string>();
    for (const [index, node] of form.value.nodes.entries()) {
      const key = node.nodeKey.trim() || String(index + 1);
      if (!node.nodeKey.trim()) return `节点 ${index + 1} 缺少节点标识`;
      if (keys.has(key)) return `节点标识 ${key} 重复`;
      keys.add(key);
      if (!node.skillId) return `节点 ${key} 未选择 Skill`;
      if (!node.questionTemplate.trim()) return `节点 ${key} 未填写问题模板`;
      const invalidVariable = unknownTemplateVariable(node.questionTemplate, allowedNodeVariables);
      if (invalidVariable) return `节点 ${key} 使用了无效变量 ${invalidVariable}`;
      if (node.maxAttempts < 1) return `节点 ${key} 最大尝试次数至少为 1`;
    }
    for (const node of form.value.nodes) {
      const missing = node.dependsOn.find(parent => !keys.has(parent));
      if (missing) return `节点 ${node.nodeKey} 引用了不存在的前置节点 ${missing}`;
    }
    return cycleError(form.value.nodes);
  }
  if (!form.value.triggers.length) return '至少配置一个触发关键词';
  if (form.value.triggers.some(trigger => !trigger.keyword.trim())) return '触发关键词不能为空';
  if (!form.value.summaryQuestionTemplate.trim()) return '请填写最终汇总问题模板';
  const invalidVariable = unknownTemplateVariable(form.value.summaryQuestionTemplate, allowedSummaryVariables);
  if (invalidVariable) return `最终汇总问题模板使用了无效变量 ${invalidVariable}`;
  return null;
}

async function load() {
  loading.value = true;
  error.value = '';
  step.value = 1;
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

function goTo(next: Step) {
  error.value = '';
  const stepError = next > step.value ? currentStepError(step.value) : null;
  if (stepError) {
    error.value = stepError;
    return;
  }
  step.value = next;
}

async function save() {
  error.value = '';
  if (validationErrors.value.length) {
    error.value = validationErrors.value[0];
    return;
  }
  saving.value = true;
  try {
    const saved = props.editId == null ? await createSkillFlow(form.value) : await updateSkillFlow(props.editId, form.value);
    await validateSkillFlow(saved.id);
    emit('saved');
    emit('update:open', false);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存流程失败';
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
        <div class="stepper" aria-label="编辑步骤">
          <button :class="{ active: step === 1 }" @click="goTo(1)">1 基本信息</button>
          <button :class="{ active: step === 2 }" @click="goTo(2)">2 Skill 与依赖</button>
          <button :class="{ active: step === 3 }" @click="goTo(3)">3 触发、汇总与通知</button>
        </div>
        <main class="drawer-body">
          <div v-if="loading" class="empty">加载中…</div>
          <template v-else>
            <section v-if="step === 1" class="form-section">
              <label><span>流程名称 *</span><input v-model="form.name" placeholder="如 每日质量综合分析" /></label>
              <label><span>流程编码</span><input v-model="form.code" placeholder="可选；仅允许字母、数字、下划线" /></label>
              <label><span>说明</span><textarea v-model="form.description" rows="3" placeholder="说明该流程处理的业务问题" /></label>
              <label><span>最大并发 Skill 数 *</span><input v-model.number="form.maxParallelism" min="1" max="20" type="number" /></label>
              <label class="toggle-row"><input v-model="form.enabled" type="checkbox" /><span>保存后启用流程</span></label>
            </section>

            <section v-else-if="step === 2" class="form-section wide">
              <div class="flow-preview"><strong>开始（等待全部指标）</strong><span>-></span><span class="preview-nodes">{{ preview || '请添加 Skill 节点' }}</span><span>-></span><strong>结束（汇总）</strong></div>
              <div class="section-heading"><div><h4>Skill 节点</h4><p>按顺序配置节点；前置节点成功后才会运行当前节点。</p></div><button class="btn primary" @click="addNode">添加 Skill</button></div>
              <div v-for="(node, index) in form.nodes" :key="index" class="node-row">
                <div class="node-toolbar"><strong>节点 {{ index + 1 }}</strong><div><button class="icon-button" title="上移" :disabled="index === 0" @click="moveNode(index, -1)">↑</button><button class="icon-button" title="下移" :disabled="index === form.nodes.length - 1" @click="moveNode(index, 1)">↓</button><button class="icon-button danger" title="删除节点" :disabled="form.nodes.length === 1" @click="removeNode(index)">×</button></div></div>
                <div class="node-grid">
                  <label><span>节点标识 *</span><input v-model="node.nodeKey" placeholder="quality_analysis" /></label>
                  <label><span>Skill *</span><select v-model.number="node.skillId" @change="syncNodeSkillName(node)"><option :value="null">请选择 Skill</option><option v-for="skill in skills" :key="skill.id" :value="skill.id">{{ skill.name }}</option></select></label>
                  <label><span>依赖指标</span><select v-model="node.metricIds" multiple><option v-for="metric in metrics" :key="metric.id" :value="metric.id">{{ metric.name }} ({{ metric.code }})</option></select></label>
                  <label><span>前置 Skill</span><select v-model="node.dependsOn" multiple><option v-for="key in nodeKeys.filter(key => key !== node.nodeKey)" :key="key" :value="key">{{ key }}</option></select></label>
                  <label><span>最大尝试次数 *</span><input v-model.number="node.maxAttempts" min="1" max="10" type="number" /></label>
                  <label class="toggle-row"><input v-model="node.required" type="checkbox" /><span>必需节点</span></label>
                </div>
                <label><span>Skill 问题模板 *</span><textarea v-model="node.questionTemplate" rows="3" placeholder="请基于 {server_date} 分析…" /></label>
                <div class="variable-bar"><span>插入变量：</span><button v-for="variable in ['{server_date}', '{original_question}', '{flow_name}', '{skill_name}', '{upstream_results}']" :key="variable" @click="insertVariable(variable, index)">{{ variable }}</button></div>
              </div>
              <div class="metric-summary"><strong>流程依赖指标</strong><span v-if="!metricsInUse.length">尚未选择指标</span><span v-for="item in metricsInUse" :key="item.metric?.id"><b>{{ item.metric?.code || item.metric?.name || '未知指标' }}</b> 影响 {{ item.skillsUsing.join('、') }}</span></div>
            </section>

            <section v-else class="form-section wide">
              <div class="flow-preview"><strong>开始（等待全部指标）</strong><span>-></span><span class="preview-nodes">{{ preview || '请添加 Skill 节点' }}</span><span>-></span><strong>结束（汇总）</strong><span class="summary-preview">{{ form.summaryQuestionTemplate || '请填写最终汇总问题模板' }}</span></div>
              <div class="section-heading"><div><h4>触发关键词</h4><p>关键词在所有长任务流程中唯一，按优先级匹配。</p></div><button class="btn" @click="addTrigger">添加关键词</button></div>
              <div v-if="!form.triggers.length" class="subtle-empty">未配置关键词，聊天不会触发这个流程。</div>
              <div v-for="(trigger, index) in form.triggers" :key="index" class="trigger-row"><input v-model="trigger.keyword" placeholder="输入触发关键词" /><input v-model.number="trigger.priority" type="number" min="0" aria-label="优先级" /><label class="toggle-row"><input v-model="trigger.enabled" type="checkbox" /><span>启用</span></label><button class="icon-button danger" title="删除关键词" @click="removeTrigger(index)">×</button></div>
              <label><span>最终汇总问题模板 *</span><textarea v-model="form.summaryQuestionTemplate" rows="6" placeholder="请结合 {original_question} 和 {all_results} 生成报告…" /></label>
              <div class="variable-bar"><span>插入变量：</span><button v-for="variable in ['{server_date}', '{original_question}', '{flow_name}', '{all_results}']" :key="variable" @click="insertVariable(variable, 'summary')">{{ variable }}</button></div>
              <label class="toggle-row"><input v-model="form.notifyEnabled" type="checkbox" /><span>汇总完成后通知触发用户</span></label>
            </section>
            <div v-if="validationErrors.length" class="validation"><strong>保存前需处理：</strong><span v-for="item in validationErrors" :key="item">{{ item }}</span></div>
            <div v-if="error" class="error">{{ error }}</div>
          </template>
        </main>
        <footer class="drawer-footer"><button class="btn" @click="emit('update:open', false)">取消</button><button v-if="step > 1" class="btn" @click="goTo((step - 1) as Step)">上一步</button><button v-if="step < 3" class="btn primary" @click="goTo((step + 1) as Step)">下一步</button><button v-else class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存流程' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgb(15 23 42 / 45%); }
.drawer { width: min(1040px, 96vw); height: 100%; display: flex; flex-direction: column; background: #fff; box-shadow: -8px 0 24px rgb(15 23 42 / 12%); }
.drawer-header, .drawer-footer, .section-heading, .node-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.drawer-header { padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }.drawer-header h3 { margin: 0; color: #0f172a; font-size: 18px; }
.stepper { display: flex; gap: 18px; padding: 10px 20px 0; border-bottom: 1px solid #e2e8f0; overflow-x: auto; }.stepper button { min-width: max-content; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #64748b; padding: 8px 2px 10px; cursor: pointer; font-size: 14px; }.stepper button.active { color: #2563eb; border-color: #2563eb; font-weight: 600; }
.drawer-body { flex: 1; overflow: auto; padding: 20px; }.drawer-footer { justify-content: flex-end; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.form-section { display: grid; gap: 14px; max-width: 620px; }.form-section.wide { max-width: none; }.form-section label { display: grid; gap: 5px; }.form-section label > span, .section-heading h4 { color: #475569; font-size: 13px; font-weight: 600; }.section-heading h4 { color: #0f172a; font-size: 15px; margin: 0; }.section-heading p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #cbd5e1; border-radius: 6px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; font-size: 14px; } select[multiple] { min-height: 86px; } textarea { resize: vertical; }.toggle-row { display: flex !important; align-items: center; grid-template-columns: none !important; gap: 7px !important; color: #475569; font-size: 13px; }.toggle-row input { width: auto; }
.flow-preview { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 12px; border-left: 3px solid #3b82f6; background: #f8fafc; color: #475569; font-size: 13px; }.preview-nodes { color: #1d4ed8; }.summary-preview { flex-basis: 100%; overflow: hidden; color: #64748b; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.section-heading { margin-top: 8px; }.node-row { display: grid; gap: 10px; padding: 14px 0; border-top: 1px solid #e2e8f0; }.node-toolbar strong { color: #0f172a; font-size: 14px; }.node-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }.variable-bar { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; color: #64748b; font-size: 12px; }.variable-bar button { border: 1px solid #bfdbfe; border-radius: 4px; background: #eff6ff; color: #1d4ed8; padding: 3px 6px; cursor: pointer; font-size: 12px; }.metric-summary { display: flex; flex-wrap: wrap; gap: 8px 14px; padding: 10px 0; border-top: 1px solid #e2e8f0; color: #475569; font-size: 13px; }.metric-summary strong { color: #0f172a; }.metric-summary span { white-space: nowrap; }.trigger-row { display: grid; grid-template-columns: minmax(160px, 1fr) 110px auto 30px; align-items: center; gap: 8px; }.subtle-empty, .empty { color: #94a3b8; font-size: 13px; padding: 18px 0; }
.validation, .error { display: grid; gap: 4px; margin-top: 18px; padding: 10px 12px; border-left: 3px solid #f59e0b; background: #fffbeb; color: #92400e; font-size: 13px; }.error { border-color: #dc2626; background: #fef2f2; color: #b91c1c; }
.btn, .icon-button { border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn { padding: 7px 14px; }.btn.primary { border-color: #3b82f6; background: #3b82f6; color: #fff; }.btn:disabled, .icon-button:disabled { cursor: not-allowed; opacity: .45; }.icon-button { width: 28px; height: 28px; padding: 0; font-size: 18px; line-height: 1; }.icon-button.danger { color: #dc2626; border-color: #fecaca; }
@media (max-width: 760px) { .drawer { width: 100vw; }.node-grid { grid-template-columns: 1fr; }.trigger-row { grid-template-columns: 1fr 90px auto 30px; }.drawer-body { padding: 14px; }.stepper { padding-inline: 14px; } }
</style>
