<script setup lang="ts">
import { ref, watch } from 'vue';

const props = withDefaults(defineProps<{ modelValue: string[]; label?: string; disabled?: boolean }>(), { label: '收件人', disabled: false });
const emit = defineEmits<{ (e: 'update:modelValue', ids: string[]): void }>();
const dragged = ref<string | null>(null);
const insertion = ref<{ id: string; after: boolean } | null>(null);
const announcement = ref('');
function clearDrag() { dragged.value = null; insertion.value = null; }
watch(() => [props.modelValue, props.disabled], clearDrag, { deep: true });
function start(event: DragEvent, id: string) {
  if (props.disabled) { event.preventDefault(); return; }
  dragged.value = id;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', id);
  }
}
function over(event: DragEvent, id: string) {
  if (props.disabled || !dragged.value || !props.modelValue.includes(dragged.value) || dragged.value === id) {
    insertion.value = null;
    return;
  }
  event.preventDefault();
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  insertion.value = { id, after: event.clientY > rect.top + rect.height / 2 };
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
}
function commit(ids: string[], id: string) {
  emit('update:modelValue', ids);
  announcement.value = `${props.label} ${id} 已移至第 ${ids.indexOf(id) + 1} 位`;
}
function drop(event: DragEvent, target: string) {
  const source = dragged.value;
  if (props.disabled || !source || source === target || !props.modelValue.includes(source) || !props.modelValue.includes(target)) { clearDrag(); return; }
  over(event, target);
  const after = insertion.value?.after ?? false;
  const ids = props.modelValue.filter(id => id !== source);
  ids.splice(ids.indexOf(target) + (after ? 1 : 0), 0, source);
  clearDrag();
  if (ids.some((id, index) => id !== props.modelValue[index])) commit(ids, source);
}
function move(index: number, delta: number) {
  if (props.disabled || index + delta < 0 || index + delta >= props.modelValue.length) return;
  const ids = [...props.modelValue];
  const [id] = ids.splice(index, 1);
  ids.splice(index + delta, 0, id);
  clearDrag();
  commit(ids, id);
}
</script>

<template>
  <div class="recipient-order">
    <p v-if="modelValue.length > 1" class="order-help">拖动 ⋮⋮ 调整顺序，也可点击 ↑ / ↓</p>
    <ol class="recipient-order-list" :aria-label="label" @dragleave.self="insertion = null" @dragend="clearDrag">
      <li v-for="(id, index) in modelValue" :key="id" :data-recipient-id="id"
          :class="['recipient-row', { 'is-dragging': dragged === id, 'insert-before': insertion?.id === id && !insertion.after, 'insert-after': insertion?.id === id && insertion.after }]"
          @dragover="over($event, id)" @drop="drop($event, id)">
        <button type="button" class="drag-handle" :draggable="!disabled" :disabled="disabled" :aria-label="`拖动${label} ${id}`" title="拖动调整顺序"
                @dragstart="start($event, id)" @dragend="clearDrag">⋮⋮</button>
        <span class="recipient-position" aria-hidden="true">{{ index + 1 }}.</span>
        <span class="recipient-label"><slot :id="id" :index="index">{{ id }}</slot></span>
        <button type="button" :disabled="disabled || index === 0" :aria-label="`上移${label} ${id}`" title="上移" @click="move(index, -1)">↑</button>
        <button type="button" :disabled="disabled || index === modelValue.length - 1" :aria-label="`下移${label} ${id}`" title="下移" @click="move(index, 1)">↓</button>
        <slot name="actions" :id="id" :index="index" />
      </li>
    </ol>
    <span class="sr-only" role="status" aria-live="polite">{{ announcement }}</span>
  </div>
</template>

<style scoped>
.order-help{font-size:12px;color:#64748b;margin:6px 0}
.recipient-order-list{padding:0;margin:0;list-style:none}
.recipient-row{position:relative;display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding:8px 4px;min-width:0;border-radius:4px}
.recipient-label{flex:1 1 120px;min-width:0;overflow-wrap:anywhere}
.recipient-position{color:#64748b;font-size:12px}
.recipient-row button{flex:none;border:1px solid #cbd5e1;border-radius:4px;background:#f8fafc;padding:4px 7px;color:#334155;cursor:pointer}
.recipient-row button:disabled{opacity:.4;cursor:default}
.recipient-row .drag-handle{cursor:grab;user-select:none;letter-spacing:-2px}
.recipient-row .drag-handle:active{cursor:grabbing}
.is-dragging{opacity:.45}
.insert-before:before,.insert-after:after{content:'';position:absolute;left:0;right:0;height:3px;background:#2563eb;pointer-events:none}
.insert-before:before{top:0}.insert-after:after{bottom:0}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
