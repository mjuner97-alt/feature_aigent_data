<script setup lang="ts">
/** 例会反馈记录页，按批次、版本、部门和接收人展示反馈进度。 */

import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { listFeedbackRecords, type PptFeedbackRecord } from '../../api/pptAnomaly';
import { getLoggedInUserId } from '../../utils/auth';
import PptVersionAssignmentsDialog from '../../components/ppt/PptVersionAssignmentsDialog.vue';

/** 反馈列表中的一条原始事项记录。 */
type Page = PptFeedbackRecord['page'] & { configurableTitle: string | null };
/** 按经办人聚合的反馈任务及通知进度。 */
type Recipient = {
  key: string; batchId: number; batchName: string; versionId: number; versionNo: number; fileName: string;
  departments: string[]; pages: Page[];
};

const router = useRouter();
const versionAssignments = ref<{ versionId: number; versionNo: number }>();
const userId = getLoggedInUserId();
const records = ref<PptFeedbackRecord[]>([]);
const loading = ref(false);
const error = ref('');
const batch = ref<number>();
const version = ref<number>();
const department = ref<string>();
const progress = ref<string>();
const pageNumber = ref(1);
const pageSize = 20;

// 从批次入口接收筛选上下文，并同步反馈记录页的选中条件。
watch(router.currentRoute, route => {
  if (route.path !== '/ppt-anomaly/feedback-records') return;
  const { batch: batchId } = window.history.state?.pptFeedbackFilter ?? {};
  batch.value = Number(batchId) || undefined;
  version.value = undefined;
}, { immediate: true });

const batches = computed(() => [...new Map(records.value.map(({ page }) => [page.batchId, page.batchName]))]);
const hasSelectedBatch = computed(() => batches.value.some(([id]) => id === batch.value));
const versions = computed(() => [...new Map(records.value
  .filter(({ page }) => !batch.value || page.batchId === batch.value)
  .map(({ page }) => [page.versionId, `${batch.value ? '' : page.batchName + ' · '}v${page.versionNo} · ${page.fileName}`]))]);
const departments = computed(() => [...new Set(records.value.flatMap(record => record.departments))].sort());

const recipients = computed(() => {
  const groups = new Map<string, Recipient>();
  for (const { page: recordPage, departments, configurableTitle } of records.value) {
    const page = { ...recordPage, configurableTitle };
    const key = JSON.stringify([page.versionId, page.recipient]);
    const group = groups.get(key);
    if (group) {
      group.pages.push(page);
      group.departments = [...new Set([...group.departments, ...departments])];
    } else groups.set(key, {
      key, batchId: page.batchId, batchName: page.batchName, versionId: page.versionId, versionNo: page.versionNo,
      fileName: page.fileName, departments, pages: page.findingId === null ? [] : [page],
    });
  }
  return [...groups.values()].map(group => {
    const assigned = new Map(group.pages.filter(page => page.status).map(page => [page.findingId, page.status]));
    const completed = [...assigned.values()].filter(status => status === 'CONFIRMED' || status === 'ADJUSTED').length;
    return {
      ...group, total: assigned.size, completed, hasNotice: group.pages.some(page => page.noticeKind),
      unassigned: group.pages.some(page => page.recipient === null),
      unassignedTitles: [...new Set(group.pages
        .filter(page => page.recipient === null && page.configurableTitle)
        .map(page => page.configurableTitle!))],
      notifiedAt: latest(group.pages.map(page => page.notifiedAt)),
      feedbackAt: latest(group.pages.map(page => page.feedbackAt)),
    };
  }).sort((a, b) => {
    const first = a.notifiedAt ?? '';
    const second = b.notifiedAt ?? '';
    return first === second ? 0 : first < second ? 1 : -1;
  });
});
const filtered = computed(() => recipients.value.filter(row =>
  (!batch.value || row.batchId === batch.value) && (!version.value || row.versionId === version.value)
  && (!department.value || row.departments.includes(department.value))
  && (!progress.value
    || (progress.value === 'unassigned' && row.unassigned)
    || (progress.value === 'done' && row.total > 0 && row.completed === row.total)
    || (progress.value === 'pending' && row.completed < row.total)),
));
const visible = computed(() => filtered.value.slice((pageNumber.value - 1) * pageSize, pageNumber.value * pageSize));
watch(filtered, () => { pageNumber.value = 1; });

/** 从多个通知或反馈时间中选取最新时间。 */
function latest(values: Array<string | null>) {
  return values.reduce<string | null>((result, value) => value && (!result || value > result) ? value : result, null);
}

/** 将记录时间格式化为列表展示文字。 */
function date(value: string | null) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—';
}

/** 组合通知类型和通知时间的展示标签。 */
function notice(kind: string | null, time: string | null) {
  if (!kind) return '暂无通知记录';
  const label = kind === 'RESOLVED' ? '版本变更通知' : '反馈通知';
  return time ? `${date(time)} · ${label}` : `${label} · 时间未记录`;
}

/** 通过路由状态打开指定经办人的反馈记录详情。 */
function openDetail(row: Recipient, mode: 'view' | 'edit') {
  const pages = row.pages.filter(page => page.status && page.findingId !== null);
  if (!pages.length || (mode === 'edit' && pages[0].recipient !== userId)) return;
  const page = (mode === 'edit' ? pages.find(item => item.status === 'PENDING_FEEDBACK') : undefined) ?? pages[0];
  void router.push({
    path: '/ppt-anomaly/overview',
    state: { pptFeedbackContext: { version: String(page.versionId), recordOwner: page.recipient, finding: String(page.findingId), mode } },
  });
}

function openVersionAssignments(row: { versionId: number; versionNo: number }) {
  versionAssignments.value = { versionId: row.versionId, versionNo: row.versionNo };
}

function closeVersionAssignments() {
  versionAssignments.value = undefined;
  void load();
}

/** 读取反馈记录并构建批次、版本及接收人的汇总展示。 */
async function load() {
  loading.value = true;
  error.value = '';
  try {
    records.value = await listFeedbackRecords();
  } catch {
    error.value = '反馈记录加载失败，请重试。';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="records-page">
    <h2>反馈记录</h2>
    <div class="filters">
      <el-select v-model="batch" clearable placeholder="全部批次" aria-label="批次" @change="version = undefined">
        <el-option v-for="[id, name] in batches" :key="id" :label="name" :value="id" />
        <el-option v-if="batch && !hasSelectedBatch" label="当前批次" :value="batch" />
      </el-select>
      <el-select v-model="version" clearable placeholder="全部版本" aria-label="版本">
        <el-option v-for="[id, label] in versions" :key="id" :label="label" :value="id" />
      </el-select>
      <el-select v-model="department" clearable placeholder="全部部门" aria-label="部门">
        <el-option v-for="name in departments" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="progress" clearable placeholder="全部反馈状态" aria-label="反馈状态">
        <el-option label="未完成" value="pending" />
        <el-option label="已完成" value="done" />
        <el-option label="待分派" value="unassigned" />
      </el-select>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false">
      <el-button link type="primary" @click="load">重试</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="visible" row-key="key" empty-text="暂无匹配的反馈记录">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table class="page-details" :data="row.pages" size="small" empty-text="暂无反馈任务">
            <el-table-column label="页面" min-width="180">
              <template #default="{ row: item }">P{{ item.pageNo }} · {{ item.title || '未命名页面' }}</template>
            </el-table-column>
            <el-table-column prop="section" label="板块" min-width="100" />
            <el-table-column label="通知时间" min-width="175">
              <template #default="{ row: item }">{{ notice(item.noticeKind, item.notifiedAt) }}</template>
            </el-table-column>
            <el-table-column label="反馈状态" width="100">
              <template #default="{ row: item }">
                {{ item.recipient === null ? '待分派' : item.status === 'PENDING_FEEDBACK' ? '待反馈' : item.status ? '已反馈' : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="反馈时间" min-width="135">
              <template #default="{ row: item }">{{ date(item.feedbackAt) }}</template>
            </el-table-column>
            <el-table-column v-if="row.pages.some((item: Page) => item.configurableTitle)" label="操作" width="115">
              <template #default="{ row: item }">
                <el-button v-if="item.configurableTitle" link type="primary" @click="openVersionAssignments(item)">
                  调整分派
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column prop="batchName" label="批次" min-width="125" show-overflow-tooltip />
      <el-table-column prop="fileName" label="文件名" min-width="175" show-overflow-tooltip />
      <el-table-column label="部门" min-width="95" show-overflow-tooltip>
        <template #default="{ row }">{{ row.departments.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="通知时间" min-width="175">
        <template #default="{ row }">{{ notice(row.hasNotice ? 'PENDING' : null, row.notifiedAt) }}</template>
      </el-table-column>
      <el-table-column label="反馈进度" width="125">
        <template #default="{ row }">{{ row.unassigned ? '待分派' : row.total ? `${row.completed}/${row.total}` : row.pages.length ? '—' : '暂无反馈任务' }}</template>
      </el-table-column>
      <el-table-column label="反馈时间" min-width="135">
        <template #default="{ row }">{{ date(row.feedbackAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="115">
        <template #default="{ row }">
          <el-button v-if="row.total && row.pages[0].recipient === userId" link type="primary" @click="openDetail(row, 'edit')">编辑</el-button>
          <el-button v-if="row.total" link type="primary" @click="openDetail(row, 'view')">查看</el-button>
          <el-button v-if="row.pages.some((item: Page) => item.configurableTitle)" link type="primary" @click="openVersionAssignments(row)">
            调整分派
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="filtered.length > pageSize" v-model:current-page="pageNumber"
      :page-size="pageSize" :total="filtered.length" layout="prev, pager, next" />
    <PptVersionAssignmentsDialog v-if="versionAssignments" :version-id="versionAssignments.versionId"
      :version-no="versionAssignments.versionNo" @close="closeVersionAssignments" />
  </section>
</template>

<style scoped>
.records-page { padding: clamp(12px, 2vw, 24px); }
h2 { margin: 0 0 16px; color: #142441; font-size: 24px; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.filters .el-select { width: min(100%, 205px); }
.page-details { padding-inline: 12px; box-sizing: border-box; }
.el-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
