<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  getSkill,
  getSkillDraft,
  listPendingPublishes,
  approveDraft,
  rejectDraft,
  approvePublish,
  rejectPublish,
} from '../../api/skill';
import type { SkillDetail, SkillDraft, PublishPendingItem } from '../../types/skill';

const route = useRoute();
const router = useRouter();
const skill = ref<SkillDetail | null>(null);
const draft = ref<SkillDraft | null>(null);
const publishPending = ref<PublishPendingItem | null>(null);

const loading = ref(false);
const error = ref('');

const comment = ref('');
const actionLoading = ref(false);
const actionError = ref('');
const actionDone = ref('');

const skillId = computed(() => Number(route.params.id));

async function load() {
  const id = skillId.value;
  if (!id) return;
  loading.value = true;
  error.value = '';
  skill.value = null;
  draft.value = null;
  publishPending.value = null;
  try {
    skill.value = await getSkill(id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载 Skill 失败';
    loading.value = false;
    return;
  }
  // 并行加载草稿与发布待审列表(失败不阻塞)
  const results = await Promise.allSettled([getSkillDraft(id), listPendingPublishes()]);
  if (results[0].status === 'fulfilled') {
    draft.value = results[0].value;
  }
  if (results[1].status === 'fulfilled') {
    publishPending.value = results[1].value.find((p) => p.skillId === id) ?? null;
  }
  loading.value = false;
}

const hasPending = computed(() => {
  return (!!draft.value && draft.value.status === 'PENDING') || !!publishPending.value;
});

async function doApprove() {
  const id = skillId.value;
  if (actionLoading.value) return;
  actionLoading.value = true;
  actionError.value = '';
  actionDone.value = '';
  try {
    if (publishPending.value) {
      await approvePublish(publishPending.value.id, comment.value || '通过');
      actionDone.value = '已通过该发布审批';
    } else if (draft.value) {
      await approveDraft(id, comment.value || '通过');
      actionDone.value = '已通过该草稿审批';
    } else {
      actionError.value = '无可审批条目';
      return;
    }
    comment.value = '';
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '审批通过失败';
  } finally {
    actionLoading.value = false;
  }
}

async function doReject() {
  const id = skillId.value;
  if (actionLoading.value) return;
  if (!comment.value.trim()) {
    actionError.value = '退回请填写原因';
    return;
  }
  actionLoading.value = true;
  actionError.value = '';
  actionDone.value = '';
  try {
    if (publishPending.value) {
      await rejectPublish(publishPending.value.id, comment.value);
      actionDone.value = '已退回该发布审批';
    } else if (draft.value) {
      await rejectDraft(id, comment.value);
      actionDone.value = '已退回该草稿审批';
    } else {
      actionError.value = '无可审批条目';
      return;
    }
    comment.value = '';
    await load();
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '审批退回失败';
  } finally {
    actionLoading.value = false;
  }
}

watch(() => route.params.id, load, { immediate: true });
</script>

<template>
  <div class="approval-detail">
    <button class="back" @click="router.push('/skills/approvals')">← 返回审批列表</button>

    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="error" class="error">{{ error }}</div>

    <template v-else-if="skill">
      <h2 class="page-title">
        审批详情
        <span v-if="publishPending" class="type-badge publish">发布审批</span>
        <span v-else-if="draft && draft.status === 'PENDING'" class="type-badge draft">草稿审批</span>
      </h2>

      <!-- Skill 信息 -->
      <section class="block">
        <h3 class="block-title"><span class="bar"></span>Skill 信息</h3>
        <div class="info">
          <div class="info-row"><label>名称</label><span>{{ skill.name }}</span></div>
          <div class="info-row"><label>所有者</label><span>{{ skill.ownerUserId }}</span></div>
          <div class="info-row"><label>状态</label><span>{{ skill.status }}</span></div>
          <div class="info-row"><label>分类</label><span>{{ skill.category || '未分类' }}</span></div>
          <div class="info-row"><label>标签</label><span>{{ skill.tags }}</span></div>
          <div class="info-row"><label>更新时间</label><span>{{ skill.updatedAt }}</span></div>
        </div>
      </section>

      <!-- 发布待审详情 -->
      <section v-if="publishPending" class="block">
        <h3 class="block-title"><span class="bar"></span>待审发布内容</h3>
        <div class="info">
          <div class="info-row"><label>提交人</label><span>{{ publishPending.createdBy }}</span></div>
          <div class="info-row"><label>提交时间</label><span>{{ publishPending.createdAt }}</span></div>
          <div class="info-row"><label>名称</label><span>{{ publishPending.name }}</span></div>
          <div class="info-row"><label>分类</label><span>{{ publishPending.category || '未分类' }}</span></div>
          <div class="info-row"><label>描述</label><span>{{ publishPending.description }}</span></div>
        </div>
        <div class="content-label">内容</div>
        <pre class="content">{{ publishPending.content }}</pre>
      </section>

      <!-- 草稿待审详情 -->
      <section v-else-if="draft && draft.status === 'PENDING'" class="block">
        <h3 class="block-title"><span class="bar"></span>待审草稿内容</h3>
        <div class="info">
          <div class="info-row"><label>提交人</label><span>{{ draft.createdBy }}</span></div>
          <div class="info-row"><label>提交时间</label><span>{{ draft.createdAt }}</span></div>
          <div class="info-row"><label>名称</label><span>{{ draft.name }}</span></div>
          <div class="info-row"><label>分类</label><span>{{ draft.category || '未分类' }}</span></div>
          <div class="info-row"><label>描述</label><span>{{ draft.description }}</span></div>
        </div>
        <div class="content-label">内容</div>
        <pre class="content">{{ draft.content }}</pre>
      </section>

      <div v-else class="no-pending">该 Skill 当前无待审批条目。</div>

      <!-- 审批操作 -->
      <section v-if="hasPending" class="block">
        <h3 class="block-title"><span class="bar"></span>审批操作</h3>
        <div class="approval-actions">
          <textarea v-model="comment" class="comment-input" placeholder="审批意见(退回必填)" rows="3"></textarea>
          <div class="btns">
            <button class="approve" :disabled="actionLoading" @click="doApprove">通过</button>
            <button class="reject" :disabled="actionLoading" @click="doReject">退回</button>
          </div>
        </div>
        <div v-if="actionError" class="action-error">{{ actionError }}</div>
        <div v-if="actionDone" class="action-done">{{ actionDone }}</div>
      </section>
    </template>

    <div v-else class="loading">加载中…</div>
  </div>
</template>

<style scoped>
.approval-detail { max-width: 900px; }
.back {
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #475569;
  margin-bottom: 12px;
}
.back:hover { border-color: #93c5fd; color: #2563eb; }

.page-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  font-size: 24px;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  margin: 0 0 16px;
}
.type-badge {
  -webkit-text-fill-color: initial;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}
.type-badge.publish { background: #dbeafe; color: #1d4ed8; }
.type-badge.draft { background: #fef3c7; color: #b45309; }

.loading, .error { color: #94a3b8; font-size: 14px; padding: 24px 0; }
.error { color: #dc2626; }

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

.info { display: flex; flex-direction: column; gap: 6px; }
.info-row { display: flex; gap: 8px; font-size: 13px; }
.info-row label { width: 80px; color: #94a3b8; flex-shrink: 0; }
.info-row span { color: #334155; }

.content-label { font-size: 13px; color: #94a3b8; margin: 10px 0 6px; }
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
  margin: 0;
}

.no-pending {
  margin-top: 16px;
  padding: 12px 14px;
  border-radius: 6px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
}

.approval-actions { display: flex; flex-direction: column; gap: 10px; }
.comment-input {
  width: 100%;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  font-size: 13px;
  outline: none;
  resize: vertical;
  box-sizing: border-box;
  font-family: inherit;
}
.comment-input:focus { border-color: #6366f1; }
.btns { display: flex; gap: 10px; }
.approve {
  padding: 8px 24px;
  border-radius: 6px;
  border: 1px solid #16a34a;
  background: #16a34a;
  color: #fff;
  cursor: pointer;
  font-size: 13px;
}
.approve:hover:not(:disabled) { background: #15803d; }
.reject {
  padding: 8px 24px;
  border-radius: 6px;
  border: 1px solid #dc2626;
  background: #fff;
  color: #dc2626;
  cursor: pointer;
  font-size: 13px;
}
.reject:hover:not(:disabled) { background: #fef2f2; }
.btns button:disabled { opacity: 0.6; cursor: not-allowed; }
.action-error { color: #dc2626; font-size: 13px; margin-top: 8px; }
.action-done { color: #16a34a; font-size: 13px; margin-top: 8px; }
</style>
