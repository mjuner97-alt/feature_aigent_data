<script setup lang="ts">
/**
 * Skill 列表页
 *
 * 职责:
 *  - 按 view(all/used/liked/created/popular)展示 Skill 列表,含搜索/筛选/排序/分页
 *  - 创建 Skill 走 /skills/new 全页面表单
 *  - 编辑入口在详情页 /skills/:id/edit
 */
import { ref, watch, computed } from 'vue';
import { useRouter } from 'vue-router';
import SkillManage from '../../components/SkillManage.vue';
import { listSkills } from '../../api/skill';
import type { SkillListItem } from '../../types/skill';

const router = useRouter();

const props = withDefaults(defineProps<{
  view: 'all' | 'used' | 'liked' | 'created' | 'popular';
  showRank?: boolean;
}>(), { showRank: false });

const items = ref<SkillListItem[]>([]);
const sort = ref<'likes' | 'updated' | 'name'>('likes');
const keyword = ref('');
const owner = ref('');
const dimension = ref('');
const dimensions = [
  { value: 'PERSONAL', label: '个人' },
  { value: 'GROUP', label: '小组' },
  { value: 'DEPARTMENT', label: '部门' },
  { value: 'PRODUCT_LINE', label: '产品线' },
  { value: 'COMPANY', label: '杭研' },
];

// 分页:采用"加载更多"模式,每次追加一页,后端默认每页 20 条
const PAGE_SIZE = 20;
const POPULAR_TOP = 10; // 热门榜只展示 Top 10
const page = ref(0);
const loading = ref(false);
const hasMore = ref(true);

// 热门榜固定 Top 10、不显示加载更多
const isPopular = computed(() => props.view === 'popular');
const pageSize = computed(() => isPopular.value ? POPULAR_TOP : PAGE_SIZE);

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

// 展示层过滤:dimension 在前端过滤(后端已不做,避免破坏 SQL 分页)
const visibleItems = computed(() => {
  if (!dimension.value) return items.value;
  return items.value.filter(it => it.dimension === dimension.value);
});

async function load(reset = false) {
  if (loading.value) return;
  if (reset) {
    page.value = 0;
    hasMore.value = true;
  }
  loading.value = true;
  try {
    const size = pageSize.value;
    const offset = page.value * size;
    const batch = await listSkills({
      view: props.view, sort: sort.value,
      keyword: keyword.value || undefined,
      dimension: dimension.value || undefined,
      owner: owner.value || undefined,
      limit: size,
      offset,
    });
    if (reset) {
      items.value = batch;
    } else {
      items.value = [...items.value, ...batch];
    }
    // 返回不足一页 -> 没有更多了;热门榜固定 Top 10 不分页
    hasMore.value = !isPopular.value && batch.length >= size;
    if (hasMore.value) page.value++;
  } finally {
    loading.value = false;
  }
}

// 筛选/排序/视图变更 -> 重置分页并重新加载
watch([sort, dimension, () => props.view], () => load(true), { immediate: true });

// 创建 Skill -> 跳转全页面表单
function openCreate() {
  router.push('/skills/new');
}
</script>

<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load(true)" />
    <input v-if="view === 'all'" v-model="owner" placeholder="按创建人筛选..." @keyup.enter="load(true)" />
    <select v-model="dimension">
      <option value="">全部维度</option>
      <option v-for="d in dimensions" :key="d.value" :value="d.value">{{ d.label }}</option>
    </select>
    <select v-model="sort">
      <option value="likes">点赞最多</option>
      <option value="updated">最新更新</option>
      <option value="name">名称</option>
    </select>
    <button class="create" @click="openCreate">＋ 创建 Skill</button>
  </div>
  <SkillManage :items="visibleItems" :show-rank="showRank" :hide-used="view === 'used'" />
  <div v-if="visibleItems.length === 0" class="empty-hint">{{ emptyHint }}</div>
  <div v-if="hasMore && visibleItems.length > 0" class="load-more">
    <button :disabled="loading" @click="load(false)">
      {{ loading ? '加载中…' : '加载更多' }}
    </button>
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
input, select { padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; color: #1e293b; }
select { font-weight: 500; cursor: pointer; }
select:hover { border-color: #93c5fd; }
.create { margin-left: auto; padding: 4px 12px; border-radius: 6px; background: #3b82f6; color: #fff; border: 1px solid #3b82f6; text-decoration: none; font-size: 13px; align-self: center; cursor: pointer; transition: background-color 0.2s; }
.create:hover { background: #2563eb; }
h2 { margin: 0 0 8px; }
.empty-hint { color: #94a3b8; font-size: 14px; text-align: center; padding: 32px 0; }
.load-more { text-align: center; padding: 16px 0; }
.load-more button { padding: 6px 24px; border: 1px solid #cbd5e1; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; color: #475569; transition: border-color 0.2s, color 0.2s; }
.load-more button:hover:not(:disabled) { border-color: #93c5fd; color: #2563eb; }
.load-more button:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
