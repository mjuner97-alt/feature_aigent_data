<template>
  <div :style="rootStyle">
    <!-- Header -->
    <div :style="headerStyle">
      <div>
        <div :style="titleStyle">会话历史</div>
        <div :style="subtitleStyle">Session History</div>
      </div>
      <div :style="headerRight">
        <el-radio-group v-model="scope" size="small" @change="currentPage = 1; syncQuery(); refreshConversations()">
          <el-radio-button label="mine">我的</el-radio-button>
          <el-radio-button label="all">全部</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="selectedUserId"
          placeholder="提问人"
          :style="{ width: '140px' }"
          clearable
          @keyup.enter="handleUserFilter"
          @clear="handleUserFilter"
        >
          <template #prefix>
            <span style="font-size: 14px">👤</span>
          </template>
        </el-input>
        <el-button size="small" @click="handleUserFilter">查询</el-button>
        <el-input
          v-model="searchKeyword"
          placeholder="搜索会话ID / Agent名称"
          :style="{ width: '260px' }"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleClearSearch"
        >
          <template #prefix>
            <span style="font-size: 14px">🔍</span>
          </template>
        </el-input>
        <span :style="updateStyle">上次更新: {{ lastUpdated }}</span>
        <span v-if="error" :style="errorStyle">⚠ {{ error }}</span>
        <el-button size="small" :loading="loading" @click="refreshConversations">
          刷新
        </el-button>
      </div>
    </div>

    <!-- Table -->
    <div :style="tableContainerStyle">
      <el-table
        :data="conversations"
        v-loading="loading"
        :style="{ width: '100%' }"
        @row-click="handleRowClick"
        :row-style="{ cursor: 'pointer' }"
        stripe
        border
      >
        <el-table-column label="会话ID" min-width="180">
          <template #default="{ row }">
            <span :style="idStyle" :title="row.conversationId">
              {{ row.conversationId?.slice(0, 12) }}…
            </span>
          </template>
        </el-table-column>
        <el-table-column label="提问人" min-width="120">
          <template #default="{ row }">
            <span>{{ row.agentName || row.userId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="dark">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" min-width="170">
          <template #default="{ row }">
            <span :style="timeStyle">{{ formatTime(row.startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">
            <span :style="durationStyle(row.durationMs)">{{ formatDuration(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Token In" width="100" align="right">
          <template #default="{ row }">
            <span :style="tokenStyle">{{ row.tokenInput ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Token Out" width="100" align="right">
          <template #default="{ row }">
            <span :style="tokenStyle">{{ row.tokenOutput ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Spans" width="80" align="right">
          <template #default="{ row }">
            <span :style="metaStyle">{{ row.eventCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="120">
          <template #default="{ row }">
            <span :style="metaStyle">{{ row.model || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="goDetail(row.conversationId)">
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Pagination -->
    <div :style="paginationStyle">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useRouter } from 'vue-router';
import { listConversations, search } from '../api/trace';
import type { Conversation, TraceStatus } from '../types/trace';
import { STATUS_TAG_TYPE } from '../types/trace';
import dayjs from 'dayjs';
import { getLoggedInUserId } from '../utils/auth';

const router = useRouter();
const route = useRoute();

const loading = ref(false);
const error = ref<string | null>(null);
const lastUpdated = ref('--');
const conversations = ref<Conversation[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const searchKeyword = ref('');
const selectedUserId = ref<string>('');
const scope = ref<'mine' | 'all'>((route.query.scope as 'mine' | 'all') || 'mine');

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
    SUCCESS: '成功',
    ERROR: '异常',
    TIMEOUT: '超时',
    RUNNING: '运行中',
  };
  return map[status] ?? status;
}

function formatTime(ts: string): string {
  if (!ts) return '-';
  return dayjs(ts).format('YYYY-MM-DD HH:mm:ss');
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
    // 搜索时先拉列表（带 userId 筛选），再客户端按关键字过滤
    const data = await listConversations(
      undefined,
      selectedUserId.value || undefined,
      searchKeyword.value.trim() || undefined,
      currentPage.value - 1,
      pageSize,
    );
    conversations.value = data.conversations ?? [];
    total.value = data.total ?? 0;
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

// ---- styles ----
const rootStyle: any = {
  display: 'flex', flexDirection: 'column', height: '100%',
  background: '#f1f5f9', overflow: 'hidden',
};
const headerStyle: any = {
  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
  padding: '16px 20px', flexShrink: 0, background: '#ffffff',
  borderBottom: '1px solid #e2e8f0',
};
const titleStyle: any = { fontSize: 18, fontWeight: 700, color: '#0f172a' };
const subtitleStyle: any = { fontSize: 11, color: '#94a3b8', marginTop: 2 };
const headerRight: any = { display: 'flex', alignItems: 'center', gap: 12 };
const updateStyle: any = { fontSize: 11, color: '#94a3b8', fontFamily: 'ui-monospace, monospace' };
const errorStyle: any = { fontSize: 12, color: '#ef4444', fontWeight: 500 };
const tableContainerStyle: any = {
  flex: 1, overflow: 'auto', padding: '0 20px',
};
const paginationStyle: any = {
  display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
  padding: '12px 20px', background: '#ffffff', borderTop: '1px solid #e2e8f0', flexShrink: 0,
};
const idStyle: any = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: '0.82rem', color: '#475569',
};
const timeStyle: any = {
  fontSize: '0.82rem', color: '#475569',
};
const tokenStyle: any = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: '0.82rem', color: '#e6a23c',
};
const metaStyle: any = {
  fontSize: '0.82rem', color: '#94a3b8',
};

function durationStyle(ms: number): any {
  let color = '#67c23a'; // green < 3s
  if (ms >= 10000) color = '#f56c6c'; // red > 10s
  else if (ms >= 3000) color = '#e6a23c'; // orange 3-10s
  return {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem', fontWeight: 600, color,
  };
}
</script>
