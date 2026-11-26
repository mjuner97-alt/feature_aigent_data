<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getPptSentMailPage, type PptSentMailPage } from '../../api/pptAnomaly';
import PptListFilterBar from '../../components/ppt/PptListFilterBar.vue';

const route = useRoute();
const router = useRouter();
function queryId(value: unknown) {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) return undefined;
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : undefined;
}
const batchId = computed(() => queryId(route.query.batchId));
const versionId = computed(() => batchId.value === undefined ? undefined : queryId(route.query.versionId));
const page = ref(1);
const pageSize = 20;
const result = ref<PptSentMailPage>();
const loading = ref(false);
const error = ref('');
const filters = ref<PptSentMailPage['filters']>();
const batches = computed(() => [...new Map((filters.value ?? []).map(row => [row.batchId, row.batchName]))]
  .map(([id, name]) => ({ id, name })));
const versions = computed(() => (filters.value ?? []).filter(row => row.batchId === batchId.value));
const recipientGroups = { to: '主送', cc: '抄送' } as const;
const previewLimit = 2;
const fullListOpen = ref(false);
const fullListTitle = ref('');
const fullList = ref<{ id: string; name: string }[]>([]);
const recipientKeyword = ref('');
const filteredPeople = computed(() => {
  const term = recipientKeyword.value.trim().toLowerCase();
  return fullList.value.filter(({ id, name }) => id.toLowerCase().includes(term) || name.toLowerCase().includes(term));
});
let activeRequest: AbortController | undefined;

async function load(refreshFilters = false) {
  activeRequest?.abort();
  const request = new AbortController();
  activeRequest = request;
  const includeFilters = refreshFilters || !filters.value;
  loading.value = true;
  error.value = '';
  try {
    const data = await getPptSentMailPage(page.value, batchId.value, versionId.value, includeFilters, request.signal);
    if (request.signal.aborted) return;
    if (includeFilters) filters.value = data.filters;
    result.value = data;
  } catch (cause) {
    if (request.signal.aborted) return;
    result.value = undefined;
    error.value = cause instanceof Error ? cause.message : '读取通知记录失败';
  } finally {
    if (activeRequest === request) loading.value = false;
  }
}

function filterBatch(value: number | '' | undefined) {
  void router.replace({ query: {
    ...route.query, batchId: typeof value === 'number' ? String(value) : undefined, versionId: undefined,
  } });
}

function filterVersion(value: number | '' | undefined) {
  void router.replace({ query: { ...route.query, versionId: typeof value === 'number' ? String(value) : undefined } });
}

function recipientLabel(id: string) {
  const name = result.value?.names[id];
  return name && name !== id ? `${name}（${id}）` : id;
}

function showRecipients(group: keyof typeof recipientGroups, ids: string[]) {
  fullListTitle.value = `${recipientGroups[group]}名单`;
  fullList.value = ids.map(id => ({ id, name: result.value?.names[id] || '—' }));
  recipientKeyword.value = '';
  fullListOpen.value = true;
}

watch([batchId, versionId], () => { page.value = 1; }, { flush: 'sync' });
watch([batchId, versionId, page], () => { void load(); }, { immediate: true });
onBeforeUnmount(() => activeRequest?.abort());
</script>

<template>
  <section class="notification-records">
    <PptListFilterBar class="notification-filters">
      <template #leading><h2>通知记录</h2></template>
      <el-select :model-value="batchId" class="ppt-filter-select" filterable clearable
        aria-label="筛选批次" placeholder="全部批次" @change="filterBatch">
        <el-option v-for="batch in batches" :key="batch.id" :label="batch.name" :value="batch.id" />
      </el-select>
      <el-select :model-value="versionId" class="ppt-filter-select version-filter" filterable clearable
        aria-label="筛选版本" :placeholder="batchId === undefined ? '请先选择批次' : '全部版本'"
        :disabled="batchId === undefined"
        @change="filterVersion">
        <el-option v-for="version in versions" :key="version.versionId" :label="`v${version.versionNo}`" :value="version.versionId" />
      </el-select>
      <el-button :loading="loading" @click="load(true)">刷新</el-button>
    </PptListFilterBar>

    <el-alert v-if="error" :title="error" type="error" :closable="false">
      <el-button link type="primary" @click="load(true)">重试</el-button>
    </el-alert>
    <el-table v-else v-loading="loading" class="notification-table" :data="result?.rows ?? []"
      row-key="id" empty-text="暂无通知记录">
      <el-table-column label="批次" min-width="180">
        <template #default="{ row }">
          <span class="truncate batch-name" :title="row.batchName">{{ row.batchName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="版本" min-width="145">
        <template #default="{ row }">
          <el-tag size="small" type="info" effect="plain">v{{ row.versionNo }}</el-tag>
          <small class="truncate file-name" :title="row.fileName">{{ row.fileName }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="department" label="部门" min-width="105" show-overflow-tooltip />
      <el-table-column v-for="group in (['to', 'cc'] as const)" :key="group" :label="recipientGroups[group]" min-width="190">
        <template #default="{ row }">
          <div v-if="row[group].length" class="recipient-preview">
            <span v-for="id in row[group].slice(0, previewLimit)" :key="id" class="truncate"
              :title="recipientLabel(id)">{{ recipientLabel(id) }}</span>
            <el-button v-if="row[group].length > previewLimit" link type="primary"
              :aria-label="`查看全部${recipientGroups[group]}，共 ${row[group].length} 人`"
              @click="showRecipients(group, row[group])">查看全部（{{ row[group].length }}）</el-button>
          </div>
          <span v-else class="muted">{{ group === 'to' ? '未记录' : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="发送时间" width="180">
        <template #default="{ row }">
          <time class="sent-time" :datetime="row.sentAt">{{ row.sentAt.replace('T', ' ').slice(0, 19) }}</time>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="result && result.total > pageSize" v-model:current-page="page"
      :page-size="pageSize" :total="result.total" layout="total, prev, pager, next" />

    <el-dialog v-model="fullListOpen" :title="fullListTitle" width="min(540px, 94vw)" append-to-body>
      <div class="recipient-dialog-toolbar">
        <span class="muted">共 {{ fullList.length }} 人</span>
        <el-input v-model="recipientKeyword" clearable aria-label="搜索名单" placeholder="搜索姓名或统一认证号" />
      </div>
      <el-table :data="filteredPeople" row-key="id" max-height="min(360px, 45vh)" empty-text="未找到匹配人员">
        <el-table-column prop="name" label="姓名" min-width="110" show-overflow-tooltip />
        <el-table-column prop="id" label="统一认证号" min-width="160" show-overflow-tooltip />
      </el-table>
      <template #footer><el-button @click="fullListOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.notification-records { padding: clamp(12px, 1.5vw, 20px); }
.notification-filters { --ppt-filter-width: 220px; margin-bottom: 12px; }
.version-filter { --ppt-filter-width: 128px; }
h2 { margin: 0; color: #142441; font-size: 22px; }
.el-alert { margin-bottom: 10px; }
.notification-table { width: 100%; border-radius: 10px; }
.notification-table :deep(.el-table__cell) { padding-block: 8px; }
.batch-name { font-weight: 600; }
.truncate { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-name, .muted { color: #64748b; }
.recipient-preview { display: grid; justify-items: start; gap: 2px; min-width: 0; }
.recipient-preview .truncate { max-width: 100%; }
.recipient-dialog-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.recipient-dialog-toolbar .el-input { width: min(240px, 70%); }
.sent-time { white-space: nowrap; font-variant-numeric: tabular-nums; }
.el-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
