<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { Search } from '@element-plus/icons-vue';
import {
  feedbackReuseAttachmentUrl, feedbackReuseOptions, feedbackReusePreviewUrl, type PptFeedbackReuseOption,
} from '../../api/pptAnomaly';
import { unpackFeedback } from './feedbackContent';
import { feedbackPageTitle } from './feedbackPageTitle';
import PptSlideViewer from './PptSlideViewer.vue';

const props = defineProps<{
  findingId: number; findingTitle: string; hasContent: boolean; applying: boolean; applyError: string;
}>();
const emit = defineEmits<{ close: []; use: [PptFeedbackReuseOption] }>();
const keyword = ref(props.findingTitle?.trim() ? feedbackPageTitle(props.findingTitle) : '');
const appliedKeyword = ref('');
const options = ref<PptFeedbackReuseOption[]>([]);
const selected = ref<PptFeedbackReuseOption>();
const loading = ref(false);
const error = ref('');
const confirming = ref(false);
let request: AbortController | undefined;

function attachmentUrl(sourceId: number, name: string) {
  return feedbackReuseAttachmentUrl(props.findingId, sourceId, name);
}

const note = computed(() => selected.value
  ? unpackFeedback(selected.value.source.explanation, name => attachmentUrl(selected.value!.source.findingId, name))
  : undefined);
const previousSlide = computed(() => {
  const option = selected.value?.source;
  if (!option) return undefined;
  return {
    id: option.findingId, pageNo: option.pageNo, imageUrl: feedbackReusePreviewUrl(props.findingId, option.findingId),
    renderStatus: 'AVAILABLE',
  };
});

const candidates = computed(() => options.value.map(option => {
  const feedback = unpackFeedback(option.source.explanation, () => '');
  const text = document.createElement('template');
  text.innerHTML = feedback.html;
  const excerpt = text.content.textContent?.replace(/\s+/g, ' ').trim();
  return {
    option,
    excerpt: excerpt || (feedback.files.length || text.content.querySelector('img') ? '含反馈截图' : ''),
  };
}));

function author(option: PptFeedbackReuseOption) {
  const id = option.source.userId;
  return option.authorName && option.authorName !== id ? `${option.authorName}（${id}）` : id;
}

function select(option?: PptFeedbackReuseOption) {
  confirming.value = false;
  selected.value = option;
}

async function search(value: string) {
  request?.abort();
  const controller = new AbortController();
  request = controller;
  loading.value = true;
  error.value = '';
  confirming.value = false;
  try {
    const query = value.trim();
    const result = await feedbackReuseOptions(props.findingId, query, controller.signal);
    if (controller.signal.aborted) return;
    appliedKeyword.value = query;
    options.value = result;
    select(result.find(option => option.source.findingId === selected.value?.source.findingId) ?? result[0]);
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure instanceof Error ? failure.message : '历史反馈加载失败';
  } finally {
    if (!controller.signal.aborted) loading.value = false;
  }
}

function useSelected() {
  if (!selected.value) return;
  if (props.hasContent && !confirming.value) {
    confirming.value = true;
    return;
  }
  emit('use', selected.value);
}

onMounted(() => void search(keyword.value));
onBeforeUnmount(() => request?.abort());
</script>

<template>
  <el-dialog :model-value="true" title="复用上一版反馈" class="ppt-feedback-reuse-dialog"
    append-to-body destroy-on-close align-center width="min(1060px, calc(100vw - 32px))"
    :close-on-click-modal="false" @close="emit('close')">
    <div class="reuse-layout" :class="{ 'reuse-layout-empty': !selected }">
      <section class="reuse-options" aria-label="上一版反馈候选">
        <form class="reuse-search" @submit.prevent="search(keyword)">
          <el-input v-model="keyword" :prefix-icon="Search" placeholder="搜索上一版反馈标题"
            aria-label="搜索上一版反馈标题" clearable :disabled="applying" @clear="search('')" />
          <el-button native-type="submit" :disabled="applying">搜索</el-button>
        </form>
        <div class="reuse-summary">
          <span role="status">{{ loading ? '正在搜索…' : `${options.length} 条反馈` }}</span>
        </div>
        <div class="reuse-results" :aria-busy="loading">
          <el-alert v-if="error" :title="error" type="error" :closable="false" />
          <p v-else-if="!loading && !options.length" class="reuse-empty" role="status">
            {{ appliedKeyword ? '没有匹配的反馈，可更换标题或清空搜索' : '上一版暂无可复用的反馈' }}
          </p>
          <button v-for="{ option, excerpt } in candidates" :key="option.source.findingId" type="button"
            class="reuse-option" :aria-pressed="selected?.source.findingId === option.source.findingId"
            :disabled="applying || loading" @click="select(option)">
            <span class="reuse-title"><span class="reuse-page">P{{ option.source.pageNo }}</span>
              <strong>{{ option.source.title || '未命名页面' }}</strong></span>
            <span v-if="excerpt" class="reuse-excerpt">{{ excerpt }}</span>
            <span class="reuse-meta">{{ [option.source.chapter, option.authorName || option.source.userId].filter(Boolean).join(' · ') }}</span>
          </button>
        </div>
      </section>
      <section v-if="selected && previousSlide" class="reuse-preview" aria-label="所选旧页及反馈">
        <header class="reuse-preview-header">
          <h3>{{ selected.source.title || '未命名页面' }}</h3>
          <p class="reuse-meta">V{{ selected.source.versionNo }} · P{{ selected.source.pageNo }} · {{ selected.source.department }}</p>
        </header>
        <PptSlideViewer :key="selected.source.findingId" :slide="previousSlide" :page-no="selected.source.pageNo"
          :unavailable="false" wide />
        <section class="reuse-note">
          <div class="reuse-note-header">
            <strong>上一版补充说明</strong>
            <span class="reuse-meta">{{ author(selected) }} · {{ selected.source.createdAt?.replace('T', ' ').slice(0, 16) }}</span>
          </div>
          <div v-if="note?.html" class="reuse-rich" v-html="note.html" />
          <div v-for="name in note?.files" :key="name" class="reuse-rich">
            <img :src="attachmentUrl(selected.source.findingId, name)" alt="上一版反馈截图" loading="lazy" />
          </div>
        </section>
      </section>
    </div>
    <el-alert v-if="applyError" class="reuse-error" :title="applyError" type="error" :closable="false" />
    <template #footer>
      <div class="reuse-footer">
        <span v-if="confirming" role="status">当前已有内容，确认后将替换草稿。</span>
        <span v-else-if="selected" class="reuse-meta">已选 V{{ selected.source.versionNo }} · P{{ selected.source.pageNo }}</span>
        <el-button @click="emit('close')">取消</el-button>
        <el-button type="primary" :loading="applying" :disabled="!selected || loading" @click="useSelected">
          {{ confirming ? '确认替换' : '带入草稿' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.ppt-feedback-reuse-dialog.el-dialog) { --el-dialog-padding-primary: 20px; border-radius: 12px; }
:global(.ppt-feedback-reuse-dialog .el-dialog__title) { color: #152743; font-weight: 650; }
.reuse-layout { display: grid; grid-template-columns: minmax(230px, 1fr) minmax(0, 1.9fr); border-top: 1px solid #e3e9f2; }
.reuse-layout-empty { grid-template-columns: minmax(0, 1fr); }
.reuse-options {
  min-width: 0; max-height: 67vh; display: flex; flex-direction: column;
  padding: 14px 16px 0 0; border-right: 1px solid #e3e9f2;
}
.reuse-layout-empty .reuse-options { border-right: 0; padding-right: 0; }
.reuse-search, .reuse-summary, .reuse-title, .reuse-note-header, .reuse-footer { display: flex; align-items: center; gap: 8px; }
.reuse-summary { margin: 10px 0 7px; color: #536781; font-size: 12px; }
.reuse-results { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.reuse-empty { margin: 6px 0 0; padding: 20px 12px; color: #64758b; background: #f6f8fb; border-radius: 7px; text-align: center; }
.reuse-option { width: 100%; display: grid; gap: 4px; text-align: left; padding: 8px 10px; margin-bottom: 3px;
  border: 1px solid transparent; border-radius: 7px; color: #152743; background: white; cursor: pointer; font: inherit; }
.reuse-option:hover { background: #f5f8fd; }
.reuse-option[aria-pressed="true"] { background: #edf4ff; border-color: #c9daf6; }
.reuse-title, .reuse-meta { overflow-wrap: anywhere; }
.reuse-page { flex: none; color: #2451af; font-size: 12px; font-weight: 650; font-variant-numeric: tabular-nums; }
.reuse-excerpt { color: #334761; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden;
  overflow-wrap: anywhere; font-size: 13px; line-height: 1.45; }
.reuse-meta { color: #536781; font-size: 12px; }
.reuse-preview { display: grid; align-content: start; min-width: 0; gap: 10px; padding: 12px 2px 2px 16px;
  max-height: 67vh; overflow: auto; overscroll-behavior: contain; }
.reuse-preview-header h3, .reuse-preview-header p { margin: 0; }
.reuse-preview-header h3 { color: #152743; font-size: 18px; margin-bottom: 2px; }
.reuse-note { padding: 9px 0 0; border-top: 1px solid #e3e9f2; min-width: 0; }
.reuse-note-header { justify-content: space-between; flex-wrap: wrap; margin-bottom: 6px; color: #152743; }
.reuse-rich { white-space: pre-wrap; overflow-wrap: anywhere; overflow-x: auto; color: #263a54; }
.reuse-rich :deep(img) { display: block; max-width: 100%; max-height: 200px; object-fit: contain; }
.reuse-rich :deep(table) { border-collapse: collapse; }
.reuse-rich :deep(th), .reuse-rich :deep(td) { border: 1px solid #dce5f1; padding: 4px 7px; }
.reuse-footer { justify-content: flex-end; flex-wrap: wrap; border-top: 1px solid #e3e9f2; padding-top: 12px; }
.reuse-footer span { margin-right: auto; }
.reuse-error { margin-top: 12px; }
@media (max-width: 650px) {
  .reuse-layout { grid-template-columns: minmax(0, 1fr); max-height: 70vh; overflow-y: auto; }
  .reuse-options { max-height: none; padding-right: 0; border-right: 0; }
  .reuse-results { max-height: 24vh; }
  .reuse-preview { max-height: none; padding: 12px 0 2px; margin-top: 8px; border-top: 1px solid #e3e9f2; }
}
</style>
