<script setup lang="ts">
/** 创建与复制共用的类型选择器，先确认任务模式再进入编辑页。 */
import { computed, ref, watch } from 'vue';
import { listAllLongTaskTypes, type LongTaskType } from '../api/longTaskConfig';
import { creatableFlowTypes } from '../utils/flowCreation';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'selected', type: LongTaskType): void;
  (e: 'cancelled'): void;
}>();
const types = ref<LongTaskType[]>([]),
  selectedId = ref<number | null>(null);
const loading = ref(false),
  error = ref('');
const selected = computed(() => types.value.find((type) => type.id === selectedId.value));
let request = 0;
/** 加载可创建类型，并用请求序号屏蔽弹窗关闭后的迟到响应。 */
async function load() {
  const current = ++request;
  loading.value = true;
  error.value = '';
  types.value = [];
  selectedId.value = null;
  try {
    const items = await listAllLongTaskTypes();
    if (current === request) types.value = creatableFlowTypes(items);
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : '读取任务类型失败';
  } finally {
    if (current === request) loading.value = false;
  }
}
function cancel() {
  emit('update:open', false);
  emit('cancelled');
}
function onOpenChange(value: boolean) {
  if (!value) cancel();
}
/** 确认已启用的类型，交由父组件进入对应创建或复制流程。 */
function confirm() {
  if (!selected.value || loading.value) return;
  emit('selected', selected.value);
  emit('update:open', false);
}
watch(
  () => props.open,
  (open) => {
    if (open) void load();
    else ++request;
  },
  { immediate: true }
);
</script>
<template>
  <el-dialog
    :model-value="open"
    title="选择长任务类型"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="onOpenChange"
  >
    <p class="type-hint">选择要创建的任务类型</p>
    <p v-if="loading">正在读取任务类型…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="!types.length">暂无可创建的任务类型，请先在长任务类型管理中配置并启用。</p>
    <div v-else class="type-options" role="radiogroup" aria-label="创建长任务类型">
      <label v-for="type in types" :key="type.id" class="type-card" :class="{ active: selectedId === type.id }">
        <input
          type="radio"
          name="long-task-type"
          :value="type.id"
          :checked="selectedId === type.id"
          @change="selectedId = type.id"
        />
        <span class="type-copy"
          ><strong>{{ type.name }}</strong
          ><span v-if="type.description">{{ type.description }}</span></span
        >
      </label>
    </div>
    <template #footer>
      <button type="button" @click="cancel">取消</button>
      <button type="button" class="primary" :disabled="loading || !selected" @click="confirm">下一步</button>
    </template>
  </el-dialog>
</template>
<style scoped>
p {
  color: #64748b;
  line-height: 1.6;
}
.type-hint {
  margin: 0 0 20px;
  font-size: 14px;
}
.type-options {
  display: grid;
  gap: 12px;
  padding-bottom: 8px;
}
.type-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s,
    box-shadow 0.15s;
}
.type-card:hover {
  border-color: #93c5fd;
  background: #f8fbff;
}
.type-card.active {
  border-color: #3b82f6;
  background: #eff6ff;
  box-shadow: 0 0 0 1px #3b82f6;
}
.type-card:focus-within {
  outline: 2px solid #93c5fd;
  outline-offset: 3px;
}
.type-card input {
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: #2563eb;
  flex-shrink: 0;
  cursor: pointer;
}
.type-copy {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.type-copy strong {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}
.active .type-copy strong {
  color: #1d4ed8;
}
.type-copy > span {
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
button {
  padding: 9px 20px;
  border: 1px solid #d8e0eb;
  border-radius: 8px;
  background: white;
  color: #475569;
  cursor: pointer;
  margin-left: 10px;
  font: inherit;
  font-size: 14px;
}
button:hover {
  background: #f8fafc;
}
.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: white;
}
.primary:hover {
  background: #1d4ed8;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: 3px;
}
</style>
