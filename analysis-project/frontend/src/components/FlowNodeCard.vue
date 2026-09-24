<script setup lang="ts">
import { ref, watch } from 'vue';
import type { SkillFlowNode } from '../types/skillFlow';
import type { ParamSchemaItem, ScriptRegistryListItem } from '../types/scriptRegistry';
import type { SkillDependencyMetric } from '../types/skillJob';
import { coerceParamValue, defaultParamValue, normalizedParamType, paramsFromSchema, validateParamValue } from '../utils/scriptParams';

/** 流程节点配置卡片:Python 执行节点区和报告输出大纲行内嵌两处复用;排序相关控件仅在 sortable 时显示。 */
const props = withDefaults(defineProps<{
  node: SkillFlowNode;
  /** 工具栏标题(节点区带序号,大纲内嵌为节点名)。 */
  title: string;
  /** 当前脚本的参数定义(未加载为 null)。 */
  schema: ParamSchemaItem[] | null;
  scripts: ScriptRegistryListItem[];
  metrics: SkillDependencyMetric[];
  scriptLoading?: boolean;
  metricLoading?: boolean;
  debugDisabled?: boolean;
  dragging?: boolean;
  sortable?: boolean;
  isFirst?: boolean;
  isLast?: boolean;
  removeDisabled?: boolean;
  /** 删除按钮文案:节点区为「删除卡片」,大纲章节内为「移出章节」。 */
  removeTitle?: string;
}>(), {
  scriptLoading: false,
  metricLoading: false,
  debugDisabled: false,
  dragging: false,
  sortable: true,
  isFirst: false,
  isLast: false,
  removeDisabled: false,
  removeTitle: '删除卡片',
});

const emit = defineEmits<{
  (e: 'move', direction: -1 | 1): void;
  (e: 'remove'): void;
  (e: 'debug'): void;
  (e: 'drag-start', event: DragEvent): void;
  (e: 'drag-over'): void;
  (e: 'drag-end'): void;
  (e: 'script-change'): void;
  (e: 'script-params-change', value: { value: Record<string, unknown> | null; error: string }): void;
  (e: 'search-scripts', query: string): void;
  (e: 'search-metrics', query: string): void;
  (e: 'set-metric', value: number | null): void;
}>();

const scriptParamsText = ref('{}');
const scriptParamsError = ref('');
const schemaValues = ref<Record<string, unknown>>({});
function formatScriptParams(value: Record<string, unknown> | undefined): string {
  try { return JSON.stringify(value || {}, null, 2); } catch { return '{}'; }
}
function parseScriptParams(raw: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('根值必须是 JSON 对象');
    return value as Record<string, unknown>;
  } catch (e) {
    scriptParamsError.value = e instanceof Error ? e.message : 'JSON 格式不正确';
    return null;
  }
}
function onScriptParamsInput(raw: string) {
  scriptParamsText.value = raw;
  const value = parseScriptParams(raw);
  if (value) { scriptParamsError.value = ''; emit('script-params-change', { value, error: '' }); }
  else emit('script-params-change', { value: null, error: scriptParamsError.value });
}
watch(() => props.node.scriptParams, value => {
  const formatted = formatScriptParams(value);
  if (formatted !== scriptParamsText.value) scriptParamsText.value = formatted;
}, { deep: true, immediate: true });
watch(() => props.node.scriptId, () => {
  scriptParamsError.value = '';
  scriptParamsText.value = formatScriptParams(props.node.scriptParams);
  schemaValues.value = { ...(props.node.scriptParams || {}) };
});

const hasSchema = () => !!props.schema && props.schema.length > 0;
function schemaValue(item: ParamSchemaItem): unknown {
  if (!(item.name in schemaValues.value)) schemaValues.value[item.name] = defaultParamValue(item.type);
  let value = schemaValues.value[item.name];
  // 兼容旧流程：数组参数历史上可能保存成单个字符串，不能直接 v-for 字符符号。
  if (normalizedParamType(item.type).array && !Array.isArray(value)) {
    if (typeof value === 'string') {
      try {
        const parsed = JSON.parse(value);
        value = Array.isArray(parsed) ? parsed : [value];
      } catch { value = value ? [value] : []; }
    } else value = value == null ? [] : [value];
    schemaValues.value[item.name] = value;
  }
  return normalizedParamType(item.type).base === 'date' && typeof value === 'string'
    ? value.replace(/\//g, '-') : value;
}
function setSchemaValue(item: ParamSchemaItem, value: unknown) {
  const normalized = coerceParamValue(value, item.type);
  schemaValues.value[item.name] = normalized;
  const error = validateParamValue(normalized, item.type);
  scriptParamsError.value = error ? `${item.name}：${error}` : '';
  if (!error) {
    scriptParamsText.value = formatScriptParams(schemaValues.value);
    emit('script-params-change', { value: { ...schemaValues.value }, error: '' });
  } else emit('script-params-change', { value: null, error: scriptParamsError.value });
}
function addArrayItem(item: ParamSchemaItem) {
  const values = Array.isArray(schemaValue(item)) ? [...schemaValue(item) as unknown[]] : [];
  values.push(defaultParamValue(normalizedParamType(item.type).base));
  setSchemaValue(item, values);
}
function removeArrayItem(item: ParamSchemaItem, index: number) {
  const values = Array.isArray(schemaValue(item)) ? [...schemaValue(item) as unknown[]] : [];
  values.splice(index, 1);
  setSchemaValue(item, values);
}
function updateArrayItem(item: ParamSchemaItem, index: number, value: unknown) {
  const values = Array.isArray(schemaValue(item)) ? [...schemaValue(item) as unknown[]] : [];
  values[index] = value;
  setSchemaValue(item, values);
}
function inputType(type: string): string {
  const base = normalizedParamType(type).base;
  return base === 'int' || base === 'float' ? 'number' : 'text';
}
function numberStep(type: string): string { return normalizedParamType(type).base === 'float' ? 'any' : '1'; }
function syncSchemaValues() {
  if (!hasSchema()) return;
  const generated = paramsFromSchema(props.schema || []);
  schemaValues.value = { ...generated, ...(props.node.scriptParams || {}) };
  scriptParamsText.value = formatScriptParams(schemaValues.value);
}
watch(() => props.schema, syncSchemaValues, { deep: true, immediate: true });
watch(() => props.node.scriptParams, value => {
  if (hasSchema()) schemaValues.value = { ...schemaValues.value, ...(value || {}) };
}, { deep: true });

function selectedScript(node: SkillFlowNode, scripts: ScriptRegistryListItem[]): ScriptRegistryListItem | undefined {
  return scripts.find(script => script.scriptId === node.scriptId);
}
</script>

<template>
  <div class="node-card" :class="{ dragging }" @dragover.prevent="emit('drag-over')" @drop.prevent="emit('drag-end')">
    <div class="node-toolbar">
      <span v-if="sortable" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="emit('drag-start', $event)" @dragend="emit('drag-end')">⇕</span>
      <strong>{{ title }}</strong>
      <div>
        <button v-if="node.nodeType !== 'SKILL'" class="btn" title="用当前参数真实执行一次脚本，验证参数是否可用" :disabled="debugDisabled" @click="emit('debug')">试跑</button>
        <template v-if="sortable">
          <button class="icon-button" title="上移" :disabled="isFirst" @click="emit('move', -1)">↑</button>
          <button class="icon-button" title="下移" :disabled="isLast" @click="emit('move', 1)">↓</button>
        </template>
        <button class="icon-button danger" :title="removeTitle" :disabled="removeDisabled" @click="emit('remove')">×</button>
      </div>
    </div>
    <div class="node-grid">
      <label><span>节点名称</span><input v-model="node.nodeName" :placeholder="node.skillName" /></label>
      <label v-if="node.nodeType !== 'SKILL'"><span>Python 脚本 *</span><el-select v-model="node.scriptId" popper-class="script-select-dropdown" filterable remote reserve-keyword :remote-method="(query: string) => emit('search-scripts', query)" :loading="scriptLoading" placeholder="请选择脚本" clearable style="width: 100%" @change="emit('script-change')"><el-option v-for="script in scripts" :key="script.scriptId" :value="script.scriptId" :label="script.name"><template #default><div class="script-option"><strong>{{ script.name }}</strong><span>{{ script.description || '暂无用途描述' }}</span><small>脚本 ID：{{ script.scriptId }}</small></div></template></el-option></el-select><div v-if="selectedScript(node, scripts)" class="script-detail"><div class="script-detail-title">脚本说明</div><p>{{ selectedScript(node, scripts)?.description || '暂无用途描述' }}</p><div class="script-detail-meta"><span>脚本 ID：{{ selectedScript(node, scripts)?.scriptId }}</span></div></div></label>
      <label v-else><span>旧 Skill（只读兼容）</span><input :value="node.skillName || node.skillId || ''" readonly /></label>
      <div v-if="node.nodeType !== 'SKILL'" class="node-params">
        <div class="node-params-title">脚本参数</div>
        <div v-if="!node.scriptId" class="param-empty">请先选择 Python 脚本</div>
        <div v-if="node.scriptId && hasSchema()" class="param-list">
          <div v-for="item in schema" :key="item.name" class="param-row">
            <div>
              <span class="param-name">{{ item.name }}<em v-if="item.required"> *</em><small class="param-type">{{ item.type }}</small></span>
              <small v-if="item.description" class="param-desc-inline">{{ item.description }}</small>
            </div>
            <template v-if="normalizedParamType(item.type).array">
              <div class="array-editor">
                <div v-for="(value, index) in (schemaValue(item) as unknown[] || [])" :key="index" class="array-row">
                  <input :type="inputType(normalizedParamType(item.type).base)" :step="numberStep(item.type)" :placeholder="normalizedParamType(item.type).base === 'date' ? 'YYYY-MM-DD' : normalizedParamType(item.type).base === 'datetime' ? 'YYYY-MM-DD HH:mm' : ''" :value="value as any" @input="updateArrayItem(item, index, ($event.target as HTMLInputElement).value)" />
                  <button type="button" class="icon-button danger" title="删除此项" @click="removeArrayItem(item, index)">×</button>
                </div>
                <button type="button" class="btn add-array" @click="addArrayItem(item)">＋ 添加一项</button>
              </div>
            </template>
            <template v-else-if="normalizedParamType(item.type).base === 'boolean'">
              <label class="param-check"><input type="checkbox" :checked="schemaValue(item) === true" @change="setSchemaValue(item, ($event.target as HTMLInputElement).checked)" /> 是</label>
            </template>
            <template v-else>
              <input :type="inputType(item.type)" :step="numberStep(item.type)" :placeholder="normalizedParamType(item.type).base === 'date' ? 'YYYY-MM-DD' : normalizedParamType(item.type).base === 'datetime' ? 'YYYY-MM-DD HH:mm' : ''" :value="schemaValue(item) as any" @input="setSchemaValue(item, ($event.target as HTMLInputElement).value)" />
            </template>
          </div>
        </div>
        <textarea v-else-if="node.scriptId" class="params-json-input" :value="scriptParamsText" rows="8" spellcheck="false" placeholder="例如：{&#10;  &quot;regions&quot;: [&quot;华东&quot;, &quot;华南&quot;]&#10;}" @input="onScriptParamsInput(($event.target as HTMLTextAreaElement).value)" />
        <small v-if="node.scriptId && !hasSchema()" class="param-hint">暂未读取到参数定义，保留 JSON 输入兼容旧脚本。</small>
        <small v-if="scriptParamsError" class="param-error">JSON 参数无效：{{ scriptParamsError }}</small>
      </div>
      <label v-if="node.nodeType === 'SKILL'"><span>本流程问题 *</span><textarea v-model="node.questionTemplate" rows="3" placeholder="填写该 Skill 在本流程中要执行的问题" /></label>
      <label><span>依赖指标</span><el-select :model-value="node.metricIds[0] ?? null" filterable remote reserve-keyword :remote-method="(query: string) => emit('search-metrics', query)" :loading="metricLoading" placeholder="无需依赖指标" clearable style="width: 100%" @change="(value: number | null) => emit('set-metric', value)"><el-option v-for="metric in metrics" :key="metric.id" :value="metric.id" :label="`${metric.name} (${metric.code})`" /></el-select></label>
    </div>
  </div>
</template>

<style scoped>
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #cbd5e1; border-radius: 6px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; font-size: 14px; }
label { display: grid; gap: 5px; } label > span { color: #475569; font-size: 13px; font-weight: 600; }
.node-card { display: grid; gap: 14px; padding: 16px; border: 1px solid #dbe4f0; border-radius: 10px; background: #fbfdff; }.node-card.dragging { border-color: #3b82f6; background: #eff6ff; }
.node-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.node-toolbar strong { color: #0f172a; font-size: 14px; flex: 1; }.node-toolbar > div { display: flex; gap: 4px; }
.drag-handle { cursor: grab; color: #94a3b8; font-size: 18px; padding: 0 4px; user-select: none; }.drag-handle:active { cursor: grabbing; }
.node-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; align-items: start; }
.script-option { display: grid; gap: 2px; line-height: 1.35; padding: 3px 0; }.script-option strong { color: #0f172a; font-size: 13px; }.script-option span { overflow: hidden; color: #64748b; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.script-option small { color: #94a3b8; font-size: 11px; }
.script-detail { display: grid; gap: 5px; margin-top: 8px; padding: 10px 12px; border: 1px solid #dbeafe; border-radius: 8px; background: #f8fbff; }.script-detail-title { color: #1d4ed8; font-size: 12px; font-weight: 700; }.script-detail p { margin: 0; color: #475569; font-size: 12px; line-height: 1.5; }.script-detail-meta { display: flex; flex-wrap: wrap; gap: 8px 14px; color: #64748b; font-size: 11px; }
:global(.script-select-dropdown) { min-width: 520px !important; }
:global(.script-select-dropdown .el-select-dropdown__item) { height: auto !important; min-height: 72px !important; padding: 9px 14px !important; line-height: 1.35 !important; white-space: normal !important; }
:global(.script-select-dropdown .el-select-dropdown__item .script-option) { width: 100%; }
.node-params { display: grid; gap: 8px; padding: 10px 12px; border: 1px dashed #cbd5e1; border-radius: 8px; background: #fff; }
.node-params-title { color: #475569; font-size: 13px; font-weight: 600; }
.param-list { display: grid; gap: 8px; }
.param-row { display: grid; grid-template-columns: minmax(120px, 220px) minmax(0, 1fr); gap: 6px 10px; align-items: center; }
.param-name { display: block; color: #1e293b; font-size: 13px; font-weight: 600; word-break: break-all; }.param-name em { color: #dc2626; font-style: normal; }
.param-type { display: inline-block; margin-left: 6px; padding: 0 5px; border: 1px solid #dbe4f0; border-radius: 4px; background: #f1f5f9; color: #64748b; font-size: 11px; font-weight: 400; line-height: 16px; vertical-align: 1px; }
.param-input input { width: 100%; }
.param-check { display: flex !important; align-items: center; gap: 7px !important; grid-template-columns: none !important; color: #475569; font-size: 13px; }.param-check input { width: auto; }
.param-desc { grid-column: 2; color: #94a3b8; font-size: 12px; }
.param-desc-inline { display: block; margin-top: 3px; color: #94a3b8; font-size: 11px; line-height: 1.35; }
.array-editor { display: grid; gap: 6px; }.array-row { display: grid; grid-template-columns: minmax(0, 1fr) 28px; gap: 6px; align-items: center; }.add-array { justify-self: start; padding: 5px 10px; font-size: 12px; }
.param-empty { color: #94a3b8; font-size: 12px; }
.params-json-input { min-height: 150px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; line-height: 1.5; }
.param-hint { color: #64748b; font-size: 12px; line-height: 1.4; }
.param-error { color: #dc2626; font-size: 12px; line-height: 1.4; }
.btn, .icon-button { border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn { padding: 7px 14px; }.node-toolbar .btn { border-color: #3b82f6; background: #3b82f6; color: #fff; }.node-toolbar .btn:hover:not(:disabled) { background: #2563eb; border-color: #2563eb; }.btn:disabled, .icon-button:disabled { cursor: not-allowed; opacity: .45; }.icon-button { width: 28px; height: 28px; padding: 0; font-size: 18px; line-height: 1; }.icon-button.danger { color: #dc2626; border-color: #fecaca; }
@media (max-width: 760px) { .node-grid { grid-template-columns: 1fr; } }
</style>
