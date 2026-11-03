<script setup lang="ts">
import { computed, watch } from 'vue';
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
const cached = new Map<number, SkillDependencyMetric>();
watch(() => props.metrics, metrics => {
  for (const metric of metrics) cached.set(metric.id, metric);
}, { immediate: true, deep: true });
const options = computed(() => {
  const entries = new Map(props.metrics.map(metric => [metric.id, metric]));
  for (const id of props.modelValue) {
    if (!entries.has(id)) entries.set(id, cached.get(id) || { id, name: `指标 #${id}`, code: '', enabled: true });
  }
  return [...entries.values()];
});
function label(id: number): string {
  const metric = options.value.find(item => item.id === id);
  return metric ? metricOptionLabel(metric) : `指标 #${id}`;
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
    :clearable="false" style="width: 100%" @update:model-value="update">
    <template #tag>
      <el-tag v-for="id in modelValue" :key="id" :closable="!disabled" disable-transitions
        @close="removeMetric(id, $event)">{{ label(id) }}</el-tag>
    </template>
    <el-option v-for="metric in options" :key="metric.id" :value="metric.id"
      :label="metricOptionLabel(metric)" :title="metric.description" />
  </el-select>
</template>
