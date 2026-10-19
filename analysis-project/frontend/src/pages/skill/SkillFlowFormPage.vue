<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { onBeforeRouteLeave } from 'vue-router';
import SkillFlowEditorDrawer from '../../components/SkillFlowEditorDrawer.vue';

const route = useRoute();
const router = useRouter();
const open = ref(true);
const editor = ref<{ isDirty: boolean } | null>(null);
const editId = computed(() => {
  const value = Number(route.params.id);
  return Number.isInteger(value) && value > 0 ? value : null;
});
const copyFromId = computed(() => {
  if (editId.value != null || typeof route.query.copyFrom !== 'string') return null;
  const value = Number(route.query.copyFrom);
  return Number.isInteger(value) && value > 0 ? value : null;
});

function back() {
  router.push({ path: '/skills/jobs', query: { tab: 'flows' } });
}

onBeforeRouteLeave(() => !editor.value?.isDirty || confirm('当前修改尚未保存，确定离开吗？'));

</script>

<template>
  <SkillFlowEditorDrawer ref="editor" v-model:open="open" page :edit-id="editId" :copy-from-id="copyFromId" :known-flows="[]" @saved="back" @update:open="value => { if (!value) back(); }" />
</template>
