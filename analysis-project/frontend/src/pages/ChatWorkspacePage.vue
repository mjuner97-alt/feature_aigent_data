<template>
  <div class="chat-workspace">
    <div class="chat-content"><ChatPage /></div>
  </div>
</template>

<script setup lang="ts">
import ChatPage from './ChatPage.vue';
</script>

<style scoped>
.chat-workspace,
.chat-content {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.chat-workspace {
  flex-direction: column;
  background: #f8fafc;
}
</style>
