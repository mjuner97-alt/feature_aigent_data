<script setup lang="ts">
import type { SkillListItem } from '../types/skill';
defineProps<{ item: SkillListItem }>();
defineEmits<{ (e: 'like'): void }>();
</script>

<template>
  <div class="card">
    <div class="top">
      <span class="count">👍 {{ item.likeCount }}</span>
    </div>
    <div class="name">{{ item.name }}</div>
    <div class="desc">{{ item.description }}</div>
    <div class="meta">{{ item.ownerUserId }} · {{ item.category || '未分类' }}</div>
    <div class="tags">
      <span v-for="t in (item.tags || '').split(',').filter(Boolean)" :key="t" class="tag">#{{ t }}</span>
      <span v-if="item.used" class="used">已使用</span>
    </div>
    <div class="actions">
      <button class="like" :class="{ on: item.liked }" @click="$emit('like')">
        <svg class="thumb-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/>
        </svg>
        <span>{{ item.liked ? '已点赞' : '点赞' }}</span>
        <span class="ripple"></span>
      </button>
      <RouterLink :to="`/skills/${item.id}`" class="detail">详情</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 6px; }
.top { display: flex; justify-content: flex-end; }
.count { color: #db2777; font-weight: 600; }
.name {
  font-weight: 700;
  font-size: 20px;
  line-height: 1.3;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.desc { color: #64748b; font-size: 13px; min-height: 18px; }
.meta { color: #94a3b8; font-size: 12px; }
.tags { display: flex; gap: 4px; flex-wrap: wrap; }
.tag { background: #f1f5f9; padding: 0 6px; border-radius: 4px; font-size: 11px; color: #475569; }
.used { background: #e0f2fe; color: #0284c7; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.actions { display: flex; gap: 8px; align-items: center; margin-top: 4px; }

.like {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.2s, color 0.2s, border-color 0.2s, transform 0.1s;
}
.like:hover { border-color: #93c5fd; color: #2563eb; }
.like:active { transform: scale(0.94); }
.like .thumb-icon { fill: currentColor; transition: fill 0.2s; }
.like.on {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}
.like.on:active { transform: scale(0.94); }

/* 波纹动画:点赞时从图标位置扩散 */
.like .ripple {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  width: 0;
  height: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  pointer-events: none;
}
.like.on .ripple {
  animation: ripple 0.6s ease-out;
}
@keyframes ripple {
  0% { width: 0; height: 0; opacity: 0.6; }
  100% { width: 120px; height: 120px; opacity: 0; }
}

.detail { font-size: 12px; color: #2563eb; text-decoration: none; }
</style>
