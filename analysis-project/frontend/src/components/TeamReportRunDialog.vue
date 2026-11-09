<script setup lang="ts">
/** 部门及团队报告手动执行入口，维护本次临时选择并在提交成功后交由列表切换到执行记录。 */
import { computed, onUnmounted, ref, watch } from 'vue';
import type { SkillFlow } from '../types/skillFlow';
import { flowBatchProgress, runSkillFlowBatch } from '../api/skillFlow';
const props = defineProps<{ flow: SkillFlow | null; open: boolean }>();
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'submitted'): void }>();
const requestId = ref('');
const search = ref(''),
  selected = ref<string[]>([]),
  busy = ref(false),
  error = ref(''),
  progress = ref<Awaited<ReturnType<typeof flowBatchProgress>>>([]);
let timer: ReturnType<typeof setInterval> | undefined;
let generation = 0;
const statusReady = ref(false);
const department = ref(''),
  reviewSelected = ref(false);
const key = (t: { teamId: string | null }) => t.teamId || 'department';
const currentUser = globalThis.localStorage?.getItem('skill-user-id') || 'demo-user';
const targets = computed(() =>
  (props.flow?.teamReport?.targets || []).filter(
    () => currentUser === props.flow?.createdBy || (props.flow?.executors ?? []).includes(currentUser)
  )
);
const departmentName = (t: (typeof targets.value)[number]) =>
  t.departmentName || props.flow?.teamReport?.departmentName || '未分配部门';
const groups = computed(() => {
  const grouped = new Map<string, { name: string; targets: typeof targets.value }>();
  for (const target of targets.value) {
    const name = departmentName(target);
    if (!grouped.has(name)) grouped.set(name, { name, targets: [] });
    grouped.get(name)!.targets.push(target);
  }
  return [...grouped.values()];
});
const selectedKeys = computed(() => new Set(selected.value));
const currentDepartment = computed(() => groups.value.find((g) => g.name === department.value)?.name || '');
const query = computed(() => search.value.trim().toLowerCase());
const visible = computed(() =>
  targets.value.filter((t) =>
    reviewSelected.value
      ? selectedKeys.value.has(key(t))
      : !!currentDepartment.value &&
        departmentName(t) === currentDepartment.value &&
        (!query.value || (t.teamName || departmentName(t)).toLowerCase().includes(query.value))
  )
);
const selectedDepartmentCount = computed(
  () => groups.value.filter((g) => g.targets.some((t) => selectedKeys.value.has(key(t)))).length
);
const selectedCount = (items: typeof targets.value) => items.filter((t) => selectedKeys.value.has(key(t))).length;
const listTitle = computed(() =>
  reviewSelected.value ? '已选团队' : currentDepartment.value || '请选择部门'
);
function chooseDepartment(name: string) {
  department.value = name;
  search.value = '';
  reviewSelected.value = false;
}
function toggleReview() {
  reviewSelected.value = !reviewSelected.value;
  search.value = '';
}
/** 仅追加当前部门或搜索结果，保留其他部门已选对象。 */
function selectVisible() {
  selected.value = [...new Set([...selected.value, ...visible.value.map(key)])];
}
/** 仅移除当前可见对象，避免搜索或切换部门时误清空其他选择。 */
function clearVisible() {
  const keys = new Set(visible.value.map(key));
  selected.value = selected.value.filter((id) => !keys.has(id));
}
watch(search, () => {
  if (query.value) reviewSelected.value = false;
});
const terminal = (status: string) => ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED'].includes(status);
const active = computed(() => progress.value.some((row) => !terminal(row.status)));
/** 刷新当前轮次状态；版本号用于丢弃弹窗关闭或重新打开前发出的旧请求。 */
async function refresh() {
  if (!props.flow || !props.open) return;
  const id = props.flow.id,
    version = generation;
  try {
    const result = await flowBatchProgress(id);
    if (version === generation && props.open) {
      progress.value = result;
      statusReady.value = true;
    }
  } catch (e) {
    if (version === generation && props.open) error.value = String(e);
  }
}
/** 等待批次创建成功后关闭选择弹窗，并通知父列表跳转执行记录。 */
async function submit() {
  if (
    !props.flow ||
    !statusReady.value ||
    props.flow.enabled === false ||
    busy.value ||
    active.value ||
    !selected.value.length
  )
    return;
  busy.value = true;
  error.value = '';
  try {
    // 失败重试沿用本次请求标识，避免网络重试重复创建批次。
    await runSkillFlowBatch(props.flow.id, selected.value, requestId.value);
    requestId.value = globalThis.crypto.randomUUID();
    // 批次已入队即可跳转，不等待所有团队执行结束。
    emit('update:open', false);
    emit('submitted');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败';
    await refresh();
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.open,
  async (open) => {
    const version = ++generation;
    clearInterval(timer);
    statusReady.value = false;
    if (!open) return;
    requestId.value = globalThis.crypto.randomUUID();
    search.value = '';
    department.value = '';
    reviewSelected.value = false;
    selected.value = [];
    progress.value = [];
    error.value = '';
    await refresh();
    if (version === generation && props.open) timer = setInterval(refresh, 3000);
  },
  { immediate: true }
);
onUnmounted(() => {
  generation++;
  clearInterval(timer);
});
</script>
<template>
  <el-dialog
    align-center
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    class="team-report-run-dialog"
    :model-value="open"
    :title="(flow?.name || '') + ' · 手动执行'"
    width="min(800px, calc(100vw - 32px))"
    @update:model-value="emit('update:open', $event)"
  >
    <div class="run-content">
      <p v-if="error" role="alert" class="run-error">{{ error }}</p>
      <p class="selection-hint">先选择部门，再勾选需要执行的团队；可切换部门继续选择。</p>
      <div class="selection-toolbar">
        <div class="search-field"
          ><svg
            aria-hidden="true"
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
          >
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="m16 16 4.5 4.5" /></svg
          ><input
              v-model="search"
              :disabled="!currentDepartment || reviewSelected"
              :placeholder="reviewSelected ? '查看已选团队中' : currentDepartment ? '搜索当前部门的团队' : '请先在下方选择部门'"
              aria-label="搜索当前部门的团队"
            /><button
            v-if="search"
            type="button"
            class="clear-search"
            aria-label="清除搜索"
            @click="search = ''"
            >×</button
          ></div
        >
      </div>
      <div class="team-selection" aria-label="选择执行团队">
        <nav class="department-nav" aria-label="部门导航">
          <div class="nav-heading"
            >1. 选择部门 <span>{{ groups.length }}</span></div
          >
          <div class="department-list"
            ><button
              v-for="group in groups"
              :key="group.name"
              type="button"
              class="department-item"
              :class="{ current: !query && !reviewSelected && currentDepartment === group.name }"
              :aria-current="!query && !reviewSelected && currentDepartment === group.name ? 'true' : undefined"
              @click="chooseDepartment(group.name)"
              ><span>{{ group.name }}</span
              ><small>{{ selectedCount(group.targets) }} / {{ group.targets.length }}</small></button
            ></div
          >
        </nav>
        <section class="team-panel" aria-label="团队列表">
          <div class="list-heading"
            ><div
              ><strong>{{ reviewSelected ? listTitle : `2. ${listTitle}` }}</strong
              ><span>{{ visible.length }} 个团队</span></div
            ><div class="selection-actions"
              ><button type="button" :disabled="busy || !visible.length" @click="selectVisible">{{
                reviewSelected ? '全选当前结果' : query ? '全选搜索结果' : '全选当前部门'
              }}</button
              ><button type="button" :disabled="busy || !selectedCount(visible)" @click="clearVisible">{{
                reviewSelected ? '清空当前结果' : query ? '清空搜索结果' : '清空当前部门'
              }}</button></div
            ></div
          >
          <div class="team-list"
            ><label
              v-for="target in visible"
              :key="key(target)"
              class="team-option"
              :class="{ selected: selectedKeys.has(key(target)) }"
              ><input v-model="selected" type="checkbox" :value="key(target)" :disabled="busy" /><span class="team-copy"
                ><strong>{{ target.teamName || departmentName(target) }}</strong
                ><span v-if="query || reviewSelected" class="team-department">{{ departmentName(target) }}</span></span
              ><small v-if="!target.to.length && target.notifyEnabled !== false" class="recipient-warning"
                >未配置收件人</small
              ></label
            ><div v-if="!visible.length" class="empty-teams">{{
              !targets.length
                ? '暂无可执行团队'
                : reviewSelected
                  ? '尚未选择团队'
                  : !currentDepartment
                    ? '请先选择左侧部门，再勾选团队'
                    : '当前部门没有匹配的团队'
            }}</div></div
          >
        </section>
      </div>
      <div class="run-footer"
        ><div class="selection-summary"
          ><button type="button" class="summary-toggle" :aria-expanded="reviewSelected" @click="toggleReview"
            >已选 <strong>{{ selectedDepartmentCount }}</strong> 个部门、<strong>{{ selected.length }}</strong> 个团队
            <span>{{ reviewSelected ? '返回选择' : '查看已选' }}</span></button
          ><button type="button" class="clear-all" :disabled="busy || !selected.length" @click="selected = []"
            >清空全部</button
          ></div
        ><div
          ><button type="button" :disabled="busy" @click="emit('update:open', false)">取消</button
          ><button
            class="submit-run"
            type="button"
            :disabled="!statusReady || busy || active || !selected.length || flow?.enabled === false"
            @click="submit"
            >{{
              !statusReady ? '读取批次状态…' : busy ? '提交中…' : active ? '等待本轮全部结束' : '确认提交执行'
            }}</button
          ></div
        ></div
      >
    </div>
  </el-dialog>
</template>
<style scoped>
.run-content {
  color: #334155;
  padding-top: 8px;
}
.run-error {
  color: #b91c1c;
  margin: 0 0 16px;
}
.selection-hint {
  margin: 0 0 14px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}
.selection-toolbar {
  display: flex;
  margin-bottom: 16px;
}
.search-field {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  padding: 0 12px;
  border: 1px solid #d8e0eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #94a3b8;
}
.search-field:focus-within {
  border-color: #60a5fa;
  box-shadow: 0 0 0 3px #eff6ff;
  background: #fff;
}
.search-field input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  padding: 11px 0;
  color: #334155;
  font: inherit;
  font-size: 14px;
}
.search-field input:disabled {
  cursor: not-allowed;
}
.search-field input::placeholder {
  color: #94a3b8;
}
button {
  padding: 9px 14px;
  border: 1px solid #d8e0eb;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
button:hover {
  background: #f8fafc;
  border-color: #bfdbfe;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 2px;
}
.clear-search {
  border: 0;
  padding: 0 3px;
  font-size: 20px;
  background: transparent;
}
.team-selection {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  height: min(420px, 55vh);
  min-height: 220px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}
.department-nav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #f8fafc;
  border-right: 1px solid #e2e8f0;
}
.nav-heading {
  padding: 17px 14px 13px;
  font-size: 13px;
  font-weight: 600;
}
.nav-heading span {
  margin-left: 6px;
  color: #94a3b8;
  font-weight: 400;
}
.department-list {
  overflow: auto;
  min-height: 0;
  padding: 0 8px 8px;
}
.department-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  margin-bottom: 4px;
  padding: 11px 8px;
}
.department-item > span {
  min-width: 0;
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.department-item small {
  flex-shrink: 0;
  font-size: 12px;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}
.department-item.current {
  background: #eaf2ff;
  color: #1d4ed8;
  border-color: #dbeafe;
}
.department-item.current small {
  color: #2563eb;
}
.team-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.list-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 10px 14px;
  box-sizing: border-box;
  border-bottom: 1px solid #eef2f6;
}
.list-heading > div:first-child {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.list-heading strong {
  font-size: 14px;
  overflow-wrap: anywhere;
}
.list-heading span {
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
}
.selection-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.selection-actions button {
  padding: 5px 7px;
  border: 0;
  color: #2563eb;
  font-size: 12px;
}
.team-list {
  overflow: auto;
  min-height: 0;
  flex: 1;
  padding: 4px 0;
}
.team-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #f1f5f9;
  background: #fff;
  cursor: pointer;
  min-width: 0;
  min-height: 46px;
  box-sizing: border-box;
}
.team-option:last-child {
  border-bottom: 0;
}
.team-option:hover {
  background: #f8fafc;
}
.team-option.selected {
  background: #f5f9ff;
}
.team-option input {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: #2563eb;
  flex-shrink: 0;
}
.team-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.team-copy strong {
  font-size: 14px;
  font-weight: 400;
  overflow-wrap: anywhere;
}
.team-department {
  color: #94a3b8;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.recipient-warning {
  color: #b45309;
  font-size: 12px;
  flex-shrink: 0;
}
.empty-teams {
  text-align: center;
  padding: 48px 16px;
  color: #94a3b8;
  font-size: 13px;
}
.run-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
  color: #64748b;
  font-size: 13px;
}
.run-footer > div {
  display: flex;
  gap: 10px;
}
.run-footer .selection-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  min-width: 0;
}
.summary-toggle,
.clear-all {
  padding: 4px 0;
  border: 0;
  background: transparent;
  text-align: left;
  font-size: 12px;
}
.summary-toggle > span {
  color: #2563eb;
  margin-left: 6px;
  white-space: nowrap;
}
.clear-all {
  color: #94a3b8;
}
.run-footer strong {
  color: #2563eb;
}
.submit-run {
  background: #2563eb;
  color: #fff;
  border-color: #2563eb;
  font-weight: 600;
  white-space: nowrap;
}
.submit-run:hover {
  background: #1d4ed8;
}
.submit-run:disabled {
  background: #e2e8f0;
  border-color: #e2e8f0;
  color: #64748b;
}
@media (max-width: 600px) {
  .team-selection {
    grid-template-columns: 120px minmax(0, 1fr);
  }
  .department-item {
    flex-wrap: wrap;
    gap: 2px;
  }
  .list-heading {
    flex-wrap: wrap;
    padding: 10px;
  }
  .selection-actions {
    gap: 4px;
  }
  .team-option {
    padding: 12px 10px;
    flex-wrap: wrap;
    gap: 8px;
  }
  .recipient-warning {
    margin-left: 23px;
  }
  .run-footer {
    flex-wrap: wrap;
  }
  .run-footer > div:last-child {
    margin-left: auto;
  }
  .run-footer .selection-summary {
    width: 100%;
  }
}
</style>
