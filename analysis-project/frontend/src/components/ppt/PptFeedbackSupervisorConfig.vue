<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PptListFilterBar from './PptListFilterBar.vue';
import { addPptFeedbackSupervisors, getPptFeedbackSupervisors, listPptReportDepartments,
  searchPptDirectoryPeople, setPptFeedbackSupervisorEnabled,
  type PptFeedbackSupervisor, type PptPerson } from '../../api/pptAnomaly';

const saved = ref<PptFeedbackSupervisor[]>([]);
const departments = ref<string[]>([]);
const department = ref('');
const visiblePeople = computed(() => saved.value.filter(person =>
  !department.value || person.departments.includes(department.value)));
const existingIds = computed(() => new Set(saved.value.map(person => person.userId)));

const addOpen = ref(false);
const selected = ref<string[]>([]);
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

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [people, options] = await Promise.all([getPptFeedbackSupervisors(), listPptReportDepartments()]);
    departments.value = options.map(item => item.name);
    saved.value = people;
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '督办人配置加载失败';
  } finally {
    loading.value = false;
  }
}

function openAdd() {
  searchId++;
  selected.value = [];
  choices.value.clear();
  results.value = [];
  searching.value = false;
  searchError.value = '';
  saveError.value = '';
  addOpen.value = true;
}

async function search(keyword: string) {
  const id = ++searchId;
  results.value = [];
  searchError.value = '';
  searching.value = Boolean(keyword.trim());
  if (!searching.value) return;
  try {
    const people = await searchPptDirectoryPeople(keyword.trim());
    if (id !== searchId) return;
    people.forEach(person => choices.value.set(person.userId, person));
    results.value = people;
  } catch {
    if (id === searchId) searchError.value = '人员搜索失败，请重试';
  } finally {
    if (id === searchId) searching.value = false;
  }
}

async function add() {
  if (!selected.value.length || saving.value) return;
  saving.value = true;
  saveError.value = '';
  try {
    saved.value = await addPptFeedbackSupervisors(selected.value);
    department.value = '';
    addOpen.value = false;
    ElMessage.success('反馈督办人已新增');
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : '督办人新增失败';
  } finally {
    saving.value = false;
  }
}

async function toggle(person: PptFeedbackSupervisor) {
  if (changingId.value || (!person.enabled && !person.available)) return;
  changingId.value = person.userId;
  try {
    saved.value = await setPptFeedbackSupervisorEnabled(person.userId, !person.enabled);
    ElMessage.success(person.enabled ? '反馈督办人已禁用' : '反馈督办人已启用');
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '状态更新失败');
  } finally {
    changingId.value = undefined;
  }
}

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="supervisor-settings">
    <PptListFilterBar>
      <template #leading><p class="supervisor-note">首次待反馈通知和逾期提醒会抄送已启用的督办人。</p></template>
      <el-select v-model="department" filterable clearable aria-label="筛选部门"
        placeholder="全部部门" class="ppt-filter-select" :disabled="loading || !!loadError">
        <el-option v-for="name in departments" :key="name" :label="name" :value="name" />
      </el-select>
      <el-button type="primary" :disabled="loading || !!loadError || !!changingId" @click="openAdd">新增</el-button>
    </PptListFilterBar>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false">
      <el-button link type="primary" @click="load">重新加载</el-button>
    </el-alert>
    <el-table v-else :data="visiblePeople" row-key="userId"
      :empty-text="department ? '该部门暂无反馈督办人' : '暂无反馈督办人'">
      <el-table-column label="姓名" min-width="120">
        <template #default="{ row }">{{ row.available ? row.name : '—' }}</template>
      </el-table-column>
      <el-table-column prop="userId" label="统一认证号" min-width="170" />
      <el-table-column label="部门" min-width="180">
        <template #default="{ row }">{{ row.departments.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="台账状态" width="120">
        <template #default="{ row }">
          <span v-if="row.available">有效</span>
          <el-tag v-else type="warning">台账无此人</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="督办状态" width="105">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link :type="row.enabled ? 'danger' : 'primary'" :loading="changingId === row.userId"
            :disabled="!!changingId || (!row.enabled && !row.available)" @click="toggle(row)">
            {{ row.enabled ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="addOpen" title="新增反馈督办人" width="min(520px, 94vw)" append-to-body
      :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving">
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="add">
        <el-form-item label="反馈督办人" :error="searchError">
          <el-select v-model="selected" multiple filterable remote :remote-method="search"
            :loading="searching" :disabled="saving" placeholder="输入姓名或统一认证号搜索" style="width: 100%">
            <el-option v-for="person in options" :key="person.userId" :value="person.userId"
              :disabled="existingIds.has(person.userId)"
              :label="`${person.name}（${person.userId}）${existingIds.has(person.userId) ? ' · 已配置' : ''}`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selected.length" @click="add">新增</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.supervisor-settings { display: grid; gap: 14px; }
.supervisor-note { margin: 0; }
</style>
