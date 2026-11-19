<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { listPptNotifications } from '../../api/pptAnomaly';
import type { PptMailRecord } from '../../api/pptAnomaly';
import FlowNotificationHistory from '../FlowNotificationHistory.vue';

const props = defineProps<{ versionId: number; versionNo: number | null }>();
defineEmits<{ close: [] }>();
const records = ref<PptMailRecord[]>([]);
const loading = ref(false);
const error = ref('');
let request = 0;
async function load() {
  const current = ++request;
  records.value = [];
  loading.value = true;
  error.value = '';
  try {
    const rows = await listPptNotifications(props.versionId);
    if (current === request) records.value = rows;
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : '查询通知记录失败';
  } finally {
    if (current === request) loading.value = false;
  }
}
function people(value: string | null) {
  try {
    const ids: unknown = JSON.parse(value || 'null');
    return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : undefined;
  } catch { return undefined; }
}
const notifications = computed(() => records.value.map(row => ({
  id: row.id, status: row.status, sentAt: row.sentAt,
  requestJson: JSON.stringify({ title: row.subject, content: row.body,
    toUserList: people(row.toUsers), toCCUserList: people(row.ccUsers) }),
})));
watch(() => props.versionId, load, { immediate: true });
</script>

<template>
  <el-drawer :model-value="true" :title="`通知记录 · v${versionNo ?? versionId}`"
    size="min(720px, 100%)" append-to-body @update:model-value="(open: boolean) => { if (!open) $emit('close') }">
    <div v-loading="loading" class="notification-body">
      <template v-if="error">
        <el-alert :title="error" type="error" :closable="false" />
        <el-button class="retry" @click="load">重新加载</el-button>
      </template>
      <FlowNotificationHistory v-else-if="!loading" :notifications="notifications" />
    </div>
  </el-drawer>
</template>

<style scoped>
.notification-body { min-height: 100px; }
.retry { margin-top: 12px; }
</style>
