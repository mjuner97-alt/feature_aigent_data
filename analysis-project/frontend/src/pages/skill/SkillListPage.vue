<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import SkillList from '../../components/SkillList.vue';
import { listSkills } from '../../api/skill';
import type { SkillListItem } from '../../types/skill';

const props = withDefaults(defineProps<{
  view: 'all' | 'used' | 'liked' | 'created' | 'popular';
  showRank?: boolean;
  allowCategory?: boolean;
}>(), { showRank: false, allowCategory: false });

const items = ref<SkillListItem[]>([]);
const sort = ref<'likes' | 'updated' | 'name'>('likes');
const category = ref('');
const keyword = ref('');
const categories = ['数据', '办公', '研发', '业务'];

const title = computed(() => ({
  all: '全部 Skill', used: '我使用的 Skill', liked: '我点赞的 Skill',
  created: '我创建的 Skill', popular: '热门榜',
}[props.view]));

async function load() {
  items.value = await listSkills({
    view: props.view, sort: sort.value,
    category: category.value || undefined,
    keyword: keyword.value || undefined,
  });
}
watch([sort, category, () => props.view], load, { immediate: true });
</script>

<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load" />
    <select v-if="allowCategory" v-model="category">
      <option value="">全部分类</option>
      <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
    </select>
    <select v-model="sort">
      <option value="likes">点赞最多</option>
      <option value="updated">最新更新</option>
      <option value="name">名称</option>
    </select>
    <RouterLink v-if="view === 'created'" class="create" to="/skills/new">＋ 创建 Skill</RouterLink>
  </div>
  <SkillList :items="items" :show-rank="showRank" />
</template>

<style scoped>
.bar { display: flex; gap: 8px; margin-bottom: 8px; }
input, select { padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 6px; }
.create { margin-left: auto; padding: 4px 12px; border-radius: 6px; background: #3b82f6; color: #fff; text-decoration: none; font-size: 13px; align-self: center; }
.create:hover { background: #2563eb; }
h2 { margin: 0 0 8px; }
</style>
