<template>
  <div class="chat-workspace">
    <div class="chat-tabs" role="tablist" aria-label="AI 对话视图">
      <button
        class="chat-tab"
        :class="{ active: activeTab === 'conversation' }"
        role="tab"
        :aria-selected="activeTab === 'conversation'"
        @click="selectTab('conversation')"
      >
        <el-icon><ChatDotRound /></el-icon>
        <span>对话</span>
      </button>
      <button
        class="chat-tab"
        :class="{ active: activeTab === 'history' }"
        role="tab"
        :aria-selected="activeTab === 'history'"
        @click="selectTab('history')"
      >
        <el-icon><Clock /></el-icon>
        <span>对话记录</span>
      </button>
    </div>

    <div class="chat-tab-content">
      <ChatPage v-if="activeTab === 'conversation'" />
      <SessionHistoryPage v-else />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChatDotRound, Clock } from '@element-plus/icons-vue';
import ChatPage from './ChatPage.vue';
import SessionHistoryPage from './SessionHistoryPage.vue';

type ChatTab = 'conversation' | 'history';

const route = useRoute();
const router = useRouter();
const activeTab = computed<ChatTab>(() => route.query.tab === 'history' ? 'history' : 'conversation');

function selectTab(tab: ChatTab) {
  const query = { ...route.query };
  if (tab === 'history') query.tab = 'history';
  else delete query.tab;
  router.replace({ path: '/chat', query });
}
</script>

<style scoped>
.chat-workspace {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  background: #f8fafc;
}

.chat-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 48px;
  padding: 0 24px;
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}

.chat-tab {
  height: 48px;
  padding: 0 16px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #64748b;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.chat-tab:hover { color: #0f172a; }
.chat-tab.active { color: #2563eb; border-bottom-color: #2563eb; }
.chat-tab-content { display: flex; flex: 1; min-height: 0; overflow: hidden; }

@media (max-width: 720px) {
  .chat-tabs { padding: 0 12px; }
  .chat-tab { flex: 1; justify-content: center; }
}
</style>
