<script setup lang="ts">
/** 反馈富文本编辑器，处理草稿、截图、表格、历史反馈及提交事件。 */

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { PptFinding } from '../../types/pptAnomaly';
import { feedbackEdits, feedbackHistory, feedbackAttachmentUrl, toPptTitleDetails, type FeedbackNote, type PptTitleDetail } from '../../api/pptAnomaly';
import { cleanFeedbackHtml, decodeFeedback, encodeFeedback, unpackFeedback, type FeedbackDraft } from './feedbackContent';
const props = defineProps<{
  finding: Pick<PptFinding, 'id' | 'editable' | 'ownerUserId' | 'status'>;
  draft?: FeedbackDraft;
  saving: boolean;
  compact?: boolean;
  readOnly?: boolean;
  conflict?: boolean;
  showDetailLinks?: boolean;
}>();
const emit = defineEmits<{ draft: [FeedbackDraft]; save: []; retry: [string] }>();
const canEdit = computed(() => props.finding.editable && !props.readOnly);
const error = ref('');
const displayedFeedback = ref<ReturnType<typeof unpackFeedback>>();
const details = ref<PptTitleDetail[]>([]);
const loadingFeedback = ref(true);
const loadFailed = ref(false);
const hadCurrent = ref(false);
const baseRevision = ref('');
const historyOpen = ref(false);
const historyLoading = ref(false);
const historyError = ref('');
const retryRevision = ref('');
type HistoryContent = ReturnType<typeof unpackFeedback>;
const historyRows = ref<{
  id: number; changedAt: string; changedBy: string; before: HistoryContent | null; after: HistoryContent;
}[]>([]);
const editor = ref<HTMLElement>();
const maxBytes = 16 * 1024;
const tablePicker = ref(false);
const editing = ref(false);
const editorDisabled = computed(() => !editing.value || props.saving || loadingFeedback.value || loadFailed.value);
const rows = ref<number>(3);
const columns = ref<number>(3);
const activeImage = ref<HTMLImageElement>();
const images = new Map<number, { file: File; url: string }>();
let nextImage = 0;
let caret: Range | undefined;
/** 为新增截图建立编辑器图片标识和临时预览地址。 */
function registerImage(file: File) {
  const id = nextImage++;
  images.set(id, { file, url: URL.createObjectURL(file) });
  return id;
}

// 组件销毁时释放截图临时地址，避免长期占用浏览器内存。
onBeforeUnmount(() => {
  for (const { url } of images.values()) URL.revokeObjectURL(url);
});

// 切换事项时恢复反馈内容，并取消上一事项尚未完成的图片读取。
watch([editor, () => props.finding], async ([element, finding], _, onCleanup) => {
  if (canEdit.value && !element) return;
  const controller = new AbortController();
  onCleanup(() => controller.abort());
  loadingFeedback.value = true;
  loadFailed.value = false;
  editing.value = false;
  error.value = '';
  displayedFeedback.value = undefined;
  details.value = [];
  hadCurrent.value = false;
  baseRevision.value = '';
  caret = undefined;
  try {
    if (element && props.draft) {
      baseRevision.value = props.draft.baseRevision;
      hadCurrent.value = finding.status === 'CONFIRMED' || finding.status === 'ADJUSTED';
      const ids = props.draft.files.map(registerImage);
      element.innerHTML = decodeFeedback(props.draft.explanation, index => images.get(ids[index])?.url ?? null);
      return;
    }
    const history = await feedbackHistory(finding.id);
    if (controller.signal.aborted) return;
    baseRevision.value = history.baseRevision;
    details.value = toPptTitleDetails(history.detailLinks ?? []);
    hadCurrent.value = !!history.current;
    const note: FeedbackNote | null = history.current ?? history.previous;
    if (!note) return;
    const feedback = unpackFeedback(note.explanation ?? '', name => feedbackAttachmentUrl(finding.id, name));
    if (!element) {
      displayedFeedback.value = feedback;
      return;
    }
    const content = document.createElement('template');
    content.innerHTML = feedback.html;
    for (const name of feedback.files) {
      const image = document.createElement('img');
      image.src = feedbackAttachmentUrl(finding.id, name);
      image.alt = '反馈图片';
      content.content.append(image);
    }
    const ids = new Map<string, number>();
    await Promise.all(Array.from(content.content.querySelectorAll('img')).map(async image => {
      const response = await fetch(image.src, { signal: controller.signal });
      if (!response.ok) throw new Error('反馈图片加载失败');
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      const type = blob.type === 'image/jpg' ? 'image/jpeg' : blob.type;
      const key = image.src;
      let id = ids.get(key);
      if (id === undefined) {
        id = registerImage(new File([blob], type === 'image/jpeg' ? 'feedback.jpg' : 'feedback.png', { type }));
        ids.set(key, id);
      }
      image.dataset.feedbackImage = String(id);
      image.src = images.get(id)!.url;
    }));
    if (!controller.signal.aborted) element.replaceChildren(content.content);
  } catch {
    if (!controller.signal.aborted) {
      loadFailed.value = true;
      error.value = '补充说明或图片加载失败，请重新打开此页后重试';
    }
  } finally {
    if (!controller.signal.aborted) {
      loadingFeedback.value = false;
    }
  }
}, { immediate: true, flush: 'post' });

/** 从已保存反馈切换到编辑状态并恢复内容。 */
async function startEditing() {
  editing.value = true;
  await nextTick();
  editor.value?.focus();
}

/** 读取编辑器内容和图片顺序，生成可提交的反馈草稿。 */
function capture() {
  if (editorDisabled.value) return;
  const element = editor.value;
  if (!element) return;
  if (activeImage.value && !element.contains(activeImage.value)) activeImage.value = undefined;
  const snapshot = element.cloneNode(true) as HTMLElement;
  const files: File[] = [];
  const slots = new Map<number, number>();
  for (const image of snapshot.querySelectorAll<HTMLImageElement>('img[data-feedback-image]')) {
    const id = Number(image.dataset.feedbackImage);
    const attachment = images.get(id);
    if (!attachment) { image.remove(); continue; }
    if (!slots.has(id)) {
      slots.set(id, files.length);
      files.push(attachment.file);
    }
    image.dataset.feedbackImage = String(slots.get(id));
  }
  const explanation = files.length || snapshot.textContent?.trim() || snapshot.querySelector('table') || hadCurrent.value
    ? encodeFeedback(snapshot.innerHTML) : '';
  error.value = new TextEncoder().encode(explanation).length > maxBytes
    ? '补充说明过长，请缩减到 16 KB 以内' : '';
  emit('draft', { explanation, files, baseRevision: baseRevision.value });
}

async function openHistory() {
  const id = props.finding.id;
  historyOpen.value = true;
  historyLoading.value = true;
  historyError.value = '';
  retryRevision.value = '';
  historyRows.value = [];
  try {
    const current = props.conflict ? await feedbackHistory(id) : null;
    const edits = await feedbackEdits(id);
    if (props.finding.id !== id) return;
    const content = (note: FeedbackNote) => unpackFeedback(note.explanation ?? '', name => feedbackAttachmentUrl(id, name));
    historyRows.value = edits.map(edit => ({ ...edit, before: edit.before ? content(edit.before) : null, after: content(edit.after) }));
    retryRevision.value = current?.baseRevision ?? '';
  } catch {
    historyError.value = '修改记录加载失败，请重试';
  } finally {
    historyLoading.value = false;
  }
}

/** 记录当前编辑器光标位置，供插入内容后恢复。 */
function rememberCaret() {
  const selection = window.getSelection();
  if (editor.value && selection?.rangeCount && editor.value.contains(selection.getRangeAt(0).commonAncestorContainer)) {
    caret = selection.getRangeAt(0).cloneRange();
  }
}

/** 恢复编辑器焦点及最近一次光标位置。 */
function restoreCaret() {
  const element = editor.value!;
  element.focus();
  const range = caret && element.contains(caret.commonAncestorContainer) ? caret : document.createRange();
  if (range !== caret) {
    range.selectNodeContents(element);
    range.collapse(false);
  }
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  return range;
}

/** 将粘贴的表格插入编辑器并保留行列结构。 */
function insertTables(html: string) {
  const element = editor.value;
  if (!element) return;
  const range = restoreCaret();
  const template = document.createElement('template');
  template.innerHTML = html;
  const fragment = template.content;
  if (!fragment.querySelector('table')) return;
  let block = range.startContainer;
  while (block !== element && block.parentNode !== element) block = block.parentNode!;
  const insideTable = block instanceof Element && (block.matches('table') || block.querySelector('table'));
  if (block !== element && insideTable) {
    range.setStartAfter(block);
    range.collapse(true);
  } else if (block !== element && block instanceof HTMLElement) {
    range.collapse(true);
    if (block.matches('p, div') && !block.textContent && !block.querySelector(':not(br)')) {
      block.before(fragment);
      range.setStart(block, 0);
      range.collapse(true);
      rememberCaret();
      capture();
      return;
    }
    const tail = range.cloneRange();
    tail.setEnd(block, block.childNodes.length);
    const continuation = block.cloneNode(false);
    continuation.appendChild(tail.extractContents());
    if (continuation.hasChildNodes()) block.after(continuation);
    range.setStartAfter(block);
    range.collapse(true);
  } else {
    range.collapse(true);
  }
  const paragraph = document.createElement('p');
  paragraph.append(document.createElement('br'));
  fragment.append(paragraph);
  range.insertNode(fragment);
  range.selectNodeContents(paragraph);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  rememberCaret();
  capture();
}

/** 校验并插入截图文件，维护图片数量与临时预览。 */
function addFiles(incoming: File[]) {
  if (!incoming.length || !editor.value) return;
  const existing = new Set(Array.from(editor.value.querySelectorAll<HTMLImageElement>('img[data-feedback-image]'),
    image => image.dataset.feedbackImage));
  if (incoming.some(file => !['image/png', 'image/jpeg'].includes(file.type))) {
    error.value = '图片须为 PNG 或 JPEG。请将 Excel 或 PowerPoint 图表截图后粘贴。';
    return;
  }
  if (incoming.some(file => file.size === 0 || file.size > 2 * 1024 * 1024)) {
    error.value = '图片为空或超过 2 MB，请重新截图或压缩图片。';
    return;
  }
  if (existing.size + incoming.length > 3) {
    error.value = '每项反馈最多添加 3 张图片，请先移除已有图片。';
    return;
  }
  const range = restoreCaret();
  range.collapse(false);
  for (const file of incoming) {
    const id = registerImage(file);
    const image = document.createElement('img');
    image.dataset.feedbackImage = String(id);
    image.src = images.get(id)!.url;
    image.alt = '反馈图片';
    range.insertNode(image);
    range.setStartAfter(image);
    range.collapse(true);
  }
  rememberCaret();
  capture();
}

/** 读取文件选择控件中的截图并加入编辑器。 */
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  addFiles(Array.from(input.files ?? []));
  input.value = '';
}

/** 选中点击的反馈图片，供删除或调整位置。 */
function selectImage(event: MouseEvent) {
  if (editorDisabled.value) return;
  if (event.target instanceof HTMLImageElement && event.target.dataset.feedbackImage !== undefined) activeImage.value = event.target;
  rememberCaret();
}

watch(activeImage, (image, previousImage) => {
  previousImage?.removeAttribute('data-selected');
  image?.setAttribute('data-selected', '');
});

/** 删除当前选中的图片并更新草稿。 */
function removeImage() {
  activeImage.value?.remove();
  activeImage.value = undefined;
  capture();
}

/** 调整选中图片在反馈内容中的位置。 */
function moveImage() {
  const image = activeImage.value;
  if (!image || !editor.value?.contains(image)) return;
  const range = restoreCaret();
  range.collapse(true);
  range.insertNode(image);
  range.setStartAfter(image);
  range.collapse(true);
  rememberCaret();
  activeImage.value = undefined;
  capture();
}

/** 处理剪贴板中的截图、表格、富文本和纯文字。 */
function paste(event: ClipboardEvent) {
  if (editorDisabled.value) return;
  event.preventDefault();
  const data = event.clipboardData;
  if (!data) return;
  rememberCaret();
  const html = data.getData('text/html');
  const tableHtml = html && cleanFeedbackHtml(html, false);
  if (tableHtml && /<table[>\s]/i.test(tableHtml)) {
    insertTables(tableHtml);
    return;
  }
  const files = Array.from(data.files);
  const supported = files.filter(file => ['image/png', 'image/jpeg'].includes(file.type));
  if (supported.length || files.length) {
    addFiles(supported.length ? supported : files);
    return;
  }
  // Office 图表可能以嵌入图片形式提供，剪贴板文件列表中未必包含该图片。
  const clipboardMarkup = document.createElement('template');
  clipboardMarkup.innerHTML = html;
  const chartSource = clipboardMarkup.content.querySelector('img')?.getAttribute('src') ?? '';
  const embedded = /^data:(image\/(?:png|jpeg));base64,([a-z0-9+/]+={0,2})$/i.exec(chartSource);
  if (embedded) {
    const [, type, payload] = embedded;
    if (payload.length > Math.ceil(2 * 1024 * 1024 / 3) * 4) {
      error.value = '图片超过 2 MB，请压缩后重试。';
      return;
    }
    try {
      const bytes = Uint8Array.from(atob(payload), character => character.charCodeAt(0));
      addFiles([new File([bytes], type.endsWith('png') ? 'chart.png' : 'chart.jpg', { type })]);
    } catch {
      error.value = '无法读取剪贴板中的图片，请重新截图后粘贴。';
    }
    return;
  }
  const plain = data.getData('text/plain');
  if (plain.includes('\t')) {
    const table = document.createElement('table');
    const body = table.createTBody();
    for (const line of plain.replace(/\r?\n$/, '').split(/\r?\n/)) {
      const row = body.insertRow();
      for (const value of line.split('\t')) row.insertCell().textContent = value;
    }
    insertTables(table.outerHTML);
  } else if (plain) {
    document.execCommand('insertText', false, plain);
    capture();
  } else if (html) {
    error.value = '剪贴板没有可插入的图片或表格，请将图表截图后粘贴。';
  }
}

/** 在当前光标位置插入用户指定行列的表格。 */
function insertTable() {
  const cells = '<td><br></td>'.repeat(columns.value);
  insertTables(`<table><tbody>${`<tr>${cells}</tr>`.repeat(rows.value)}</tbody></table>`);
  tablePicker.value = false;
}
</script>
<template>
  <section class="feedback" :aria-labelledby="`ppt-feedback-label-${finding.id}`">
    <div class="feedback-heading">
      <h4 :id="`ppt-feedback-label-${finding.id}`" class="field-label">补充说明：</h4>
      <div class="feedback-actions">
        <el-button size="small" :disabled="loadingFeedback" @click="openHistory">
          {{ conflict ? '核对最新反馈' : '修改记录' }}
        </el-button>
        <template v-if="canEdit">
          <span v-if="draft" class="draft-status" role="status">待提交</span>
          <el-button v-if="editing" size="small" type="primary" :disabled="editorDisabled || !draft || conflict"
            @click="emit('save')">保存本页</el-button>
          <button v-else class="tool" type="button" :disabled="saving || loadingFeedback || loadFailed"
            @click="startEditing">编辑</button>
        </template>
      </div>
    </div>
    <div v-if="displayedFeedback" class="previous-feedback">
      <div class="rich" v-html="displayedFeedback.html" />
      <div v-for="name in displayedFeedback.files" :key="name" class="rich">
        <img :src="feedbackAttachmentUrl(finding.id, name)" alt="反馈截图" loading="lazy" />
      </div>
    </div>
    <template v-if="canEdit">
      <p v-if="editing" :id="`ppt-feedback-help-${finding.id}`" class="help">支持粘贴 Excel 单元格和图表截图。图片限 PNG 或 JPEG，每张最多 2 MB，最多 3 张。</p>
      <div class="composer" :class="{ 'composer-editing': editing }">
        <div v-if="editing" class="tools">
          <label class="tool" :class="{ disabled: editorDisabled }">
            插入图片
            <input type="file" accept="image/png,image/jpeg" multiple :disabled="editorDisabled" @change="chooseFiles" />
          </label>
          <button class="tool" type="button" :disabled="editorDisabled" :aria-expanded="tablePicker" :aria-controls="`ppt-table-size-${finding.id}`" @click="tablePicker = !tablePicker">插入表格</button>
          <template v-if="activeImage">
            <button class="tool" type="button" :disabled="editorDisabled" @click="moveImage">移动图片到光标处</button>
            <button class="tool" type="button" :disabled="editorDisabled" @click="removeImage">移除图片</button>
          </template>
        </div>
        <div v-if="editing && tablePicker" :id="`ppt-table-size-${finding.id}`" class="tools">
          <label>行数 <select v-model.number="rows" :disabled="editorDisabled"><option v-for="size in 20" :key="size" :value="size">{{ size }}</option></select></label>
          <label>列数 <select v-model.number="columns" :disabled="editorDisabled"><option v-for="size in 12" :key="size" :value="size">{{ size }}</option></select></label>
          <button class="tool" type="button" :disabled="editorDisabled" @click="insertTable">插入 {{ rows }} × {{ columns }}</button>
        </div>
        <div ref="editor" class="rich editor" :contenteditable="!editorDisabled" :role="editing ? 'textbox' : undefined" :aria-multiline="editing ? true : undefined" :aria-busy="loadingFeedback" :aria-readonly="editing ? editorDisabled : undefined"
          :aria-labelledby="`ppt-feedback-label-${finding.id}`" :aria-describedby="editing ? `ppt-feedback-help-${finding.id}` : undefined" :data-placeholder="loadingFeedback ? '正在加载反馈…' : loadFailed ? '' : editing ? '填写处理进展或补充说明' : '暂无补充说明'"
          @input="rememberCaret(); capture()" @click="selectImage" @keyup="rememberCaret" @mouseup="rememberCaret" @focusout="rememberCaret" @paste="paste" @drop.prevent />
      </div>
      <p v-if="editing && activeImage" class="help">已选中图片。将光标放到目标位置，再点「移动图片到光标处」。</p>
    </template>
    <span v-else-if="!compact" class="readonly">仅反馈部门成员可以编辑</span>
    <p v-else-if="!error && !loadingFeedback && !displayedFeedback?.html && !displayedFeedback?.files.length" class="help">暂无补充说明</p>
    <el-dialog v-model="historyOpen" class="ppt-feedback-history-dialog" title="反馈修改记录"
      append-to-body align-center width="min(1120px, calc(100vw - 32px))">
      <div v-loading="historyLoading" class="edit-history">
        <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />
        <el-empty v-else-if="!historyLoading && !historyRows.length" description="暂无修改记录" />
        <article v-for="row in historyRows" :key="row.id" class="edit-entry">
          <header class="edit-entry-header">
            <strong>{{ row.changedBy }}</strong>
            <time :datetime="row.changedAt">{{ row.changedAt?.replace('T', ' ').slice(0, 19) }}</time>
          </header>
          <div class="edit-comparison">
            <section v-for="{ label, content, updated } in [
              { label: '修改前', content: row.before, updated: false },
              { label: '修改后', content: row.after, updated: true },
            ]" :key="label" class="edit-version" :class="{ 'edit-version-updated': updated }">
              <h5>{{ label }}</h5>
              <template v-if="content?.html || content?.files.length">
                <div v-if="content.html" class="rich" v-html="content.html" />
                <div v-for="name in content.files" :key="name" class="rich">
                  <img :src="feedbackAttachmentUrl(finding.id, name)" alt="反馈截图" loading="lazy" />
                </div>
              </template>
              <p v-else class="edit-version-empty">{{ content ? '空白' : '未填写' }}</p>
            </section>
          </div>
        </article>
      </div>
      <template v-if="conflict && retryRevision && !readOnly" #footer>
        <el-button type="primary" @click="historyOpen = false; emit('retry', retryRevision)">确认使用我的草稿</el-button>
      </template>
    </el-dialog>
    <section v-if="showDetailLinks && details.length" class="detail-links">
      <h4 class="field-label">明细地址</h4>
      <ul>
        <li v-for="(detail, index) in details" :key="index">
          <el-link :href="detail.url" type="primary" target="_blank" rel="noopener noreferrer">{{ detail.name }}</el-link>
        </li>
      </ul>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<style scoped>
.detail-links { margin-top: 16px; overflow-wrap: anywhere; }
.detail-links ul { list-style: none; margin: 4px 0 0; padding: 0; }
.detail-links li + li { margin-top: 4px; }
.feedback {
  padding: clamp(12px, 1.5vw, 18px);
  background: #fff;
  border: 1px solid #e3e9f2;
  border-radius: 9px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  font-size: 14px;
}
.readonly, .previous-feedback { color: #475467; font-size: 13px; }
.previous-feedback { border: 1px solid #e3e9f2; border-radius: 6px; background: #f8fafc; }
.composer { border: 1px solid #cbd5e1; border-radius: 8px; overflow: hidden; }
.composer-editing:focus-within { border-color: #2563eb; box-shadow: 0 0 0 2px #dbeafe; }
.feedback-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.feedback-actions { display: flex; gap: 8px; }
:global(.ppt-feedback-history-dialog.el-dialog) {
  --el-dialog-padding-primary: clamp(14px, 2vw, 24px);
  --el-dialog-border-radius: 14px;
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}
:global(.ppt-feedback-history-dialog .el-dialog__title) {
  color: #172b49;
  font-size: 20px;
  font-weight: 600;
}
:global(.ppt-feedback-history-dialog .el-dialog__body) {
  overflow: auto;
  overscroll-behavior: contain;
}
.edit-history {
  display: grid;
  gap: 14px;
  min-height: 120px;
  padding: clamp(10px, 1.5vw, 18px);
  background: #f3f6fa;
  border-radius: 10px;
}
.edit-entry {
  min-width: 0;
  padding: clamp(12px, 1.5vw, 18px);
  border: 1px solid #e0e7f0;
  border-radius: 10px;
  background: #fff;
}
.edit-entry-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: baseline;
  gap: 4px 16px;
  margin-bottom: 12px;
  color: #172b49;
  overflow-wrap: anywhere;
}
.edit-entry-header time {
  color: #52627a;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.edit-comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.edit-version {
  min-width: 0;
  min-height: 94px;
  border: 1px solid #e0e7f0;
  border-radius: 8px;
  background: #f8fafc;
}
.edit-version-updated {
  border-color: #bed7f7;
  background: #f1f7ff;
}
.edit-version h5 {
  margin: 0;
  padding: 9px 12px;
  border-bottom: 1px solid #e0e7f0;
  border-radius: 7px 7px 0 0;
  background: #eef2f7;
  color: #475467;
  font-size: 13px;
}
.edit-version-updated h5 {
  border-color: #bed7f7;
  background: #e3efff;
  color: #174a91;
}
.edit-version .rich {
  padding: 12px;
  color: #24364d;
  line-height: 1.65;
}
.edit-version-empty {
  margin: 0;
  padding: 12px;
  color: #52627a;
}
@media (max-width: 640px) {
  .edit-comparison { grid-template-columns: minmax(0, 1fr); }
}
.draft-status { align-self: center; color: #80520c; font-size: 13px; }
.tools { display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 10px; background: #f8fafc; align-items: center; border-bottom: 1px solid #e3e9f2; }
.tool { position: relative; background: #fff; color: #344054; border: 1px solid #d0d5dd; border-radius: 4px; padding: 4px 8px; font: inherit; cursor: pointer; }
.tool input { position: absolute; inset: 0; opacity: 0; width: 100%; cursor: pointer; }
.tool.disabled, .tool:disabled { opacity: .55; cursor: default; }
.tool:focus-within { outline: 2px solid #2563eb; outline-offset: -2px; }
.editor:focus { outline: none; }
.rich { padding: 10px; white-space: pre-wrap; overflow: auto; overflow-wrap: anywhere; color: #1d4ed8; }
.field-label { margin: 0; font-size: 17px; font-weight: 600; color: #172b49; }
.feedback-heading .field-label { color: #1d4ed8; }
.help { margin: 0 0 4px; font-size: 13px; color: #52627a; }
.editor { padding: 14px; background: #f8fafc; line-height: 1.65; }
.composer-editing .editor { min-height: clamp(120px, 24dvh, 200px); background: #fff; }
.editor:empty::before { content: attr(data-placeholder); color: #667085; pointer-events: none; }
.rich :deep(img) { display: block; max-width: 100%; max-height: 320px; object-fit: contain; }
.composer-editing .editor :deep(img) { cursor: pointer; }
.editor :deep(img[data-selected]) { outline: 2px solid #2563eb; outline-offset: 2px; }
.rich :deep(table) { border-collapse: collapse; margin: 8px 0; }
.rich :deep(td), .rich :deep(th) { border: 1px solid #aab6c7; padding: 6px 10px; min-width: 70px; }
</style>
