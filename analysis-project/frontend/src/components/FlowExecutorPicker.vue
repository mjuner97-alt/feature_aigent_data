<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { batchUserNames, searchSkillUsers } from '../api/skill';
const props = defineProps<{ modelValue: string[]; ownerId: string }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: string[]): void }>();
const keyword = ref('');
const busy = ref(false);
const error = ref('');
const names = ref<Record<string, string>>({});
const results = ref<{ userId: string; name: string }[]>([]);
let searchVersion = 0, nameVersion = 0;
onUnmounted(() => { searchVersion++; nameVersion++; });
watch(() => [props.ownerId, ...props.modelValue], async () => {
  const version = ++nameVersion;
  try {
    const found = await batchUserNames([props.ownerId, ...props.modelValue].filter(Boolean));
    if (version === nameVersion) names.value = { ...names.value, ...found };
  } catch { /* User IDs still identify saved executors if name lookup is unavailable. */ }
}, { immediate: true });
watch(keyword, () => { searchVersion++; busy.value = false; results.value = []; error.value = ''; });
async function search() {
  if (!keyword.value.trim()) return;
  const version = ++searchVersion;
  busy.value = true; error.value = ''; results.value = [];
  try {
    const found = await searchSkillUsers(keyword.value.trim());
    if (version !== searchVersion) return;
    results.value = found;
    if (!found.length) error.value = '未找到匹配人员';
  } catch { if (version === searchVersion) error.value = '搜索失败，请重试'; }
  finally { if (version === searchVersion) busy.value = false; }
}
function add(item: { userId: string; name: string }) {
  if (item.userId === props.ownerId || props.modelValue.includes(item.userId)) return;
  names.value[item.userId] = item.name || item.userId;
  emit('update:modelValue', [...props.modelValue, item.userId]);
}
</script>
<template>
  <div class="executor-picker">
    <h4>可执行人（非必填）</h4>
    <p>创建人始终可以执行，不能移除；不添加其他人时，仅创建人可以手动执行。此名单仅用于手动执行授权。</p>
    <div class="executor-owner">创建人：{{ names[ownerId] || ownerId }}({{ ownerId }})</div>
    <div class="executor-search"><input v-model="keyword" placeholder="搜索可执行人姓名或统一认证号" @keyup.enter="search" /><button type="button" class="btn" :disabled="busy || !keyword.trim()" @click="search">搜索可执行人</button></div>
    <p v-if="error" role="status">{{ error }}</p>
    <div class="executor-results"><div v-for="item in results.filter(person => person.userId !== ownerId && !modelValue.includes(person.userId))" :key="item.userId"><span>{{ item.name || item.userId }}({{ item.userId }})</span><button type="button" class="btn" :aria-label="`添加可执行人 ${item.userId}`" @click="add(item)">添加</button></div></div>
    <div class="executor-chips"><span v-for="id in modelValue" :key="id" class="executor-chip">{{ names[id] || id }}({{ id }})<button type="button" :aria-label="`移除可执行人 ${id}`" @click="emit('update:modelValue', modelValue.filter(value => value !== id))">×</button></span></div>
  </div>
</template>
<style scoped>
.executor-picker h4 { color: #0f172a; font-size: 15px; font-weight: 600; }
.executor-picker{display:grid;gap:10px;margin-top:14px;padding:14px;border:1px solid #dbeafe;border-radius:8px}.executor-picker h4,.executor-picker p{margin:0}.executor-picker p,.executor-owner{font-size:12px;color:#64748b;line-height:1.6}.executor-search{display:flex;gap:8px}.executor-search input{flex:1;min-width:0;padding:8px;border:1px solid #cbd5e1;border-radius:6px}.executor-results>div{display:flex;align-items:center;gap:12px;margin:6px 0}.executor-chips{display:flex;gap:6px;flex-wrap:wrap}.executor-chip{padding:5px 8px;background:#dbeafe;border-radius:5px;font-size:12px;color:#1e3a8a}.executor-chip button{margin-left:6px;border:0;background:transparent;cursor:pointer;color:inherit}.btn{border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc;padding:6px 10px;cursor:pointer;white-space:nowrap}.btn:disabled{opacity:.5;cursor:default}
</style>
