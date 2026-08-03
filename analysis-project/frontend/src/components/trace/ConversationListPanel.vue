<template>
  <div class="conversation-list-panel">
    <div class="panel-header">
      <span class="panel-title">会话列表</span>
      <span v-if="conversations.length" class="panel-count">共 {{ conversations.length }} 条</span>
    </div>

    <div class="search-box">
      <el-input
        v-model="keyword"
        placeholder="搜索 conversationId / agentName"
        clearable
        :prefix-icon="Search"
        @input="onSearch"
      />
    </div>

    <el-scrollbar class="list-scroll" v-loading="loading">
      <div v-if="filteredConversations.length === 0 && !loading" class="empty-state">
        <el-empty description="暂无会话数据" :image-size="80" />
      </div>

      <div
        v-for="conv in filteredConversations"
        :key="conv.conversationId"
        class="conv-card"
        :class="{ selected: conv.conversationId === selectedId }"
        @click="onSelect(conv.conversationId)"
      >
        <div class="conv-card-header">
          <span class="conv-id" :title="conv.conversationId">{{ conv.conversationId }}</span>
          <el-tag :type="statusTagType(conv.status)" size="small" effect="light">
            {{ conv.status }}
          </el-tag>
        </div>

        <div class="conv-card-body">
          <div class="conv-meta">
            <span class="meta-item">
              <el-icon><User /></el-icon>
              {{ conv.agentName || '-' }}
            </span>
            <span class="meta-item">
              <el-icon><Link /></el-icon>
              {{ (conv as any).source || '-' }}
            </span>
          </div>
          <div class="conv-meta">
            <span class="meta-item">
              <el-icon><Clock /></el-icon>
              {{ formatTime(conv.startTime) }}
            </span>
            <span class="meta-item duration" :class="durationClass(conv.durationMs)">
              {{ formatDuration(conv.durationMs) }}
            </span>
          </div>
          <div class="conv-meta">
            <span class="meta-item">
              <el-icon><Histogram /></el-icon>
              {{ conv.eventCount }} events
            </span>
            <span class="meta-item" v-if="conv.tokenInput || conv.tokenOutput">
              <el-icon><Coin /></el-icon>
              {{ conv.tokenInput || 0 }}/{{ conv.tokenOutput || 0 }}
            </span>
          </div>
        </div>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { Search, User, Link, Clock, Histogram, Coin } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import type { TraceConversation, TraceStatus } from '../../types/trace';
import { STATUS_TAG_TYPE } from '../../types/trace';

const props = defineProps<{
  conversations: TraceConversation[];
  loading?: boolean;
  selectedId?: string;
}>();

const emit = defineEmits<{
  (e: 'select', conversationId: string): void;
  (e: 'search', keyword: string): void;
}>();

const keyword = ref('');

const filteredConversations = computed(() => {
  if (!keyword.value.trim()) return props.conversations;
  const kw = keyword.value.trim().toLowerCase();
  return props.conversations.filter(
    (c) =>
      c.conversationId.toLowerCase().includes(kw) ||
      (c.agentName || '').toLowerCase().includes(kw),
  );
});

function onSearch() {
  emit('search', keyword.value);
}

function onSelect(conversationId: string) {
  emit('select', conversationId);
}

function statusTagType(status: TraceStatus) {
  return STATUS_TAG_TYPE[status] ?? 'info';
}

function formatTime(ts: string): string {
  if (!ts) return '-';
  return dayjs(ts).format('MM-DD HH:mm:ss');
}

function formatDuration(ms: number): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function durationClass(ms: number): string {
  if (ms > 10000) return 'dur-slow';
  if (ms > 3000) return 'dur-mid';
  return 'dur-fast';
}
</script>

<style scoped>
.conversation-list-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.panel-count {
  font-size: 12px;
  color: #909399;
}

.search-box {
  padding: 8px 12px;
  flex-shrink: 0;
}

.list-scroll {
  flex: 1;
  overflow: hidden;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}

.conv-card {
  padding: 10px 14px;
  border-bottom: 1px solid #f5f7fa;
  cursor: pointer;
  transition: background-color 0.15s;
}

.conv-card:hover {
  background: #f5f7fa;
}

.conv-card.selected {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
  padding-left: 11px;
}

.conv-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.conv-id {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}

.conv-card-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.conv-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #606266;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.meta-item .el-icon {
  font-size: 12px;
  color: #909399;
}

.duration.dur-fast { color: #67c23a; font-weight: 600; }
.duration.dur-mid { color: #e6a23c; font-weight: 600; }
.duration.dur-slow { color: #f56c6c; font-weight: 600; }
</style>
