<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { onBeforeRouteLeave } from 'vue-router';
import SkillFlowEditorDrawer from '../../components/SkillFlowEditorDrawer.vue';
import SkillFlowTypeDialog from '../../components/SkillFlowTypeDialog.vue';
import { listAllLongTaskTypes, type LongTaskType } from '../../api/longTaskConfig';
import { creatableFlowTypes } from '../../utils/flowCreation';

const route = useRoute();
const router = useRouter();
const open = ref(true);
const editor = ref<{ isDirty: boolean } | null>(null);
const createType=ref<LongTaskType|null>(null),typeDialogOpen=ref(false),resolvingType=ref(true);
const typeError=ref('');
const editId = computed(() => {
  const value = Number(route.params.id);
  return Number.isInteger(value) && value > 0 ? value : null;
});
const copyFromId = computed(() => {
  if (editId.value != null || typeof route.query.copyFrom !== 'string') return null;
  const value = Number(route.query.copyFrom);
  return Number.isInteger(value) && value > 0 ? value : null;
});
let typeRequest=0;
watch(() => [editId.value,route.query.typeId] as const,async ([id,typeId])=>{
  const request=++typeRequest;createType.value=null;typeDialogOpen.value=false;typeError.value='';
  if(id!=null){resolvingType.value=false;return;}
  resolvingType.value=true;
  try {
    if(typeof typeId==='string') {
      const types=creatableFlowTypes(await listAllLongTaskTypes());
      if(request!==typeRequest)return;
      createType.value=types.find(type=>String(type.id)===typeId)||null;
    }
  } catch(e){if(request===typeRequest)typeError.value=e instanceof Error?e.message:'读取任务类型失败';}
  finally {if(request===typeRequest){resolvingType.value=false;typeDialogOpen.value=!createType.value;}}
},{immediate:true});
function selectType(type:LongTaskType){createType.value=type;typeError.value='';}

function back() {
  router.push({ path: '/skills/jobs', query: { tab: 'flows' } });
}

onBeforeRouteLeave(() => !editor.value?.isDirty || confirm('当前修改尚未保存，确定离开吗？'));

</script>

<template>
  <p v-if="resolvingType">正在读取任务类型…</p>
  <p v-if="typeError" role="alert">{{ typeError }}</p>
  <SkillFlowTypeDialog v-model:open="typeDialogOpen" @selected="selectType" @cancelled="back" />
  <SkillFlowEditorDrawer v-if="!resolvingType && (editId != null || createType)" ref="editor" v-model:open="open" page :edit-id="editId" :copy-from-id="copyFromId" :create-type="createType" :known-flows="[]" @saved="back" @update:open="value => { if (!value) back(); }" />
</template>
