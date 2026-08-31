<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { Calendar, EditPen } from '@element-plus/icons-vue';
import { parseScheduleRules, stringifyScheduleRules, type ScheduleRules } from '../utils/scheduleRules';
const props = defineProps<{ modelValue?: string | null }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: string | null): void }>();
const days = [['MON','周一'],['TUE','周二'],['WED','周三'],['THU','周四'],['FRI','周五'],['SAT','周六'],['SUN','周日']] as const;
const rules = reactive<ScheduleRules>({}); const open = ref(false); let syncing = false;
const summary = computed(() => days.filter(([key]) => key in rules).map(([,label]) => label).join('、'));
function load(value?: string | null) { syncing = true; Object.keys(rules).forEach(k => delete rules[k]); Object.assign(rules, parseScheduleRules(value)); syncing = false; }
function toggle(day: string) { if (day in rules) delete rules[day]; else rules[day] = []; }
function clearAll() { Object.keys(rules).forEach(k => delete rules[k]); }
watch(() => props.modelValue, load, { immediate: true });
watch(rules, () => { if (!syncing) emit('update:modelValue', stringifyScheduleRules(rules)); }, { deep: true });
</script>
<template>
  <div>
    <button type="button" class="trigger" @click="open = true"><el-icon><Calendar /></el-icon><span :class="{ muted: !summary }">{{ summary || '请选择星期' }}</span><el-icon class="edit"><EditPen /></el-icon></button>
    <el-dialog v-model="open" title="选择自动触发星期" width="420px" append-to-body>
      <div class="weekday-grid">
        <button v-for="([key,label]) in days" :key="key" type="button" :class="{ active: key in rules }" :aria-pressed="key in rules" @click="toggle(key)">{{ label }}</button>
      </div>
      <div class="hint">所选星期内，依赖数据准备完成后立即自动触发；不选默认每天都执行。</div>
      <template #footer><el-button v-if="summary" text @click="clearAll">清空</el-button><el-button type="primary" @click="open = false">完成</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.trigger { width:100%; height:36px; display:grid; grid-template-columns:18px 1fr 18px; align-items:center; gap:8px; padding:0 10px; border:1px solid #cbd5e1; border-radius:6px; background:#fff; color:#334155; text-align:left; cursor:pointer; font:inherit; }
.trigger:hover { border-color:#409eff; }.trigger span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }.trigger .muted { color:#94a3b8; }.edit { color:#64748b; }
.weekday-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; }.weekday-grid button { height:38px; border:1px solid #cbd5e1; border-radius:5px; background:#fff; color:#475569; cursor:pointer; }.weekday-grid button:hover { border-color:#409eff; color:#409eff; }.weekday-grid button.active { background:#409eff; border-color:#409eff; color:#fff; font-weight:600; }
.hint { margin-top:14px; color:#94a3b8; font-size:12px; }
</style>
