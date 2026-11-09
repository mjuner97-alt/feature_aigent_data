<script setup lang="ts">
import { computed, ref } from 'vue';
import type { TeamReportConfig, TeamReportTarget } from '../types/skillFlow';

const props = defineProps<{ config: TeamReportConfig; modelValue: string | null; disabled?: boolean }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: string | null): void }>();
const open = ref(false);
const department = ref('');
const search = ref('');
const pending = ref<string | null>(null);
const noun = computed(() => props.config.reportType === 'TEAM' ? '团队' : '部门');
const departmentName = (target: TeamReportTarget) => target.departmentName || props.config.departmentName || '未分配部门';
const groups = computed(() => [...new Set(props.config.targets.map(departmentName))]);
const chosen = computed(() => props.config.targets.find(target => target.teamId === props.modelValue));
const draft = computed(() => props.config.targets.find(target => target.teamId === pending.value));
const description = (target: TeamReportTarget) => target.teamName
  ? `${departmentName(target)} / ${target.teamName}` : departmentName(target);
const visible = computed(() => props.config.targets.filter(target => departmentName(target) === department.value
  && (target.teamName || departmentName(target)).toLowerCase().includes(search.value.trim().toLowerCase())));
function show() {
  if (props.disabled || !props.config.targets.length) return;
  pending.value = props.modelValue;
  department.value = chosen.value ? departmentName(chosen.value) : groups.value[0] || '';
  search.value = '';
  open.value = true;
}
function confirm() {
  if (props.disabled || !draft.value) return;
  emit('update:modelValue', pending.value);
  open.value = false;
}
</script>

<template>
  <div class="debug-picker">
    <span class="picker-label">试跑{{ noun }}</span>
    <button class="picker-trigger" type="button" aria-label="节点试跑对象" aria-haspopup="dialog"
      :aria-expanded="open" :disabled="disabled || !config.targets.length" @click="show">
      <span><small>{{ chosen ? '当前已选' : '尚未选择' }}</small><strong>{{ chosen ? description(chosen) : `请选择试跑${noun}` }}</strong></span>
      <span class="change-label">更换 <span aria-hidden="true">⌄</span></span>
    </button>
    <el-dialog v-model="open" append-to-body align-center title="选择节点试跑对象" width="min(800px, calc(100vw - 32px))">
      <div class="picker-content">
        <p class="picker-hint">先选择部门，再选择本次试跑使用的{{ noun }}；每次只能选择一个。</p>
        <input v-model="search" class="picker-search" :placeholder="`搜索当前部门的${noun}`" :aria-label="`搜索当前部门的${noun}`" />
        <div class="picker-columns">
          <nav class="picker-departments" aria-label="试跑部门导航">
            <div class="column-heading">1. 选择部门 <small>{{ groups.length }}</small></div>
            <button v-for="name in groups" :key="name" type="button" class="department-button"
              :class="{ current: department === name }" :aria-current="department === name ? 'true' : undefined"
              @click="department = name; search = ''">
              <span>{{ name }}</span><small v-if="draft && departmentName(draft) === name">已选</small>
            </button>
          </nav>
          <section class="picker-teams" :aria-label="`选择试跑${noun}`">
            <div class="column-heading">2. {{ department }} <small>{{ visible.length }} 个{{ noun }}</small><span class="single-badge">单选</span></div>
            <div class="picker-options">
              <label v-for="target in visible" :key="target.teamId || 'department'" class="picker-option" :class="{ selected: pending === target.teamId }">
                <input v-model="pending" type="radio" name="node-debug-target" :value="target.teamId" :disabled="disabled" />
                <span>{{ target.teamName || departmentName(target) }}</span><small v-if="pending === target.teamId">已选择</small>
              </label>
              <p v-if="!visible.length" class="picker-empty">没有匹配的{{ noun }}</p>
            </div>
          </section>
        </div>
        <div class="picker-footer">
          <span>已选：<strong>{{ draft ? description(draft) : '尚未选择' }}</strong></span>
          <div><button type="button" @click="open = false">取消</button><button type="button" class="confirm" :disabled="disabled || !draft" @click="confirm">确认选择</button></div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.debug-picker { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 220px; max-width: 560px; }
.picker-label { white-space: nowrap; font-size: 12px; color: #475569; }
button { font: inherit; cursor: pointer; border: 1px solid #d8e0eb; background: #fff; color: #475569; border-radius: 6px; padding: 9px 14px; }
button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible, input:focus-visible { outline: 2px solid #60a5fa; outline-offset: 2px; }
.picker-trigger { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex: 1; min-width: 0; text-align: left; border-color: #bfdbfe; padding: 10px 14px; }
.picker-trigger:hover { background: #eff6ff; border-color: #60a5fa; }
.picker-trigger > span:first-child { display: grid; gap: 4px; min-width: 0; }
.picker-trigger small { color: #64748b; font-size: 11px; }
.picker-trigger strong { color: #1d4ed8; font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }
.change-label { white-space: nowrap; font-size: 12px; color: #2563eb; }
.picker-content { color: #334155; }
.picker-hint { margin: 0 0 16px; font-size: 13px; color: #64748b; }
.picker-search { width: 100%; box-sizing: border-box; border: 1px solid #d8e0eb; border-radius: 8px; background: #f8fafc; padding: 12px 14px; margin-bottom: 16px; font: inherit; color: #334155; }
.picker-columns { display: grid; grid-template-columns: 220px minmax(0, 1fr); height: min(360px, 48vh); min-height: 200px; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
.picker-departments { background: #f8fafc; border-right: 1px solid #e2e8f0; overflow: auto; }
.column-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 16px; font-size: 14px; font-weight: 600; border-bottom: 1px solid #eef2f6; overflow-wrap: anywhere; }
.column-heading small { font-size: 12px; font-weight: 400; color: #94a3b8; }
.department-button { display: flex; justify-content: space-between; align-items: center; gap: 8px; width: calc(100% - 16px); margin: 6px 8px; border-color: transparent; background: transparent; text-align: left; overflow-wrap: anywhere; }
.department-button.current { background: #eaf2ff; color: #1d4ed8; border-color: #dbeafe; }
.department-button small { flex-shrink: 0; color: #2563eb; }
.picker-teams { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.single-badge { margin-left: auto; padding: 2px 7px; border-radius: 4px; background: #eff6ff; color: #2563eb; font-size: 12px; font-weight: 400; }
.picker-options { overflow: auto; }
.picker-option { display: flex; align-items: center; gap: 12px; padding: 16px; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
.picker-option:hover { background: #f8fafc; }
.picker-option.selected { background: #eff6ff; color: #1d4ed8; box-shadow: inset 3px 0 #3b82f6; }
.picker-option input { width: 16px; height: 16px; margin: 0; accent-color: #2563eb; flex-shrink: 0; }
.picker-option > span { flex: 1; overflow-wrap: anywhere; }
.picker-option small { color: #2563eb; white-space: nowrap; }
.picker-empty { text-align: center; padding: 32px 16px; color: #94a3b8; }
.picker-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 20px; padding-top: 16px; border-top: 1px solid #e2e8f0; font-size: 13px; }
.picker-footer > span { overflow-wrap: anywhere; }
.picker-footer strong { font-weight: 500; color: #2563eb; }
.picker-footer > div { display: flex; flex-shrink: 0; gap: 10px; }
.confirm { background: #2563eb; border-color: #2563eb; color: #fff; }
@media (max-width: 600px) { .picker-columns { grid-template-columns: 120px minmax(0, 1fr); } .column-heading, .picker-option { padding: 12px; } .picker-footer { flex-wrap: wrap; } .picker-footer > div { margin-left: auto; } }
</style>
