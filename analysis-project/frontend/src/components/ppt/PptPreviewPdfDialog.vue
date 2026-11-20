<script setup lang="ts">
/** 给历史材料补传 PDF，保存后可翻页检查并放大对应的转换图片。 */
import { onBeforeUnmount, ref } from 'vue';
import { previewPptImportImage, uploadPptPreviewPdf } from '../../api/pptAnomaly';
import type { PptSlide, PptVersion } from '../../types/pptAnomaly';
import PptSlideViewer from './PptSlideViewer.vue';

const props = defineProps<{ version: PptVersion }>();
const emit = defineEmits<{ close: [] }>();
const file = ref<File>();
const busy = ref(false);
const error = ref('');
const saved = ref(false);
const pageCount = ref(0);
const pageNo = ref(1);
const loading = ref(false);
const slide = ref<PptSlide>();
let request: AbortController | undefined;

function clearPreview() {
  request?.abort();
  request = undefined;
  if (slide.value?.imageUrl) URL.revokeObjectURL(slide.value.imageUrl);
  slide.value = undefined;
}
onBeforeUnmount(clearPreview);

async function loadPage() {
  if (!Number.isInteger(pageNo.value) || pageNo.value < 1 || pageNo.value > pageCount.value) pageNo.value = 1;
  clearPreview();
  const active = new AbortController();
  request = active;
  loading.value = true;
  error.value = '';
  try {
    const image = await previewPptImportImage({ versionId: props.version.id, pageNo: pageNo.value }, active.signal);
    if (!active.signal.aborted) slide.value = {
      id: pageNo.value, pageNo: pageNo.value, imageUrl: URL.createObjectURL(image), renderStatus: 'READY',
    };
  } catch (cause) {
    if (!active.signal.aborted) error.value = cause instanceof Error ? cause.message : 'PDF 页面加载失败，请重试';
  } finally {
    if (request === active) loading.value = false;
  }
}

async function save() {
  if (!file.value || busy.value) return;
  if (!file.value.name.toLowerCase().endsWith('.pdf') || !file.value.size || file.value.size > 50 * 1024 * 1024) {
    error.value = '请选择非空的 PDF 文件，最大 50 MB';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    const result = await uploadPptPreviewPdf(props.version.id, file.value);
    pageCount.value = result.pageCount;
    pageNo.value = 1;
    saved.value = true;
    await loadPage();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'PDF 上传失败，请重试';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="true" title="上传预览 PDF" width="min(880px, 94vw)" append-to-body
    :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy"
    @update:model-value="(open: boolean) => { if (!open && !busy) emit('close') }">
    <p>{{ version.fileName }} · v{{ version.versionNo }}</p>
    <p>请上传同一份 PPT 导出的完整 PDF，页数和顺序须一致，最大 50 MB。保存后用于各处页面预览。</p>
    <input type="file" accept=".pdf,application/pdf" aria-label="预览 PDF" :disabled="busy"
      @change="file = ($event.target as HTMLInputElement).files?.[0]" />
    <el-alert v-if="saved" title="PDF 已保存，可翻页检查效果并点击图片放大。" type="success" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="saved && !busy" class="pdf-pages">
      <label for="pdf-preview-page">查看页码</label>
      <el-input-number id="pdf-preview-page" v-model="pageNo" :min="1" :max="pageCount" :precision="0" :value-on-clear="1" :disabled="busy || loading"
        @change="loadPage" />
      <span>共 {{ pageCount }} 页</span>
      <el-button :disabled="busy || loading" @click="loadPage">重新加载本页</el-button>
    </div>
    <p v-if="loading" role="status">正在生成 PDF 页面图片…</p>
    <div v-else-if="slide" class="pdf-image">
      <PptSlideViewer plain :page-no="pageNo" :slide="slide" :unavailable="false" />
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">关闭</el-button>
      <el-button type="primary" :loading="busy" :disabled="!file || loading" @click="save">保存并预览</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pdf-pages { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
.pdf-image :deep(.preview-body) { justify-items: center; }
.pdf-image :deep(.preview-body > img) { max-height: 55vh; }
.el-alert { margin-top: 12px; }
</style>
