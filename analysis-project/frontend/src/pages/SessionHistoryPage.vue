<template>
  <div class="history-shell">
    <aside class="history-nav" aria-label="历史记录导航">
      <div class="history-nav-title">历史记录</div>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'sessions' }" @click="activeTab = 'sessions'">会话历史</button>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'nodeLogs' }" @click="activeTab = 'nodeLogs'">节点执行日志</button>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'cases' }" @click="activeTab = 'cases'">案例信息</button>
      <button type="button" class="history-nav-item" :class="{ active: activeTab === 'behavior' }" @click="activeTab = 'behavior'; loadBehavior(); loadAccess()">操作记录</button>
    </aside>

    <main class="history-content" :style="rootStyle">
      <!-- 会话历史 Header -->
      <div v-show="activeTab === 'sessions'" class="session-header" :style="headerStyle">
        <div>
          <div :style="titleStyle">会话历史</div>
          <div :style="subtitleStyle">Session History</div>
        </div>
        <div class="session-header-actions" :style="headerRight">
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
      <div v-if="activeTab === 'behavior'" class="case-panel operation-panel">
        <div class="case-header access-header">
          <div><div class="case-title">操作记录</div><div class="case-subtitle">访问与配置变更</div></div>
          <div class="record-type-switch">
            <span class="filter-hint record-type-hint">{{ recordType === 'access' ? '页面与通知访问' : '脚本与长任务配置变更' }}</span>
            <el-select v-model="recordType" class="record-type-select" @change="handleRecordTypeChange">
              <el-option label="访问记录" value="access"/>
              <el-option label="资源变更" value="behavior"/>
            </el-select>
          </div>
        </div>
        <template v-if="recordType === 'access'">
          <div class="record-filters">
            <el-input v-model="accessTask" placeholder="任务" clearable class="access-control" @keyup.enter="loadAccess"/>
            <el-input v-model="accessExecutionId" placeholder="执行ID" clearable class="access-control" @keyup.enter="loadAccess"/>
            <el-input v-model="accessUser" placeholder="姓名 / 统一号（模糊）" clearable class="access-control access-user" @keyup.enter="loadAccess"/>
            <el-date-picker v-model="executionDateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="执行开始" end-placeholder="执行结束" class="access-date"/>
            <el-date-picker v-model="accessDateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="访问开始" end-placeholder="访问结束" class="access-date"/>
            <el-select v-model="accessEnvironment" class="access-control"><el-option label="生产环境" value="prod"/><el-option label="开发环境" value="dev"/><el-option label="全部环境" value="ALL"/></el-select>
            <div class="filter-buttons">
              <el-button type="primary" :loading="accessLoading" @click="loadAccess">查询</el-button>
              <el-button :loading="accessLoading" @click="loadAccess">刷新</el-button>
              <el-button @click="exportAccess">导出记录</el-button>
            </div>
          </div>
          <el-table class="access-table" :data="accessRows" v-loading="accessLoading" stripe border empty-text="暂无访问埋点"><el-table-column label="任务类型" min-width="120"><template #default="s">{{ s.row.executionType === 'LONG_TASK' ? '长任务流程' : '独立任务' }}</template></el-table-column><el-table-column label="任务" min-width="220" show-overflow-tooltip><template #default="s">{{ s.row.taskDisplayName || '-' }}{{ s.row.executionAt ? `（${dayjs(s.row.executionAt).format('YYYY-MM-DD')}）` : '' }}</template></el-table-column><el-table-column prop="executionId" label="执行ID" min-width="100"/><el-table-column label="执行时间" min-width="185"><template #default="s">{{ formatAccessDateTime(s.row.executionAt) }}</template></el-table-column><el-table-column label="人员" min-width="180"><template #default="s">{{ s.row.userName ? `${s.row.userName}(${s.row.userId})` : (s.row.userId || '-') }}</template></el-table-column><el-table-column label="来源" min-width="130"><template #default="s">{{s.row.source === 'PAGE' ? '页面点击' : '通知点击'}}</template></el-table-column><el-table-column label="状态" min-width="155"><template #default="s"><span :class="['access-status', s.row.loggedIn || s.row.eventType === 'LOGIN_VIEW' ? 'is-success' : 'is-muted']">{{s.row.eventType === 'LOGIN_VIEW' ? '登录后查看' : s.row.loggedIn ? '已登录点击' : '仅点击未登录'}}</span></template></el-table-column><el-table-column label="访问时间" min-width="210"><template #default="s">{{ formatAccessDateTime(s.row.latestEventAt) }}</template></el-table-column></el-table>
        </template>
        <template v-else>
          <div class="record-filters">
            <el-select v-model="behavior.resourceType" placeholder="资源范围" style="width:150px" @change="behavior.page = 1; loadBehavior()"><el-option label="Python 脚本" value="python_page"/><el-option label="长任务" value="long_task_flow"/></el-select>
            <span class="filter-hint">选择资源范围后可查看对应的变更操作记录</span>
            <el-select v-model="behavior.operationType" clearable placeholder="操作类型" style="width:130px"><el-option label="新增" value="create"/><el-option label="编辑" value="update"/><el-option label="删除" value="delete"/></el-select>
            <el-input v-model="behavior.operatorKeyword" placeholder="操作人姓名 / userId" clearable style="width:210px" @keyup.enter="loadBehavior"/>
            <el-input v-model="behavior.resourceKeyword" placeholder="资源名称" clearable style="width:160px" @keyup.enter="loadBehavior"/>
            <div class="filter-buttons">
              <el-button type="primary" :loading="behaviorLoading" @click="behavior.page=1; loadBehavior()">查询</el-button>
              <el-button plain :loading="behaviorLoading" @click="behavior.page = 1; loadBehavior()">刷新</el-button>
            </div>
          </div>
          <div class="behavior-table-wrap"><el-table class="behavior-table" :data="behavior.items" v-loading="behaviorLoading" stripe border empty-text="暂无操作记录"><el-table-column prop="resource_name" label="资源名称" min-width="220" show-overflow-tooltip/><el-table-column label="操作类型" width="90"><template #default="s">{{ operationLabel(s.row.operation_type) }}</template></el-table-column><el-table-column prop="operatorDisplayName" label="操作人" min-width="170" show-overflow-tooltip/><el-table-column label="结果" width="80"><template #default="s">{{ resultLabel(s.row.result) }}</template></el-table-column><el-table-column label="变更详情" width="110" fixed="right"><template #default="s"><el-button link type="primary" @click="openBehaviorDetail(s.row)">查看详情</el-button></template></el-table-column><el-table-column label="事件时间" width="190" fixed="right"><template #default="s">{{ formatBehaviorTime(s.row.created_at) }}</template></el-table-column></el-table></div>
          <div class="behavior-pagination"><span>共 {{ behavior.total }} 条</span><el-pagination v-model:current-page="behavior.page" :page-size="behavior.pageSize" :total="behavior.total" layout="prev, pager, next" @current-change="loadBehavior"/></div>
        </template>
        <el-dialog v-model="behaviorDetailVisible" title="变更详情" width="780px" class="behavior-detail-dialog"><div v-loading="behaviorDetailLoading" class="behavior-detail"><template v-if="behaviorDetail.event"><div class="behavior-detail-head"><div class="behavior-detail-resource">{{ behaviorDetail.event.resource_name }}</div><el-tag size="small" :type="operationTagType(behaviorDetail.event.operation_type)">{{ operationLabel(behaviorDetail.event.operation_type) }}</el-tag><span class="behavior-detail-time">{{ formatBehaviorTime(behaviorDetail.event.created_at) }}</span></div><div v-if="behaviorDetail.details?.length" class="behavior-detail-summary">{{ behaviorSummary(behaviorDetail.event, behaviorDetail.details) }}</div><el-empty v-if="!behaviorDetail.details?.length" description="该操作未产生字段级变更" :image-size="70"/><template v-else><div class="behavior-change-list"><div v-for="(item, index) in behaviorDetail.details.filter((x:any) => !isSnapshot(x))" :key="index" class="behavior-change-item"><div class="behavior-change-title"><span class="behavior-change-index">{{ String(Number(index) + 1).padStart(2, '0') }}</span><span>{{ fieldLabel(item) }}</span><span class="behavior-change-type">{{ changeAction(item) }}</span></div><div class="behavior-change-values"><div class="behavior-before"><span class="behavior-change-label">修改前</span><span class="behavior-change-value">{{ displayChangeValue(item.before_value, '空') }}</span></div><div class="behavior-change-arrow">→</div><div class="behavior-after"><span class="behavior-change-label">修改后</span><span class="behavior-change-value">{{ displayChangeValue(item.after_value, '空') }}</span></div></div></div><div v-for="item in behaviorDetail.details.filter((x:any) => isSnapshot(x))" :key="'summary-' + item.detail_no" class="behavior-change-item"><div class="behavior-change-title"><span class="behavior-change-index">配置</span><span>配置变更摘要</span><span class="behavior-change-type">已解析</span></div><div class="behavior-readable-diff"><div v-for="line in snapshotDiff(item)" :key="line">{{ line }}</div><div v-if="!snapshotDiff(item).length">未识别到可读字段变化，请展开完整快照查看。</div></div></div></div><el-collapse v-if="behaviorDetail.details.some((x:any) => isSnapshot(x))" class="behavior-raw-collapse"><el-collapse-item title="查看完整配置快照" name="raw"><div v-for="item in behaviorDetail.details.filter((x:any) => isSnapshot(x))" :key="item.detail_no" class="behavior-raw-snapshot"><div class="behavior-raw-title">{{ item.before_value === '（初始）' ? '修改后完整配置' : '修改前 / 修改后完整配置' }}</div><div class="behavior-change-values"><pre class="behavior-change-value behavior-before">{{ displayChangeValue(item.before_value, '空') }}</pre><div class="behavior-change-arrow">→</div><pre class="behavior-change-value behavior-after">{{ displayChangeValue(item.after_value, '空') }}</pre></div></div></el-collapse-item></el-collapse></template></template></div></el-dialog>
      </div>

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
                <el-dropdown v-if="row.executeRecords && row.executeRecords.length > 0" trigger="click" @command="(cmd: ExecuteRecord) => handleViewExecution(row, cmd)">
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
const activeTab = ref<'sessions' | 'nodeLogs' | 'cases' | 'access' | 'behavior'>('sessions');
const recordType = ref<'access' | 'behavior'>('access');
const recordTypeHint = ref('记录用户从页面或通知点击进入任务时的来源、登录状态与访问时间');
function handleRecordTypeChange(type: 'access' | 'behavior') {
  if (type === 'access') {
    recordTypeHint.value = '记录用户从页面或通知点击进入任务时的来源、登录状态与访问时间';
    loadAccess();
  } else {
    recordTypeHint.value = '记录 Python 脚本与长任务配置的新增、编辑、删除等变更操作';
    behavior.value.page = 1;
    loadBehavior();
  }
}
const behaviorLoading = ref(false);
const behaviorDetailVisible = ref(false);
const behaviorDetailLoading = ref(false);
const behaviorDetail = ref<any>({ event: null, details: [] });
const operationLabel = (value: string) => ({ create: '新增', update: '编辑', delete: '删除' }[value] || value || '-');
const resultLabel = (value: string) => ({ success: '成功', failed: '失败' }[value] || value || '-');
const operationTagType = (value: string) => ({ create: 'success', update: 'warning', delete: 'danger' }[value] || 'info');
function displayChangeValue(value: unknown, emptyText = '空') {
  if (value === null || value === undefined || value === '') return emptyText;
  const raw = String(value);
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'object' ? JSON.stringify(parsed, null, 2) : String(parsed);
  } catch { return raw; }
}
function fieldLabel(item: any) {
  return ({ name: '流程名称', description: '描述', task_question: '任务问题', triggers: '触发关键词', nodes: '执行节点', enabled: '启用状态', script_content: '脚本内容', script_id: '脚本标识', params_schema: '参数配置', definition: '完整配置' } as Record<string, string>)[item.field_name] || item.field_label || item.field_name;
}
function isSnapshot(item: any) { return item.field_name === 'definition' || item.field_name === 'script_content'; }
function snapshotDiff(item: any): string[] {
  if (item.field_name === 'script_content') return ['脚本源码已修改（完整内容请展开快照查看）'];
  let before: any, after: any;
  try { before = JSON.parse(item.before_value); after = JSON.parse(item.after_value); } catch { return []; }
  const lines: string[] = [];
  const labels: Record<string, string> = { name: '流程名称', description: '描述', taskQuestion: '任务问题', enabled: '启用状态', scheduleRules: '调度规则', summaryQuestionTemplate: '汇总问题模板' };
  Object.keys(labels).forEach(k => { if (JSON.stringify(before?.[k]) !== JSON.stringify(after?.[k])) lines.push(`${labels[k]}：${displayChangeValue(before?.[k], '空')} → ${displayChangeValue(after?.[k], '空')}`); });
  const nodeMap = (nodes: any[]) => new Map<string, any>(nodes.map((n: any) => [String(n.nodeKey), n] as [string, any]));
  const bn = nodeMap(before?.nodes || []);
  const an = nodeMap(after?.nodes || []);
  an.forEach((n: any, key: string) => { if (!bn.has(key)) lines.push(`新增执行节点：${n.nodeName || key}`); else if (JSON.stringify(bn.get(key)) !== JSON.stringify(n)) lines.push(`修改执行节点：${n.nodeName || key}`); });
  bn.forEach((n: any, key: string) => { if (!an.has(key)) lines.push(`删除执行节点：${n.nodeName || key}`); });
  if (JSON.stringify(before?.triggers) !== JSON.stringify(after?.triggers)) lines.push('触发关键词已修改');
  return lines;
}
function changeAction(item: any) {
  const type = item.change_type;
  if (type === 'create') return '已新增';
  if (type === 'delete') return '已删除';
  return '已修改';
}
function behaviorSummary(event: any, details: any[]) {
  const action = operationLabel(event.operation_type);
  const names = details.slice(0, 3).map(fieldLabel).join('、');
  const suffix = details.length > 3 ? `等 ${details.length} 个字段` : `${details.length} 个字段`;
  return `${action}“${event.resource_name}”，${action === '删除' ? '原记录已移除' : `共${suffix}：${names}`}`;
}
const formatBehaviorTime = (value: string) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
async function openBehaviorDetail(row: any) {
  behaviorDetailVisible.value = true;
  behaviorDetailLoading.value = true;
  behaviorDetail.value = { event: row, details: [] };
  try {
    const r = await fetch('/api/session-records/behavior/' + encodeURIComponent(row.event_id));
    if (!r.ok) throw new Error('详情加载失败');
    behaviorDetail.value = await r.json();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '详情加载失败'); }
  finally { behaviorDetailLoading.value = false; }
}
const behavior = ref({ resourceType: 'python_page', operationType: '', operatorKeyword: '', resourceKeyword: '', keyword: '', page: 1, pageSize: 20, total: 0, items: [] as any[] });
async function loadBehavior() { behaviorLoading.value = true; try { const b = behavior.value, q = new URLSearchParams({ resourceType: b.resourceType, page: String(b.page), pageSize: String(b.pageSize) }); if (b.operationType) q.set('operationType', b.operationType); if (b.operatorKeyword) q.set('operatorKeyword', b.operatorKeyword); if (b.resourceKeyword) q.set('resourceKeyword', b.resourceKeyword); if (b.keyword) q.set('keyword', b.keyword); const r = await fetch('/api/session-records/behavior?' + q); if (!r.ok) throw new Error('加载失败'); const d = await r.json(); behavior.value.items = d.items || []; behavior.value.total = d.total || 0; } catch (e) { ElMessage.error(e instanceof Error ? e.message : '操作记录加载失败'); } finally { behaviorLoading.value = false; } }
const accessRows = ref<any[]>([]);
const accessLoading = ref(false);
const accessTask = ref('');
const accessExecutionId = ref('');
const accessUser = ref('');
const accessEnvironment = ref('');
const executionDateRange = ref<string[]>([]);
const accessDateRange = ref<string[]>([]);
async function loadAccess() {
  accessLoading.value = true;
  try {
    const params = new URLSearchParams();
    const userId = accessUser.value.trim();
    if (userId) params.set('userId', userId);
    if (accessTask.value.trim()) params.set('taskName', accessTask.value.trim());
    if (accessExecutionId.value.trim()) params.set('executionId', accessExecutionId.value.trim());
    params.set('environment', accessEnvironment.value.trim()); if (executionDateRange.value.length === 2) { params.set('executionFrom', executionDateRange.value[0]); params.set('executionTo', executionDateRange.value[1]); } if (accessDateRange.value.length === 2) { params.set('accessFrom', accessDateRange.value[0]); params.set('accessTo', accessDateRange.value[1]); }
    const response = await fetch('/api/notification-access/link-clicks?' + params, { headers: { 'X-User-Id': getLoggedInUserId() || '' } });
    if (!response.ok) throw new Error('查询失败(' + response.status + ')');
    accessRows.value = await response.json();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '访问埋点查询失败'); }
  finally { accessLoading.value = false; }
}
async function exportAccess() {
  const params = new URLSearchParams();
  const userId = accessUser.value.trim();
  if (userId) params.set('userId', userId);
  if (accessTask.value.trim()) params.set('taskName', accessTask.value.trim());
  if (accessExecutionId.value.trim()) params.set('executionId', accessExecutionId.value.trim());
  params.set('environment', accessEnvironment.value.trim()); if (executionDateRange.value.length === 2) { params.set('executionFrom', executionDateRange.value[0]); params.set('executionTo', executionDateRange.value[1]); } if (accessDateRange.value.length === 2) { params.set('accessFrom', accessDateRange.value[0]); params.set('accessTo', accessDateRange.value[1]); }
  const response = await fetch('/api/notification-access/link-clicks/export?' + params, { headers: { 'X-User-Id': getLoggedInUserId() || '' } });
  if (!response.ok) { ElMessage.error('导出失败'); return; }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `长任务访问埋点${dayjs().format('YYYYMMDDHHmmss')}.csv`; anchor.click(); URL.revokeObjectURL(url);
}
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

function formatAccessDateTime(ts?: string): string {
  return ts ? dayjs(ts).format('YYYY-MM-DD HH:mm:ss') : '-';
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
    // 案例状态属于列表的附加信息，后台刷新，避免案例服务变慢时阻塞会话列表首屏。
    void refreshCaseState().catch(() => undefined);
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
    void refreshCaseState().catch(() => undefined);
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

onMounted(async () => {
  try { const response = await fetch('/api/notification-access/environment', { headers: { 'X-User-Id': getLoggedInUserId() || '' } }); if (response.ok) accessEnvironment.value = (await response.json()).environment || 'dev'; } catch { accessEnvironment.value = 'dev'; }
  applyRouteState();
  refreshConversations();
  if (activeTab.value === 'behavior') { loadBehavior(); loadAccess(); }
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
.access-header { gap: 20px; }
.session-header { gap: 16px; }
.session-header-actions { flex: 1; min-width: 0; justify-content: flex-end; flex-wrap: wrap; row-gap: 8px; }
.session-header-actions :deep(.el-input) { max-width: 260px; }
.record-type-switch { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.record-type-select { width: 130px; flex-shrink: 0; }
.record-type-select :deep(.el-select__wrapper) { font-weight: 600; }
.record-type-hint { color: #64748b; font-size: 12px; white-space: nowrap; flex-shrink: 0; }
.access-control { width: 150px; }
.access-user { width: 140px; }
.access-date { width: 205px; flex: 0 0 205px; }
.access-date :deep(.el-range-input) { min-width: 0; }
.access-date :deep(.el-range-separator) { padding: 0 2px; }
.access-table { width: 100%; flex: 1; min-width: 0; border-radius: 8px; overflow: hidden; }
.access-table :deep(.el-table__header th) { background: linear-gradient(180deg, #f8fafc 0%, #eef4fb 100%); color: #334155; font-weight: 700; font-size: 13px; letter-spacing: .02em; border-bottom: 2px solid #dbe5f0; }
.access-table :deep(.el-table__cell) { padding: 13px 0; color: #475569; }
.access-table :deep(.el-table__row:hover > td) { background: #f0f7ff !important; }
.access-status { display: inline-flex; align-items: center; padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.access-status.is-success { background: #ecfdf5; color: #047857; }
.access-status.is-muted { background: #f1f5f9; color: #64748b; }
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

/* 操作记录：标题栏(右侧仅类型切换) + 可换行筛选区 + 表格 + 分页四个区域。 */
.behavior-panel { padding: 22px 24px 18px; background: #f8fafc; gap: 0; }
.record-filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 14px 16px; margin-bottom: 14px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; box-shadow: 0 1px 2px rgb(15 23 42 / 4%); }
.record-filters :deep(.el-input), .record-filters :deep(.el-select) { max-width: 220px; }
.filter-buttons { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.filter-buttons :deep(.el-button) { margin-left: 0; min-width: 76px; }
.filter-hint { color: #64748b; font-size: 12px; white-space: nowrap; }
.operation-panel { overflow: auto; }
.behavior-table-wrap { flex: 1; min-height: 240px; overflow: auto; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; }
.behavior-table { min-width: 980px; }
.behavior-table :deep(.el-table__header th) { background: #f8fafc; color: #475569; font-weight: 600; }
.behavior-table :deep(.el-table__cell) { padding: 11px 0; }
.behavior-change-list { display: flex; flex-direction: column; gap: 12px; padding: 4px 2px; }
.behavior-change-item { padding: 12px 14px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.behavior-detail-head { display: flex; align-items: center; gap: 10px; padding: 10px 12px; margin-bottom: 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.behavior-detail-resource { font-size: 15px; font-weight: 650; color: #0f172a; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.behavior-detail-time { color: #64748b; font-size: 12px; white-space: nowrap; }
.behavior-detail-summary { margin: 0 2px 12px; padding: 11px 14px; border-left: 3px solid #6366f1; border-radius: 6px; background: #eef2ff; color: #3730a3; font-size: 13px; line-height: 1.6; }
.behavior-change-title { display: flex; align-items: center; gap: 7px; font-weight: 600; color: #334155; margin-bottom: 9px; }
.behavior-change-index { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 20px; border-radius: 5px; background: #e0e7ff; color: #4338ca; font-size: 11px; font-family: ui-monospace, monospace; }
.behavior-change-type { color: #64748b; font-weight: 400; font-size: 12px; }
.behavior-change-values { display: grid; grid-template-columns: 1fr 24px 1fr; gap: 10px; align-items: center; }
.behavior-change-arrow { color: #94a3b8; text-align: center; font-size: 18px; }
.behavior-change-values > div { min-width: 0; }
.behavior-change-label { display: block; color: #64748b; font-size: 12px; margin-bottom: 4px; }
.behavior-change-value { display: block; min-height: 20px; max-height: 180px; overflow: auto; padding: 8px 10px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; color: #0f172a; white-space: pre-wrap; word-break: break-word; font-family: inherit; line-height: 1.55; }
.behavior-before .behavior-change-value { background: #fff7ed; border-color: #fed7aa; color: #9a3412; }
.behavior-after .behavior-change-value { background: #f0fdf4; border-color: #bbf7d0; color: #166534; }
.behavior-pagination { display: flex; align-items: center; justify-content: space-between; padding: 14px 2px 0; color: #64748b; font-size: 13px; }
.behavior-pagination :deep(.el-pagination) { padding: 0; }
@media (max-width: 900px) {
  .session-header { align-items: flex-start !important; flex-wrap: wrap; }
  .session-header-actions { width: 100%; justify-content: flex-start; }
  .session-header-actions :deep(.el-input) { flex: 1 1 180px; max-width: none; }
  .session-header-actions :deep(.el-radio-group) { flex: 0 0 auto; }
  .access-header { align-items: flex-start; flex-wrap: wrap; }
  .record-type-switch { width: 100%; justify-content: flex-end; }
  .record-type-hint { font-size: 11px; }
  .behavior-panel { padding: 16px; }
  .record-filters :deep(.el-input), .record-filters :deep(.el-select) { max-width: none; flex: 1 1 180px; }
  .record-filters .access-date { flex: 1 1 180px; width: auto; }
  .filter-buttons { margin-left: 0; width: 100%; }
  .filter-buttons :deep(.el-button) { flex: 1; }
}
</style>





