<template>
  <div :style="{ display: 'flex', flex: 1, minHeight: 0 }">
    <!-- Center: chat -->
    <div :style="{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }">
      <ChatPanel
        ref="chatPanelRef"
        :user-id="userId"
        :conversation-id="conversationId"
        @on-conversation-id="handleConversationId"
        @on-user-message="handleUserMessage"
        @on-subagent-plan-change="handleSubagentPlanChange"
        @on-stream-done="handleStreamDone"
      />
    </div>

    <!-- Right: state panels -->
    <div :style="stateColStyle">
      <div :style="stateHeaderStyle">
        <span>PlanNotebook + 状态机</span>
        <span v-if="conversationId" :style="{ fontSize: '0.72rem', color: '#94a3b8', fontFamily: 'ui-monospace, monospace' }">
          {{ conversationId.slice(0, 8) }}…
        </span>
      </div>
      <div :style="userIdRowStyle">
        <span :style="userIdLabelStyle">用户身份</span>
        <span :style="userIdChipStyle(!!urlUserId)" :title="urlUserId ? '来源: URL ?userId=' : '来源: localStorage (改 URL 加 ?userId=xxx 切换)'">
          {{ userId }}
        </span>
      </div>

      <div v-if="!conversationId" :style="emptyStateStyle">
        发送一条消息开始会话，<br />状态机会在 2 秒内显示。
      </div>
      <div v-if="conversationId && !state" :style="emptyStateStyle">加载状态…</div>

      <template v-if="state">
        <StateMachineView :state="state" :subagent-plans="subagentPlans" />
        <PlanPanel :subagent-plans="Object.values(subagentPlans)" />
        <TodoListPanel :tasks="state.tasks" :subagent-todo-write-counts="subagentTodoCounts" />
        <TaskDependencyGraph :tasks="state.tasks" />
        <InterruptButton
          :enabled="!!chatPanelRef?.busy"
          :interrupt-flag="state.interruptControl.flag"
          @interrupt="handleInterrupt"
        />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ChatPanel from '../components/ChatPanel.vue';
import PlanPanel from '../components/PlanPanel.vue';
import TodoListPanel from '../components/TodoListPanel.vue';
import TaskDependencyGraph from '../components/TaskDependencyGraph.vue';
import StateMachineView from '../components/StateMachineView.vue';
import InterruptButton from '../components/InterruptButton.vue';
import { useSessionState } from '../api/sessionState';
import { getOrCreateUserId, rememberSession } from '../utils/session';
import type { SubagentPlanState } from '../types/sessionState';

const route = useRoute();
const router = useRouter();

// User ID resolution
const urlUserId = computed(() => (route.query.userId as string) ?? null);
const userId = computed(() => urlUserId.value || getOrCreateUserId());

// Conversation ID from URL
const conversationId = ref<string | null>((route.query.session as string) ?? null);

// ChatPanel ref for interrupt
const chatPanelRef = ref<InstanceType<typeof ChatPanel> | null>(null);

// Poll state
const { state, refresh: refreshState } = useSessionState(
  computed(() => userId.value),
  conversationId as unknown as import('vue').Ref<string | null | undefined>,
);

// Subagent plan state
const subagentPlans = ref<Record<string, SubagentPlanState>>({});
const subagentTodoCounts = ref<Record<string, number>>({});

// Pending remember (for first send)
const pendingRemember = ref<string | null>(null);

function handleConversationId(id: string) {
  conversationId.value = id;
  const query = { ...route.query, session: id };
  router.replace({ query });
}

function handleUserMessage(text: string) {
  if (conversationId.value) {
    rememberSession(conversationId.value, text);
  } else {
    pendingRemember.value = text;
  }
}

watch(conversationId, (newId) => {
  if (newId && pendingRemember.value) {
    rememberSession(newId, pendingRemember.value);
    pendingRemember.value = null;
  }
});

function handleSubagentPlanChange(plans: Record<string, SubagentPlanState>, counts: Record<string, number>) {
  subagentPlans.value = plans;
  subagentTodoCounts.value = counts;
}

function handleStreamDone() {
  refreshState();
}

async function handleInterrupt() {
  if (chatPanelRef.value) {
    await chatPanelRef.value.interrupt();
  }
  refreshState();
}

const stateColStyle = {
  width: 380, flexShrink: 0, background: '#ffffff',
  borderLeft: '1px solid #e2e8f0', display: 'flex', flexDirection: 'column',
  minHeight: 0, overflowY: 'auto',
};

const stateHeaderStyle = {
  padding: '14px 18px', borderBottom: '1px solid #f1f5f9',
  fontSize: '0.92rem', fontWeight: 600, color: '#0f172a',
  display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
};

const emptyStateStyle = {
  padding: '40px 20px', textAlign: 'center', color: '#94a3b8',
  fontSize: '0.88rem', lineHeight: 1.7,
};

const userIdRowStyle = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '8px 18px', borderBottom: '1px solid #f1f5f9',
  fontSize: '0.78rem', flexShrink: 0,
};

const userIdLabelStyle = {
  color: '#94a3b8', fontWeight: 500,
};

function userIdChipStyle(fromUrl: boolean) {
  return {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.74rem', fontWeight: 600,
    padding: '2px 8px', borderRadius: 4,
    background: fromUrl ? '#ddd6fe' : '#e0e7ff',
    color: fromUrl ? '#5b21b6' : '#3730a3',
    border: fromUrl ? '1px solid #c4b5fd' : '1px solid #c7d2fe',
    cursor: 'help',
  };
}
</script>
