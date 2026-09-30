<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const url = ref('');
const error = ref('');

onMounted(async () => {
  const id = Number(route.params.id);
  if (!Number.isInteger(id) || id <= 0) { error.value = '报告编号无效'; return; }
  try {
    const response = await fetch(`/api/skill-flow-executions/${id}/report`, { cache: 'no-store' });
    if (!response.ok) throw new Error('报告暂不可用');
    url.value = URL.createObjectURL(await response.blob());
  } catch (e) {
    error.value = e instanceof Error ? e.message : '报告加载失败';
  }
});

onBeforeUnmount(() => { if (url.value) URL.revokeObjectURL(url.value); });
</script>

<template>
  <main class="public-report">
    <div v-if="error" class="error">{{ error }}</div>
    <iframe v-else-if="url" :src="url" title="长任务报告" />
    <div v-else class="loading">正在加载报告…</div>
  </main>
</template>

<style scoped>
.public-report { width: 100vw; height: 100vh; overflow: hidden; background: #fff; }
iframe { display: block; width: 100%; height: 100%; border: 0; }
.loading, .error { padding: 40px; color: #64748b; text-align: center; }
.error { color: #b91c1c; }
</style>
