<script setup lang="ts">
/** 组织督办人配置，支持查看全部关系和维护有权限的部门。 */

import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PptListFilterBar from './PptListFilterBar.vue';
import { addPptFeedbackSupervisors, deletePptFeedbackSupervisor, getPptFeedbackSupervisors, listPptReportDepartments,
  searchPptDirectoryPeople,
  updatePptFeedbackSupervisor, type PptFeedbackSupervisor, type PptPerson } from '../../api/pptAnomaly';

const saved = ref<PptFeedbackSupervisor[]>([]);
const departments = ref<string[]>([]);
const editableDepartments = ref<string[]>([]);
const props = defineProps<{ canManageAllConfigurations: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const department = ref('');
const visibleAssignments = computed(() => saved.value.flatMap(person => {
  const names = person.supervisorDepartments.length ? person.supervisorDepartments : [''];
  return [...new Set(names)]
    .filter(name => !department.value || department.value === name)
    .map(name => ({ name, person, key: JSON.stringify([name, person.userId]) }));
}));
const showActions = computed(() => visibleAssignments.value.some(row => canMaintain(row.name)));

const addOpen = ref(false);
const editing = ref(false);
const selected = ref<string[]>([]);
const selectedDepartment = ref('');
const choices = ref(new Map<string, PptPerson>());
const results = ref<PptPerson[]>([]);
const loading = ref(true);
const searching = ref(false);
const saving = ref(false);
const changingId = ref<string>();
const loadError = ref('');
const searchError = ref('');
const saveError = ref('');
let searchId = 0;

const options = computed(() => {
  const people = new Map(results.value.map(person => [person.userId, person]));
  for (const id of selected.value) {
    const person = choices.value.get(id);
    if (person) people.set(id, person);
  }
  return [...people.values()];
});

/** 加载督办人、全部部门筛选项和可维护部门。 */
async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [people, options] = await Promise.all([getPptFeedbackSupervisors(), listPptReportDepartments()]);
    // 历史督办关系中的部门也可作为筛选项。
    const listedDepartments = options.map(item => item.name);
    const assignedDepartments = people.flatMap(person => person.supervisorDepartments);
    departments.value = [...new Set([...listedDepartments, ...assignedDepartments])].sort();
    // 部门筛选显示全部选项，新增和移除使用有维护权限的部门。
    editableDepartments.value = options.filter(item => item.editable).map(item => item.name);
    saved.value = people;
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '督办人配置加载失败';
  } finally {
    loading.value = false;
  }
}

/** 重置人员搜索和部门选项，打开新增督办关系弹窗。 */
function openAdd() {
  searchId++;
  selected.value = [];
  selectedDepartment.value = '';
  choices.value.clear();
  results.value = [];
  searching.value = false;
  searchError.value = '';
  saveError.value = '';
  editing.value = false;
  addOpen.value = true;
}

/** 使用该部门的全部督办人打开编辑弹窗。 */
function openEdit(name: string) {
  searchId++;
  editing.value = true;
  const people = saved.value.filter(person => person.supervisorDepartments.includes(name));
  selected.value = people.map(person => person.userId);
  selectedDepartment.value = name;
  choices.value.clear();
  for (const person of people) choices.value.set(person.userId, {
    userId: person.userId,
    name: person.name,
    department: person.departments[0] || '',
  });
  results.value = [];
  searching.value = false;
  searchError.value = '';
  saveError.value = '';
  addOpen.value = true;
}

/** 按姓名或认证号搜索督办人候选，保留当前选中人员。 */
async function search(keyword: string) {
  const id = ++searchId;
  const term = keyword.trim();
  results.value = [];
  searchError.value = '';
  searching.value = Boolean(term);
  if (!term) return;
  try {
    const people = await searchPptDirectoryPeople(term);
    if (id !== searchId) return;
    people.forEach(person => choices.value.set(person.userId, person));
    results.value = people;
  } catch {
    if (id === searchId) searchError.value = '人员搜索失败，请重试';
  } finally {
    if (id === searchId) searching.value = false;
  }
}

/** 追加所选部门的督办关系，并刷新列表和维护权限。 */
async function add() {
  if (!selected.value.length || !selectedDepartment.value || saving.value) return;
  saving.value = true;
  saveError.value = '';
  try {
    saved.value = await addPptFeedbackSupervisors(selected.value, [selectedDepartment.value]);
    addOpen.value = false;
    // 操作人如果新增了自己的督办关系，父页面也需要更新授权范围。
    emit('changed');
    await load();
    ElMessage.success('反馈督办部门已保存');
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : '督办人新增失败';
  } finally {
    saving.value = false;
  }
}

/** 保存督办人部门编辑。 */
async function saveEdit() {
  if (!editing.value || !selectedDepartment.value || !selected.value.length || saving.value) return;
  saving.value = true;
  saveError.value = '';
  try {
    saved.value = await updatePptFeedbackSupervisor(selected.value, [selectedDepartment.value]);
    addOpen.value = false;
    editing.value = false;
    emit('changed');
    await load();
    ElMessage.success('编辑成功');
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : '督办人编辑失败';
  } finally {
    saving.value = false;
  }
}

/** 统一显示人员姓名和认证号。 */
function personLabel(person: Pick<PptPerson, 'userId' | 'name'>) {
  return person.name && person.name !== person.userId ? `${person.name}（${person.userId}）` : person.userId;
}

function canMaintain(name: string) {
  return props.canManageAllConfigurations || editableDepartments.value.includes(name);
}

/** 确认后移除该人员在当前部门的督办关系。 */
async function remove(person: PptFeedbackSupervisor, name: string) {
  if (changingId.value) return;
  changingId.value = name || person.userId;
  try {
    const message = `从「${name || '未配置'}」移除督办人「${personLabel(person)}」？`;
    await ElMessageBox.confirm(message, '移除组织督办人', {
      type: 'warning', customClass: 'ppt-confirm-box', confirmButtonText: '删除', cancelButtonText: '取消',
    });
    saved.value = await deletePptFeedbackSupervisor(person.userId, name || undefined);
    // 移除自身的督办关系也可能收回相应部门的维护权限。
    emit('changed');
    await load();
    ElMessage.success('删除成功');
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(cause instanceof Error ? cause.message : '督办人删除失败');
    }
  } finally {
    changingId.value = undefined;
  }
}

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="supervisor-settings">
    <PptListFilterBar>
      <template #leading><p class="supervisor-note">待反馈通知和逾期提醒邮件会抄送组织督办人。</p></template>
      <el-select v-model="department" filterable clearable aria-label="筛选督办部门"
        placeholder="全部督办部门" class="ppt-filter-select" :disabled="loading || !!loadError">
        <el-option v-for="name in departments" :key="name" :label="name" :value="name" />
      </el-select>
      <el-button v-if="editableDepartments.length" type="primary" :disabled="loading || !!loadError || !!changingId" @click="openAdd">新增</el-button>
    </PptListFilterBar>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false">
      <el-button link type="primary" @click="load">重新加载</el-button>
    </el-alert>
    <el-table v-else :data="visibleAssignments" row-key="key"
      :empty-text="department ? '该部门暂无组织督办人' : '暂无组织督办人'">
      <el-table-column label="督办部门" min-width="160">
        <template #default="{ row }">{{ row.name || '未配置' }}</template>
      </el-table-column>
      <el-table-column label="姓名（统一认证号）" min-width="230">
        <template #default="{ row }">{{ personLabel(row.person) }}</template>
      </el-table-column>
      <el-table-column label="人员所属部门" min-width="160">
        <template #default="{ row }">{{ row.person.departments.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="showActions" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <div v-if="canMaintain(row.name)" class="supervisor-actions">
            <el-button v-if="row.name && editableDepartments.includes(row.name)" link type="primary" :disabled="!!changingId || saving"
              @click="openEdit(row.name)">编辑</el-button>
            <el-button link type="danger" :disabled="!!changingId || saving"
              @click="remove(row.person, row.name)">删除</el-button>
          </div>
          <span v-else class="unavailable-action">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="addOpen" :title="editing ? '编辑组织督办人' : '新增组织督办人'" width="min(520px, 94vw)" append-to-body
      :show-close="!saving" :close-on-click-modal="false" :close-on-press-escape="!saving">
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="editing ? saveEdit() : add()">
        <el-form-item label="督办部门" required>
          <el-select v-model="selectedDepartment" filterable :disabled="saving || editing"
            placeholder="选择被督办的部门" style="width: 100%">
            <el-option v-for="name in editableDepartments" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织督办人" required :error="searchError">
          <el-select v-model="selected" multiple filterable remote :reserve-keyword="false" :remote-method="search"
            :loading="searching" :disabled="saving"
            placeholder="输入姓名或统一认证号搜索" style="width: 100%">
            <el-option v-for="person in options" :key="person.userId" :value="person.userId"
              :label="personLabel(person)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selected.length || !selectedDepartment"
          @click="editing ? saveEdit() : add()">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.supervisor-settings { display: grid; gap: 14px; }
.supervisor-note { margin: 0; }
.unavailable-action { color: #64748b; }
.supervisor-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.supervisor-actions :deep(.el-button) { margin: 0; flex-shrink: 0; }
</style>
