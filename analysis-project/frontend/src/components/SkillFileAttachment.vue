<script setup lang="ts">
/**
 * Skill 脚本附件管理组件(共享)
 *
 * 设计: 表单内只展示"已引用"文件(紧凑列表)+ 一个"文件库"按钮;
 *       点击按钮弹出可滚动小窗,在小窗内管理文件库(上传/筛选/引用/下载),
 *       避免文件过多时把表单撑得过长。
 *  - 编辑模式(skillId!=null): 引用立即落库;创建模式(skillId==null): 暂存,skill 创建后批量引用
 *  - notOwner(disabled): 只读展示该 Skill 已引用文件,不开放文件库
 *
 * 由 SkillFormPage 和 SkillFormDrawer 共用。
 */
import { ref, computed, watch } from 'vue';
import {
  uploadFile,
  listFiles,
  fetchFileBlob,
  getSkillFiles,
  addSkillFile,
  removeSkillFile,
  deleteFile,
} from '../api/skill';
import type { SkillFileItem, SkillFileReferenceItem } from '../types/skill';

const props = defineProps<{
  skillId: number | null;
  disabled: boolean;
}>();

// ============ 状态 ============

// 已引用文件(编辑模式从服务端拉取;创建模式为暂存)。同时作为引用 ID 的来源。
const referencedFiles = ref<SkillFileReferenceItem[]>([]);
const referencedFileIds = computed(() => new Set(referencedFiles.value.map(f => f.id)));

// 文件库(当前用户全部已上传文件,弹窗内展示)
const libraryFiles = ref<SkillFileItem[]>([]);
const libraryLoading = ref(false);
const fileTypeFilter = ref('');

const loading = ref(false);
const uploading = ref(false);
const error = ref('');

// 文件库弹窗
const modalOpen = ref(false);

// 拖拽状态
const dragOver = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

// ============ 加载 ============

async function loadReferencedFiles() {
  if (props.skillId == null) {
    referencedFiles.value = [];
    return;
  }
  loading.value = true;
  try {
    referencedFiles.value = await getSkillFiles(props.skillId);
  } catch {
    referencedFiles.value = [];
  } finally {
    loading.value = false;
  }
}

async function loadLibrary() {
  libraryLoading.value = true;
  try {
    libraryFiles.value = await listFiles(fileTypeFilter.value || undefined);
  } catch {
    libraryFiles.value = [];
  } finally {
    libraryLoading.value = false;
  }
}

// skillId 变化(进入编辑/创建)时重新加载已引用文件
watch(() => props.skillId, () => {
  loadReferencedFiles();
}, { immediate: true });

// ============ 引用 / 取消引用 ============

function isReferenced(fileId: number): boolean {
  return referencedFileIds.value.has(fileId);
}

/** SkillFileItem -> SkillFileReferenceItem(创建模式暂存用) */
function toReferenceItem(f: SkillFileItem): SkillFileReferenceItem {
  return {
    id: f.id,
    filename: f.filename,
    fileType: f.fileType,
    fileSize: f.fileSize,
    description: f.description,
    referenceType: 'ATTACHMENT',
    referencedAt: new Date().toISOString(),
  };
}

async function toggleReference(file: SkillFileItem) {
  if (props.disabled) return;
  const referenced = isReferenced(file.id);
  try {
    if (referenced) {
      if (props.skillId != null) await removeSkillFile(props.skillId, file.id);
      referencedFiles.value = referencedFiles.value.filter(f => f.id !== file.id);
    } else {
      if (props.skillId != null) await addSkillFile(props.skillId, file.id, 'ATTACHMENT');
      referencedFiles.value = [...referencedFiles.value, toReferenceItem(file)];
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '引用操作失败';
  }
}

/** 紧凑列表里的取消引用(按 id) */
async function unreferenceFile(fileId: number) {
  if (props.disabled) return;
  try {
    if (props.skillId != null) await removeSkillFile(props.skillId, fileId);
    referencedFiles.value = referencedFiles.value.filter(f => f.id !== fileId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '取消引用失败';
  }
}

/**
 * 从文件库删除文件(磁盘 + DB 级联,影响所有引用它的 Skill)。
 * 不可撤销,需二次确认。
 */
async function deleteFileFromLibrary(file: SkillFileItem) {
  if (props.disabled) return;
  const msg = `确定从文件库删除 "${file.filename}"?\n该文件将从文件库及所有引用它的 Skill 中移除,此操作不可撤销。`;
  if (!confirm(msg)) return;
  error.value = '';
  try {
    await deleteFile(file.id);
    // 若该文件已被当前 Skill 引用(含创建模式暂存),同步移除
    if (referencedFileIds.value.has(file.id)) {
      referencedFiles.value = referencedFiles.value.filter(f => f.id !== file.id);
    }
    await loadLibrary();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除文件失败';
  }
}

// ============ 上传 ============

const ALLOWED_EXTENSIONS = ['.py', '.sql'];
const MAX_SIZE_BYTES = 1024 * 1024; // 1MB

function validateFile(file: File): string | null {
  const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(ext)) {
    return `不支持的文件类型,仅允许 ${ALLOWED_EXTENSIONS.join(' / ')}`;
  }
  if (file.size > MAX_SIZE_BYTES) {
    return '文件大小超过 1MB 限制';
  }
  return null;
}

async function handleFiles(files: FileList | File[]) {
  if (props.disabled) return;
  error.value = '';
  for (const file of files) {
    const err = validateFile(file);
    if (err) {
      error.value = err;
      continue;
    }
    uploading.value = true;
    try {
      const result = await uploadFile(file);
      // 上传后默认引用到当前 Skill(编辑模式立即落库,创建模式暂存)
      if (props.skillId != null) {
        await addSkillFile(props.skillId, result.id, 'ATTACHMENT');
      }
      referencedFiles.value = [...referencedFiles.value, {
        id: result.id,
        filename: result.filename,
        fileType: result.fileType,
        fileSize: result.fileSize,
        description: result.description,
        referenceType: 'ATTACHMENT',
        referencedAt: result.createdAt,
      }];
      await loadLibrary(); // 刷新文件库(同名覆盖等场景以服务端为准)
    } catch (e) {
      error.value = e instanceof Error ? e.message : '上传失败';
    } finally {
      uploading.value = false;
    }
  }
}

function pickFile() {
  fileInput.value?.click();
}

function onFileInput(e: Event) {
  const input = e.target as HTMLInputElement;
  if (input.files && input.files.length > 0) {
    handleFiles(input.files);
  }
  input.value = ''; // 重置,允许重复选择同一文件
}

function onDrop(e: DragEvent) {
  dragOver.value = false;
  if (props.disabled) return;
  if (e.dataTransfer?.files) {
    handleFiles(e.dataTransfer.files);
  }
}

function onDragOver(e: DragEvent) {
  e.preventDefault();
  if (!props.disabled) dragOver.value = true;
}

function onDragLeave() {
  dragOver.value = false;
}

// ============ 下载 ============

async function downloadFile(fileId: number) {
  try {
    const { blob, filename } = await fetchFileBlob(fileId);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '下载失败';
  }
}

// ============ 文件库弹窗 ============

function openModal() {
  if (props.disabled) return;
  error.value = '';
  modalOpen.value = true;
  loadLibrary();
}

function closeModal() {
  modalOpen.value = false;
}

// ============ 格式化 / 图标 ============

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

function fileIcon(fileType: string): string {
  switch (fileType) {
    case 'PYTHON': return '🐍';
    case 'SQL': return '🗃️';
    default: return '📄';
  }
}

// ============ 暴露给父组件: 创建模式批量引用 ============

function getPendingFileIds(): number[] {
  return referencedFiles.value.map(f => f.id);
}

async function attachPendingFiles(skillId: number) {
  for (const f of referencedFiles.value) {
    try {
      await addSkillFile(skillId, f.id, 'ATTACHMENT');
    } catch {
      // 单个引用失败不阻塞
    }
  }
}

defineExpose({ getPendingFileIds, attachPendingFiles });
</script>

<template>
  <div class="attachment-section">
    <div class="attachment-header">
      <span class="label">脚本附件</span>
      <button v-if="!disabled" type="button" class="library-btn" @click="openModal">
        📂 文件库
      </button>
    </div>

    <div v-if="error" class="upload-error">{{ error }}</div>

    <!-- 已引用文件(紧凑展示,限高可滚) -->
    <div v-if="loading" class="empty-inline">加载中…</div>
    <div v-else-if="referencedFiles.length > 0" class="ref-chips">
      <div v-for="f in referencedFiles" :key="f.id" class="ref-chip">
        <span class="chip-icon">{{ fileIcon(f.fileType) }}</span>
        <span class="chip-name" :title="f.filename">{{ f.filename }}</span>
        <span class="chip-size">{{ formatSize(f.fileSize) }}</span>
        <button type="button" class="chip-action download" @click="downloadFile(f.id)" title="下载">⬇</button>
        <button v-if="!disabled" type="button" class="chip-action remove" @click="unreferenceFile(f.id)" title="取消引用">×</button>
      </div>
    </div>
    <div v-else-if="!disabled" class="empty-inline">暂无附件,点击"文件库"选择</div>
    <div v-else class="empty-inline">暂无附件</div>

    <!-- 文件库弹窗 -->
    <Teleport to="body">
      <transition name="modal-fade">
        <div v-if="modalOpen" class="modal-mask" @click.self="closeModal">
          <div class="modal">
            <div class="modal-header">
              <h3>我的文件库</h3>
              <button type="button" class="modal-close" @click="closeModal" aria-label="关闭">×</button>
            </div>
            <div class="modal-body">
              <!-- 工具栏: 上传 + 筛选 + 引用计数 -->
              <div class="modal-toolbar">
                <button type="button" class="upload-btn" :disabled="uploading" @click="pickFile">
                  {{ uploading ? '上传中…' : '＋ 上传新文件' }}
                </button>
                <input
                  ref="fileInput"
                  type="file"
                  :accept="ALLOWED_EXTENSIONS.join(',')"
                  multiple
                  class="file-input"
                  @change="onFileInput"
                />
                <select v-model="fileTypeFilter" class="filter-select" @change="loadLibrary">
                  <option value="">全部</option>
                  <option value="PYTHON">Python</option>
                  <option value="SQL">SQL</option>
                </select>
                <span class="ref-summary">已引用 {{ referencedFiles.length }}</span>
              </div>
              <div class="upload-hint">.py / .sql ｜ 单文件 ≤ 1MB ｜ 点击文件行引用 / 取消引用</div>
              <div v-if="error" class="upload-error">{{ error }}</div>

              <!-- 文件列表(可滚动) -->
              <div v-if="libraryLoading" class="modal-empty">加载中…</div>
              <div
                v-else-if="libraryFiles.length === 0"
                class="modal-empty drop-area"
                :class="{ 'drag-over': dragOver }"
                @drop="onDrop"
                @dragover="onDragOver"
                @dragleave="onDragLeave"
              >
                文件库为空,点击"上传新文件"或拖拽文件到此
              </div>
              <div
                v-else
                class="modal-list drop-area"
                :class="{ 'drag-over': dragOver }"
                @drop="onDrop"
                @dragover="onDragOver"
                @dragleave="onDragLeave"
              >
                <div
                  v-for="f in libraryFiles"
                  :key="f.id"
                  class="file-row"
                  :class="{ referenced: isReferenced(f.id) }"
                  :title="isReferenced(f.id) ? '点击取消引用' : '点击引用到当前 Skill'"
                  @click="toggleReference(f)"
                >
                  <span class="ref-check">
                    <span v-if="isReferenced(f.id)" class="check-on">✓</span>
                    <span v-else class="check-off">○</span>
                  </span>
                  <span class="file-icon">{{ fileIcon(f.fileType) }}</span>
                  <span class="file-main">
                    <span class="file-name" :title="f.filename">{{ f.filename }}</span>
                    <span v-if="f.description" class="file-desc">{{ f.description }}</span>
                  </span>
                  <span v-if="isReferenced(f.id)" class="ref-badge">已引用</span>
                  <span class="file-size">{{ formatSize(f.fileSize) }}</span>
                  <button type="button" class="file-action download" @click.stop="downloadFile(f.id)" title="下载">⬇</button>
                  <button type="button" class="file-action delete" @click.stop="deleteFileFromLibrary(f)" title="从文件库删除">🗑</button>
                </div>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn primary" @click="closeModal">完成</button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>
  </div>
</template>

<style scoped>
.attachment-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.attachment-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.label {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
}

/* 文件库按钮 */
.library-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #cbd5e1;
  background: #fff;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s, background-color 0.2s;
}
.library-btn:hover {
  border-color: #93c5fd;
  color: #2563eb;
  background: #eff6ff;
}
.upload-error {
  color: #dc2626;
  font-size: 13px;
}

/* 已引用紧凑列表 */
.ref-chips {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 132px;
  overflow-y: auto;
}
.ref-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  font-size: 13px;
}
.chip-icon {
  font-size: 14px;
  flex-shrink: 0;
}
.chip-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1e293b;
  font-weight: 500;
}
.chip-size {
  color: #94a3b8;
  font-size: 12px;
  flex-shrink: 0;
}
.chip-action {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  padding: 2px 6px;
  border-radius: 4px;
  flex-shrink: 0;
  transition: background-color 0.2s;
}
.chip-action.download {
  color: #3b82f6;
}
.chip-action.download:hover {
  background: #dbeafe;
}
.chip-action.remove {
  color: #94a3b8;
  font-size: 16px;
  line-height: 1;
}
.chip-action.remove:hover {
  color: #dc2626;
  background: #fee2e2;
}
.empty-inline {
  font-size: 13px;
  color: #94a3b8;
  padding: 6px 0;
}

/* 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.modal {
  width: 560px;
  max-width: 92vw;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(15, 23, 42, 0.18);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #e2e8f0;
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}
.modal-close {
  border: none;
  background: transparent;
  font-size: 22px;
  line-height: 1;
  color: #64748b;
  cursor: pointer;
  padding: 0 4px;
  border-radius: 4px;
}
.modal-close:hover {
  background: #f1f5f9;
  color: #0f172a;
}
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.modal-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #e2e8f0;
}

/* 工具栏 */
.modal-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.upload-btn {
  border: 1px dashed #93c5fd;
  background: #eff6ff;
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  color: #2563eb;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}
.upload-btn:hover:not(:disabled) {
  border-color: #3b82f6;
  background: #dbeafe;
}
.upload-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.file-input {
  display: none;
}
.filter-select {
  padding: 4px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
  background: #fff;
  color: #1e293b;
}
.ref-summary {
  margin-left: auto;
  font-size: 12px;
  color: #64748b;
}
.upload-hint {
  font-size: 12px;
  color: #94a3b8;
}

/* 拖拽区 */
.drop-area {
  border-radius: 8px;
  transition: background-color 0.15s, border-color 0.15s;
}
.drop-area.drag-over {
  background: #dbeafe;
  outline: 2px dashed #3b82f6;
  outline-offset: -2px;
}

/* 文件列表(弹窗内,随 modal-body 滚动) */
.modal-empty {
  color: #94a3b8;
  font-size: 13px;
  text-align: center;
  padding: 28px 10px;
  border: 1px dashed #e2e8f0;
  border-radius: 8px;
}
.modal-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}
.file-row:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}
.file-row.referenced {
  background: #eff6ff;
  border-color: #93c5fd;
}
.ref-check {
  width: 18px;
  flex-shrink: 0;
  text-align: center;
  font-size: 14px;
}
.check-on {
  color: #3b82f6;
  font-weight: 700;
}
.check-off {
  color: #cbd5e1;
}
.file-icon {
  font-size: 14px;
  flex-shrink: 0;
}
.file-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1e293b;
  font-weight: 500;
}
.file-desc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: #94a3b8;
}
.ref-badge {
  flex-shrink: 0;
  font-size: 11px;
  color: #2563eb;
  background: #dbeafe;
  border-radius: 4px;
  padding: 1px 6px;
}
.file-size {
  color: #94a3b8;
  font-size: 12px;
  flex-shrink: 0;
}
.file-action {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  padding: 2px 6px;
  border-radius: 4px;
  transition: background-color 0.2s;
  flex-shrink: 0;
}
.file-action.download {
  color: #3b82f6;
}
.file-action.download:hover {
  background: #dbeafe;
}
.file-action.delete {
  color: #94a3b8;
}
.file-action.delete:hover {
  color: #dc2626;
  background: #fee2e2;
}

/* 按钮 */
.btn {
  padding: 8px 18px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  cursor: pointer;
  font-size: 14px;
}
.btn.primary {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}
.btn.primary:hover {
  background: #2563eb;
}

/* 弹窗动画 */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s;
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
</style>
