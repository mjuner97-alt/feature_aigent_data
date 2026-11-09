<script setup lang="ts">
import { onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getLoggedInUserId } from '../utils/auth';

const route = useRoute();
const router = useRouter();

onMounted(async () => {
  const executionId = String(route.query.executionId || '');
  const type = route.query.type === 'flow' ? 'flow' : 'job';
  if (!executionId) { router.replace('/'); return; }
  const userId = getLoggedInUserId();
  if (!userId) {
    router.replace({ path: '/login', query: { ...route.query, ...(type === 'flow' ? { returnFlowExecutionId: executionId } : { returnExecutionId: executionId }) } });
    return;
  }
  const endpoint = type === 'flow' ? '/api/skill-flow-executions/notification-link' : '/api/skill-jobs/notification-link';
  const response = await fetch(`${endpoint}?executionId=${encodeURIComponent(executionId)}&returnFromLogin=true`, {
    headers: { 'X-User-Id': userId }, redirect: 'follow'
  });
  if (response.ok && response.url) window.location.assign(response.url);
  else window.location.assign('/');
});
</script>

<template><div style="padding:40px;text-align:center">正在打开报告…</div></template>
