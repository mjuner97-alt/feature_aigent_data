<script setup lang="ts">
/** PPT 原页预览组件，处理图片加载、失败提示及重试。 */

import { computed, nextTick, ref, watch } from 'vue';
import { FullScreen } from '@element-plus/icons-vue';
import type { PptSlide } from '../../types/pptAnomaly';

const props = defineProps<{ pageNo: number; slide?: PptSlide; unavailable: boolean; plain?: boolean; wide?: boolean }>();
const enlarged = ref(false);
const enlargeButton = ref<HTMLButtonElement>();
const failed = ref(false);
const loaded = ref(false);
const attempt = ref(0);
const previewUrl = computed(() => {
  const url = props.slide?.imageUrl;
  if (!url || url.startsWith('blob:')) return url;
  return `${url}${url.includes('?') ? '&' : '?'}attempt=${attempt.value}`;
});
watch(() => props.slide?.imageUrl, () => {
  enlarged.value = false;
  failed.value = false;
  loaded.value = false;
  attempt.value = 0;
});
/** 清除预览错误状态并重新请求 PPT 页面图片。 */
function retry() {
  failed.value = false;
  loaded.value = false;
  attempt.value++;
}

async function closePreview() {
  enlarged.value = false;
  await nextTick();
  enlargeButton.value?.focus({ preventScroll: true });
}
</script>

<template>
  <component :is="plain || wide ? 'div' : 'details'" class="preview" :class="{ plain, wide }" :open="plain || wide ? undefined : true">
    <summary v-if="!plain && !wide">核对 PPT 原页 · P{{ pageNo }}</summary>
    <div class="preview-body">
      <p v-if="wide" class="preview-label">上一版 PPT 原页</p>
      <p v-if="!slide" class="preview-state" role="status">{{ unavailable ? '暂无可用预览' : '正在读取预览…' }}</p>
      <p v-else-if="!slide.imageUrl" class="preview-state">暂无可用预览</p>
      <div v-else-if="failed" class="preview-state">
        预览暂时无法加载
        <el-button link type="primary" @click="retry">重试</el-button>
      </div>
      <template v-else>
        <p v-if="!loaded" class="preview-state" role="status">正在加载预览…</p>
        <img v-show="loaded" class="preview-image" :src="previewUrl" :alt="`原始 PPT P${pageNo}`"
          @load="loaded = true" @error="failed = true" @click="enlarged = true" />
        <button v-if="loaded" ref="enlargeButton" type="button" class="preview-link" @click="enlarged = true"
          aria-label="放大查看 PPT 原页">
          <el-icon aria-hidden="true"><FullScreen /></el-icon>
          放大查看
        </button>
        <Teleport v-if="enlarged && previewUrl" to="body">
          <div class="ppt-slide-image-viewer">
            <el-image-viewer :url-list="[previewUrl]" hide-on-click-modal close-on-press-escape
              :scale="0.8" :z-index="5000" @close="closePreview" />
          </div>
        </Teleport>
      </template>
    </div>
  </component>
</template>

<style scoped>
.preview { border: 1px solid #e3e9f2; border-radius: 8px; background: #f8fafc; color: #475467; font-size: 13px; }
summary { padding: 9px 12px; cursor: pointer; }
.preview-body { display: grid; justify-items: start; gap: 8px; padding: 0 12px 12px; }
.preview.plain { border: 0; border-radius: 0; background: transparent; }
.plain .preview-body { padding: 0; }
.preview-body p { margin: 0; }
.preview-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid #cbdcf8;
  border-radius: 8px;
  background: #edf4ff;
  color: #1757b8;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  font: inherit;
}
.preview-link:hover { background: #dceaff; border-color: #adc8f3; }
.preview-link:focus-visible { outline: 2px solid #1757b8; outline-offset: 2px; }
img { display: block; max-width: 100%; max-height: 160px; object-fit: contain; cursor: zoom-in; }
.preview.wide { border: 0; background: transparent; }
.wide .preview-body {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px 12px;
  padding: 0;
}
.wide .preview-label { grid-area: 1 / 1; color: #526681; font-weight: 600; }
.wide .preview-link { grid-area: 1 / 2; justify-self: end; min-height: 28px; padding: 0 8px; border-color: transparent; background: transparent; }
.wide .preview-link:hover { background: #e8f0fd; }
.wide .preview-image, .wide .preview-state { grid-column: 1 / -1; width: 100%; }
.wide .preview-image { max-height: min(37vh, 360px); border: 1px solid #dce5f1; border-radius: 7px; background: #f4f7fb; }
.wide .preview-state {
  display: grid; place-items: center; align-content: center;
  min-height: 100px; border: 1px solid #dce5f1; border-radius: 7px;
  padding: 10px; background: #f4f7fb; text-align: center;
}
:global(.ppt-slide-image-viewer .el-image-viewer__canvas) { pointer-events: none; }
:global(.ppt-slide-image-viewer .el-image-viewer__img) { pointer-events: auto; }
:global(.ppt-slide-image-viewer .el-image-viewer__close) { width: 48px; height: 48px; }
</style>
