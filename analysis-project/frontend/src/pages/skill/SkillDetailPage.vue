<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getSkill, getLikeStatus, likeSkill, unlikeSkill, referenceSkill, deleteSkill, getReferencers, currentUserId, getSkillPublishes } from '../../api/skill';
import type { SkillDetail, LikeStatus, SkillPublishRecord } from '../../types/skill';

const route = useRoute();
const router = useRouter();
const skill = ref<SkillDetail | null>(null);
const like = ref<LikeStatus>({ liked: false, likeCount: 0 });
const referenced = ref(false);
const referencerCount = ref(0);
const deleting = ref(false);
const deleteError = ref('');

// 维度/发布相关状态(只读展示,维度切换走编辑表单)
const publishes = ref<SkillPublishRecord[]>([]);
const publishLoading = ref(false);
const publishError = ref('');

// 仅所有者可编辑/删除(后端 update/delete 做 owner 校验,前端先门控)
const canManage = computed(() => !!skill.value && skill.value.ownerUserId === currentUserId());

// 维度类型 -> 前缀(COMPANY 特殊处理为空前缀,直接显示 targetName)
const TYPE_LABEL: Record<string, string> = {
  GROUP: '小组',
  DEPARTMENT: '部门',
  PRODUCT_LINE: '产品线',
  COMPANY: '',           // 杭研直接显示,不加前缀
};

// 拼接维度展示文本(如"小组:开发一组"、"杭研")
function formatDimension(p: SkillPublishRecord): string {
  const label = TYPE_LABEL[p.targetType] ?? '';
  return label ? `${label}:${p.targetName}` : p.targetName;
}

// 派生:当前维度标签(取已审批通过的记录)
const dimensionLabel = computed(() => {
  const approved = publishes.value.filter(p => p.status === 'APPROVED');
  if (approved.length === 0) return '个人';
  return approved.map(formatDimension).join('、');
});

// 派生:是否有审批中的申请
const hasPendingPublish = computed(() =>
  publishes.value.some(p => p.status === 'PENDING')
);

async function load() {
  const id = Number(route.params.id);
  skill.value = await getSkill(id);
  like.value = await getLikeStatus(id);
  try {
    const refs = await getReferencers(id);
    referencerCount.value = refs.length;
  } catch {
    referencerCount.value = 0;
  }
  loadPublishes(id);
}

async function loadPublishes(id: number) {
  publishLoading.value = true;
  publishError.value = '';
  try {
    publishes.value = await getSkillPublishes(id);
  } catch (e) {
    publishError.value = e instanceof Error ? e.message : '获取维度信息失败';
  } finally {
    publishLoading.value = false;
  }
}

async function toggleLike() {
  if (!skill.value) return;
  like.value = like.value.liked
    ? await unlikeSkill(skill.value.id)
    : await likeSkill(skill.value.id);
}
async function doReference() {
  if (!skill.value) return;
  await referenceSkill(skill.value.id);
  referenced.value = true;
}
async function doDelete() {
  if (!skill.value || deleting.value) return;
  if (!confirm(`确定删除 Skill "${skill.value.name}"?此操作不可撤销。`)) return;
  deleting.value = true;
  deleteError.value = '';
  try {
    await deleteSkill(skill.value.id);
    router.push('/skills/created');
  } catch (e) {
    deleteError.value = e instanceof Error ? e.message : '删除失败';
  } finally {
    deleting.value = false;
  }
}
watch(() => route.params.id, load, { immediate: true });
</script>

<template>
  <div v-if="skill">
    <h2 class="skill-title">{{ skill.name }} <span class="cnt">👍 {{ like.likeCount }}</span></h2>
    <div class="meta">{{ skill.ownerUserId }} · 状态 {{ skill.status }}</div>
    <!-- 维度展示区(只读,维度切换请走编辑表单) -->
    <div class="dimension-bar">
      <span class="dim-label">维度:</span>
      <span class="dim-value">{{ publishLoading ? '加载中…' : dimensionLabel }}</span>
      <span v-if="hasPendingPublish" class="dim-pending">审批中</span>
    </div>
    <div v-if="publishError" class="dim-error">{{ publishError }}</div>
    <!-- 发布记录列表(折叠) -->
    <details v-if="publishes.length > 0" class="publish-list">
      <summary>发布记录 ({{ publishes.length }})</summary>
      <ul>
        <li v-for="p in publishes" :key="p.id" class="publish-item">
          <span class="p-target">{{ formatDimension(p) }}</span>
          <span class="p-status" :class="p.status.toLowerCase()">{{ p.status }}</span>
          <span class="p-meta">提交人 {{ p.submitter }}</span>
          <span v-if="p.approver" class="p-meta">审批人 {{ p.approver }}</span>
        </li>
      </ul>
    </details>
    <div v-if="canManage" class="manage">
      <RouterLink class="edit" :to="`/skills/${skill.id}/edit`">✎ 编辑</RouterLink>
      <button class="del" :disabled="deleting" @click="doDelete">{{ deleting ? '删除中…' : '🗑 删除' }}</button>
    </div>
    <div v-if="deleteError" class="del-error">{{ deleteError }}</div>
    <div class="actions">
      <button class="like" :class="{ on: like.liked }" @click="toggleLike">
        <svg class="thumb-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/>
        </svg>
        <span>{{ like.liked ? '已点赞' : '点赞' }}</span>
        <span class="ripple"></span>
      </button>
      <button class="ref" :disabled="referenced" @click="doReference">{{ referenced ? '已引用' : '引用' }}</button>
      <span class="referencer-count">被 {{ referencerCount }} 人引用</span>
    </div>
    <section class="block">
      <h3 class="block-title"><span class="bar"></span>描述</h3>
      <p class="desc">{{ skill.description }}</p>
    </section>
    <section class="block">
      <h3 class="block-title"><span class="bar"></span>内容</h3>
      <pre class="content">{{ skill.content }}</pre>
    </section>
  </div>
  <div v-else>加载中…</div>
</template>

<style scoped>
.skill-title {
  font-weight: 700;
  font-size: 24px;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  margin: 0 0 8px;
}
.cnt { -webkit-text-fill-color: initial; color: #db2777; font-size: 16px; }
.meta { color: #94a3b8; margin-bottom: 8px; }
.dimension-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.dim-label { color: #94a3b8; }
.dim-value { font-weight: 600; color: #475569; }
.dim-pending { background: #fef3c7; color: #b45309; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.dim-error { color: #dc2626; font-size: 13px; margin-bottom: 8px; }
.publish-list { margin-bottom: 12px; font-size: 13px; }
.publish-list summary { cursor: pointer; color: #64748b; }
.publish-item { display: flex; gap: 8px; padding: 4px 0; align-items: center; list-style: none; }
.p-target { font-weight: 500; }
.p-status { padding: 0 6px; border-radius: 4px; font-size: 11px; }
.p-status.approved { background: #d1fae5; color: #047857; }
.p-status.pending { background: #fef3c7; color: #b45309; }
.p-status.rejected { background: #fee2e2; color: #b91c1c; }
.p-meta { color: #94a3b8; font-size: 12px; }
.actions { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.referencer-count { font-size: 12px; color: #64748b; padding: 4px 8px; }

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
.like.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.like.on:active { transform: scale(0.94); }

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
.like.on .ripple { animation: ripple 0.6s ease-out; }
@keyframes ripple {
  0% { width: 0; height: 0; opacity: 0.6; }
  100% { width: 120px; height: 120px; opacity: 0; }
}

.ref { padding: 6px 14px; border-radius: 16px; border: 1px solid #cbd5e1; background: #fff; cursor: pointer; font-size: 13px; color: #475569; transition: border-color 0.2s, color 0.2s; }
.ref:hover:not(:disabled) { border-color: #93c5fd; color: #2563eb; }
button:disabled { opacity: 0.6; cursor: not-allowed; }

.manage { display: flex; gap: 8px; margin-bottom: 12px; }
.edit { padding: 6px 14px; border-radius: 16px; border: 1px solid #cbd5e1; background: #fff; font-size: 13px; color: #475569; text-decoration: none; transition: border-color 0.2s, color 0.2s; }
.edit:hover { border-color: #93c5fd; color: #2563eb; }
.del { padding: 6px 14px; border-radius: 16px; border: 1px solid #fecaca; background: #fff; cursor: pointer; font-size: 13px; color: #dc2626; transition: background-color 0.2s, border-color 0.2s; }
.del:hover:not(:disabled) { background: #fef2f2; border-color: #f87171; }
.del-error { color: #dc2626; font-size: 13px; margin-bottom: 8px; }

.block { margin-top: 16px; }
.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 10px;
}
.block-title .bar {
  display: inline-block;
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: linear-gradient(180deg, #6366f1, #3b82f6);
}
.desc {
  margin: 0;
  padding: 12px 14px;
  background: #f8fafc;
  border-left: 3px solid #e2e8f0;
  border-radius: 6px;
  color: #334155;
  font-size: 14px;
  line-height: 1.7;
}
.content {
  background: #0f172a;
  color: #e2e8f0;
  padding: 14px 16px;
  border-radius: 8px;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  border: 1px solid #1e293b;
}
</style>
