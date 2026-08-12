<script setup lang="ts">
/**
 * SkillJob 管理页面
 *
 * 功能：列表查看、搜索筛选、创建/编辑/删除、触发执行、查看执行记录
 */
import { ref, onMounted } from 'vue';
import { listJobs, deleteJob, triggerJob, updateJob } from '../../api/skillJob';
import { listSkills, currentUserId } from '../../api/skill';
import type { SkillJob } from '../../types/skillJob';
import type { SkillListItem } from '../../types/skill';
import SkillJobFormDrawer from '../../components/SkillJobFormDrawer.vue';
import SkillJobExecutionDrawer from '../../components/SkillJobExecutionDrawer.vue';

const me = currentUserId();
function isOwner(job: SkillJob) {
  return job.createdBy === me;
}

const jobs = ref<SkillJob[]>([]);
const loading = ref(false);
const keyword = ref('');
const enabledFilter = ref<boolean | null>(null);
const createdBy = ref('');

// 表单弹窗
const formOpen = ref(false);
const editId = ref<number | null>(null);

// 执行记录抽屉
const execOpen = ref(false);
const execJobId = ref<number | null>(null);
const execCanDownload = ref(false);

// Skill 名称缓存
const skillNames = ref<Record<number, string>>({});

// 触发中的 Job
const triggering = ref<Set<number>>(new Set());
const triggerMsg = ref('');

onMounted(() => load());

async function load() {
  loading.value = true;
  try {
    jobs.value = await listJobs(enabledFilter.value ?? undefined, keyword.value || undefined, createdBy.value || undefined);
    // 加载 Skill 名称
    if (jobs.value.length > 0) {
      const skills = await listSkills({ limit: 200 });
      const map: Record<number, string> = {};
      skills.forEach(s => { map[s.id] = s.name; });
      skillNames.value = map;
    }
  } catch (e) {
    console.error('加载失败', e);
  } finally {
    loading.value = false;
  }
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
</script>

<template>
  <div class="job-page">
    <div class="page-header">
      <h2>定时任务管理</h2>
      <div class="header-actions">
        <input v-model="keyword" placeholder="搜索任务名称…" class="search-input" @keyup.enter="load" />
        <input v-model="createdBy" placeholder="按创建人筛选…" class="search-input" @keyup.enter="load" />
        <select v-model="enabledFilter" @change="load" class="filter-select">
          <option :value="null">全部</option>
          <option :value="true">已启用</option>
          <option :value="false">已禁用</option>
        </select>
        <button class="btn ghost" @click="load">刷新</button>
        <button class="btn primary" @click="openCreate">+ 创建任务</button>
      </div>
    </div>

    <div v-if="triggerMsg" class="toast">{{ triggerMsg }}</div>

    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="jobs.length === 0" class="empty">
      <p>暂无任务，点击「创建任务」新增</p>
    </div>
    <table v-else class="job-table">
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
        <tr v-for="job in jobs" :key="job.id">
          <td>
            <span class="col-name selectable" :title="job.name">{{ job.name }}</span>
          </td>
          <td>{{ job.skillId ? (skillNames[job.skillId] || `#${job.skillId}`) : '未配置' }}</td>
          <td class="col-metric">
            <span v-if="job.metricName || job.metricCode" :title="`code: ${job.metricCode}`">
              {{ job.metricName || job.metricCode }}
            </span>
            <span v-else class="muted-cell">未配置</span>
          </td>
          <td class="col-template selectable" :title="job.questionTemplate">{{ job.questionTemplate || '未配置' }}</td>
          <td>
            <span class="status-badge" :class="job.enabled ? 'st-on' : 'st-off'">
              {{ job.enabled ? '启用' : '禁用' }}
            </span>
          </td>
          <td class="col-owner">
            <span :class="isOwner(job) ? 'owner-me' : ''" :title="job.createdBy">{{ job.createdBy || '-' }}</span>
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

    <SkillJobFormDrawer v-model:open="formOpen" :edit-id="editId" @saved="load" />
    <SkillJobExecutionDrawer v-model:open="execOpen" :job-id="execJobId" :can-download="execCanDownload" />
  </div>
</template>

<style scoped>
.job-page { padding: 4px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.search-input { padding: 7px 12px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; width: 200px; }
.filter-select { padding: 7px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; background: #fff; }
.btn { padding: 7px 16px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:hover { background: #2563eb; }
.btn.ghost { background: #fff; color: #475569; }
.btn.ghost:hover { background: #f1f5f9; }

.toast { position: fixed; top: 20px; left: 50%; transform: translateX(-50%); background: #16a34a; color: #fff; padding: 8px 20px; border-radius: 8px; font-size: 14px; z-index: 2000; box-shadow: 0 4px 12px rgba(0,0,0,0.15); }

.loading, .empty { color: #94a3b8; font-size: 14px; padding: 40px 0; text-align: center; background: #fff; border-radius: 8px; }

.job-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
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
.owner-me { color: #1d4ed8; font-weight: 600; }
.readonly-tag { font-size: 11px; color: #94a3b8; padding: 2px 6px; border: 1px dashed #cbd5e1; border-radius: 4px; }
.col-actions { display: flex; gap: 4px; white-space: nowrap; align-items: center; }

.status-badge { font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 4px; }
.st-on { background: #d1fae5; color: #065f46; }
.st-off { background: #f1f5f9; color: #64748b; }

.btn-action { padding: 4px 10px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 12px; cursor: pointer; background: #fff; color: #475569; }
.btn-action:hover { background: #f1f5f9; }
.btn-action.trigger { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn-action.trigger:hover { background: #2563eb; }
.btn-action.trigger:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-action.toggle { color: #92400e; border-color: #fcd34d; }
.btn-action.toggle:hover { background: #fffbeb; }
.btn-action.danger { color: #dc2626; border-color: #fecaca; }
.btn-action.danger:hover { background: #fef2f2; }
</style>
