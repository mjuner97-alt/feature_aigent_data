<script setup lang="ts">
/**
 * 维度级联选择器(下拉浮层 + 多选)
 *
 * 数据结构:PublishTargetGroup[] —— 按 orgType 分组,每组含若干具体组织 target。
 * 已选项 key 格式:"orgType:orgId"(与原逻辑保持一致)。
 *
 * 交互:
 *  - 点击触发器展开浮层,左列显示维度类型,选中某类型后右列显示该类型下组织 checkbox。
 *  - 已选项以标签形式展示在触发器内,可点 × 删除单个。
 *  - 支持清空全部、disabled 状态。
 *  - 点击组件外部关闭浮层。
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import type { PublishTargetGroup, PublishTarget } from '../types/skill';

const props = withDefaults(
  defineProps<{
    /** 维度分组数据 */
    groups: PublishTargetGroup[];
    /** 已选项 key 集合("orgType:orgId") */
    modelValue: Set<string>;
    /** 是否禁用 */
    disabled?: boolean;
    /** 占位提示 */
    placeholder?: string;
    /** 同一维度内最多可选数量,超出后禁止继续选中。默认 5。 */
    max?: number;
  }>(),
  { disabled: false, placeholder: '请选择维度(不选=个人维度)', max: 5 }
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: Set<string>): void;
}>();

const rootRef = ref<HTMLElement | null>(null);
const open = ref(false);
// 当前激活的维度类型(左列选中项),默认第一个
const activeOrgType = ref<string>('');

// 初始化激活项为第一个分组(若有)
watch(
  () => props.groups,
  gs => {
    if (gs.length > 0 && !gs.some(g => g.orgType === activeOrgType.value)) {
      activeOrgType.value = gs[0].orgType;
    }
  },
  { immediate: true }
);

// 当前激活分组
const activeGroup = computed<PublishTargetGroup | undefined>(() =>
  props.groups.find(g => g.orgType === activeOrgType.value)
);

// 右列筛选关键字:部门/小组/产品线过多时,按名称快速过滤当前维度下的组织列表(不影响已选项与其他维度)
const searchKeyword = ref('');
const hasSearch = computed(() => searchKeyword.value.trim().length > 0);
const filteredTargets = computed<PublishTarget[]>(() => {
  const g = activeGroup.value;
  if (!g) return [];
  const kw = searchKeyword.value.trim().toLowerCase();
  if (!kw) return g.targets;
  return g.targets.filter(t =>
    t.displayName.toLowerCase().includes(kw) ||
    t.fullLabel.toLowerCase().includes(kw)
  );
});
// 切换维度类型时清空筛选关键字
watch(activeOrgType, () => { searchKeyword.value = ''; });

// 当前激活维度下已选数量
const activeSelectedCount = computed<number>(() => {
  const g = activeGroup.value;
  if (!g) return 0;
  return g.targets.filter(t => isSelected(t)).length;
});

// 当前激活维度是否已达上限(用于禁用未选项)
const activeReachedMax = computed<boolean>(() => activeSelectedCount.value >= props.max);

// 某个 target 是否因达上限而应被禁用(已选项不受限,未选项在达上限时禁用)
function isTargetLocked(t: PublishTarget): boolean {
  if (props.disabled) return true;
  if (isSelected(t)) return false;
  return activeReachedMax.value;
}

// 已选项明细(用于标签展示)
const selectedItems = computed<{ key: string; label: string }[]>(() => {
  const items: { key: string; label: string }[] = [];
  for (const g of props.groups) {
    for (const t of g.targets) {
      const key = `${t.orgType}:${t.orgId}`;
      if (props.modelValue.has(key)) {
        items.push({ key, label: t.displayName });
      }
    }
  }
  return items;
});

// 某个 target 是否已选
function isSelected(t: PublishTarget): boolean {
  return props.modelValue.has(`${t.orgType}:${t.orgId}`);
}

// 某分组下是否全部已选
function isGroupAllSelected(g: PublishTargetGroup): boolean {
  return g.targets.length > 0 && g.targets.every(t => isSelected(t));
}

// 某分组下是否部分已选(半选状态)
function isGroupPartialSelected(g: PublishTargetGroup): boolean {
  const sel = g.targets.filter(t => isSelected(t)).length;
  return sel > 0 && sel < g.targets.length;
}

// 达到上限时的提示文案(临时展示,3 秒后自动清除)
const maxLimitTip = ref('');
let tipTimer: ReturnType<typeof setTimeout> | null = null;
function showMaxTip(msg: string) {
  maxLimitTip.value = msg;
  if (tipTimer) clearTimeout(tipTimer);
  tipTimer = setTimeout(() => { maxLimitTip.value = ''; }, 3000);
}

// 切换某个 target 的选中状态
// 维度互斥:不同 orgType 之间不能同时选中。选中其他维度的项时,自动清空原维度的选择;
// 同一维度内可多选(如同时选"开发一组"和"开发二组"),但受 max 限制。
function toggleTarget(t: PublishTarget) {
  if (props.disabled) return;
  const key = `${t.orgType}:${t.orgId}`;
  const cur = props.modelValue;
  if (cur.has(key)) {
    // 取消选中:仅删除该项
    const next = new Set(cur);
    next.delete(key);
    emit('update:modelValue', next);
    return;
  }
  // 新增选中:维度互斥,只保留同维度类型的已选项,丢弃其他维度
  const prefix = `${t.orgType}:`;
  const sameDim: string[] = [];
  for (const k of cur) {
    if (k.startsWith(prefix)) sameDim.push(k);
  }
  // 上限校验:同维度内已达 max 则禁止继续选中
  if (sameDim.length >= props.max) {
    showMaxTip(`同一维度内最多选择 ${props.max} 个,已达上限。如需更换,请先取消已选项。`);
    return;
  }
  const next = new Set<string>(sameDim);
  next.add(key);
  emit('update:modelValue', next);
}

// 切换某个分组的全选/全不选
// 维度互斥:全选某维度时,清空其他维度的已选项,仅保留并补全该分组。
// 上限校验:若该分组总数超过 max,仅选中前 max 个并提示。
function toggleGroupAll(g: PublishTargetGroup) {
  if (props.disabled) return;
  if (isGroupAllSelected(g)) {
    // 取消全选:仅清空该分组的项
    const next = new Set(props.modelValue);
    for (const t of g.targets) next.delete(`${t.orgType}:${t.orgId}`);
    emit('update:modelValue', next);
    return;
  }
  // 全选该维度:维度互斥,丢弃其他维度,只保留该分组项
  const all = g.targets;
  if (all.length > props.max) {
    // 超过上限:只取前 max 个
    const next = new Set<string>();
    for (let i = 0; i < props.max; i++) {
      const t = all[i];
      next.add(`${t.orgType}:${t.orgId}`);
    }
    emit('update:modelValue', next);
    showMaxTip(`该维度共 ${all.length} 个组织,最多只能选 ${props.max} 个,已为你选中前 ${props.max} 个。`);
    return;
  }
  const next = new Set<string>();
  for (const t of all) next.add(`${t.orgType}:${t.orgId}`);
  emit('update:modelValue', next);
}

// 删除单个已选标签
function removeTag(key: string) {
  if (props.disabled) return;
  const next = new Set(props.modelValue);
  next.delete(key);
  emit('update:modelValue', next);
}

// 清空全部
function clearAll() {
  if (props.disabled) return;
  if (props.modelValue.size === 0) return;
  emit('update:modelValue', new Set());
}

// 切换浮层开关
function toggleOpen() {
  if (props.disabled) return;
  open.value = !open.value;
}

// 点击组件外部关闭浮层
function handleClickOutside(e: MouseEvent) {
  if (!open.value) return;
  const target = e.target as Node;
  if (rootRef.value && !rootRef.value.contains(target)) {
    open.value = false;
  }
}

onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside);
});
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleClickOutside);
});
</script>

<template>
  <div ref="rootRef" class="dim-cascader" :class="{ disabled }">
    <!-- 触发器:展示已选标签 -->
    <div class="trigger" @click="toggleOpen">
      <div class="tags" v-if="selectedItems.length > 0">
        <span v-for="item in selectedItems" :key="item.key" class="tag">
          <span class="tag-text">{{ item.label }}</span>
          <span
            v-if="!disabled"
            class="tag-close"
            @click.stop="removeTag(item.key)"
            aria-label="移除"
          >×</span>
        </span>
      </div>
      <span v-else class="placeholder">{{ placeholder }}</span>
      <div class="trigger-right">
        <span
          v-if="selectedItems.length > 0 && !disabled"
          class="clear-btn"
          @click.stop="clearAll"
          aria-label="清空"
        >清空</span>
        <span class="arrow" :class="{ open }">▾</span>
      </div>
    </div>

    <!-- 下拉浮层 -->
    <div v-if="open" class="panel">
      <!-- 左列:维度类型 -->
      <div class="col-types">
        <div
          v-for="g in groups"
          :key="g.orgType"
          class="type-item"
          :class="{ active: activeOrgType === g.orgType }"
          @click="activeOrgType = g.orgType"
        >
          <span class="type-label">{{ g.typeLabel }}</span>
          <span class="type-count">{{ g.targets.filter(t => isSelected(t)).length }}/{{ g.targets.length }}</span>
        </div>
        <div v-if="groups.length === 0" class="empty">暂无可选维度</div>
      </div>
      <!-- 右列:该类型下的具体组织 -->
      <div class="col-targets">
        <div v-if="activeGroup" class="targets-header">
          <label v-if="!hasSearch" class="select-all" :class="{ disabled }">
            <input
              type="checkbox"
              :disabled="disabled"
              :checked="isGroupAllSelected(activeGroup)"
              :indeterminate.prop="isGroupPartialSelected(activeGroup)"
              @change="toggleGroupAll(activeGroup)"
            />
            <span>全选 {{ activeGroup.typeLabel }}</span>
          </label>
          <span v-else class="filter-hint">
            筛选结果 {{ filteredTargets.length }}/{{ activeGroup.targets.length }}
          </span>
          <span class="count-badge" :class="{ reached: activeReachedMax }">
            {{ activeSelectedCount }}/{{ max }}
          </span>
        </div>
        <div v-if="activeGroup && activeGroup.orgType !== 'COMPANY'" class="search-box">
          <input
            v-model="searchKeyword"
            type="text"
            class="search-input"
            :placeholder="`筛选${activeGroup.typeLabel}…`"
          />
        </div>
        <div class="targets-list">
          <label
            v-for="t in filteredTargets"
            :key="t.orgId"
            class="target-item"
            :class="{ checked: isSelected(t), disabled: isTargetLocked(t) }"
          >
            <input
              type="checkbox"
              :disabled="isTargetLocked(t)"
              :checked="isSelected(t)"
              @change="toggleTarget(t)"
            />
            <div class="target-text">
              <div class="target-name">{{ t.displayName }}</div>
            </div>
          </label>
          <div v-if="activeGroup && filteredTargets.length === 0" class="empty">无匹配组织</div>
        </div>
        <div v-if="maxLimitTip" class="max-tip">{{ maxLimitTip }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dim-cascader { position: relative; width: 100%; font-size: 14px; }
.dim-cascader.disabled { opacity: 0.7; }

.trigger {
  min-height: 36px;
  padding: 4px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  flex-wrap: wrap;
}
.dim-cascader.disabled .trigger { cursor: not-allowed; background: #f1f5f9; }
.trigger:hover { border-color: #94a3b8; }

.tags { display: flex; flex-wrap: wrap; gap: 4px; flex: 1; }
.tag {
  display: inline-flex; align-items: center; gap: 4px;
  background: #eff6ff; color: #1e40af;
  border: 1px solid #bfdbfe;
  border-radius: 4px; padding: 2px 6px; font-size: 12px; line-height: 1.4;
}
.tag-text { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag-close {
  cursor: pointer; color: #1e40af; font-weight: 600; padding: 0 2px;
  border-radius: 2px; line-height: 1;
}
.tag-close:hover { background: #dbeafe; color: #1e3a8a; }

.placeholder { color: #94a3b8; font-size: 13px; flex: 1; }
.trigger-right { display: flex; align-items: center; gap: 6px; color: #94a3b8; }
.clear-btn { font-size: 12px; color: #64748b; cursor: pointer; padding: 2px 4px; border-radius: 3px; }
.clear-btn:hover { background: #f1f5f9; color: #1e293b; }
.arrow { transition: transform 0.15s; font-size: 10px; }
.arrow.open { transform: rotate(180deg); }

.panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 50;
  width: 100%;
  min-width: 460px;
  max-height: 320px;
  display: flex;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  overflow: hidden;
}

.col-types {
  width: 160px; flex-shrink: 0;
  border-right: 1px solid #e2e8f0;
  overflow-y: auto;
  background: #f8fafc;
}
.type-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 12px; cursor: pointer; font-size: 13px; color: #1e293b;
  border-left: 2px solid transparent;
}
.type-item:hover { background: #f1f5f9; }
.type-item.active { background: #eff6ff; border-left-color: #3b82f6; color: #1e40af; font-weight: 600; }
.type-count { font-size: 11px; color: #94a3b8; }
.type-item.active .type-count { color: #3b82f6; }
.empty { padding: 16px 12px; font-size: 12px; color: #94a3b8; text-align: center; }

.col-targets { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.targets-header { padding: 6px 10px; border-bottom: 1px solid #f1f5f9; background: #fff; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.select-all { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #475569; cursor: pointer; }
.select-all.disabled { cursor: not-allowed; }
.select-all input { margin: 0; }
.count-badge { font-size: 11px; color: #64748b; background: #f1f5f9; padding: 1px 6px; border-radius: 8px; }
.count-badge.reached { color: #b45309; background: #fef3c7; }
.filter-hint { font-size: 12px; color: #64748b; }
.search-box { padding: 6px 10px; border-bottom: 1px solid #f1f5f9; }
.search-input {
  width: 100%; box-sizing: border-box;
  padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 4px;
  font-size: 13px; outline: none; color: #1e293b;
}
.search-input:focus { border-color: #3b82f6; }
.max-tip { font-size: 12px; color: #92400e; background: #fef3c7; border-top: 1px solid #fde68a; padding: 6px 10px; line-height: 1.4; }

.targets-list { flex: 1; overflow-y: auto; padding: 4px 0; }
.target-item {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 6px 10px; cursor: pointer; font-size: 13px; color: #1e293b;
}
.target-item:hover { background: #f8fafc; }
.target-item.checked { background: #eff6ff; }
.target-item.disabled { cursor: not-allowed; opacity: 0.6; }
.target-item input { margin: 2px 0 0 0; flex-shrink: 0; }
.target-text { display: flex; flex-direction: column; gap: 2px; }
.target-name { font-weight: 500; }
.target-full { font-size: 11px; color: #94a3b8; }
</style>
