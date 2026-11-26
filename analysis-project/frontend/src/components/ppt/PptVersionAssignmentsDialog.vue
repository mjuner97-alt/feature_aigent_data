<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { assignmentOptions, feedbackDirectory } from '../../api/pptAnomaly';
import type { PptFinding } from '../../types/pptAnomaly';
import PptAssignmentDialog from './PptAssignmentDialog.vue';

const props = defineProps<{
  versionId: number; versionNo: number | null; initialSection?: string | null; initialPageNo?: number | null;
}>();
const emit = defineEmits<{ close: [changed: boolean] }>();
let changed = false;

function close() {
  emit('close', changed);
}

type Directory = Awaited<ReturnType<typeof feedbackDirectory>>;
type Options = Awaited<ReturnType<typeof assignmentOptions>>;
type Page = { page: number; title: string | null; findings: PptFinding[] };

const data = ref<{ directory: Directory; options: Options }>();
const editing = ref<{ page: Page; finding?: PptFinding }>();
const activeChapter = ref('');
const loading = ref(true);
const error = ref('');

const chapters = computed(() => {
  const groups = new Map<string, Page[]>();
  const findingsByPage = new Map<number, PptFinding[]>();
  for (const finding of data.value?.directory.departmentFindings ?? []) {
    const findings = findingsByPage.get(finding.pageNo) ?? [];
    findings.push(finding);
    findingsByPage.set(finding.pageNo, findings);
  }
  for (const page of data.value?.options.pages ?? []) {
    const findings = findingsByPage.get(page.page) ?? [];
    if (page.skipped && !findings.length) continue;
    const chapter = page.section || findings.find(finding => finding.chapter)?.chapter || '';
    const pages = groups.get(chapter) ?? [];
    pages.push({ page: page.page, title: page.title, findings });
    groups.set(chapter, pages);
  }
  return groups;
});
const activePages = computed(() => chapters.value.get(activeChapter.value) ?? []);

function handlersFor(findingId: number) {
  return data.value?.directory.handlers[findingId] ?? [];
}

function availableDepartments(page: Page) {
  const assigned = new Set(page.findings.flatMap(finding => finding.departments));
  return (data.value?.options.departments ?? []).filter(department => !assigned.has(department));
}

async function loadAssignments() {
  loading.value = true;
  error.value = '';
  try {
    const [directory, options] = await Promise.all([
      feedbackDirectory(props.versionId, undefined, undefined, false), assignmentOptions(props.versionId),
    ]);
    const firstLoad = !data.value;
    data.value = { directory, options };
    if (firstLoad) {
      const section = props.initialSection;
      const pageSection = [...chapters.value].find(([, pages]) =>
        pages.some(page => page.page === props.initialPageNo))?.[0];
      activeChapter.value = section != null && chapters.value.has(section)
        ? section : pageSection ?? chapters.value.keys().next().value ?? '';
    }
    if (!chapters.value.has(activeChapter.value)) {
      activeChapter.value = chapters.value.keys().next().value ?? '';
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '经办人列表加载失败，请重试';
  } finally {
    loading.value = false;
  }
}

function reloadAssignments() {
  changed = true;
  editing.value = undefined;
  void loadAssignments();
}

function assignmentSaved() {
  changed = true;
  ElMessage.success('分派已更新');
  reloadAssignments();
}

onMounted(loadAssignments);
</script>

<template>
  <el-dialog class="version-assignments-dialog" :model-value="true" title="调整分派" width="min(900px, 94vw)" align-center append-to-body
    :close-on-click-modal="false" @update:model-value="(open: boolean) => { if (!open) close() }">
    <p v-if="data" class="version-summary">
      <span v-if="versionNo !== null" class="version-badge">V{{ versionNo }}</span><span>{{ data.directory.fileName }}</span>
    </p>
    <template v-if="error">
      <el-alert :title="error" type="error" :closable="false" show-icon />
      <el-button class="retry" @click="loadAssignments">重新加载</el-button>
    </template>
    <div v-else v-loading="loading" class="assignments">
      <template v-if="chapters.size">
        <el-form-item label="板块" class="chapter-filter">
          <el-select v-model="activeChapter" filterable :empty-values="[null, undefined]"
            :disabled="loading" aria-label="选择板块">
            <el-option v-for="chapter in chapters.keys()" :key="chapter" :value="chapter"
              :label="chapter || '未归属板块'" />
          </el-select>
        </el-form-item>
        <div class="assignment-pages">
          <section v-for="page in activePages" :key="page.page" class="assignment-page">
            <header class="page-heading">
              <h3><span class="page-badge">P{{ page.page }}</span>{{ page.title || '未命名页面' }}</h3>
              <el-button plain type="primary" size="small" :disabled="loading || !availableDepartments(page).length"
                @click="editing = { page }">添加部门</el-button>
            </header>
            <el-table :data="page.findings" row-key="id" empty-text="该页暂无反馈部门" size="small">
              <template #empty>
                <div class="assignment-empty"><strong>该页暂无反馈部门</strong><span>点击右上角“添加部门”，设置反馈部门及经办人</span></div>
              </template>
              <el-table-column label="反馈部门" min-width="120">
                <template #default="{ row }">{{ row.departments.join('、') || '未归属' }}</template>
              </el-table-column>
              <el-table-column label="当前经办人" min-width="150">
                <template #default="{ row }">
                  <div v-for="person in handlersFor(row.id)" :key="person.userId">
                    {{ person.name ? `${person.name}（${person.userId}）` : person.userId }}
                  </div>
                  <span v-if="!handlersFor(row.id).length">未分派</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="96">
                <template #default="{ row }">
                  <el-button v-if="row.departments.length === 1" link type="primary" :disabled="loading"
                    @click="editing = { page, finding: row }">调整</el-button>
                  <span v-else class="legacy-label">历史归属</span>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="此版本暂无可分派页面" />
    </div>
    <template #footer>
      <el-button class="close-button" @click="close">关闭</el-button>
    </template>
    <PptAssignmentDialog v-if="editing" :version-id="versionId" :finding="editing.finding"
      :fixed-page="editing.page"
      :available-departments="[...(editing.finding?.departments ?? []), ...availableDepartments(editing.page)]"
      :handlers="editing.finding ? handlersFor(editing.finding.id) : []"
      :title="editing.finding ? '调整分派' : '添加部门及经办人'"
      @close="editing = undefined" @saved="assignmentSaved" @reload="reloadAssignments" />
  </el-dialog>
</template>

<style scoped>
.version-summary { display: flex; align-items: baseline; gap: 10px; margin: 0 0 20px; padding: 12px 16px; background: #f5f7fc; border-radius: 8px; color: #52627a; overflow-wrap: anywhere; line-height: 1.6; }
.version-badge { flex-shrink: 0; padding: 0 8px; border-radius: 4px; background: #e7efff; color: #315fbc; font-size: 12px; font-weight: 600; }
.chapter-filter { margin-bottom: 20px; }
.chapter-filter :deep(.el-form-item__label) { color: #52627a; font-weight: 500; }
.retry { margin-top: 12px; }
.assignments { min-height: 76px; }
.assignment-pages { display: grid; gap: 16px; max-height: 50vh; overflow: auto; }
.assignment-page { min-width: 0; border: 1px solid #e1e7f0; border-radius: 10px; overflow: hidden; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; background: #f7f9fd; border-bottom: 1px solid #e8edf5; }
.page-heading h3 { display: flex; align-items: baseline; gap: 10px; margin: 0; color: #243652; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.page-badge { flex-shrink: 0; color: #5474a5; font-size: 12px; }
.page-heading .el-button { flex-shrink: 0; white-space: nowrap; }
.assignment-page :deep(.el-table th.el-table__cell) { background: #fff; color: #69788e; font-weight: 500; }
.assignment-page :deep(.el-table .cell) { padding-inline: 16px; }
.assignment-page :deep(.el-table__inner-wrapper::before) { display: none; }
.assignment-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 28px 16px; line-height: 1.6; }
.assignment-empty strong { color: #64748b; font-size: 14px; font-weight: 500; }
.assignment-empty span { color: #94a0b2; font-size: 12px; }
.close-button { min-width: 80px; }
:global(.version-assignments-dialog.el-dialog) { padding: 24px; border-radius: 12px; }
:global(.version-assignments-dialog .el-dialog__title) { color: #1e304d; font-size: 20px; font-weight: 600; }
:global(.version-assignments-dialog .el-dialog__header) { padding-bottom: 20px; }
:global(.version-assignments-dialog .el-dialog__footer) { margin-top: 20px; padding-top: 16px; border-top: 1px solid #edf0f5; }
.legacy-label { color: #687387; }
</style>
