<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { deleteSkillFlow, listSkillFlows, setSkillFlowEnabled } from '../api/skillFlow';
import type { SkillFlow } from '../types/skillFlow';
import SkillFlowEditorDrawer from './SkillFlowEditorDrawer.vue';

const flows = ref<SkillFlow[]>([]);
const keyword = ref('');
const loading = ref(false);
const error = ref('');
const editorOpen = ref(false);
const editId = ref<number | null>(null);
const page = ref(1);
const pageSize = ref(20);
const pagedFlows = computed(() => flows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));

function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-'; }
function templateSummary(value: string) { return value.length > 46 ? `${value.slice(0, 46)}…` : value || '-'; }
function triggerSummary(flow: SkillFlow) { return flow.triggers.map(item => item.keyword).filter(Boolean).join('、') || '-'; }
function metricCount(flow: SkillFlow) { return new Set(flow.nodes.flatMap(node => node.metricIds || [])).size; }

async function load() {
  loading.value = true;
  error.value = '';
  try { flows.value = await listSkillFlows(keyword.value.trim() || undefined); page.value = 1; }
  catch (e) { error.value = e instanceof Error ? e.message : '加载长任务流程失败'; flows.value = []; }
  finally { loading.value = false; }
}

function create() { editId.value = null; editorOpen.value = true; }
function edit(id: number) { editId.value = id; editorOpen.value = true; }
async function toggle(flow: SkillFlow) {
  try { await setSkillFlowEnabled(flow.id, !flow.enabled); flow.enabled = !flow.enabled; }
  catch (e) { alert(e instanceof Error ? e.message : '更新流程状态失败'); }
}
async function remove(flow: SkillFlow) {
  if (!confirm(`确认删除流程「${flow.name}」？历史执行记录将保留。`)) return;
  try { await deleteSkillFlow(flow.id); await load(); }
  catch (e) { alert(e instanceof Error ? e.message : '删除流程失败'); }
}

onMounted(load);
</script>

<template>
  <section class="flow-list">
    <header class="page-header"><div><h2>长任务流程</h2><p>配置聊天触发的多 Skill 协作流程。</p></div><div class="actions"><input v-model="keyword" class="search-input" placeholder="搜索流程名称或关键词" @keyup.enter="load" /><button class="btn" @click="load">刷新</button><button class="btn primary" @click="create">创建流程</button></div></header>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!flows.length" class="empty">暂无长任务流程</div>
    <div v-else class="table-wrap"><table><thead><tr><th>流程名称</th><th>触发关键词</th><th>Skill 数</th><th>依赖指标</th><th>最终汇总模板</th><th>最大并发</th><th>通知</th><th>状态</th><th>最近更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="flow in pagedFlows" :key="flow.id"><td><strong>{{ flow.name }}</strong><small v-if="flow.description">{{ flow.description }}</small></td><td :title="triggerSummary(flow)">{{ triggerSummary(flow) }}</td><td>{{ flow.nodes.length }}</td><td>{{ metricCount(flow) }}</td><td class="template" :title="flow.summaryQuestionTemplate">{{ templateSummary(flow.summaryQuestionTemplate) }}</td><td>{{ flow.maxParallelism }}</td><td><span class="tag" :class="flow.notifyEnabled ? 'good' : 'muted'">{{ flow.notifyEnabled ? '开启' : '关闭' }}</span></td><td><span class="tag" :class="flow.enabled ? 'good' : 'muted'">{{ flow.enabled ? '启用' : '禁用' }}</span></td><td class="time">{{ formatTime(flow.updatedAt) }}</td><td class="row-actions"><button class="link-button" @click="edit(flow.id)">编辑</button><button class="link-button" @click="toggle(flow)">{{ flow.enabled ? '禁用' : '启用' }}</button><button class="link-button danger" @click="remove(flow)">删除</button></td></tr></tbody></table></div>
    <div v-if="flows.length > pageSize" class="pagination"><el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50]" :total="flows.length" layout="total, sizes, prev, pager, next" @size-change="(size: number) => { pageSize = size; page = 1; }" /></div>
    <SkillFlowEditorDrawer v-model:open="editorOpen" :edit-id="editId" :known-flows="flows" @saved="load" />
  </section>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }.page-header h2 { margin: 0; color: #0f172a; font-size: 20px; }.page-header p { margin: 4px 0 0; color: #64748b; font-size: 13px; }.actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }.search-input { width: 220px; padding: 7px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font: inherit; font-size: 13px; }.btn { padding: 7px 14px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 13px; }.btn.primary { color: #fff; border-color: #3b82f6; background: #3b82f6; }.table-wrap { overflow-x: auto; border-radius: 8px; box-shadow: 0 1px 3px rgb(0 0 0 / 6%); }table { width: 100%; min-width: 1180px; border-collapse: collapse; background: #fff; }th, td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; text-align: left; vertical-align: middle; font-size: 13px; color: #334155; }th { background: #f8fafc; color: #475569; font-size: 12px; white-space: nowrap; }tr:hover td { background: #f8fafc; }td strong { display: block; color: #0f172a; }td small { display: block; max-width: 180px; overflow: hidden; color: #94a3b8; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.template { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.time { color: #64748b; white-space: nowrap; }.tag { display: inline-block; border-radius: 4px; padding: 2px 7px; font-size: 11px; font-weight: 700; }.tag.good { background: #dcfce7; color: #166534; }.tag.muted { background: #f1f5f9; color: #64748b; }.row-actions { display: flex; gap: 6px; white-space: nowrap; }.link-button { border: 0; background: transparent; color: #2563eb; cursor: pointer; padding: 3px; font-size: 12px; }.link-button.danger { color: #dc2626; }.empty { padding: 48px 0; color: #94a3b8; text-align: center; font-size: 14px; }.error { margin-bottom: 12px; padding: 9px 12px; border-left: 3px solid #dc2626; background: #fef2f2; color: #b91c1c; font-size: 13px; }.pagination { display: flex; justify-content: flex-end; padding-top: 14px; }@media (max-width: 700px) { .search-input { width: min(100%, 220px); }.actions { width: 100%; } }
</style>
