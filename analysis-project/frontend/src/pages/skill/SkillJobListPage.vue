<script setup lang="ts">
/**
 * SkillJob 管理页面
 *
 * 功能：列表查看、搜索筛选、创建/编辑/删除、触发执行、查看执行记录
 */
import { computed, ref, onMounted, onUnmounted, watch } from 'vue';
import { Download, EditPen, View } from '@element-plus/icons-vue';
import { downloadExecutionFile, listJobs, listExecutionCenter, deleteJob, triggerJob, updateJob, viewExecutionFile } from '../../api/skillJob';
import { currentUserId } from '../../api/skill';
import { listMetrics } from '../../api/skillDependencyMetric';
import type { SkillJob, SkillJobExecution } from '../../types/skillJob';
import SkillJobFormDrawer from '../../components/SkillJobFormDrawer.vue';
import SkillJobExecutionDrawer from '../../components/SkillJobExecutionDrawer.vue';
import SkillJobNotificationDrawer from '../../components/SkillJobNotificationDrawer.vue';
import SkillJobReportEditorDrawer from '../../components/SkillJobReportEditorDrawer.vue';
import SkillFlowList from '../../components/SkillFlowList.vue';
import SkillFlowExecutionList from '../../components/SkillFlowExecutionList.vue';

const me = currentUserId();
function isOwner(job: SkillJob) {
  return job.createdBy === me;
}

// 创建人展示:有姓名时显示"姓名 (userId)",否则仅 userId
function creatorLabel(job: SkillJob): string {
  return job.createdByName ? `${job.createdByName} (${job.createdBy})` : (job.createdBy || '-');
}

const jobs = ref<SkillJob[]>([]);
const loading = ref(false);
const keyword = ref('');
const enabledFilter = ref<boolean | null>(null);
const createdBy = ref('');
// 长任务流程（多 Skill 协作流）暂未上线，先隐藏入口；上线时改回 true 即可
const showSkillFlow = false;
const activeTab = ref<'manage' | 'flows' | 'execution' | 'flow-execution'>('manage');
const centerExecutions = ref<SkillJobExecution[]>([]);
const executionLoading = ref(false);
const executionError = ref('');
const executionStatusFilter = ref('');
const executionCreatedBy = ref('');
const managePage = ref(1);
const managePageSize = ref(20);
const executionPage = ref(1);
const executionPageSize = ref(20);
let executionTimer: ReturnType<typeof setInterval> | undefined;

const pagedJobs = computed(() => {
  const start = (managePage.value - 1) * managePageSize.value;
  return jobs.value.slice(start, start + managePageSize.value);
});

const pagedCenterExecutions = computed(() => {
  const start = (executionPage.value - 1) * executionPageSize.value;
  return centerExecutions.value.slice(start, start + executionPageSize.value);
});

function changeManagePageSize(size: number) {
  managePageSize.value = size;
  managePage.value = 1;
}

function changeExecutionPageSize(size: number) {
  executionPageSize.value = size;
  executionPage.value = 1;
}

watch(() => jobs.value.length, (total) => {
  const lastPage = Math.max(1, Math.ceil(total / managePageSize.value));
  if (managePage.value > lastPage) managePage.value = lastPage;
});

watch(() => centerExecutions.value.length, (total) => {
  const lastPage = Math.max(1, Math.ceil(total / executionPageSize.value));
  if (executionPage.value > lastPage) executionPage.value = lastPage;
});

// 表单弹窗
const formOpen = ref(false);
const editId = ref<number | null>(null);

// 执行记录抽屉
const execOpen = ref(false);
const execJobId = ref<number | null>(null);
const execCanDownload = ref(false);

// 通知记录抽屉
const notifyOpen = ref(false);
const notifyExecId = ref<number | null>(null);
const notifyCanResend = ref(false);
const viewing = ref<Set<number>>(new Set());
const downloading = ref<Set<number>>(new Set());
const reportEditorOpen = ref(false);
const reportEditorExecutionId = ref<number | null>(null);

// 依赖指标描述缓存 (id -> description), 列表 hover 查看描述
const metricDescMap = ref<Record<number, string>>({});

// 触发中的 Job
const triggering = ref<Set<number>>(new Set());
const triggerMsg = ref('');

onMounted(() => {
  load();
  loadExecutionCenter();
  executionTimer = setInterval(() => {
    if (activeTab.value === 'execution' && centerExecutions.value.some(e => e.status === 'RUNNING' || e.status === 'PENDING')) {
      loadExecutionCenter(true);
    }
  }, 5000);
});

onUnmounted(() => {
  if (executionTimer) clearInterval(executionTimer);
});

async function loadExecutionCenter(silent = false) {
  if (!silent) executionLoading.value = true;
  executionError.value = '';
  try {
    centerExecutions.value = await listExecutionCenter(
      executionStatusFilter.value || undefined,
      executionCreatedBy.value.trim() || undefined,
    );
  } catch (e) {
    console.error('加载执行中心失败', e);
    executionError.value = e instanceof Error ? e.message : '加载执行中心失败';
    if (!silent) centerExecutions.value = [];
  } finally {
    executionLoading.value = false;
  }
}

function openExecutionCenter() {
  activeTab.value = 'execution';
  loadExecutionCenter();
}

function filterExecutionCenter() {
  executionPage.value = 1;
  loadExecutionCenter();
}

function executionStatus(status: string): string {
  return { RUNNING: '运行中', PENDING: '排队中', SUCCESS: '成功', FAILED: '失败', SKIPPED: '未执行' }[status] || status;
}

function triggerLabel(triggerType: string): string {
  return { MANUAL: '手动触发', EXTERNAL: '外部触发', METRIC: '指标触发' }[triggerType] || triggerType;
}

function executionStatusClass(status: string): string {
  return { RUNNING: 'st-running', PENDING: 'st-pending', SUCCESS: 'st-success', FAILED: 'st-failed', SKIPPED: 'st-off' }[status] || '';
}

function notificationStatus(status?: string | null): string {
  if (!status) return '暂无记录';
  return { PENDING: '等待发送', SENDING: '发送中', SUCCESS: '已提交通知平台', FAILED: '发送失败', SKIPPED: '通知未启用' }[status] || status;
}

function notificationStatusClass(status?: string | null): string {
  if (!status) return 'nt-none';
  return `nt-${status.toLowerCase()}`;
}

function executionCreatorLabel(exec: SkillJobExecution): string {
  return exec.createdByName ? `${exec.createdByName} (${exec.createdBy})` : (exec.createdBy || '-');
}

function openNotifications(exec: SkillJobExecution) {
  notifyExecId.value = exec.id;
  notifyCanResend.value = exec.createdBy === me && exec.status === 'SUCCESS' && !!exec.mdFileExists;
  notifyOpen.value = true;
}

async function viewCenterReport(exec: SkillJobExecution) {
  if (viewing.value.has(exec.id)) return;
  viewing.value.add(exec.id);
  try {
    await viewExecutionFile(exec.id);
  } catch (e) {
    alert(e instanceof Error ? e.message : '打开失败');
  } finally {
    viewing.value.delete(exec.id);
  }
}

async function downloadCenterReport(exec: SkillJobExecution) {
  if (downloading.value.has(exec.id)) return;
  downloading.value.add(exec.id);
  try {
    await downloadExecutionFile(exec.id);
  } catch (e) {
    alert(e instanceof Error ? e.message : '下载失败');
  } finally {
    downloading.value.delete(exec.id);
  }
}

function editCenterReport(exec: SkillJobExecution) {
  reportEditorExecutionId.value = exec.id;
  reportEditorOpen.value = true;
}

function executionReason(message?: string): string {
  if (!message) return '';
  if (message.startsWith('JobNotFound')) return '任务不存在';
  if (message.startsWith('JobDisabled')) return '任务已禁用';
  if (message.startsWith('JobNotConfigured')) return '任务配置不完整';
  if (message.startsWith('SkillPermissionDenied')) return 'Skill 权限失效';
  return message;
}

async function load() {
  loading.value = true;
  try {
    jobs.value = await listJobs(enabledFilter.value ?? undefined, keyword.value || undefined, createdBy.value || undefined);
    // 加载依赖指标描述 (admin 预置只读, 列表 hover 查看描述)
    try {
      const metrics = await listMetrics();
      const descMap: Record<number, string> = {};
      metrics.forEach(m => { if (m.description) descMap[m.id] = m.description; });
      metricDescMap.value = descMap;
    } catch {
      metricDescMap.value = {};
    }
  } catch (e) {
    console.error('加载失败', e);
  } finally {
    loading.value = false;
  }
}

function filterJobs() {
  managePage.value = 1;
  load();
}

function openCreate() {
  editId.value = null;
  formOpen.value = true;
}

function openEdit(id: number) {
  editId.value = id;
  formOpen.value = true;
}

function openExecutions(job: SkillJob) {
  execJobId.value = job.id;
  execCanDownload.value = isOwner(job);
  execOpen.value = true;
}

async function remove(id: number) {
  if (!confirm('确认删除此任务？')) return;
  try {
    await deleteJob(id);
    jobs.value = jobs.value.filter(j => j.id !== id);
  } catch (e) {
    alert(e instanceof Error ? e.message : '删除失败');
  }
}

async function trigger(id: number) {
  triggering.value.add(id);
  triggerMsg.value = '';
  try {
    await triggerJob(id);
    triggerMsg.value = '已提交到执行队列';
    await loadExecutionCenter(true);
    setTimeout(() => { triggerMsg.value = ''; }, 2000);
  } catch (e) {
    alert(e instanceof Error ? e.message : '触发失败');
  } finally {
    triggering.value.delete(id);
  }
}

async function toggleEnabled(job: SkillJob) {
  try {
    await updateJob(job.id, { enabled: !job.enabled });
    job.enabled = !job.enabled;
  } catch (e) {
    alert(e instanceof Error ? e.message : '操作失败');
  }
}

function fmtTime(t: string) {
  if (!t) return '-';
  return t.replace('T', ' ').substring(0, 19);
}

/** 依赖指标单元格 hover title: code + 描述 (若后端已返回/已缓存) */
function metricTitle(job: SkillJob): string {
  const parts: string[] = [];
  if (job.metricCode) parts.push(`code: ${job.metricCode}`);
  const desc = job.metricId ? metricDescMap.value[job.metricId] : '';
  if (desc) parts.push(desc);
  return parts.join('\n');
}
</script>

<template>
  <div class="job-page">
    <div class="page-header">
      <h2>定时任务</h2>
      <div class="header-actions">
        <template v-if="activeTab === 'manage'">
          <input v-model="keyword" placeholder="搜索任务名称…" class="search-input" @keyup.enter="filterJobs" />
          <input v-model="createdBy" placeholder="创建人 userId" class="search-input creator-input" @keyup.enter="filterJobs" />
          <select v-model="enabledFilter" @change="filterJobs" class="filter-select">
            <option :value="null">全部状态</option>
            <option :value="true">已启用</option>
            <option :value="false">已禁用</option>
          </select>
          <button class="btn primary" @click="openCreate">+ 创建任务</button>
        </template>
        <template v-else-if="activeTab === 'execution'">
          <input v-model="executionCreatedBy" placeholder="创建人 userId" class="search-input creator-input" @keyup.enter="filterExecutionCenter" />
          <select v-model="executionStatusFilter" class="filter-select" @change="filterExecutionCenter">
            <option value="">全部状态</option>
            <option value="RUNNING">运行中</option>
            <option value="PENDING">排队中</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
            <option value="SKIPPED">未执行</option>
          </select>
        </template>
        <button v-if="activeTab === 'manage' || activeTab === 'execution'" class="btn ghost" @click="activeTab === 'manage' ? load() : loadExecutionCenter()">刷新</button>
      </div>
    </div>

    <div class="job-tabs" role="tablist">
      <button class="job-tab" :class="{ active: activeTab === 'manage' }" @click="activeTab = 'manage'">独立任务</button>
      <button v-if="showSkillFlow" class="job-tab" :class="{ active: activeTab === 'flows' }" @click="activeTab = 'flows'">长任务流程</button>
      <button class="job-tab" :class="{ active: activeTab === 'execution' }" @click="openExecutionCenter">执行记录</button>
      <button v-if="showSkillFlow" class="job-tab" :class="{ active: activeTab === 'flow-execution' }" @click="activeTab = 'flow-execution'">长任务执行记录</button>
    </div>

    <SkillFlowList v-if="activeTab === 'flows'" />
    <SkillFlowExecutionList v-else-if="activeTab === 'flow-execution'" />

    <template v-else-if="activeTab === 'execution'">
      <div v-if="executionError" class="center-error">{{ executionError }}</div>
      <div v-if="executionLoading" class="loading">加载中…</div>
      <div v-else-if="centerExecutions.length === 0" class="empty"><p>暂无符合条件的执行记录</p></div>
      <div v-else class="job-table-wrap">
        <table class="job-table execution-table">
          <thead><tr><th>任务名称</th><th>创建人</th><th>Skill</th><th>触发方式</th><th>执行状态</th><th>通知状态</th><th>提交时间</th><th>执行 ID</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="exec in pagedCenterExecutions" :key="exec.id">
              <td><span class="col-name">{{ exec.jobName || `任务 #${exec.jobId}` }}</span></td>
              <td class="col-owner execution-owner" :title="executionCreatorLabel(exec)">{{ executionCreatorLabel(exec) }}</td>
              <td>{{ exec.skillName || '-' }}</td>
              <td>{{ triggerLabel(exec.triggerType) }}</td>
              <td>
                <span class="status-badge" :class="executionStatusClass(exec.status)">{{ executionStatus(exec.status) }}</span>
                <span v-if="exec.status === 'PENDING' && exec.queueAhead != null" class="queue-hint">{{ exec.queueAhead === 0 ? '即将执行' : `前面 ${exec.queueAhead} 个` }}</span>
                <span v-if="(exec.status === 'SKIPPED' || exec.status === 'FAILED') && exec.errorMsg" class="execution-reason" :class="{ failed: exec.status === 'FAILED' }" :title="exec.errorMsg">{{ executionReason(exec.errorMsg) }}</span>
              </td>
              <td><span class="notify-badge" :class="notificationStatusClass(exec.latestNotificationStatus)">{{ notificationStatus(exec.latestNotificationStatus) }}</span></td>
              <td class="col-time">{{ fmtTime(exec.createdAt) }}</td>
              <td>#{{ exec.id }}</td>
              <td class="col-actions">
                <template v-if="exec.mdFileExists && exec.createdBy === me">
                  <button class="btn-action with-icon" :disabled="viewing.has(exec.id)" title="预览报告" @click="viewCenterReport(exec)">
                    <el-icon><View /></el-icon><span>{{ viewing.has(exec.id) ? '打开中…' : '预览' }}</span>
                  </button>
                  <button class="btn-action with-icon" title="编辑 HTML" @click="editCenterReport(exec)">
                    <el-icon><EditPen /></el-icon><span>编辑</span>
                  </button>
                  <button class="btn-action with-icon" :disabled="downloading.has(exec.id)" title="下载 HTML" @click="downloadCenterReport(exec)">
                    <el-icon><Download /></el-icon><span>{{ downloading.has(exec.id) ? '下载中…' : '下载' }}</span>
                  </button>
                </template>
                <button class="btn-action" :disabled="exec.createdBy !== me" :title="exec.createdBy === me ? '' : '仅任务创建人可查看通知'" @click="openNotifications(exec)">通知记录</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!executionLoading && centerExecutions.length > 0" class="pagination-bar">
        <el-pagination
          v-model:current-page="executionPage"
          :page-size="executionPageSize"
          :page-sizes="[10, 20, 50]"
          :total="centerExecutions.length"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="changeExecutionPageSize"
        />
      </div>
    </template>

    <template v-else>
    <div v-if="triggerMsg" class="toast">{{ triggerMsg }}</div>

    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="jobs.length === 0" class="empty">
      <p>暂无任务，点击「创建任务」新增</p>
    </div>
    <div v-else class="job-table-wrap">
    <table class="job-table">
      <thead>
        <tr>
          <th>任务名称</th>
          <th>关联 Skill</th>
          <th>依赖指标</th>
          <th>提问内容</th>
          <th>状态</th>
          <th>创建人</th>
          <th>创建时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="job in pagedJobs" :key="job.id">
          <td>
            <span class="col-name selectable" :title="job.name">{{ job.name }}</span>
          </td>
          <td>{{ job.skillId ? (job.skillName || `#${job.skillId}`) : '未配置' }}</td>
          <td class="col-metric">
            <span v-if="job.metricName || job.metricCode" :title="metricTitle(job)">
              {{ job.metricName || job.metricCode }}
            </span>
            <span v-else class="muted-cell">未配置</span>
          </td>
          <td class="col-template selectable" :title="job.questionTemplate">{{ job.questionTemplate || '未配置' }}</td>
          <td class="col-status">
            <span class="status-badge" :class="job.enabled ? 'st-on' : 'st-off'">
              {{ job.enabled ? '启用' : '禁用' }}
            </span>
          </td>
          <td class="col-owner">
            <span :class="isOwner(job) ? 'owner-me' : ''" :title="creatorLabel(job)">{{ creatorLabel(job) }}</span>
          </td>
          <td class="col-time">{{ fmtTime(job.createdAt) }}</td>
          <td class="col-actions">
            <button class="btn-action trigger" v-if="isOwner(job)" :disabled="triggering.has(job.id) || !job.enabled" @click="trigger(job.id)">
              {{ triggering.has(job.id) ? '排队中…' : '触发' }}
            </button>
            <button class="btn-action" @click="openExecutions(job)">记录</button>
            <template v-if="isOwner(job)">
              <button class="btn-action" @click="openEdit(job.id)">编辑</button>
              <button class="btn-action toggle" @click="toggleEnabled(job)">{{ job.enabled ? '禁用' : '启用' }}</button>
              <button class="btn-action danger" @click="remove(job.id)">删除</button>
            </template>
            <span v-else class="readonly-tag" title="仅创建人可修改/删除/触发">只读</span>
          </td>
        </tr>
      </tbody>
    </table>
    </div>
    <div v-if="!loading && jobs.length > 0" class="pagination-bar">
      <el-pagination
        v-model:current-page="managePage"
        :page-size="managePageSize"
        :page-sizes="[10, 20, 50]"
        :total="jobs.length"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="changeManagePageSize"
      />
    </div>

    <SkillJobFormDrawer v-model:open="formOpen" :edit-id="editId" @saved="load" />
    <SkillJobExecutionDrawer v-model:open="execOpen" :job-id="execJobId" :can-download="execCanDownload" />
    </template>
    <SkillJobNotificationDrawer v-model:open="notifyOpen" :exec-id="notifyExecId" :can-resend="notifyCanResend" @changed="loadExecutionCenter(true)" />
    <SkillJobReportEditorDrawer
      v-model:open="reportEditorOpen"
      :execution-id="reportEditorExecutionId"
      @saved="loadExecutionCenter(true)"
    />
  </div>
</template>

<style scoped>
.job-page { padding: 4px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.search-input { padding: 7px 12px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; width: 200px; }
.creator-input { width: 160px; }
.filter-select { padding: 7px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; background: #fff; }
.btn { padding: 7px 16px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:hover { background: #2563eb; }
.btn.ghost { background: #fff; color: #475569; }
.btn.ghost:hover { background: #f1f5f9; }
.job-tabs { display: flex; gap: 24px; border-bottom: 1px solid #e2e8f0; margin-bottom: 16px; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 2px; }
.job-tab { border: 0; border-bottom: 2px solid transparent; background: transparent; color: #64748b; padding: 8px 2px 10px; font-size: 14px; cursor: pointer; }
.job-tab.active { color: #2563eb; border-bottom-color: #2563eb; font-weight: 600; }
.tab-count { display: inline-flex; min-width: 18px; height: 18px; align-items: center; justify-content: center; margin-left: 6px; padding: 0 4px; border-radius: 9px; background: #2563eb; color: #fff; font-size: 11px; }
.st-running { background: #dcfce7; color: #15803d; }
.st-pending { background: #fef3c7; color: #a16207; }
.st-success { background: #dcfce7; color: #166534; }
.st-failed { background: #fee2e2; color: #991b1b; }
.execution-reason { display: block; max-width: 180px; margin-top: 4px; color: #64748b; font-size: 11px; white-space: normal; }
.execution-reason.failed { color: #b91c1c; }
.btn-action.with-icon { display: inline-flex; align-items: center; gap: 3px; }

.toast { position: fixed; top: 20px; left: 50%; transform: translateX(-50%); background: #16a34a; color: #fff; padding: 8px 20px; border-radius: 8px; font-size: 14px; z-index: 2000; box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
.center-error { margin-bottom: 12px; padding: 9px 12px; border: 1px solid #fecaca; border-radius: 5px; background: #fef2f2; color: #b91c1c; font-size: 13px; }

.loading, .empty { color: #94a3b8; font-size: 14px; padding: 40px 0; text-align: center; background: #fff; border-radius: 8px; }

.job-table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.job-table { width: 100%; min-width: 1080px; border-collapse: collapse; background: #fff; }
.execution-table { min-width: 1240px; }
.job-table th { background: #f8fafc; padding: 10px 12px; text-align: left; font-size: 13px; font-weight: 600; color: #475569; border-bottom: 1px solid #e2e8f0; white-space: nowrap; }
.job-table td { padding: 10px 12px; font-size: 13px; color: #1e293b; border-bottom: 1px solid #f1f5f9; }
.job-table tr:hover td { background: #f8fafc; }
.col-name { font-weight: 600; }
.col-template { font-size: 12px; color: #475569; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-metric { font-size: 12px; color: #475569; white-space: nowrap; }
.muted-cell { color: #cbd5e1; }
.selectable { user-select: text; cursor: text; }
.col-time { color: #94a3b8; font-size: 12px; white-space: nowrap; }
.col-owner { font-size: 12px; color: #64748b; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.execution-owner { max-width: 180px; }
.owner-me { color: #1d4ed8; font-weight: 600; }
.readonly-tag { font-size: 11px; color: #94a3b8; padding: 2px 6px; border: 1px dashed #cbd5e1; border-radius: 4px; }
.col-status { white-space: nowrap; }
.col-actions { display: flex; gap: 4px; white-space: nowrap; align-items: center; }

.status-badge { font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 4px; }
.queue-hint { display: block; margin-top: 4px; color: #92400e; font-size: 11px; white-space: nowrap; }
.notify-badge { display: inline-block; padding: 2px 7px; border-radius: 4px; font-size: 11px; font-weight: 700; white-space: nowrap; }
.nt-success { background: #dcfce7; color: #166534; }
.nt-failed { background: #fee2e2; color: #991b1b; }
.nt-pending, .nt-sending { background: #fef3c7; color: #92400e; }
.nt-skipped, .nt-none { background: #f1f5f9; color: #64748b; }
.st-on { background: #d1fae5; color: #065f46; }
.st-off { background: #f1f5f9; color: #64748b; }

.btn-action { padding: 4px 10px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 12px; cursor: pointer; background: #fff; color: #475569; }
.btn-action:hover { background: #f1f5f9; }
.btn-action:disabled { opacity: .45; cursor: not-allowed; }
.btn-action.trigger { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn-action.trigger:hover { background: #2563eb; }
.btn-action.trigger:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-action.toggle { color: #92400e; border-color: #fcd34d; }
.btn-action.toggle:hover { background: #fffbeb; }
.btn-action.danger { color: #dc2626; border-color: #fecaca; }
.btn-action.danger:hover { background: #fef2f2; }

@media (max-width: 900px) {
  .header-actions { width: 100%; flex-wrap: wrap; }
  .search-input, .creator-input { width: min(100%, 220px); }
}

</style>
