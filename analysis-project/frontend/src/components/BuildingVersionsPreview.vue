<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { previewScriptParams } from '../api/scriptParamRule';
import { validateBuildingVersionsValue } from '../utils/scriptParams';

const props = defineProps<{ modelValue: unknown; type: string; paramName: string; scriptId?: string }>();
const previewText = ref<string>();
const error = ref('');
const loading = ref(false);
const validation = computed(() => validateBuildingVersionsValue(props.modelValue, props.type));
let sequence = 0;
function clearPreview() {
  sequence++;
  previewText.value = undefined;
  error.value = '';
  loading.value = false;
}
watch(() => [props.scriptId, props.paramName, props.type, props.modelValue], clearPreview, { deep: true, flush: 'sync' });
onBeforeUnmount(clearPreview);
async function showPreview() {
  if (!props.scriptId || validation.value || loading.value) return;
  const token = ++sequence;
  const paramName = props.paramName;
  loading.value = true;
  error.value = '';
  previewText.value = undefined;
  try {
    const result = await previewScriptParams(props.scriptId, { [paramName]: props.modelValue });
    if (token !== sequence) return;
    previewText.value = JSON.stringify(result.resolvedParams[paramName], null, 2);
  } catch (cause) {
    if (token === sequence) error.value = cause instanceof Error ? cause.message : '预览失败';
  } finally {
    if (token === sequence) loading.value = false;
  }
}
</script>

<template>
  <section class="building-preview" aria-label="在建版本参数预览" aria-live="polite">
    <div class="preview-heading"><span>在建版本<small>执行时自动查询字符串数组（版本列表）</small></span><button type="button" :disabled="!scriptId || !!validation || loading" @click="showPreview">{{ loading ? '预览中…' : '预览参数' }}</button></div>
    <pre v-if="previewText !== undefined">{{ previewText }}</pre>
    <p v-if="validation || error" role="alert">{{ validation || error }}</p>
  </section>
</template>

<style scoped>
.building-preview{grid-column:2;min-width:0;padding:9px 12px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff;color:#334155;font-size:13px}.preview-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}small{display:block;margin-top:3px;color:#94a3b8;font-size:12px}button{border:0;background:transparent;color:#2563eb;padding:4px;font:inherit;white-space:nowrap;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}pre{max-height:180px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.6}p{color:#dc2626;font-size:12px}@media(max-width:760px){.building-preview{grid-column:1}}
</style>
