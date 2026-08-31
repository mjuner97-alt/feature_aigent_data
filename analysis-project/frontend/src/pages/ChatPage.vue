<template>
  <div class="chat-page" :style="{ display: 'flex', flex: 1, minHeight: 0 }">
    <!-- Center: chat -->
    <div :style="{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }">
      <ChatPanel
        ref="chatPanelRef"
        :user-id="userId"
        :conversation-id="conversationId"
        @conversation-id="handleConversationId"
        @on-user-message="handleUserMessage"
      />
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ChatPanel from '../components/ChatPanel.vue';
import { getOrCreateUserId, rememberSession } from '../utils/session';
import { getLoggedInUserId } from '../utils/auth';

const route = useRoute();
const router = useRouter();

// User ID resolution: URL ?userId= > 登录态(skill-user-id) > 随机 id 兜底
// 优先用登录 userId,使聊天检索与 skill 引用记录(creator=登录 userId)对齐
const urlUserId = computed(() => (route.query.userId as string) ?? null);
const userId = computed(() => urlUserId.value || getLoggedInUserId() || getOrCreateUserId());

// Conversation ID from URL
const conversationId = ref<string | null>((route.query.session as string) ?? null);

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

</script>

<style scoped>
@media (max-width: 720px) {
  .chat-page { width: 100%; }
}
</style>
