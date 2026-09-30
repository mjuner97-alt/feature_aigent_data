<script setup lang="ts">
import { computed, ref } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import ReportEditorDrawer from '../../components/ReportEditorDrawer.vue';

const route = useRoute();
const router = useRouter();
const open = ref(true);
const editor = ref<{ dirty: boolean } | null>(null);
const executionId = computed(() => {
  const value = Number(route.params.id);
  return Number.isInteger(value) && value > 0 ? value : null;
});

function back() {
  router.push({ path: '/skills/jobs', query: { tab: 'execution' } });
}

onBeforeRouteLeave(() => !editor.value?.dirty || confirm('报告尚未保存，确定离开吗？'));
</script>

<template>
  <ReportEditorDrawer
    ref="editor"
    v-model:open="open"
    page
    :execution-id="executionId"
    kind="job"
    @saved="back"
    @update:open="value => { if (!value) back(); }"
  />
</template>
