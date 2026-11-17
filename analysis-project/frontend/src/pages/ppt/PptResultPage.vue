<script setup lang="ts">
/** 例会反馈汇总页面，按页展示原页、事项、经办人及反馈结果。 */

import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { downloadBatchResult, feedbackAttachmentUrl, generateBatchResult, resultSlidePreview,
  type PptResultHandler, type PptResultReport } from '../../api/pptAnomaly';
import type { PptSlide } from '../../types/pptAnomaly';
import { unpackFeedback } from '../../components/ppt/feedbackContent';
import PptSlideViewer from '../../components/ppt/PptSlideViewer.vue';

const route = useRoute();
const batchId = computed(() => {
  const value = Number(route.params.batchId);
  return Number.isSafeInteger(value) && value > 0 ? value : undefined;
});
const report = ref<PptResultReport>();
const loading = ref(false);
const previewLoading = ref(false);
const exporting = ref(false);
const error = ref('');
const exportError = ref('');
const previews = ref<Record<number, PptSlide>>({});
const previewErrors = ref<Record<number, string>>({});
const refreshKey = ref(0);
const directorySidebar = ref<HTMLElement>();
const directoryHeight = ref<string>();
let controller: AbortController | undefined;
let imageUrls: string[] = [];

// 根据目录的实际位置利用剩余可视高度，滚动吸顶后同步扩展目录。
watch(directorySidebar, (sidebar, _, onCleanup) => {
  if (!sidebar) return;
  const scroller = sidebar.closest('.ppt-anomaly-shell .content');
  const updateHeight = () => {
    const bottom = Math.min(window.innerHeight, scroller?.getBoundingClientRect().bottom ?? window.innerHeight);
    directoryHeight.value = `${Math.max(0, bottom - sidebar.getBoundingClientRect().top - 16)}px`;
  };
  const observer = new ResizeObserver(updateHeight);
  observer.observe(scroller ?? document.documentElement);
  observer.observe(sidebar.parentElement!);
  window.addEventListener('scroll', updateHeight, true);
  window.addEventListener('resize', updateHeight);
  updateHeight();
  onCleanup(() => {
    observer.disconnect();
    window.removeEventListener('scroll', updateHeight, true);
    window.removeEventListener('resize', updateHeight);
  });
}, { flush: 'post' });

const pages = computed(() => report.value?.pages.map(page => ({
  ...page,
  items: page.items.map(item => ({ ...item,
    feedback: unpackFeedback(item.explanation ?? '', name => feedbackAttachmentUrl(item.findingId, name)),
  })),
})) ?? []);
/** 展示经办人的姓名、认证号和所属部门，兼容旧接口缺少部门的情况。 */
const handlerText = (handlers: PptResultHandler[]) => handlers.map(person => {
  const identity = person.name === person.userId ? person.userId : `${person.name}（${person.userId}）`;
  return person.departments?.length ? `${identity}-${person.departments.join('、')}` : identity;
}).join('、') || '待分配';

function jumpTo(id: string) {
  const target = document.getElementById(id);
  target?.scrollIntoView({ block: 'start' });
  target?.focus({ preventScroll: true });
}

/** 读取汇总页面的原页图片并支持中止过期请求。 */
async function loadPreview(versionId: number, pageNo: number, signal: AbortSignal) {
  delete previewErrors.value[pageNo];
  try {
    const blob = await resultSlidePreview(versionId, pageNo, signal);
    if (signal.aborted) return;
    const url = URL.createObjectURL(blob);
    imageUrls.push(url);
    previews.value[pageNo] = { id: pageNo, pageNo, imageUrl: url, renderStatus: 'READY' };
  } catch (cause) {
    if (!signal.aborted) previewErrors.value[pageNo] = cause instanceof Error ? cause.message : '原页加载失败';
  }
}

// 切换批次或刷新时中止旧请求，并释放上一批原页图片的临时地址。
watch([batchId, refreshKey], async ([selectedBatchId], _, onCleanup) => {
  const request = new AbortController();
  controller = request;
  onCleanup(() => {
    request.abort();
    imageUrls.forEach(url => URL.revokeObjectURL(url));
    imageUrls = [];
  });
  report.value = undefined;
  previews.value = {};
  previewErrors.value = {};
  error.value = '';
  exportError.value = '';
  loading.value = false;
  previewLoading.value = false;
  if (selectedBatchId === undefined) {
    error.value = '批次编号无效，请返回材料列表重新选择';
    return;
  }
  loading.value = true;
  try {
    const result = await generateBatchResult(selectedBatchId, request.signal);
    if (request.signal.aborted) return;
    report.value = result;
    loading.value = false;
    previewLoading.value = true;
    // 预览渲染共用服务端的单个渲染器，逐页读取避免并发占用。
    for (const page of result.pages) {
      if (request.signal.aborted) return;
      await loadPreview(result.versionId, page.pageNo, request.signal);
    }
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '汇总结果加载失败';
  } finally {
    if (!request.signal.aborted) {
      loading.value = false;
      previewLoading.value = false;
    }
  }
}, { immediate: true });

/** 重新加载指定页的 PPT 预览。 */
async function retryPreview(pageNo: number) {
  if (!report.value || !controller || previewLoading.value) return;
  const request = controller;
  previewLoading.value = true;
  await loadPreview(report.value.versionId, pageNo, request.signal);
  if (!request.signal.aborted) previewLoading.value = false;
}

/** 下载当前汇总版本的 Word 文件。 */
async function exportWord() {
  if (!report.value || exporting.value) return;
  exporting.value = true;
  exportError.value = '';
  try {
    await downloadBatchResult(report.value);
  } catch (cause) {
    exportError.value = cause instanceof Error ? cause.message : 'Word 导出失败，请重试';
  } finally {
    exporting.value = false;
  }
}
</script>

<template>
  <section class="result-page-container">
    <header class="page-header">
      <h2>生成结果</h2>
      <RouterLink to="/ppt-anomaly/materials" class="back-link">返回材料列表</RouterLink>
    </header>
    <div v-loading="loading" class="result-content">
      <div class="result-toolbar">
        <h3 v-if="report" class="file-title">{{ report.fileName }}</h3>
        <div class="result-actions">
          <el-button :disabled="loading || exporting" @click="refreshKey++">刷新结果</el-button>
          <el-button type="primary" :loading="exporting" :disabled="!report || loading || previewLoading"
            @click="exportWord">导出 Word</el-button>
        </div>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="exportError" :title="exportError" type="error" :closable="false" />
      <p v-if="previewLoading" role="status">正在加载 PPT 原页…</p>
      <el-empty v-if="report && !pages.length" description="该版本暂无需反馈的事项" />
      <div v-if="pages.length" class="result-layout">
        <aside ref="directorySidebar" class="result-directory-sidebar" aria-label="页面目录"
          :style="{ '--directory-height': directoryHeight }">
          <details id="result-directory" class="result-directory" tabindex="-1" open>
            <summary>目录</summary>
            <nav aria-label="反馈结果目录">
              <a v-for="page in pages" :key="page.pageNo" :href="`#result-page-${page.pageNo}`"
                @click.prevent="jumpTo(`result-page-${page.pageNo}`)">
                P{{ page.pageNo }} · {{ page.title || '未命名页面' }}
              </a>
            </nav>
          </details>
        </aside>
        <div class="result-document">
          <article v-for="page in pages" :id="`result-page-${page.pageNo}`" :key="page.pageNo" class="result-page" tabindex="-1">
            <header class="result-page-heading">
              <div>
                <h3>{{ page.title || '未命名页面' }}</h3>
                <p>经办人：{{ handlerText(page.handlers) }}</p>
              </div>
            </header>
            <div v-if="previewErrors[page.pageNo]" class="preview-error" role="alert">
              {{ previewErrors[page.pageNo] }}
              <el-button link type="primary" :disabled="previewLoading || exporting" @click="retryPreview(page.pageNo)">重试原页</el-button>
            </div>
            <PptSlideViewer v-else plain :page-no="page.pageNo" :slide="previews[page.pageNo]" :unavailable="false" />
            <section v-if="page.items.length" class="page-feedback">
              <h4 class="feedback-heading">反馈记录</h4>
              <section v-for="item in page.items" :key="item.findingId" class="department-feedback">
                <h5 class="department-heading">{{ item.departments.join('、') || '未分配部门' }}：</h5>
                <p v-if="!item.judgement" class="pending">待反馈</p>
                <template v-else>
                  <div v-if="item.explanation" class="feedback-html" v-html="item.feedback.html" />
                  <p v-else>已反馈，未填写说明</p>
                  <img v-for="name in item.feedback.files" :key="name" class="feedback-image"
                    :src="feedbackAttachmentUrl(item.findingId, name)" alt="反馈图片" loading="lazy" />
                </template>
              </section>
            </section>
          </article>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.result-page-container { box-sizing: border-box; width: 100%; padding: clamp(16px, 2vw, 32px); color: #142441; }
.page-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }
.page-header h2 { margin: 0; font-size: 28px; }
.back-link { color: #2855c4; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.back-link:focus-visible { outline: 2px solid #2855c4; outline-offset: 4px; }
.result-content { min-height: 180px; padding: clamp(16px, 2vw, 32px); background: #fff; }
.result-toolbar { display: grid; grid-template-columns: minmax(210px, 1fr) minmax(0, 3fr) minmax(210px, 1fr); align-items: center; gap: 16px; margin-bottom: 28px; }
.file-title { grid-column: 2; text-align: center; overflow-wrap: anywhere; }
.result-actions { grid-column: 3; display: flex; justify-content: flex-end; align-items: center; gap: 12px; }
.result-actions .el-button + .el-button { margin-left: 0; }
.result-toolbar h3, .result-page-heading h3 { margin: 0; font-size: 18px; }
.result-page-heading p { margin: 8px 0 0; color: #52627b; overflow-wrap: anywhere; }
.result-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); align-items: start; gap: 32px; }
.result-directory-sidebar { box-sizing: border-box; width: 100%; max-width: 240px; min-width: 0; position: sticky; top: 16px; max-height: var(--directory-height, calc(100dvh - 32px)); overflow-y: auto; overflow-x: hidden; overscroll-behavior: contain; padding-right: 16px; border-right: 1px solid #e3e9f2; }
.result-document { min-width: 0; }
.result-page { padding-block: 8px 24px; }
.result-page + .result-page { margin-top: 20px; padding-top: 32px; border-top: 1px solid #e3e9f2; }
.result-page-heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.result-page-heading > div { min-width: 0; overflow-wrap: anywhere; }
.result-directory summary { width: fit-content; padding-block: 8px; cursor: pointer; font-weight: 600; }
.result-directory nav { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; }
.result-directory nav a { min-width: 0; max-width: 100%; color: #2855c4; text-decoration: none; font-size: 14px; padding-block: 4px; overflow-wrap: anywhere; white-space: normal; }
.result-directory nav a:hover { text-decoration: underline; }
.result-page, .result-directory { scroll-margin-top: 16px; }
.result-page :deep(.preview-body) { justify-items: center; }
.result-page :deep(.preview-body img) { max-height: 70vh; }
.page-feedback { margin-top: 20px; }
.department-feedback { margin-top: 16px; }
.department-heading { margin: 0 0 8px; font-size: 14px; }
.feedback-heading { margin: 14px 0 8px; font-size: 14px; }
.pending { color: #a56716; }
.feedback-html { line-height: 1.7; overflow-x: auto; overflow-wrap: anywhere; }
.feedback-html :deep(img), .feedback-image { display: block; max-width: 100%; height: auto; margin-block: 10px; }
.feedback-html :deep(table) { border-collapse: collapse; max-width: 100%; }
.feedback-html :deep(td), .feedback-html :deep(th) { border: 1px solid #cbd5e1; padding: 6px 10px; min-width: 60px; }
.preview-error { padding: 16px; background: #fef0f0; border-radius: 8px; color: #b42318; }
.el-alert { margin-block: 12px; }
@media (max-width: 1000px) {
  .result-toolbar { grid-template-columns: minmax(0, 1fr); }
  .file-title, .result-actions { grid-column: 1; }
}
@media (max-width: 760px) {
  .result-layout { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .result-directory-sidebar { position: static; max-width: 100%; max-height: 240px; padding: 0 0 16px; border-right: 0; border-bottom: 1px solid #e3e9f2; }
}
</style>
