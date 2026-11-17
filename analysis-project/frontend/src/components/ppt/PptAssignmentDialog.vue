<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { assignmentOptions, listPptDemoPeople, saveFindingAssignment, type PptDemoPerson } from '../../api/pptAnomaly';
import { searchSkillUsers } from '../../api/skill';
import type { PptFinding } from '../../types/pptAnomaly';

const props = defineProps<{
  versionId: number;
  finding?: PptFinding;
  handlers: { userId: string; name: string | null }[];
}>();
const emit = defineEmits<{ close: []; saved: [] }>();

const page = ref(props.finding?.pageNo ?? 0);
const originalDepartment = props.finding?.departments.length === 1 ? props.finding.departments[0] : '';
const department = ref(originalDepartment);
const handlers = ref(originalDepartment ? props.handlers.map(person => person.userId) : []);
const options = ref<Awaited<ReturnType<typeof assignmentOptions>>>();
const demoPeople = ref<PptDemoPerson[]>([]);
const matchedPeople = ref<PptDemoPerson[]>([]);
const remembered = ref(new Map(props.handlers.map(person => [person.userId, person.name || person.userId])));
const busy = ref(false);
const searching = ref(false);
const error = ref('');
const searchError = ref('');
let searchId = 0;

const people = computed(() => {
  const found = new Map<string, string>();
  for (const id of handlers.value) found.set(id, remembered.value.get(id) ?? id);
  for (const person of [...demoPeople.value, ...matchedPeople.value]) {
    if (person.department === department.value) found.set(person.userId, person.name);
  }
  return [...found].map(([id, name]) => ({ id, label: `${name}（${id}）` }));
});

onMounted(async () => {
  try {
    [options.value, demoPeople.value] = await Promise.all([
      assignmentOptions(props.versionId), listPptDemoPeople(),
    ]);
  } catch {
    error.value = '页面或部门加载失败，请重新打开后重试';
  }
});

function changeDepartment() {
  ++searchId;
  searching.value = false;
  matchedPeople.value = [];
  searchError.value = '';
  handlers.value = department.value === originalDepartment ? props.handlers.map(person => person.userId) : [];
}

async function searchPeople(keyword: string) {
  const request = ++searchId;
  matchedPeople.value = [];
  searchError.value = '';
  searching.value = !!keyword.trim();
  if (!searching.value) return;
  try {
    const rows = await searchSkillUsers(keyword.trim());
    if (request !== searchId) return;
    matchedPeople.value = rows.filter(person => person.department === department.value)
      .map(person => ({ ...person, department: department.value }));
    for (const person of matchedPeople.value) remembered.value.set(person.userId, person.name);
  } catch {
    if (request === searchId) searchError.value = '人员搜索失败，请重试';
  } finally {
    if (request === searchId) searching.value = false;
  }
}

async function save() {
  if (!page.value || !department.value || !handlers.value.length || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await saveFindingAssignment(props.versionId, {
      findingId: props.finding?.id, page: page.value, department: department.value, handlers: handlers.value,
    });
    emit('saved');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存归属失败，请重试';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="finding ? '调整归属' : '补建事项'" append-to-body
    width="min(540px, 94vw)" :close-on-click-modal="false" :before-close="() => { if (!busy) emit('close') }">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="busy" @submit.prevent="save">
      <el-form-item label="PPT 页面">
        <span v-if="finding">P{{ finding.pageNo }} {{ finding.title || '未命名页面' }}</span>
        <el-select v-else v-model="page" filterable placeholder="选择页面" style="width: 100%">
          <el-option v-for="entry in options?.pages" :key="entry.page" :value="entry.page"
            :label="`P${entry.page} ${entry.title || '未命名页面'}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="反馈部门">
        <el-select v-model="department" filterable allow-create default-first-option placeholder="选择或输入部门全称"
          style="width: 100%" @change="changeDepartment">
          <el-option v-for="name in options?.departments" :key="name" :value="name" :label="name" />
        </el-select>
      </el-form-item>
      <el-form-item label="经办人">
        <el-select v-model="handlers" multiple filterable remote :remote-method="searchPeople" :loading="searching"
          :disabled="!department" placeholder="输入姓名或统一认证号" style="width: 100%">
          <el-option v-for="person in people" :key="person.id" :value="person.id" :label="person.label" />
        </el-select>
        <span v-if="searchError">{{ searchError }}</span>
      </el-form-item>
      <p v-if="finding && department && department !== originalDepartment">
        转给新部门后，原反馈保留在原部门的历史记录里，新部门需重新填写。
      </p>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!page || !department || !handlers.length"
        @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
