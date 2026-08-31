<script setup lang="ts">
/**
 * SkillJob 执行记录抽屉
 *
 * 展示某个 Job 的执行记录列表，含状态、报告校验、查看报告。
 */
import { ref, watch, computed, onUnmounted } from 'vue';
import { Download, EditPen, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { downloadExecutionFile, listExecutions, retryExecution, viewExecutionFile } from '../api/skillJob';
import type { SkillJobExecution } from '../types/skillJob';
import SkillJobReportEditorDrawer from './SkillJobReportEditorDrawer.vue';

const props = defineProps<{ open: boolean; jobId: number | null; canDownload?: boolean; canEdit?: boolean }>();
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

const executions = ref<SkillJobExecution[]>([]);
const loading = ref(false);
const statusFilter = ref('');
const expandedId = ref<number | null>(null);
const currentPage = ref(1);
const pageSize = ref(20);
const retrying = ref<Set<number>>(new Set());

const pagedExecutions = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return executions.value.slice(start, start + pageSize.value);
});

/** 是否有执行中/排队中的记录，决定是否开启轮询 */
const hasInFlight = computed(() =>
  executions.value.some(e => e.status === 'RUNNING' || e.status === 'PENDING')
);

let pollTimer: ReturnType<typeof setInterval> | undefined;

watch(() => props.open, (open) => {
  if (!open || !props.jobId) {
    stopPolling();
    return;
  }
  statusFilter.value = '';
  currentPage.value = 1;
  load();
});

watch(() => executions.value.length, (total) => {
  const lastPage = Math.max(1, Math.ceil(total / pageSize.value));
  if (currentPage.value > lastPage) currentPage.value = lastPage;
});

function filterExecutions() {
  currentPage.value = 1;
  expandedId.value = null;
  load();
}

function changePageSize(size: number) {
  pageSize.value = size;
  currentPage.value = 1;
}

async function load() {
  if (!props.jobId) return;
  loading.value = true;
  try {
    executions.value = await listExecutions(props.jobId, statusFilter.value || undefined);
  } catch {
    executions.value = [];
  } finally {
    loading.value = false;
  }
  syncPolling();
}

/** 静默刷新（不闪 loading），供轮询调用 */
async function refresh() {
  if (!props.jobId) return;
  try {
    executions.value = await listExecutions(props.jobId, statusFilter.value || undefined);
  } catch {
    /* 保留旧数据，不覆盖 */
  }
  syncPolling();
}

/** 有在跑记录则开启轮询，否则停止 */
function syncPolling() {
  if (hasInFlight.value) startPolling();
  else stopPolling();
}

function startPolling() {
  if (pollTimer != null) return;
  pollTimer = setInterval(refresh, 5000);
}

function stopPolling() {
  if (pollTimer != null) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
}

onUnmounted(() => stopPolling());

function toggle(id: number) {
  expandedId.value = expandedId.value === id ? null : id;
}

function statusText(s: string) {
  return { RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', PENDING: '排队中', SKIPPED: '未执行' }[s] || s;
}
function triggerText(t: string) {
  return { MANUAL: '手动', EXTERNAL: '外部触发', METRIC: '指标触发' }[t] || t;
}
function statusClass(s: string) {
  return { RUNNING: 'st-running', SUCCESS: 'st-success', FAILED: 'st-failed', PENDING: 'st-pending', SKIPPED: 'st-skipped' }[s] || '';
}
/** 排队位置文案：仅 PENDING 且后端返回了 queueAhead 时展示；0 显示"即将执行"。 */
function queueAheadText(exec: SkillJobExecution): string {
  if (exec.status !== 'PENDING' || exec.queueAhead == null) return '';
  return exec.queueAhead === 0 ? '即将执行' : `前面还有 ${exec.queueAhead} 个`;
}
function fmtTime(t: string) {
  if (!t) return '-';
  return t.replace('T', ' ').substring(0, 19);
}

/** 计算执行耗时 startedAt -> completedAt */
function duration(startedAt: string, completedAt: string): string {
  if (!startedAt || !completedAt) return '-';
  const s = new Date(startedAt).getTime();
  const e = new Date(completedAt).getTime();
  if (isNaN(s) || isNaN(e) || e < s) return '-';
  const ms = e - s;
  if (ms < 1000) return ms + 'ms';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return sec + 's';
  const m = Math.floor(sec / 60);
  const r = sec % 60;
  return `${m}m${r}s`;
}

function close() { emit('update:open', false); }

const viewing = ref<Set<number>>(new Set());
const previewed = ref<Set<number>>(new Set());
const downloading = ref<Set<number>>(new Set());
const editorOpen = ref(false);
const editorExecutionId = ref<number | null>(null);

async function viewFile(execId: number) {
  viewing.value.add(execId);
  try {
    await viewExecutionFile(execId);
    previewed.value.add(execId);
  } catch (e) {
    alert(e instanceof Error ? e.message : '打开失败');
  } finally {
    viewing.value.delete(execId);
  }
}

async function downloadFile(execId: number) {
  downloading.value.add(execId);
  try {
    await downloadExecutionFile(execId);
  } catch (e) {
    alert(e instanceof Error ? e.message : '下载失败');
  } finally {
    downloading.value.delete(execId);
  }
}

function editFile(execId: number) {
  editorExecutionId.value = execId;
  editorOpen.value = true;
}

function executionReason(message?: string): string {
  if (!message) return '-';
  if (message.startsWith('JobNotFound')) return '任务不存在';
  if (message.startsWith('JobDisabled')) return '任务已禁用';
  if (message.startsWith('JobNotConfigured')) return '任务配置不完整';
  if (message.startsWith('SkillPermissionDenied')) return 'Skill 权限失效';
  if (/timeout|timed out|超时/i.test(message)) return '执行超时';
  if (/model|llm|模型/i.test(message)) return '模型调用失败';
  if (/report|artifact|文件|报告/i.test(message)) return '报告生成失败';
  return '';
}

function showError(exec: SkillJobExecution) {
  ElMessageBox.alert(exec.errorMsg || '暂无错误详情', `执行 #${exec.id} 错误详情`, {
    confirmButtonText: '关闭', customClass: 'execution-error-dialog',
  });
}

async function retry(exec: SkillJobExecution) {
  if (retrying.value.has(exec.id)) return;
  retrying.value.add(exec.id);
  try {
    const created = await retryExecution(exec.id);
    ElMessage.success(`已重新排队，执行 ID #${created.id}`);
    await refresh();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重试失败');
  } finally {
    retrying.value.delete(exec.id);
  }
}
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" @click.self="close">
        <div class="drawer">
          <div class="drawer-header">
            <h3>执行记录</h3>
            <button class="drawer-close" @click="close" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
            <div class="filter-bar">
              <select v-model="statusFilter" @change="filterExecutions" class="status-select">
                <option value="">全部状态</option>
                <option value="RUNNING">执行中</option>
                <option value="SUCCESS">成功</option>
                <option value="FAILED">失败</option>
                <option value="PENDING">排队中</option>
                <option value="SKIPPED">未执行</option>
              </select>
              <button class="btn ghost sm" @click="load">刷新</button>
            </div>

            <div v-if="loading" class="loading">加载中…</div>
            <div v-else-if="executions.length === 0" class="empty">暂无执行记录</div>
            <div v-else class="exec-list">
              <div v-for="exec in pagedExecutions" :key="exec.id" class="exec-item">
                <div class="exec-row" @click="toggle(exec.id)">
                  <span class="exec-id">#{{ exec.id }}</span>
                  <span class="exec-status" :class="statusClass(exec.status)">{{ statusText(exec.status) }}</span>
                  <span v-if="queueAheadText(exec)" class="exec-queue-ahead">{{ queueAheadText(exec) }}</span>
                  <span class="exec-trigger">{{ triggerText(exec.triggerType) }}</span>
                  <span class="exec-time">{{ fmtTime(exec.startedAt) }}</span>
                  <span class="exec-toggle">{{ expandedId === exec.id ? '▾' : '▸' }}</span>
                </div>
                <div v-if="expandedId === exec.id" class="exec-detail">
                  <div class="detail-row">
                    <span class="dl">会话ID</span>
                    <span class="dv">{{ exec.conversationId || '-' }}</span>
                  </div>
                  <div class="detail-row">
                    <span class="dl">报告写入</span>
                    <span class="dv" :class="exec.mdFileWritten ? 'ok' : 'no'">{{ exec.mdFileWritten ? '是' : '否' }}</span>
                  </div>
                  <div class="detail-row">
                    <span class="dl">报告存在</span>
                    <span class="dv" :class="exec.mdFileExists ? 'ok' : 'no'">{{ exec.mdFileExists ? '是' : '否' }}</span>
                  </div>
                  <div class="detail-row">
                    <span class="dl">完成时间</span>
                    <span class="dv">{{ fmtTime(exec.completedAt) }}</span>
                  </div>
                  <div class="detail-row">
                    <span class="dl">耗时</span>
                    <span class="dv">{{ duration(exec.startedAt, exec.completedAt) }}</span>
                  </div>
                  <div v-if="exec.errorMsg" class="detail-row">
                    <span class="dl">{{ exec.status === 'SKIPPED' ? '未执行原因' : '错误信息' }}</span>
                    <div class="error-summary">
                      <span v-if="executionReason(exec.errorMsg)" class="dv" :class="{ err: exec.status === 'FAILED' }">{{ executionReason(exec.errorMsg) }}</span>
                      <button class="report-action" @click.stop="showError(exec)">错误详情</button>
                      <button v-if="exec.status === 'FAILED' && canDownload" class="report-action" :disabled="retrying.has(exec.id)" @click.stop="retry(exec)">{{ retrying.has(exec.id) ? '排队中…' : '重试' }}</button>
                    </div>
                  </div>
                  <div v-if="exec.mdFileExists && canDownload" class="detail-row">
                    <span class="dl">生成文件</span>
                    <div class="report-actions">
                      <button class="report-action" :disabled="viewing.has(exec.id)" title="预览报告" @click.stop="viewFile(exec.id)">
                        <el-icon><View /></el-icon><span>{{ viewing.has(exec.id) ? '打开中…' : '预览' }}</span>
                      </button>
                      <button v-if="previewed.has(exec.id) && canEdit" class="report-action" title="编辑 HTML" @click.stop="editFile(exec.id)">
                        <el-icon><EditPen /></el-icon><span>编辑</span>
                      </button>
                      <button v-if="previewed.has(exec.id)" class="report-action" :disabled="downloading.has(exec.id)" title="下载 HTML" @click.stop="downloadFile(exec.id)">
                        <el-icon><Download /></el-icon><span>{{ downloading.has(exec.id) ? '下载中…' : '下载' }}</span>
                      </button>
                    </div>
                  </div>
                  <div v-else-if="exec.mdFileExists" class="detail-row">
                    <span class="dl">生成文件</span>
                    <span class="dv muted">仅创建人可下载</span>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="!loading && executions.length > 0" class="pagination-bar">
              <el-pagination
                v-model:current-page="currentPage"
                :page-size="pageSize"
                :page-sizes="[10, 20, 50]"
                :total="executions.length"
                layout="total, sizes, prev, pager, next"
                small
                @size-change="changePageSize"
              />
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="close">关闭</button>
          </div>
        </div>
      </div>
    </transition>
    <SkillJobReportEditorDrawer
      v-model:open="editorOpen"
      :execution-id="editorExecutionId"
      @saved="refresh"
    />
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.45); display: flex; justify-content: flex-end; z-index: 1000; }
.drawer { width: 640px; max-width: 90vw; height: 100%; background: #fff; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
.drawer-close { border: none; background: transparent; font-size: 24px; line-height: 1; color: #64748b; cursor: pointer; padding: 0 4px; border-radius: 4px; }
.drawer-close:hover { background: #f1f5f9; color: #0f172a; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.drawer-footer { display: flex; gap: 8px; justify-content: flex-end; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.loading, .empty { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }

.filter-bar { display: flex; gap: 8px; margin-bottom: 12px; }
.pagination-bar { display: flex; justify-content: flex-end; padding-top: 14px; }
.status-select { padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; background: #fff; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.ghost { background: #fff; color: #475569; }
.btn.sm { padding: 6px 12px; font-size: 13px; }

.exec-list { display: flex; flex-direction: column; gap: 4px; }
.exec-item { border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
.exec-row { display: flex; align-items: center; gap: 12px; padding: 10px 14px; cursor: pointer; background: #f8fafc; }
.exec-row:hover { background: #f1f5f9; }
.exec-id { font-weight: 700; color: #0f172a; font-size: 13px; min-width: 40px; }
.exec-status { font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 4px; }
.st-running { background: #e0e7ff; color: #4338ca; }
.st-success { background: #d1fae5; color: #065f46; }
.st-failed { background: #fee2e2; color: #991b1b; }
.st-pending { background: #fef3c7; color: #92400e; }
.st-skipped { background: #f1f5f9; color: #64748b; }
.exec-trigger { font-size: 12px; color: #64748b; }
.exec-queue-ahead { font-size: 12px; color: #92400e; background: #fffbeb; padding: 2px 8px; border-radius: 4px; border: 1px solid #fde68a; white-space: nowrap; }
.exec-time { font-size: 12px; color: #94a3b8; margin-left: auto; }
.exec-toggle { color: #94a3b8; font-size: 14px; }

.exec-detail { padding: 12px 14px; border-top: 1px solid #e2e8f0; background: #fff; }
.detail-row { display: flex; gap: 8px; padding: 4px 0; font-size: 13px; }
.dl { color: #64748b; min-width: 70px; font-weight: 600; }
.dv { color: #1e293b; word-break: break-all; }
.dv.ok { color: #16a34a; }
.dv.no { color: #dc2626; }
.dv.err { color: #dc2626; }
.dv.muted { color: #94a3b8; font-size: 12px; }
.error-summary { display: flex; min-width: 0; align-items: center; gap: 8px; flex-wrap: wrap; }
.report-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.report-action { display: inline-flex; height: 28px; align-items: center; gap: 4px; padding: 0 9px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; color: #2563eb; cursor: pointer; font-size: 12px; }
.report-action:hover { border-color: #93c5fd; background: #eff6ff; }
.report-action:disabled { opacity: 0.5; cursor: not-allowed; }

.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
