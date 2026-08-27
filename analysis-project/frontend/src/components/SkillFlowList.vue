<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { deleteSkillFlow, getSkillFlowMetricPrecheck, listSkillFlows, runSkillFlow, setSkillFlowEnabled } from '../api/skillFlow';
import { currentUserId } from '../api/skill';
import type { SkillFlow } from '../types/skillFlow';

const emit = defineEmits<{ 'view-records': [flowName: string] }>();
const props = withDefaults(defineProps<{ scope?: 'mine' | 'all'; createdBy?: string }>(), { scope: 'mine', createdBy: '' });
const me = currentUserId();
const router = useRouter();
const flows = ref<SkillFlow[]>([]);
const currentKeyword = ref('');
const currentCreatedBy = ref('');
const currentEnabled = ref<boolean | undefined>();
const loading = ref(false);
const error = ref('');
const page = ref(1);
const pageSize = ref(20);
const pagedFlows = computed(() => flows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));

function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function metricCount(flow: SkillFlow) { return new Set(flow.nodes.flatMap(node => node.metricIds || [])).size; }
function enabledStatusClass(flow: SkillFlow) { return flow.enabled ? 'st-on' : 'st-off'; }
function isOwner(flow: SkillFlow) { return flow.createdBy === me; }
function changePageSize(size: number) { pageSize.value = size; page.value = 1; }

async function load(searchKeyword?: string, createdBy?: string, enabled?: boolean, scope: 'mine' | 'all' = props.scope) {
  if (typeof searchKeyword === 'string') currentKeyword.value = searchKeyword;
  if (typeof createdBy === 'string') currentCreatedBy.value = createdBy;
  currentEnabled.value = enabled;
  loading.value = true;
  error.value = '';
  try { flows.value = await listSkillFlows(currentEnabled.value, currentKeyword.value.trim() || undefined, currentCreatedBy.value.trim() || undefined, scope); page.value = 1; }
  catch (e) { error.value = e instanceof Error ? e.message : '加载长任务流程失败'; flows.value = []; }
  finally { loading.value = false; }
}

function create() { router.push('/skills/jobs/flows/new'); }
function edit(id: number) { router.push(`/skills/jobs/flows/${id}/edit`); }
function viewRecords(flow: SkillFlow) { emit('view-records', flow.name); }
async function toggle(flow: SkillFlow) {
  try { await setSkillFlowEnabled(flow.id, !flow.enabled); flow.enabled = !flow.enabled; }
  catch (e) { alert(e instanceof Error ? e.message : '更新流程状态失败'); }
}
async function remove(flow: SkillFlow) {
  if (!confirm(`确认删除流程「${flow.name}」？历史执行记录将保留。`)) return;
  try { await deleteSkillFlow(flow.id); await load(); }
  catch (e) { alert(e instanceof Error ? e.message : '删除流程失败'); }
}

function statusText(value: string) { return ({ WAITING_METRICS: '等待指标', QUEUED: '排队中', RUNNING: '执行中', SUMMARIZING: '汇总中' } as Record<string, string>)[value] || value; }
function metricLabel(item: { metricId: number; metricCode?: string; metricName?: string }) { return item.metricName || item.metricCode || `指标 #${item.metricId}`; }

/** 手动执行:先查指标就绪,有未就绪的弹确认;确认后触发(未就绪时任务挂起等数据)。 */
async function run(flow: SkillFlow) {
  let missing: string[] = [];
  try {
    const metrics = await getSkillFlowMetricPrecheck(flow.id);
    missing = metrics.filter(item => item.status !== 'READY').map(metricLabel);
  } catch {
    // 预检失败不阻塞,仍尝试触发,由后端兜底判定
  }
  try {
    const result = await runSkillFlow(flow.id);
    if (result.created) alert('已触发任务，可在“长任务执行记录”中查看进度。');
  } catch (e) { alert(e instanceof Error ? e.message : '触发执行失败'); }
}

defineExpose({ load, create });
onMounted(() => load());
watch(() => [props.scope, props.createdBy] as const, () => load('', props.createdBy, undefined, props.scope));
</script>

<template>
  <section class="flow-list">
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!flows.length" class="empty">暂无长任务流程</div>
    <div v-else class="job-table-wrap">
      <table class="job-table flow-table">
        <thead>
          <tr>
            <th>流程名称</th>
            <th>Skill 数量</th>
            <th>依赖指标</th>
            <th>状态</th>
            <th>创建人</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="flow in pagedFlows" :key="flow.id">
            <td>
              <span class="col-name selectable" :title="flow.name">{{ flow.name }}</span>
              <span v-if="flow.description" class="col-description" :title="flow.description">{{ flow.description }}</span>
            </td>
            <td>{{ flow.nodes.length }}</td>
            <td>{{ metricCount(flow) }}</td>
            <td>
              <span class="status-badge" :class="enabledStatusClass(flow)">{{ flow.enabled ? '启用' : '禁用' }}</span>
            </td>
            <td class="col-owner">{{ flow.createdBy || '-' }}</td>
            <td class="col-time">{{ formatTime(flow.createdAt) }}</td>
            <td class="col-actions">
              <button v-if="isOwner(flow)" class="btn-action trigger" :disabled="!flow.enabled" @click="run(flow)">执行</button>
              <button class="btn-action" @click="viewRecords(flow)">记录</button>
              <template v-if="isOwner(flow)">
                <button class="btn-action" @click="edit(flow.id)">编辑</button>
                <button class="btn-action toggle" @click="toggle(flow)">{{ flow.enabled ? '禁用' : '启用' }}</button>
                <button class="btn-action danger" @click="remove(flow)">删除</button>
              </template>
              <span v-else class="readonly-tag" title="仅创建人可修改/删除/执行">只读</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!loading && flows.length > 0" class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="flows.length"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="changePageSize"
      />
    </div>
  </section>
</template>

<style scoped>
.job-table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.job-table { width: 100%; min-width: 1280px; border-collapse: collapse; background: #fff; }
.job-table th { background: #f8fafc; padding: 10px 12px; text-align: left; font-size: 13px; font-weight: 600; color: #475569; border-bottom: 1px solid #e2e8f0; white-space: nowrap; }
.job-table td { padding: 10px 12px; font-size: 13px; color: #1e293b; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.job-table tr:hover td { background: #f8fafc; }
.flow-table .col-actions { min-width: 178px; }
.col-name { display: block; font-weight: 600; }
.col-description { display: block; max-width: 180px; margin-top: 2px; overflow: hidden; color: #94a3b8; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.col-template { max-width: 240px; overflow: hidden; color: #475569; text-overflow: ellipsis; white-space: nowrap; }
.col-time { color: #94a3b8; font-size: 12px; white-space: nowrap; }
.col-owner { color: #64748b; font-size: 12px; white-space: nowrap; }
.selectable { user-select: text; cursor: text; }
.col-actions { display: flex; gap: 4px; white-space: nowrap; align-items: center; }
.status-badge { font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 4px; }
.st-on { background: #d1fae5; color: #065f46; }
.st-off { background: #f1f5f9; color: #64748b; }
.btn-action { padding: 4px 10px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 12px; cursor: pointer; background: #fff; color: #475569; }
.btn-action:hover { background: #f1f5f9; }
.btn-action.trigger { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn-action.trigger:hover { background: #2563eb; }
.btn-action.toggle { color: #92400e; border-color: #fcd34d; }
.btn-action.toggle:hover { background: #fffbeb; }
.btn-action.danger { color: #dc2626; border-color: #fecaca; }
.btn-action.danger:hover { background: #fef2f2; }
.readonly-tag { color: #94a3b8; font-size: 12px; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 2px; }
.empty { padding: 48px 0; color: #94a3b8; text-align: center; font-size: 14px; background: #fff; border-radius: 8px; }
.error { margin-bottom: 12px; padding: 9px 12px; border: 1px solid #fecaca; border-radius: 5px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
</style>
