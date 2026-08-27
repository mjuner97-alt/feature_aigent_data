<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { onBeforeRouteLeave } from 'vue-router';
import SkillFlowEditorDrawer from '../../components/SkillFlowEditorDrawer.vue';
import { listSkillFlows } from '../../api/skillFlow';
import type { SkillFlow } from '../../types/skillFlow';

const route = useRoute();
const router = useRouter();
const open = ref(true);
const editor = ref<{ isDirty: boolean } | null>(null);
const knownFlows = ref<SkillFlow[]>([]);
const editId = computed(() => {
  const value = Number(route.params.id);
  return Number.isInteger(value) && value > 0 ? value : null;
});

function back() {
  router.push({ path: '/skills/jobs', query: { tab: 'flows' } });
}

onBeforeRouteLeave(() => !editor.value?.isDirty || confirm('当前修改尚未保存，确定离开吗？'));

onMounted(async () => {
  try { knownFlows.value = await listSkillFlows(undefined, undefined, undefined, 'all'); }
  catch { knownFlows.value = []; }
});
</script>

<template>
  <SkillFlowEditorDrawer ref="editor" v-model:open="open" page :edit-id="editId" :known-flows="knownFlows" @saved="back" @update:open="value => { if (!value) back(); }" />
</template>
