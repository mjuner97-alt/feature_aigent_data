<script setup lang="ts">
defineProps<{
  to: string[];
  cc: string[];
  names: Record<string, string>;
  emptyTo?: string;
}>();
</script>

<template>
  <div v-for="group in (['to', 'cc'] as const)" :key="group" class="recipient-group">
    <span class="recipient-group-label">
      {{ group === 'to' ? '收件人' : '抄送人' }} <small>{{ group.toUpperCase() }}</small>
    </span>
    <div class="recipient-people">
      <span v-for="id in $props[group]" :key="id" class="recipient-person" :class="{ 'is-cc': group === 'cc' }">
        {{ names[id] && names[id] !== id ? `${names[id]}（${id}）` : id }}
      </span>
      <small v-if="!$props[group].length">{{ group === 'to' ? (emptyTo ?? '暂无收件人') : '暂无抄送人' }}</small>
    </div>
  </div>
</template>

<style scoped>
.recipient-group {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  min-width: 0;
}
small {
  color: #7b8ba1;
  font-size: 12px;
}
.recipient-group-label {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
  line-height: 28px;
}
.recipient-group-label small {
  font-size: 10px;
  font-weight: 400;
  margin-left: 4px;
}
.recipient-people {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  min-height: 28px;
  align-items: center;
}
.recipient-person {
  box-sizing: border-box;
  max-width: 100%;
  min-width: 0;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid #d9e7fc;
  background: #eaf2ff;
  color: #315f9c;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.recipient-person.is-cc {
  background: #f1f5f9;
  border-color: #e2e8f0;
  color: #52647d;
}
@media (max-width: 700px) {
  .recipient-group {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
