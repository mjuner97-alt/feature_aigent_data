<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{ userIds: string; names?: string | null }>();
const contacts = computed(() => {
  const names = props.names?.split('、') ?? [];
  return props.userIds.split(',').map((id, index) => ({
    id: id.trim(),
    name: names[index]?.trim(),
  })).filter(contact => contact.id);
});
</script>

<template>
  <ul class="board-contact-list" aria-label="板块联系人">
    <li v-for="contact in contacts" :key="contact.id" class="board-contact-entry">
      <strong>{{ contact.name || contact.id }}</strong>
      <span v-if="contact.name && contact.name !== contact.id" class="board-contact-id">{{ contact.id }}</span>
    </li>
  </ul>
</template>

<style scoped>
.board-contact-list { display: flex; flex-wrap: wrap; gap: 6px; width: 100%; margin: 0; padding: 0; list-style: none; }
.board-contact-entry {
  display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px;
  box-sizing: border-box; min-width: 0; max-width: 100%; padding: 5px 9px;
  border: 1px solid #dce6f5; border-radius: 8px; background: #f3f6fb;
  color: #253b60; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere;
}
.board-contact-entry strong { font-weight: 600; }
.board-contact-id { color: #52627a; font-size: 12px; }
</style>
