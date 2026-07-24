<script setup lang="ts">
import { ref, watch } from 'vue';
import type { SkillListItem } from '../types/skill';
import SkillCard from './SkillCard.vue';
import SkillRow from './SkillRow.vue';
import { likeSkill, unlikeSkill } from '../api/skill';

const props = defineProps<{ items: SkillListItem[]; showRank?: boolean }>();

const density = ref<'grid' | 'list'>(
  (localStorage.getItem('skill-density') as 'grid' | 'list') || 'grid');
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
</script>

<template>
  <div class="toolbar">
    <button :class="{ on: density === 'grid' }" @click="density = 'grid'">▦ 网格</button>
    <button :class="{ on: density === 'list' }" @click="density = 'list'">≡ 列表</button>
  </div>
  <div v-if="items.length === 0" class="empty">暂无 Skill</div>
  <div v-else-if="density === 'grid'" class="grid">
    <SkillCard v-for="it in items" :key="it.id" :item="it" @like="toggleLike(it)" />
  </div>
  <div v-else class="list">
    <SkillRow v-for="(it, i) in items" :key="it.id" :item="it" :rank="rankOf(it, i)" @like="toggleLike(it)" />
  </div>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 6px; }
button { padding: 4px 10px; border: 1px solid #94a3b8; background: #fff; border-radius: 6px; cursor: pointer; }
button.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.empty { color: #64748b; padding: 24px; text-align: center; }
</style>
