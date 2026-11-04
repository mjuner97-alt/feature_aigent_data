<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { getMetricsByIds } from '../api/skillDependencyMetric';
import type { SkillDependencyMetric } from '../types/skillJob';
import { metricOptionLabel } from '../utils/flowMetrics';

const props = withDefaults(defineProps<{
  modelValue?: number[];
  metrics: SkillDependencyMetric[];
  loading?: boolean;
  disabled?: boolean;
  placeholder?: string;
  remoteMethod?: (query: string) => void;
}>(), { modelValue: () => [], loading: false, disabled: false, placeholder: '请选择依赖指标' });
const emit = defineEmits<{
  (event: 'update:modelValue', value: number[]): void;
  (event: 'change', value: number[]): void;
}>();
// Remote searches can omit selected entries. Keep their labels until deselected.
const cached = reactive(new Map<number, SkillDependencyMetric>());
const resolution = reactive(new Map<number, 'loading' | 'missing' | 'error'>());
watch(() => props.metrics, metrics => {
  for (const metric of metrics) cached.set(metric.id, metric);
}, { immediate: true, deep: true });
// A saved ID may not be in the enabled/search list. Resolve it independently.
watch(() => props.modelValue.filter(id => !cached.has(id) && !resolution.has(id)), async ids => {
  if (!ids.length) return;
  for (const id of ids) resolution.set(id, 'loading');
  try {
    const metrics = await getMetricsByIds(ids);
    for (const metric of metrics) cached.set(metric.id, metric);
    for (const id of ids) {
      if (cached.has(id)) resolution.delete(id);
      else resolution.set(id, 'missing');
    }
  } catch {
    for (const id of ids) resolution.set(id, 'error');
  }
}, { immediate: true });
function retryFailedLookups(open: boolean): void {
  if (open) for (const [id, state] of resolution) if (state === 'error') resolution.delete(id);
}
function unresolvedLabel(id: number): string {
  const state = resolution.get(id);
  if (state === 'missing') return `指标已不存在 (#${id})`;
  if (state === 'error') return `指标加载失败 (#${id})`;
  return '指标加载中…';
}
const options = computed(() => {
  const entries = new Map(props.metrics.map(metric => [metric.id, metric]));
  for (const id of props.modelValue) {
    if (!entries.has(id)) entries.set(id, cached.get(id) || { id, name: unresolvedLabel(id), code: '', enabled: false });
  }
  return [...entries.values()];
});
function label(id: number): string {
  const metric = options.value.find(item => item.id === id);
  return metric ? `${metricOptionLabel(metric)}${cached.has(id) && metric.enabled === false ? '（已停用）' : ''}` : unresolvedLabel(id);
}
function update(values: number[]): void {
  if (props.disabled) return;
  const next = [...new Set(values)];
  emit('update:modelValue', next);
  emit('change', next);
}
function removeMetric(id: number, event: MouseEvent): void {
  // A close button inside a native label must not activate the label's input.
  event.preventDefault();
  event.stopPropagation();
  update(props.modelValue.filter(value => value !== id));
}
</script>

<template>
  <el-select :model-value="modelValue" multiple filterable remote reserve-keyword
    :remote-method="remoteMethod" :loading="loading" :disabled="disabled" :placeholder="placeholder"
    :clearable="false" style="width: 100%" @update:model-value="update" @visible-change="retryFailedLookups">
    <template #tag>
      <el-tag v-for="id in modelValue" :key="id" :closable="!disabled" disable-transitions
        @close="removeMetric(id, $event)">{{ label(id) }}</el-tag>
    </template>
    <el-option v-for="metric in options" :key="metric.id" :value="metric.id"
      :label="metricOptionLabel(metric)" :title="metric.description" :disabled="metric.enabled === false" />
  </el-select>
</template>
