<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { overview, slide } from '../../api/pptAnomaly';
import type { PptFinding, PptSlide } from '../../types/pptAnomaly';
import PptSidebarIndex from '../../components/ppt/PptSidebarIndex.vue';
import PptSlideViewer from '../../components/ppt/PptSlideViewer.vue';
import PptFeedbackForm from '../../components/ppt/PptFeedbackForm.vue';
const versionId=Number(location.hash.match(/version=(\d+)/)?.[1]||0);const department=String(new URLSearchParams(location.search).get('department')||'一部');
const findings=ref<PptFinding[]>([]);const selected=ref<PptFinding>();const currentSlide=ref<PptSlide>();
const counts=computed(()=>({all:findings.value.length,pending:findings.value.filter(x=>x.status==='PENDING_FEEDBACK').length,editable:findings.value.filter(x=>x.editable).length}));
async function select(f:PptFinding){selected.value=f;currentSlide.value=await slide(versionId,f.pageNo)}
async function reload(){findings.value=await overview(versionId,department);if(findings.value[0])await select(findings.value[0])}
onMounted(reload);
</script>
<template>
  <div class="ppt-page"><header><div><h2>PPT 异常分拣</h2><p>当前部门：{{ department }} · 全部异常：{{ counts.all }} · 待反馈：{{ counts.pending }} · 当前用户可处理：{{ counts.editable }}</p></div><div class="version">版本 {{ versionId }}</div></header>
    <main><PptSidebarIndex :findings="findings" :selected-id="selected?.id" @select="select"/><section class="content"><PptSlideViewer :slide="currentSlide"/><PptFeedbackForm :finding="selected" @saved="reload"/></section></main>
  </div>
</template>
<style scoped>
.ppt-page{height:100%;display:flex;flex-direction:column;background:#f6f7fb}.ppt-page header{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;background:#fff;border-bottom:1px solid #e6e8ef}.ppt-page h2{margin:0 0 6px}.ppt-page p{margin:0;color:#667085;font-size:13px}.version{color:#475467}.ppt-page main{min-height:0;flex:1;display:flex}.content{min-width:0;flex:1;overflow:auto}
</style>
