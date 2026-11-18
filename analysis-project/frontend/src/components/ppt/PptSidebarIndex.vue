<script setup lang="ts">
/** 反馈事项侧栏目录，用处理状态区分已反馈和待反馈页面。 */

import type { PptFinding } from '../../types/pptAnomaly';

defineProps<{ pages: PptFinding[]; selectedId?: number }>();
const emit = defineEmits<{ select: [PptFinding] }>();

/** 根据事项处理状态判断是否已经提交反馈。 */
function hasFeedback(finding: PptFinding) {
  return finding.status === 'CONFIRMED' || finding.status === 'ADJUSTED';
}
</script>

<template>
  <aside class="ppt-sidebar">
    <header class="sidebar-heading">
      <div class="heading-row">
        <slot name="filters"><strong>异常目录</strong></slot>
      </div>
      <div class="legend">
        <span class="reviewed">蓝色 · 已反馈</span>
        <span class="unreviewed">红色 · 未反馈</span>
      </div>
    </header>
    <div class="sidebar-items">
      <button v-for="page in pages" :key="page.id" class="ppt-index-item"
        :class="[hasFeedback(page) ? 'reviewed' : 'unreviewed', { active: page.id === selectedId }]"
        :aria-current="page.id === selectedId ? 'true' : undefined"
        :aria-label="`${hasFeedback(page) ? '已反馈' : '未反馈'}，P${page.pageNo} ${page.title || '未命名页面'}`"
        @click="emit('select', page)">
        <span class="page-number">P{{ page.pageNo }}</span>
        <span class="item-text">{{ page.title || '未命名页面' }}</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.ppt-sidebar { flex: 0 0 clamp(212px, 25%, 280px); min-width: 0; display: flex; flex-direction: column; border-right: 1px solid #e6e8ef; background: #fafbfe; }
.sidebar-heading { flex-shrink: 0; padding: 12px; }
.sidebar-items { min-height: 0; overflow: auto; }
.heading-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.legend { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 8px; font-size: 12px; }
.unreviewed { color: #b42318; }
.reviewed { color: #1757b8; }
.ppt-index-item { width: 100%; display: flex; align-items: baseline; gap: 8px; text-align: left; border: 0; border-top: 1px solid #edf0f5; background: transparent; padding: 10px 12px; cursor: pointer; font: inherit; font-size: 14px; line-height: 1.45; }
.ppt-index-item:hover { background: #f0f3f8; }
.ppt-index-item.active { background: #edf1f7; box-shadow: inset 3px 0 currentColor; }
.item-text { min-width: 0; overflow-wrap: anywhere; }
.page-number { flex-shrink: 0; padding: 1px 5px; border: 1px solid currentColor; border-radius: 4px; font-size: 11px; line-height: 1.35; }
</style>
