<script setup lang="ts">
import type { PptSlide } from '../../types/pptAnomaly';
defineProps<{ slide?:PptSlide }>();
</script>
<template>
  <section class="slide-viewer">
    <div v-if="slide?.imageUrl" class="slide-frame"><img :src="slide.imageUrl" alt="PPT 原始页面" /></div>
    <div v-else class="slide-placeholder"><strong>P{{ slide?.pageNo || '-' }}</strong><span v-if="slide?.renderStatus==='UNAVAILABLE'">当前环境尚未配置 PPT 页面渲染器</span><span v-else>页面图片生成中</span></div>
    <div class="slide-toolbar">{{ slide?.renderStatus || '未选择页面' }} · [原始页面] [显示识别标记] [适应窗口]</div>
  </section>
</template>
<style scoped>
.slide-viewer{background:#f1f3f7;padding:20px}.slide-frame{background:#fff;display:flex;justify-content:center}.slide-frame img{max-width:100%;height:auto}.slide-placeholder{aspect-ratio:16/9;background:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:#7a8190;font-size:14px}.slide-placeholder strong{font-size:28px;color:#334155}.slide-toolbar{padding-top:10px;color:#687385;font-size:12px}
</style>
