<script setup lang="ts">
/**
 * Skill 列表页(合并 SkillFormPage 的创建/编辑表单为内联 Drawer)
 *
 * 职责:
 *  - 按 view(all/used/liked/created/popular)展示 Skill 列表,含搜索/筛选/排序/分页
 *  - 创建/编辑 Skill 走内联 Drawer(原 SkillFormPage 逻辑),不再跳转独立路由
 *  - 维度选择使用 DimensionCascader,保存后提交发布申请走审批流
 */
import { ref, watch, computed } from 'vue';
import { useRouter } from 'vue-router';
import SkillManage from '../../components/SkillManage.vue';
import DimensionCascader from '../../components/DimensionCascader.vue';
import {
  listSkills,
  getSkill,
  createSkill,
  updateSkill,
  currentUserId,
  getPublishTargets,
  submitPublish,
  getSkillPublishes,
} from '../../api/skill';
import type { SkillListItem, SkillInput, PublishTargetGroup } from '../../types/skill';

const router = useRouter();

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

// ============ 创建/编辑表单(内联 Drawer,原 SkillFormPage 逻辑) ============
const drawerOpen = ref(false);
const editId = ref<number | null>(null);
const isEdit = computed(() => editId.value != null);

const form = ref<SkillInput>({ name: '', description: '', content: '' });
const formLoading = ref(false);
const saving = ref(false);
const formError = ref('');
const notOwner = ref(false);

// 维度多选(创建/编辑时选择目标维度,保存后提交发布申请走审批流)
const publishTargetGroups = ref<PublishTargetGroup[]>([]);
// 已选中的维度目标,key 格式 "orgType:orgId",支持同维度多选
const selectedTargets = ref<Set<string>>(new Set());
// 编辑模式下已存在的发布记录(用于展示当前维度状态)
const existingDimensionLabel = ref('个人');
// 维度提交结果反馈
const publishResult = ref('');

// 首次打开表单时加载可选发布目标(全局只加载一次)
const targetsLoaded = ref(false);
async function ensurePublishTargets() {
  if (targetsLoaded.value) return;
  try {
    publishTargetGroups.value = await getPublishTargets();
  } catch {
    // 获取失败不阻塞表单,但维度选择区会空
  }
  targetsLoaded.value = true;
}

// 打开创建表单
function openCreate() {
  editId.value = null;
  form.value = { name: '', description: '', content: '' };
  formError.value = '';
  publishResult.value = '';
  notOwner.value = false;
  existingDimensionLabel.value = '个人';
  selectedTargets.value = new Set();
  drawerOpen.value = true;
  ensurePublishTargets();
}

// 打开编辑表单:加载已有 Skill 数据
async function openEdit(id: number) {
  editId.value = id;
  form.value = { name: '', description: '', content: '' };
  formError.value = '';
  publishResult.value = '';
  notOwner.value = false;
  existingDimensionLabel.value = '个人';
  selectedTargets.value = new Set();
  drawerOpen.value = true;
  ensurePublishTargets();

  formLoading.value = true;
  try {
    const s = await getSkill(id);
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
    };
    // 后端 update 做 owner 校验,前端先挡一道:非所有者只读
    if (s.ownerUserId !== currentUserId()) notOwner.value = true;
    // 加载已有发布记录,展示当前维度
    try {
      const publishes = await getSkillPublishes(id);
      const approved = publishes.filter(p => p.status === 'APPROVED');
      if (approved.length > 0) {
        const TYPE_LABEL: Record<string, string> = {
          GROUP: '小组', DEPARTMENT: '部门', PRODUCT_LINE: '产品线', COMPANY: '',
        };
        existingDimensionLabel.value = approved.map(p => {
          const label = TYPE_LABEL[p.targetType] ?? '';
          // targetName 已是组织显示名称(如"开发一组"),按维度类型拼接一次前缀即可
          return label ? `${label}:${p.targetName}` : p.targetName;
        }).join('、');
      }
    } catch {
      // 维度信息加载失败不阻塞
    }
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    formLoading.value = false;
  }
}

async function submit() {
  formError.value = '';
  publishResult.value = '';
  if (!form.value.name.trim()) {
    formError.value = '请填写 Skill 名称';
    return;
  }
  saving.value = true;
  try {
    let skillId: number;
    if (editId.value != null) {
      await updateSkill(editId.value, form.value);
      skillId = editId.value;
    } else {
      const created = await createSkill(form.value);
      skillId = created.id;
    }
    // 保存成功后,提交维度发布申请(如有选择)
    if (selectedTargets.value.size > 0) {
      const allTargets = publishTargetGroups.value.flatMap(g => g.targets);
      const targets = allTargets.filter(t => selectedTargets.value.has(`${t.orgType}:${t.orgId}`));
      const failed: string[] = [];
      for (const t of targets) {
        try {
          // 存储组织显示名称(如"开发一组"),不含维度前缀,展示时再按维度类型拼接
          await submitPublish(skillId, t.orgType, t.orgId, t.displayName);
        } catch (e) {
          const msg = e instanceof Error ? e.message : '失败';
          failed.push(`${t.displayName}:${msg}`);
        }
      }
      if (failed.length > 0) {
        publishResult.value = `Skill 已保存,但部分维度申请提交失败:${failed.join('; ')}`;
      } else {
        publishResult.value = `Skill 已保存,${targets.length} 条维度发布申请已提交(待审批)。`;
      }
    }
    // 无 publishResult 或无失败时,刷新列表并跳转详情;有反馈时停留让用户看到
    if (!publishResult.value || !publishResult.value.includes('失败')) {
      // 刷新当前列表以反映新增/修改
      load(true);
      setTimeout(() => {
        drawerOpen.value = false;
        router.push(`/skills/${skillId}`);
      }, 1200);
    }
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function closeDrawer() {
  drawerOpen.value = false;
}
</script>

<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load(true)" />
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

  <!-- 创建/编辑表单 Drawer(内联,替代原 SkillFormPage) -->
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="drawerOpen" class="drawer-mask" @click.self="closeDrawer">
        <div class="drawer">
          <div class="drawer-header">
            <h3>{{ isEdit ? '编辑 Skill' : '创建 Skill' }}</h3>
            <button class="drawer-close" @click="closeDrawer" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
            <div v-if="formLoading" class="loading">加载中…</div>
            <div v-else>
              <div v-if="notOwner" class="warn">⚠ 你不是此 Skill 的所有者,仅可查看,无法保存修改。</div>
              <form class="form" @submit.prevent="submit">
                <label class="field">
                  <span class="label">名称 *</span>
                  <input v-model="form.name" :disabled="notOwner" placeholder="如 query_q1_quality" />
                </label>
                <label class="field">
                  <span class="label">描述</span>
                  <textarea v-model="form.description" :disabled="notOwner" rows="2" placeholder="一句话说明这个 Skill 做什么" />
                </label>
                <label class="field">
                  <span class="label">内容</span>
                  <textarea v-model="form.content" :disabled="notOwner" rows="12" class="content" placeholder="SKILL.md 正文(Markdown)" />
                </label>
                <!-- 维度级联选择器 -->
                <div class="field">
                  <span class="label">维度标签</span>
                  <div v-if="isEdit && existingDimensionLabel !== '个人'" class="dim-current">
                    当前维度:{{ existingDimensionLabel }}(下方新选维度将追加提交审批)
                  </div>
                  <DimensionCascader
                    v-model="selectedTargets"
                    :groups="publishTargetGroups"
                    :disabled="notOwner"
                  />
                  <div class="dim-tip">不选 = 个人维度(仅自己可见)。维度间互斥,只能选一种维度类型(如选了"小组"就不能再选"部门"),同维度内可多选。提交后各维度独立走审批。</div>
                </div>
                <div v-if="formError" class="error">{{ formError }}</div>
                <div v-if="publishResult" class="publish-result">{{ publishResult }}</div>
              </form>
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="closeDrawer">取消</button>
            <button type="button" class="btn primary" :disabled="saving || notOwner || formLoading" @click="submit">
              {{ saving ? '保存中…' : (isEdit ? '保存' : '创建') }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
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

/* ===== Drawer 表单样式 ===== */
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 1000;
}
.drawer {
  width: 520px;
  max-width: 90vw;
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12);
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #e2e8f0;
}
.drawer-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
.drawer-close {
  border: none;
  background: transparent;
  font-size: 24px;
  line-height: 1;
  color: #64748b;
  cursor: pointer;
  padding: 0 4px;
  border-radius: 4px;
}
.drawer-close:hover { background: #f1f5f9; color: #0f172a; }
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}
.drawer-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #e2e8f0;
}
.loading { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }

.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.form input, .form select, .form textarea {
  padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px;
  font-size: 14px; font-family: inherit; background: #fff;
}
.form textarea { resize: vertical; }
.form .content { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
.form input:disabled, .form select:disabled, .form textarea:disabled { background: #f1f5f9; color: #94a3b8; }
.warn { background: #fef3c7; border: 1px solid #f59e0b; color: #92400e; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
.error { color: #dc2626; font-size: 13px; }
.publish-result { color: #047857; font-size: 13px; background: #d1fae5; padding: 8px 12px; border-radius: 6px; }
.dim-current { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 6px 10px; border-radius: 6px; margin-bottom: 6px; }
.dim-tip { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }

/* Drawer 进出动画 */
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
