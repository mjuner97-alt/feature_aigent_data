<script setup lang="ts">
/**
 * Skill 管理组件(合并自 SkillList + SkillCard + SkillRow)
 *
 * 职责:
 *  - 网格/列表密度切换(grid/list),状态持久化到 localStorage
 *  - 内联渲染 SkillCard(网格)与 SkillRow(列表)模板,通过 v-if 切换
 *  - 维护 toggleLike(乐观更新 + 失败回滚)与 rankOf(仅 showRank 时显示排名)
 *  - 维度徽章、点赞波纹动画等样式合并
 */
import { ref, watch, computed } from 'vue';
import type { SkillListItem } from '../types/skill';
import { likeSkill, unlikeSkill } from '../api/skill';

const props = withDefaults(
  defineProps<{ items: SkillListItem[]; showRank?: boolean; hideUsed?: boolean }>(),
  { showRank: false, hideUsed: false }
);

const density = ref<'grid' | 'list'>(
  (localStorage.getItem('skill-density') as 'grid' | 'list') || 'grid'
);
watch(density, (d) => localStorage.setItem('skill-density', d));

async function toggleLike(item: SkillListItem) {
  const before = { liked: item.liked, likeCount: item.likeCount };
  item.liked = !item.liked;
  item.likeCount += item.liked ? 1 : -1; // 乐观更新
  try {
    const status = item.liked ? await likeSkill(item.id) : await unlikeSkill(item.id);
    item.likeCount = status.likeCount;
    item.liked = status.liked;
  } catch {
    item.liked = before.liked; // 回滚
    item.likeCount = before.likeCount;
  }
}

function rankOf(it: SkillListItem, i: number): number | null {
  return props.showRank ? (it.rank ?? i + 1) : null;
}

// 卡片徽章(使用/禁用状态)
function badgeClass(it: SkillListItem): string {
  if (it.disabled) return 'badge-disabled';
  if (it.used) return 'badge-used';
  return 'badge-unused';
}
function badgeIcon(it: SkillListItem): string {
  if (it.disabled) return '🚫';
  if (it.used) return '🟢';
  return '⚪';
}
function badgeTitle(it: SkillListItem): string {
  if (it.disabled) return '已禁用';
  if (it.used) return '已使用';
  return '未使用';
}

// 维度徽章
function dimClass(it: SkillListItem): string {
  return `dim-${(it.dimension || 'PERSONAL').toLowerCase()}`;
}
const DIM_LABEL_MAP: Record<string, string> = {
  PERSONAL: '个人',
  GROUP: '小组',
  DEPARTMENT: '部门',
  PRODUCT_LINE: '产品线',
  COMPANY: '杭研',
};
function dimLabel(it: SkillListItem): string {
  return DIM_LABEL_MAP[it.dimension || 'PERSONAL'] || '个人';
}
</script>

<template>
  <div class="toolbar">
    <button :class="{ on: density === 'grid' }" @click="density = 'grid'">▦ 网格</button>
    <button :class="{ on: density === 'list' }" @click="density = 'list'">≡ 列表</button>
  </div>
  <div v-if="items.length === 0" class="empty">暂无 Skill</div>

  <!-- 网格模式:内联 SkillCard 模板 -->
  <div v-else-if="density === 'grid'" class="grid">
    <div
      v-for="it in items"
      :key="it.id"
      class="card"
      :class="{ 'top-3': it.rank != null && it.rank <= 3 }"
    >
      <div class="top">
        <span class="badge" :class="badgeClass(it)" :title="badgeTitle(it)">{{ badgeIcon(it) }}</span>
        <span class="count">👍 {{ it.likeCount }}</span>
      </div>
      <div class="name">{{ it.name }}</div>
      <div class="desc">{{ it.description }}</div>
      <div class="meta">
        {{ it.ownerUserId }}
        <span class="dim-badge" :class="dimClass(it)">{{ dimLabel(it) }}</span>
      </div>
      <div v-if="it.used && !it.disabled && !hideUsed" class="tags">
        <span class="used">已使用</span>
      </div>
      <div v-if="it.disabled" class="tags">
        <span class="disabled-tag">已禁用</span>
      </div>
      <div class="actions">
        <button class="like" :class="{ on: it.liked }" @click="toggleLike(it)">
          <svg class="thumb-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/>
          </svg>
          <span>{{ it.liked ? '已点赞' : '点赞' }}</span>
          <span class="ripple"></span>
        </button>
        <RouterLink :to="`/skills/${it.id}`" class="detail">详情</RouterLink>
      </div>
    </div>
  </div>

  <!-- 列表模式:内联 SkillRow 模板 -->
  <div v-else class="list">
    <div v-for="(it, i) in items" :key="it.id" class="row">
      <span v-if="rankOf(it, i) !== null" class="rank">#{{ rankOf(it, i) }}</span>
      <div class="main">
        <div class="line1">
          <span class="name">{{ it.name }}</span>
          <span class="badge" :class="badgeClass(it)">{{ badgeIcon(it) }}</span>
          <span class="dim-badge" :class="dimClass(it)">{{ dimLabel(it) }}</span>
        </div>
        <div class="line2">
          {{ it.description }} · {{ it.ownerUserId }}
        </div>
      </div>
      <div class="right">
        <span class="count">👍 {{ it.likeCount }}</span>
        <button
          class="like"
          :class="{ on: it.liked }"
          @click="toggleLike(it)"
          :aria-label="it.liked ? '取消点赞' : '点赞'"
        >
          <svg class="thumb-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/>
          </svg>
          <span class="ripple"></span>
        </button>
        <RouterLink :to="`/skills/${it.id}`" class="detail">详情</RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 工具栏(密度切换) ===== */
.toolbar { margin-bottom: 12px; display: flex; gap: 6px; }
button { padding: 4px 10px; border: 1px solid #94a3b8; background: #fff; border-radius: 6px; cursor: pointer; }
button.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.empty { color: #64748b; padding: 24px; text-align: center; }

/* ===== 网格卡片样式 ===== */
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 6px; }
.card.top-3 { border-color: #f59e0b; box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.25); }
.top { display: flex; justify-content: space-between; align-items: center; }
.badge { font-size: 14px; line-height: 1; }
.badge-used { color: #10b981; }
.badge-disabled { color: #ef4444; }
.badge-unused { color: #94a3b8; }
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
.meta { color: #475569; font-size: 12px; }
.dim-badge { margin-left: 4px; padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.dim-personal { background: #f1f5f9; color: #64748b; }
.dim-group { background: #dbeafe; color: #2563eb; }
.dim-department { background: #d1fae5; color: #047857; }
.dim-product_line { background: #fef3c7; color: #b45309; }
.dim-company { background: #ede9fe; color: #6d28d9; }
.tags { display: flex; gap: 4px; flex-wrap: wrap; }
.used { background: #e0f2fe; color: #0284c7; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.disabled-tag { background: #fee2e2; color: #b91c1c; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.actions { display: flex; gap: 8px; align-items: center; margin-top: 4px; }

/* ===== 列表行样式 ===== */
.row { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; }
.rank { color: #f59e0b; font-weight: bold; width: 36px; }
.main { flex: 1; min-width: 0; }
.line1 { display: flex; align-items: center; gap: 8px; }
.row .name {
  font-weight: 700;
  font-size: 17px;
  color: #0f172a;
  background: none;
  -webkit-text-fill-color: initial;
}
.row .dim-badge { margin-left: 0; }
.line2 { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.right { display: flex; align-items: center; gap: 8px; }
.row .detail { font-size: 12px; color: #2563eb; text-decoration: none; }

/* ===== 点赞按钮(网格 + 列表共用,通过 .like 区分样式) ===== */
.card .like {
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
.card .like:hover { border-color: #93c5fd; color: #2563eb; }
.card .like:active { transform: scale(0.94); }
.card .like .thumb-icon { fill: currentColor; transition: fill 0.2s; }
.card .like.on {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}
.card .like.on:active { transform: scale(0.94); }

/* 网格卡片波纹:从图标位置扩散 */
.card .like .ripple {
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
.card .like.on .ripple {
  animation: ripple-card 0.6s ease-out;
}
@keyframes ripple-card {
  0% { width: 0; height: 0; opacity: 0.6; }
  100% { width: 120px; height: 120px; opacity: 0; }
}

.card .detail { font-size: 12px; color: #2563eb; text-decoration: none; }

/* 列表行点赞按钮:圆形 */
.row .like {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
  cursor: pointer;
  transition: background-color 0.2s, color 0.2s, border-color 0.2s, transform 0.1s;
}
.row .like:hover { border-color: #93c5fd; color: #2563eb; }
.row .like:active { transform: scale(0.9); }
.row .like .thumb-icon { fill: currentColor; transition: fill 0.2s; }
.row .like.on {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}
.row .like.on:active { transform: scale(0.9); }

/* 列表行波纹:从中心扩散 */
.row .like .ripple {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 0;
  height: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  pointer-events: none;
}
.row .like.on .ripple {
  animation: ripple-row 0.6s ease-out;
}
@keyframes ripple-row {
  0% { width: 0; height: 0; opacity: 0.6; }
  100% { width: 80px; height: 80px; opacity: 0; }
}
</style>
