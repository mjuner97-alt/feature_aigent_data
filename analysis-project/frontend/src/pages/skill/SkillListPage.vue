<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import SkillList from '../../components/SkillList.vue';
import { listSkills } from '../../api/skill';
import type { SkillListItem } from '../../types/skill';

const props = withDefaults(defineProps<{
  view: 'all' | 'used' | 'liked' | 'created' | 'popular';
  showRank?: boolean;
}>(), { showRank: false });

const items = ref<SkillListItem[]>([]);
const sort = ref<'likes' | 'updated' | 'name'>('likes');
const keyword = ref('');
const dimension = ref('');
const dimensions = [
  { value: 'PERSONAL', label: '个人' },
  { value: 'GROUP', label: '小组' },
  { value: 'DEPARTMENT', label: '部门' },
  { value: 'PRODUCT_LINE', label: '产品线' },
  { value: 'COMPANY', label: '公司级' },
];

const title = computed(() => ({
  all: '全部 Skill', used: '我使用的 Skill', liked: '我点赞的 Skill',
  created: '我创建的 Skill', popular: '热门榜',
}[props.view]));

const emptyHint = computed(() => ({
  all: '暂无 Skill',
  used: '浏览全部 Skill,引用你需要的',
  liked: '去全部 Skill 找找感兴趣的',
  created: '创建你的第一个 Skill',
  popular: '暂无热门 Skill',
}[props.view]));

async function load() {
  items.value = await listSkills({
    view: props.view, sort: sort.value,
    keyword: keyword.value || undefined,
    dimension: dimension.value || undefined,
  });
}
watch([sort, dimension, () => props.view], load, { immediate: true });
</script>

<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load" />
    <select v-model="dimension">
      <option value="">全部维度</option>
      <option v-for="d in dimensions" :key="d.value" :value="d.value">{{ d.label }}</option>
    </select>
    <select v-model="sort">
      <option value="likes">点赞最多</option>
      <option value="updated">最新更新</option>
      <option value="name">名称</option>
    </select>
    <RouterLink v-if="view === 'created'" class="create" to="/skills/new">＋ 创建 Skill</RouterLink>
  </div>
  <SkillList :items="items" :show-rank="showRank" :hide-used="view === 'used'" />
  <div v-if="items.length === 0" class="empty-hint">{{ emptyHint }}</div>
</template>

<style scoped>
.bar { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
input, select { padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; color: #1e293b; }
select { font-weight: 500; cursor: pointer; }
select:hover { border-color: #93c5fd; }
.create { margin-left: auto; padding: 4px 12px; border-radius: 6px; background: #3b82f6; color: #fff; text-decoration: none; font-size: 13px; align-self: center; }
.create:hover { background: #2563eb; }
h2 { margin: 0 0 8px; }
.empty-hint { color: #94a3b8; font-size: 14px; text-align: center; padding: 32px 0; }
</style>
