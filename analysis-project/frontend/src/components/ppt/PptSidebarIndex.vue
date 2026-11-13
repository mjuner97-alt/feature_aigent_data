<script setup lang="ts">
import type { PptFinding } from '../../types/pptAnomaly';
defineProps<{ findings:PptFinding[]; selectedId?:number }>();
const emit=defineEmits<{select:[PptFinding]}>();
</script>
<template>
  <aside class="ppt-sidebar">
    <div class="ppt-sidebar-title">异常目录</div>
    <button v-for="finding in findings" :key="finding.id" class="ppt-index-item" :class="{active:finding.id===selectedId}" @click="emit('select',finding)">
      <span class="seq">{{ finding.sequenceNo }}</span><span class="item-text"><b>P{{ finding.pageNo }} {{ finding.title || '未命名页面' }}</b><small>{{ finding.status }} · {{ finding.departments.join('、') || '未归属' }}</small></span>
    </button>
    <div v-if="!findings.length" class="empty">暂无异常</div>
  </aside>
</template>
<style scoped>
.ppt-sidebar{width:300px;border-right:1px solid #e6e8ef;background:#fafbfe;overflow:auto}.ppt-sidebar-title{padding:16px;font-weight:700}.ppt-index-item{width:100%;display:flex;gap:10px;text-align:left;border:0;border-top:1px solid #edf0f5;background:transparent;padding:12px 14px;cursor:pointer}.ppt-index-item.active{background:#e8f1ff}.seq{width:24px;color:#6b7280}.item-text{display:flex;flex-direction:column;gap:4px}.item-text small{color:#7b8190}.empty{padding:20px;color:#8a8f9c}
</style>
