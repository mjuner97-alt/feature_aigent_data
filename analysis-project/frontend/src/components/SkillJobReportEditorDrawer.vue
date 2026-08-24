<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Check, Close, EditPen, View } from '@element-plus/icons-vue';
import { getExecutionReportSource, saveExecutionReportSource } from '../api/skillJob';

const props = defineProps<{ open: boolean; executionId: number | null }>();
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'saved'): void;
}>();

const source = ref('');
const savedSource = ref('');
const mode = ref<'source' | 'preview'>('source');
const loading = ref(false);
const saving = ref(false);
const error = ref('');

const dirty = computed(() => source.value !== savedSource.value);
const canSave = computed(() => dirty.value && source.value.trim().length > 0 && !saving.value);

watch(() => [props.open, props.executionId] as const, ([open, executionId]) => {
  if (open && executionId) load(executionId);
  if (!open) reset();
});

async function load(executionId: number) {
  loading.value = true;
  error.value = '';
  mode.value = 'source';
  try {
    const html = await getExecutionReportSource(executionId);
    source.value = html;
    savedSource.value = html;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '读取报告源码失败';
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!props.executionId || !canSave.value) return;
  saving.value = true;
  error.value = '';
  try {
    const persisted = await saveExecutionReportSource(props.executionId, source.value);
    source.value = persisted;
    savedSource.value = persisted;
    mode.value = 'preview';
    emit('saved');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存报告失败';
  } finally {
    saving.value = false;
  }
}

function close() {
  if (dirty.value && !confirm('报告尚未保存，确定关闭吗？')) return;
  emit('update:open', false);
}

function reset() {
  source.value = '';
  savedSource.value = '';
  error.value = '';
  loading.value = false;
  saving.value = false;
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="report-editor-mask" @click.self="close">
      <section class="report-editor" aria-label="编辑 HTML 报告">
        <header>
          <div>
            <h3>编辑报告</h3>
            <span>执行 #{{ executionId }}</span>
          </div>
          <button class="icon-button" type="button" title="关闭" aria-label="关闭" @click="close">
            <el-icon><Close /></el-icon>
          </button>
        </header>

        <div class="editor-toolbar">
          <div class="mode-switch" role="tablist" aria-label="编辑模式">
            <button :class="{ active: mode === 'source' }" type="button" @click="mode = 'source'">
              <el-icon><EditPen /></el-icon><span>源码</span>
            </button>
            <button :class="{ active: mode === 'preview' }" type="button" @click="mode = 'preview'">
              <el-icon><View /></el-icon><span>预览</span>
            </button>
          </div>
          <span v-if="dirty" class="dirty-state">未保存</span>
        </div>

        <main>
          <div v-if="loading" class="empty-state">正在读取报告…</div>
          <div v-else-if="error && !source" class="error-state">{{ error }}</div>
          <template v-else>
            <div v-if="error" class="error-state compact">{{ error }}</div>
            <textarea
              v-if="mode === 'source'"
              v-model="source"
              class="source-editor"
              spellcheck="false"
              aria-label="HTML 源码"
            />
            <iframe
              v-else
              class="report-preview"
              :srcdoc="source"
              sandbox="allow-scripts allow-forms allow-modals allow-popups allow-downloads"
              title="HTML 报告预览"
            />
          </template>
        </main>

        <footer>
          <button class="btn secondary" type="button" @click="close">取消</button>
          <button class="btn primary" type="button" :disabled="!canSave" @click="save">
            <el-icon><Check /></el-icon><span>{{ saving ? '保存中…' : '保存' }}</span>
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.report-editor-mask { position: fixed; inset: 0; z-index: 1300; display: flex; justify-content: flex-end; background: rgb(15 23 42 / 48%); }
.report-editor { display: flex; width: min(1080px, 96vw); height: 100%; flex-direction: column; background: #fff; box-shadow: -10px 0 30px rgb(15 23 42 / 18%); }
header, footer { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 20px; border-bottom: 1px solid #e2e8f0; }
header h3 { margin: 0; color: #0f172a; font-size: 18px; }
header span { color: #64748b; font-size: 12px; }
.icon-button { display: inline-grid; width: 32px; height: 32px; place-items: center; border: 1px solid transparent; border-radius: 4px; background: transparent; color: #64748b; cursor: pointer; }
.icon-button:hover { border-color: #cbd5e1; background: #f8fafc; color: #0f172a; }
.editor-toolbar { display: flex; min-height: 48px; align-items: center; justify-content: space-between; padding: 8px 20px; border-bottom: 1px solid #e2e8f0; background: #f8fafc; }
.mode-switch { display: inline-flex; border: 1px solid #cbd5e1; border-radius: 6px; overflow: hidden; background: #fff; }
.mode-switch button { display: inline-flex; min-width: 92px; height: 32px; align-items: center; justify-content: center; gap: 6px; border: 0; border-right: 1px solid #cbd5e1; background: #fff; color: #475569; cursor: pointer; }
.mode-switch button:last-child { border-right: 0; }
.mode-switch button.active { background: #2563eb; color: #fff; }
.dirty-state { color: #a16207; font-size: 12px; font-weight: 600; }
main { display: flex; min-height: 0; flex: 1; flex-direction: column; padding: 14px 20px; }
.source-editor, .report-preview { width: 100%; min-height: 0; flex: 1; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; }
.source-editor { resize: none; padding: 14px; color: #1e293b; font: 13px/1.6 ui-monospace, "SFMono-Regular", Consolas, monospace; tab-size: 2; }
.source-editor:focus { border-color: #2563eb; outline: 2px solid rgb(37 99 235 / 12%); }
.report-preview { display: block; }
.empty-state, .error-state { padding: 40px 16px; color: #64748b; text-align: center; }
.error-state { color: #b91c1c; }
.error-state.compact { margin-bottom: 10px; padding: 8px 10px; border: 1px solid #fecaca; background: #fef2f2; font-size: 12px; text-align: left; }
footer { justify-content: flex-end; border-top: 1px solid #e2e8f0; border-bottom: 0; }
.btn { display: inline-flex; min-width: 88px; height: 36px; align-items: center; justify-content: center; gap: 6px; border: 1px solid #cbd5e1; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn.secondary { background: #fff; color: #475569; }
.btn.primary { border-color: #2563eb; background: #2563eb; color: #fff; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 700px) { .report-editor { width: 100vw; }.mode-switch button { min-width: 78px; }main { padding: 10px; } }
</style>
