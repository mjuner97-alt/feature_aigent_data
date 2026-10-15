<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { previewScriptParams } from '../api/scriptParamRule';
import { defaultPeriodValue, normalizedParamType, validatePeriodValue, type PeriodValue, type PeriodWindow } from '../utils/scriptParams';

const props = defineProps<{ modelValue: PeriodValue; type: string; paramName: string; scriptId?: string; editing?: boolean; pending?: boolean }>();
const emit = defineEmits<{ (e: 'change', value: PeriodValue): void; (e: 'close'): void }>();
const opened = ref(false);
const dialog = ref<HTMLDialogElement>();
const draft = ref<PeriodValue>(copyValue());
const advanced = ref(false);
const customWindow = ref(false);
const previewText = ref<string>();
const previewError = ref('');
const previewLoading = ref(false);
const maxPeriods = ref(120);
let previewSequence = 0;
function clearPreview() {
  previewSequence++;
  previewText.value = undefined;
  previewError.value = '';
  previewLoading.value = false;
}
async function showPreview() {
  if (!props.scriptId || validation.value || previewLoading.value) return;
  const token = ++previewSequence;
  previewLoading.value = true;
  previewError.value = '';
  try {
    const result = await previewScriptParams(props.scriptId, { [props.paramName]: draft.value });
    if (token !== previewSequence) return;
    maxPeriods.value = result.maxPeriods;
    previewText.value = JSON.stringify({ [props.paramName]: result.resolvedParams[props.paramName] }, null, 2);
  } catch (error) {
    if (token === previewSequence) previewError.value = error instanceof Error ? error.message : '预览失败';
  } finally {
    if (token === previewSequence) previewLoading.value = false;
  }
}
const config = computed(() => draft.value.$dynamic);
const count = computed(() => config.value.endOffset - config.value.startOffset + 1);
const isDate = computed(() => normalizedParamType(props.type).base === 'date');
const isList = computed(() => normalizedParamType(props.type).array);
const validation = computed(() => validatePeriodValue(draft.value, props.type, maxPeriods.value));
const unitLabel = (unit: string) => ({ month: '版本', quarter: '季度', year: '年' }[unit] || unit);
const formats = computed(() => config.value.unit === 'month'
  ? [{value:'{year}年{month}月份版本',label:'2026年8月份版本'}]
  : config.value.unit === 'quarter'
    ? [{value:'{year}年第{quarter}季度',label:'2026年第3季度'},{value:'{year}Q{quarter}',label:'2026Q3'}]
    : [{value:'{year}年',label:'2026年'},{value:'{year}',label:'2026'}]);
const legacyFormat = computed(() => !formats.value.some(f => f.value === config.value.format));
function describe(value: PeriodValue) {
  const c = value.$dynamic;
  if (!c) return '时间配置无效，请修改';
  const n = c.endOffset - c.startOffset + 1, unit = unitLabel(c.unit);
  if (c.startOffset === 0 && c.endOffset === 0) return `当前${unit}`;
  if (c.startOffset === -1 && c.endOffset === -1) return `上个${unit}`;
  if (c.endOffset === -1 || c.endOffset === 0) return `最近 ${n} 个${unit}${c.endOffset === 0 ? '（含当前）' : ''}`;
  return `${unit}周期：${c.startOffset} 至 ${c.endOffset}`;
}
const summary = computed(() => describe(props.modelValue));
const range = computed(() => customWindow.value ? 'custom' : count.value === 1 && config.value.endOffset === 0 ? 'current' : count.value === 1 && config.value.endOffset === -1 ? 'previous' : 'recent');
function copyValue(): PeriodValue {
  const value = { $dynamic: { ...(props.modelValue?.$dynamic || defaultPeriodValue(props.type).$dynamic) } };
  if (value.$dynamic.unit === 'month') value.$dynamic.format = '{year}年{month}月份版本';
  return value;
}
async function open() {
  draft.value = copyValue();
  customWindow.value = ![-1, 0].includes(config.value.endOffset);
  advanced.value = customWindow.value;
  opened.value = true;
  await nextTick();
  dialog.value?.showModal?.();
}
function close() {
  clearPreview();
  dialog.value?.close?.();
  opened.value = false;
  emit('close');
}
function update(patch: Partial<PeriodWindow>) {
  clearPreview();
  draft.value = { $dynamic: { ...config.value, ...patch } };
}
function recent(n: number, includeCurrent = config.value.endOffset === 0) {
  const end = includeCurrent ? 0 : -1;
  update({ startOffset: end - n + 1, endOffset: end });
}
function setRange(value: string) {
  customWindow.value = value === 'custom';
  if (value === 'custom') { advanced.value = true; return; }
  if (value === 'current') update({ startOffset: 0, endOffset: 0 });
  else if (value === 'previous') update({ startOffset: -1, endOffset: -1 });
  else recent(Math.max(2, count.value));
}
function setUnit(unit: PeriodWindow['unit']) {
  update({ unit, format: unit === 'month' ? '{year}年{month}月份版本' : isDate.value ? '{year}-{month:02}-01' : unit === 'quarter' ? '{year}年第{quarter}季度' : '{year}年' });
}
function confirm() {
  if (validation.value) return;
  emit('change', { $dynamic: { ...config.value } });
  close();
}
watch(() => props.editing, value => { if (value) void open(); }, { immediate: true });
// A schema or script change invalidates an open draft.
watch(() => [props.type, props.paramName, props.scriptId], () => { if (opened.value) close(); });
watch(draft, clearPreview, { flush: 'sync' });
</script>

<template>
  <div class="period-editor">
    <div v-if="!pending" class="period-summary">
      <span>{{ summary }}<small>执行时自动计算</small></span>
      <button type="button" class="edit-button" @click="open">修改</button>
    </div>
    <dialog v-if="opened" ref="dialog" class="period-dialog" aria-label="动态时间设置" @cancel.prevent="close" @close="opened && close()">
      <header class="dialog-header"><div><h3>动态时间</h3><p>设置 {{ paramName }} 的时间规则</p></div><button type="button" class="close-button" aria-label="关闭动态时间设置" @click="close">×</button></header>
      <div class="dialog-body">
        <div class="field-row">
          <label>周期<select aria-label="周期" :value="config.unit" @change="setUnit(($event.target as HTMLSelectElement).value as PeriodWindow['unit'])"><option value="month">版本</option><option value="quarter">季度</option><option value="year">年</option></select></label>
          <label>时间范围<select aria-label="时间范围" :value="range" @change="setRange(($event.target as HTMLSelectElement).value)"><option value="previous">上个{{ unitLabel(config.unit) }}</option><option value="current">当前{{ unitLabel(config.unit) }}</option><option v-if="isList" value="recent">最近多个周期</option><option v-if="customWindow" value="custom">自定义范围</option></select></label>
        </div>
        <div v-if="range === 'recent'" class="recent-row"><label>周期数量<input aria-label="周期数量" type="number" min="1" :max="maxPeriods" :value="count" @input="recent(Number(($event.target as HTMLInputElement).value))" /></label><label class="check"><input type="checkbox" aria-label="含当前周期" :checked="config.endOffset === 0" @change="recent(count, ($event.target as HTMLInputElement).checked)" />含当前周期</label></div>
        <label v-if="!isDate || config.unit === 'month'">格式<select aria-label="格式" :value="config.format" @change="update({format: ($event.target as HTMLSelectElement).value})"><option v-for="f in formats" :key="f.value" :value="f.value">{{ f.label }}</option><option v-if="legacyFormat" :value="config.format">保留原格式（{{ config.format }}）</option></select></label>
        <button type="button" class="advanced-toggle" :aria-expanded="advanced" @click="advanced = !advanced">{{ advanced ? '收起高级设置' : '高级设置' }}</button>
        <div v-if="advanced" class="advanced-panel">
          <label v-if="isList">顺序<select aria-label="顺序" :value="config.order" @change="update({order: ($event.target as HTMLSelectElement).value as PeriodWindow['order']})"><option value="asc">从旧到新</option><option value="desc">从新到旧</option></select></label>
          <label class="check"><input aria-label="自定义窗口" type="checkbox" :checked="customWindow" @change="customWindow = ($event.target as HTMLInputElement).checked; !customWindow && recent(isList ? Math.max(1, count) : 1)" />自定义前后周期</label>
          <div v-if="customWindow" class="field-row"><label>起始偏移<input aria-label="起始偏移" type="number" :value="config.startOffset" @input="update({startOffset:Number(($event.target as HTMLInputElement).value)})" /></label><label>结束偏移<input aria-label="结束偏移" type="number" :value="config.endOffset" @input="update({endOffset:Number(($event.target as HTMLInputElement).value)})" /></label></div>
          <small v-if="customWindow">0 为当前周期，负数为之前，正数为之后。</small>
        </div>
        <p class="auto-hint">按执行时的数据日期自动生成{{ isList ? '时间列表' : '时间值' }}。</p>
        <section class="parameter-preview" aria-label="参数预览" aria-live="polite">
          <div class="preview-heading"><span>参数预览</span><button type="button" class="edit-button" :disabled="!scriptId || !!validation || previewLoading" @click="showPreview">{{ previewLoading ? '预览中…' : '预览参数' }}</button></div>
          <pre v-if="previewText !== undefined">{{ previewText }}</pre>
          <small v-else>查看当前规则生成的实际参数，不执行脚本。</small>
          <p v-if="previewError" role="alert" class="error">{{ previewError }}</p>
        </section>
        <p v-if="validation" role="alert" class="error">{{ validation }}</p>
      </div>
      <footer><button type="button" class="cancel-button" @click="close">取消</button><button type="button" class="confirm-button" :disabled="!!validation" @click="confirm">确定</button></footer>
    </dialog>
  </div>
</template>

<style scoped>
.parameter-preview{display:grid;gap:10px;padding:12px 14px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff}.preview-heading{display:flex;align-items:center;justify-content:space-between;font-size:13px}.parameter-preview small{color:#94a3b8;font-size:12px}.parameter-preview pre{margin:0;max-height:180px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;color:#334155;font-size:12px;line-height:1.6}.preview-heading button:disabled{opacity:.45;cursor:not-allowed}
.period-editor{grid-column:2;min-width:0;font-size:13px}.period-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:9px 12px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff;color:#334155}.period-summary small{display:block;margin-top:3px;color:#94a3b8;font-size:12px}
button{font:inherit;cursor:pointer}.edit-button,.advanced-toggle{border:0;background:transparent;color:#2563eb;padding:4px;white-space:nowrap}
.period-dialog{position:fixed;inset:0;margin:auto;box-sizing:border-box;width:min(480px,calc(100vw - 32px));max-height:calc(100vh - 48px);padding:0;border:1px solid #e2e8f0;border-radius:16px;color:#334155;background:#fff;box-shadow:0 24px 80px #0f172a33;font:inherit;overflow:auto}.period-dialog::backdrop{background:#0f172a66;backdrop-filter:blur(2px)}
.dialog-header{display:flex;justify-content:space-between;align-items:start;padding:22px 24px 18px;border-bottom:1px solid #f1f5f9}.dialog-header h3{margin:0;color:#0f172a;font-size:18px}.dialog-header p{margin:7px 0 0;color:#64748b;font-size:13px;overflow-wrap:anywhere}.close-button{border:0;background:transparent;color:#94a3b8;font-size:24px;line-height:1;padding:0 4px}.dialog-body{display:grid;gap:18px;padding:22px 24px}.period-dialog label{display:grid;gap:8px;font-size:13px}.field-row,.recent-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.recent-row{align-items:end}.period-dialog input,.period-dialog select{box-sizing:border-box;width:100%;padding:10px 12px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;color:#1e293b;font:inherit;min-height:40px}.period-dialog input:focus,.period-dialog select:focus{outline:2px solid #bfdbfe;outline-offset:1px;border-color:#3b82f6}.check{display:flex!important;align-items:center;gap:8px!important;min-height:40px}.check input{width:auto;min-height:0;accent-color:#2563eb}.advanced-toggle{justify-self:start;padding:0;font-size:12px}.advanced-panel{display:grid;gap:14px;padding:16px;border-radius:8px;background:#f8fafc}.advanced-panel small{color:#64748b;line-height:1.6}.auto-hint{margin:0;color:#94a3b8;font-size:12px;line-height:1.6}.error{margin:0;color:#dc2626;font-size:12px}.period-dialog footer{display:flex;justify-content:flex-end;gap:10px;padding:16px 24px;border-top:1px solid #f1f5f9}.cancel-button,.confirm-button{padding:9px 22px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;color:#475569}.confirm-button{border-color:#2563eb;background:#2563eb;color:#fff}.confirm-button:disabled{opacity:.45;cursor:not-allowed}.period-dialog button:focus-visible{outline:2px solid #60a5fa;outline-offset:3px}
@media(max-width:760px){.period-editor{grid-column:1}.dialog-header,.dialog-body{padding:18px}.field-row,.recent-row{gap:12px}}
</style>

