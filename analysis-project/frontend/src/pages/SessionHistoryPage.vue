<template>
  <div class="history-shell">
    <aside class="history-nav" aria-label="历史记录导航">
      <div class="history-nav-title">历史记录</div>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'sessions' }" @click="activeTab = 'sessions'">会话历史</button>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'nodeLogs' }" @click="activeTab = 'nodeLogs'">节点执行日志</button>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'cases' }" @click="activeTab = 'cases'">案例信息</button>
    </aside>

    <main class="history-content" :style="rootStyle">
      <!-- 会话历史 Header -->
      <div v-show="activeTab === 'sessions'" :style="headerStyle">
        <div>
          <div :style="titleStyle">会话历史</div>
          <div :style="subtitleStyle">Session History</div>
        </div>
        <div :style="headerRight">
          <el-radio-group v-model="scope" size="small" @change="currentPage = 1; syncQuery(); refreshConversations()">
            <el-radio-button label="mine">我的</el-radio-button>
            <el-radio-button label="all">全部</el-radio-button>
          </el-radio-group>
          <el-input v-model="selectedUserId" placeholder="提问人" style="width: 140px" clearable @keyup.enter="handleUserFilter" @clear="handleUserFilter" />
          <el-button size="small" @click="handleUserFilter">查询</el-button>
          <el-input v-model="searchKeyword" placeholder="搜索会话ID / Agent名称" style="width: 260px" clearable @keyup.enter="handleSearch" @clear="handleClearSearch" />
          <span :style="updateStyle">上次更新: {{ lastUpdated }}</span>
          <span v-if="error" :style="errorStyle">⚠ {{ error }}</span>
          <el-button size="small" :loading="loading" @click="refreshConversations">刷新</el-button>
        </div>
      </div>

      <!-- 会话历史 Table -->
      <div v-show="activeTab === 'sessions'" :style="tableContainerStyle">
        <el-table :data="conversations" v-loading="loading" style="width: 100%" @row-click="handleRowClick" :row-style="{ cursor: 'pointer' }" stripe border>
          <el-table-column label="会话ID" min-width="180">
            <template #default="{ row }"><span :style="idStyle" :title="row.conversationId">{{ row.conversationId?.slice(0, 12) }}…</span></template>
          </el-table-column>
          <el-table-column label="提问人" min-width="120">
            <template #default="{ row }"><span>{{ row.agentName || row.userId || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }"><el-tag :type="statusTagType(row.status)" size="small" effect="dark">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="170">
            <template #default="{ row }"><span :style="timeStyle">{{ formatTime(row.startTime) }}</span></template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }"><span :style="durationStyle(row.durationMs)">{{ formatDuration(row.durationMs) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="center" fixed="right">
            <template #default="{ row }">
              <div class="action-buttons">
                <el-button type="primary" link size="small" @click.stop="goDetail(row.conversationId)">查看详情</el-button>
                <el-button v-if="caseConversationIds.has(row.conversationId) || row.status === 'SUCCESS'" :type="caseConversationIds.has(row.conversationId) ? 'danger' : 'success'" link size="small" :disabled="caseLoadingId !== null" :loading="caseLoadingId === row.conversationId" @click.stop="caseConversationIds.has(row.conversationId) ? cancelCase(row) : setAsCase(row)">{{ caseConversationIds.has(row.conversationId) ? '取消案例' : '设为案例' }}</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-show="activeTab === 'sessions'" :style="paginationStyle">
        <el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" @current-change="handlePageChange" />
      </div>

      <LongTaskNodeLogPanel v-if="activeTab === 'nodeLogs'" style="flex:1;overflow:auto" />

      <!-- 案例信息面板 -->
      <div v-if="activeTab === 'cases'" class="case-panel">
        <div class="case-header">
          <div>
            <div class="case-title">案例信息</div>
            <div class="case-subtitle">Conversation Cases</div>
          </div>
          <div class="case-actions">
            <span v-if="caseError" class="case-error">⚠ {{ caseError }}</span>
            <el-button size="small" :loading="caseLoading" @click="loadCases">刷新</el-button>
          </div>
        </div>

        <div class="case-table-wrap">
          <el-table :data="cases" v-loading="caseLoading" stripe border style="width: 100%" empty-text="暂无案例信息">
            <el-table-column prop="caseName" label="案例名称" min-width="150" show-overflow-tooltip />
            <el-table-column label="创建时间" width="180">
              <template #default="{ row }">{{ formatCaseTime(row.createdAt) }}</template>
            </el-table-column>

            <!-- 最近比较时间 -->
            <el-table-column label="最近比较时间" width="180">
              <template #default="{ row }">
                {{ row.executeRecords && row.executeRecords.length > 0 ? formatCaseTime(row.executeRecords[0].executeTime) : '-' }}
              </template>
            </el-table-column>

            <!-- 比较结果 -->
            <el-table-column label="比较结果" width="120" align="center">
              <template #default="{ row }">
                <template v-if="row.executeRecords && row.executeRecords.length > 0">
                  <el-tag :type="getResultTagType(row.executeRecords[0].result)" size="small" effect="light">
                    {{ getResultLabel(row.executeRecords[0].result) }}
                  </el-tag>
                </template>
                <span v-else>-</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="260" align="center" fixed="right">
              <template #default="{ row }">
                <!-- 查看最近执行情况 -->
                <el-dropdown v-if="row.executeRecords && row.executeRecords.length > 0" trigger="click" @command="(cmd) => handleViewExecution(row, cmd)">
                  <el-button type="primary" link size="small">查看最近执行情况<el-icon class="el-icon--right"><arrow-down /></el-icon></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="record in row.executeRecords" :key="record.compareResultID" :command="record">
                        <div class="dropdown-item-content">
                          <span>{{ formatCaseTime(record.executeTime) }}</span>
                          <span v-if="record.result" class="record-result" :class="record.result === '成功' ? 'text-success' : 'text-danger'">
                            ({{ record.result }})
                          </span>
                        </div>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button v-else type="primary" link size="small" disabled>暂无执行记录</el-button>

                <!-- 删除按钮 -->
                <el-button
                    type="danger"
                    link
                    size="small"
                    @click="removeCase(row)"
                >删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { listConversations } from '../api/trace';
import { setConversationAsCase, listConversationCases, deleteConversationCase } from '../api/skillRecord';
import type { Conversation, TraceStatus } from '../types/trace';
import { STATUS_TAG_TYPE } from '../types/trace';
import dayjs from 'dayjs';
import { getLoggedInUserId } from '../utils/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown } from '@element-plus/icons-vue';
import LongTaskNodeLogPanel from '../components/LongTaskNodeLogPanel.vue';

const router = useRouter();
const route = useRoute();

// ==================== 通用状态 ====================
const activeTab = ref<'sessions' | 'nodeLogs' | 'cases'>('sessions');
const loading = ref(false);
const error = ref<string | null>(null);
const lastUpdated = ref('--');

// ==================== 会话历史状态 ====================
const conversations = ref<Conversation[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const searchKeyword = ref('');
const selectedUserId = ref<string>('');
const scope = ref<'mine' | 'all'>((route.query.scope as 'mine' | 'all') || 'mine');
const caseLoadingId = ref<string | null>(null);
const caseConversationIds = ref<Set<string>>(new Set());
let caseQueryVersion = 0;

// ==================== 案例信息状态 ====================
interface ExecuteRecord {
  compareResultID: string;
  executeTime: string;
  result?: string;
}

interface CaseItem {
  id: string | number;
  caseName: string;
  createdAt: string;
  conversationId: string; // 🌟 新增：绑定会话 ID
  executeRecords?: ExecuteRecord[];
}

const cases = ref<CaseItem[]>([]);
const caseLoading = ref(false);
const caseError = ref<string | null>(null);

// ==================== 核心逻辑 ====================

async function loadCases() {
  caseLoading.value = true;
  caseError.value = null;
  try {
    const owner = getLoggedInUserId() || '';

    const resp = await fetch('http://122.244.14.11:8081/skillRecord/list', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      body: JSON.stringify({ owner: owner })
    });

    if (!resp.ok) throw new Error(`接口请求失败: ${resp.status}`);
    const raw = await resp.json();

    // 适配返回结构：{ result: "success", data: [...] }
    const list = raw.data || [];

    // 解构嵌套的 qaStandard 和 executeRecords
    cases.value = list.map((item: any) => {
      const qa = item.qaStandard || {};
      return {
        id: qa.id || qa.stdCode,
        caseName: qa.stdCode || qa.caseName || '未命名',
        createdAt: qa.createdAt,
        conversationId: qa.conversationId || '', // 🌟 提取并保存 conversationId
        executeRecords: item.executeRecords || []
      };
    });
  } catch (e: any) {
    caseError.value = e?.message || '案例信息加载失败';
  } finally {
    caseLoading.value = false;
  }
}

// 删除案例 (修改为使用 conversationId)
async function removeCase(row: CaseItem) {
  try {
    await ElMessageBox.confirm(`确定删除案例「${row.caseName}」吗？`, '删除确认', { type: 'warning' });

    // 🌟 修改：使用 conversationId 作为入参
    const targetId = String(row.conversationId ?? '');
    if (!targetId) throw new Error('缺少会话 ID，无法删除');

    await deleteConversationCase(targetId);

    ElMessage.success('已删除');
    await loadCases(); // 刷新列表
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.message || '删除失败');
    }
  }
}

function handleViewExecution(row: CaseItem, record: ExecuteRecord) {
  router.push({
    path: `/compare-result/${record.compareResultID}`,
    query: { ...route.query, tab: 'cases' }
  });
}

// 结果展示辅助函数
function getResultTagType(result?: string) {
  if (result === '成功') return 'success';
  if (result === '失败') return 'danger';
  return 'info';
}

function getResultLabel(result?: string) {
  return result || '-';
}

// ==================== 会话历史原有逻辑 ====================
function caseOwner(row: Conversation): string {
  return row.userId || getLoggedInUserId() || '';
}

async function refreshCaseState() {
  const version = ++caseQueryVersion;
  try {
    const owners = [...new Set(conversations.value.map(caseOwner))];
    if (owners.some(owner => !owner)) throw new Error('缺少案例所属用户，无法查询案例');
    const lists = await Promise.all(owners.map(listConversationCases));
    if (version !== caseQueryVersion) return;
    caseConversationIds.value = new Set(lists.flat().map(item => item.conversationId));
  } catch (e) {
    if (version === caseQueryVersion) ElMessage.error(e instanceof Error ? e.message : '查询案例失败');
    throw e;
  }
}

async function cancelCase(row: Conversation) {
  if (caseLoadingId.value !== null) return;
  caseLoadingId.value = row.conversationId;
  try {
    const message = await deleteConversationCase(row.conversationId);
    ++caseQueryVersion;
    caseConversationIds.value = new Set([...caseConversationIds.value].filter(id => id !== row.conversationId));
    ElMessage.success(message);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消案例失败');
  } finally {
    caseLoadingId.value = null;
  }
}

async function setAsCase(row: Conversation) {
  if (row.status !== 'SUCCESS' || caseLoadingId.value !== null) return;
  caseLoadingId.value = row.conversationId;
  const defaultCode = row.agentName || 'conversation-case';
  try {
    const result = await ElMessageBox.prompt('请输入案例标准编码（stdCode）', '设为案例', {
      inputValue: defaultCode,
      inputPlaceholder: '例如 newTESTCODE1',
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputValidator: (value: string) => value.trim() ? true : '标准编码不能为空',
    });
    caseLoadingId.value = row.conversationId;
    const message = await setConversationAsCase(row.conversationId, result.value.trim(), caseOwner(row));
    ++caseQueryVersion;
    caseConversationIds.value = new Set([...caseConversationIds.value, row.conversationId]);
    ElMessage.success(message);
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e?.message || '设为案例失败');
  } finally {
    caseLoadingId.value = null;
  }
}

function syncQuery() {
  router.replace({ query: { ...route.query, scope: scope.value, user: selectedUserId.value || undefined, q: searchKeyword.value || undefined, page: currentPage.value > 1 ? String(currentPage.value) : undefined } });
}

function applyRouteState() {
  scope.value = route.query.scope === 'all' ? 'all' : 'mine';
  selectedUserId.value = (route.query.user as string) || '';
  searchKeyword.value = (route.query.q as string) || '';
  currentPage.value = Math.max(1, Number(route.query.page) || 1);
}

function statusTagType(status: TraceStatus) {
  return STATUS_TAG_TYPE[status] ?? 'info';
}

function statusLabel(status: TraceStatus): string {
  const map: Record<string, string> = {
    SUCCESS: '成功', ERROR: '异常', TIMEOUT: '超时', RUNNING: '运行中',
  };
  return map[status] ?? status;
}

function formatTime(ts: string): string {
  if (!ts) return '-';
  return dayjs(ts).format('YYYY-MM-DD HH:mm:ss');
}

function formatCaseTime(ts: string): string {
  if (!ts) return '-';
  return dayjs(ts).format('YYYY/MM/DD HH:mm');
}

function formatDuration(ms: number): string {
  if (ms == null || ms === 0) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

async function refreshConversations() {
  loading.value = true;
  error.value = null;
  try {
    const data = await listConversations(
        undefined,
        scope.value === 'mine' ? (selectedUserId.value || getLoggedInUserId() || undefined) : (selectedUserId.value || undefined),
        searchKeyword.value.trim() || undefined,
        currentPage.value - 1,
        pageSize,
    );
    conversations.value = data.conversations ?? [];
    total.value = data.total ?? 0;
    await refreshCaseState().catch(() => undefined);
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  } catch (e: any) {
    error.value = '会话列表加载失败';
  } finally {
    loading.value = false;
  }
}

async function handleSearch() {
  if (!searchKeyword.value.trim()) {
    handleClearSearch();
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const data = await listConversations(
        undefined,
        selectedUserId.value || undefined,
        searchKeyword.value.trim() || undefined,
        currentPage.value - 1,
        pageSize,
    );
    conversations.value = data.conversations ?? [];
    total.value = data.total ?? 0;
    await refreshCaseState().catch(() => undefined);
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  } catch {
    error.value = '搜索失败';
  } finally {
    loading.value = false;
  }
}

function handleClearSearch() {
  searchKeyword.value = '';
  currentPage.value = 1;
  refreshConversations();
  syncQuery();
}

function handleUserFilter() {
  currentPage.value = 1;
  refreshConversations();
  syncQuery();
}

function handlePageChange(page: number) {
  currentPage.value = page;
  if (searchKeyword.value.trim()) {
    handleSearch();
  } else {
    refreshConversations();
  }
  syncQuery();
}

function handleRowClick(row: Conversation) {
  goDetail(row.conversationId);
}

function goDetail(conversationId: string) {
  router.push({ path: `/sessions/${encodeURIComponent(conversationId)}`, query: { ...route.query } });
}

onMounted(() => {
  applyRouteState();
  refreshConversations();
});

watch(() => route.query, () => { applyRouteState(); refreshConversations(); });

watch(activeTab, (tab) => {
  if (tab === 'cases' && cases.value.length === 0) loadCases();
});

// ---- Styles ----
const rootStyle: any = { display: 'flex', flexDirection: 'column', height: '100%', background: '#f1f5f9', overflow: 'hidden' };
const headerStyle: any = { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', flexShrink: 0, background: '#ffffff', borderBottom: '1px solid #e2e8f0' };
const titleStyle: any = { fontSize: 18, fontWeight: 700, color: '#0f172a' };
const subtitleStyle: any = { fontSize: 11, color: '#94a3b8', marginTop: 2 };
const headerRight: any = { display: 'flex', alignItems: 'center', gap: 12 };
const updateStyle: any = { fontSize: 11, color: '#94a3b8', fontFamily: 'ui-monospace, monospace' };
const errorStyle: any = { fontSize: 12, color: '#ef4444', fontWeight: 500 };
const tableContainerStyle: any = { flex: 1, overflow: 'auto', padding: '0 20px' };
const paginationStyle: any = { display: 'flex', justifyContent: 'flex-end', alignItems: 'center', padding: '12px 20px', background: '#ffffff', borderTop: '1px solid #e2e8f0', flexShrink: 0 };
const idStyle: any = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#475569' };
const timeStyle: any = { fontSize: '0.82rem', color: '#475569' };

function durationStyle(ms: number): any {
  let color = '#67c23a';
  if (ms >= 10000) color = '#f56c6c';
  else if (ms >= 3000) color = '#e6a23c';
  return { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', fontWeight: 600, color };
}
</script>

<style scoped>
.history-shell { display: flex; height: 100%; min-width: 0; }
.history-nav { width: 200px; box-sizing: border-box; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; flex-shrink: 0; }
.history-nav-title { font-weight: bold; color: #fff; margin-bottom: 12px; }
.history-nav-item { width: 100%; border: 0; padding: 8px 10px; border-radius: 6px; background: transparent; color: #cbd5e1; text-align: left; font: inherit; cursor: pointer; }
.history-nav-item:hover:not(.active) { background: #1e293b; color: #fff; }
.history-nav-item.active { background: #3b82f6; color: #fff; }
.history-nav-item:focus-visible { outline: 2px solid #93c5fd; outline-offset: 2px; }
.history-content { min-width: 0; flex: 1; }
.action-buttons { display: flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap; }
.action-buttons :deep(.el-button) { margin-left: 0; }

/* 案例信息面板 */
.case-panel { flex: 1; overflow: hidden; display: flex; flex-direction: column; padding: 16px 20px; box-sizing: border-box; }
.case-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; flex-shrink: 0; }
.case-title { font-size: 18px; font-weight: 700; color: #0f172a; }
.case-subtitle { font-size: 11px; color: #94a3b8; margin-top: 2px; }
.case-actions { display: flex; align-items: center; gap: 12px; }
.case-error { font-size: 12px; color: #ef4444; font-weight: 500; }
.case-table-wrap { flex: 1; overflow: auto; }

/* 下拉菜单项的样式 */
.dropdown-item-content {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
  min-width: 140px;
}
.record-result { font-weight: 600; font-size: 12px; }
.text-success { color: #67c23a; }
.text-danger { color: #f56c6c; }
</style>