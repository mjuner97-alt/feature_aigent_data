<script setup lang="ts">
/**
 * Skill 审批列表页(独立页面,从详情页迁移回来)
 *
 * 职责:
 *  - 待审批 / 已审批 两个子 tab,各自带数量徽章
 *  - 待审批项支持内联通过/退回操作(不必跳转详情)
 *  - 点击列表项跳转对应 Skill 详情
 */
import { ref, watch, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import {
  listPendingPublishes,
  listApprovedPublishes,
  approvePublish,
  rejectPublish,
} from '../../api/skill';
import type { PublishPendingItem } from '../../types/skill';

const router = useRouter();

const tab = ref<'pending' | 'approved'>('pending');
const pendingList = ref<PublishPendingItem[]>([]);
const approvedList = ref<PublishPendingItem[]>([]);
const loading = ref(false);
const errorMsg = ref('');

// 内联审批操作状态(按 publishId 索引)
const actionLoading = ref<Set<number>>(new Set());
const actionError = ref<Record<number, string>>({});
const actionDone = ref<Record<number, string>>({});
const commentMap = ref<Record<number, string>>({});

async function load() {
  loading.value = true;
  errorMsg.value = '';
  const results = await Promise.allSettled([listPendingPublishes(), listApprovedPublishes()]);
  if (results[0].status === 'fulfilled') {
    pendingList.value = results[0].value;
  } else {
    errorMsg.value = '待审批列表加载失败';
  }
  if (results[1].status === 'fulfilled') {
    approvedList.value = results[1].value;
  } else {
    errorMsg.value = (errorMsg.value ? errorMsg.value + ';' : '') + '已审批列表加载失败';
  }
  loading.value = false;
}

function goDetail(item: PublishPendingItem) {
  router.push(`/skills/${item.skillId}`);
}

function getComment(id: number): string {
  return commentMap.value[id] ?? '';
}
function setComment(id: number, v: string) {
  commentMap.value[id] = v;
}

async function doApprove(item: PublishPendingItem) {
  if (actionLoading.value.has(item.id)) return;
  actionLoading.value.add(item.id);
  actionError.value[item.id] = '';
  actionDone.value[item.id] = '';
  try {
    await approvePublish(item.id, getComment(item.id) || '通过');
    actionDone.value[item.id] = '已通过';
    // 从待审批列表移除,加到已审批列表头部
    pendingList.value = pendingList.value.filter(p => p.id !== item.id);
    approvedList.value = [{ ...item, status: 'APPROVED' }, ...approvedList.value];
  } catch (e) {
    actionError.value[item.id] = e instanceof Error ? e.message : '审批通过失败';
  } finally {
    actionLoading.value.delete(item.id);
  }
}

async function doReject(item: PublishPendingItem) {
  if (actionLoading.value.has(item.id)) return;
  const c = getComment(item.id).trim();
  if (!c) {
    actionError.value[item.id] = '退回请填写原因';
    return;
  }
  actionLoading.value.add(item.id);
  actionError.value[item.id] = '';
  actionDone.value[item.id] = '';
  try {
    await rejectPublish(item.id, c);
    actionDone.value[item.id] = '已退回';
    pendingList.value = pendingList.value.filter(p => p.id !== item.id);
    approvedList.value = [{ ...item, status: 'REJECTED' }, ...approvedList.value];
  } catch (e) {
    actionError.value[item.id] = e instanceof Error ? e.message : '审批退回失败';
  } finally {
    actionLoading.value.delete(item.id);
  }
}

onMounted(load);
</script>

<template>
  <div class="sub-tabs">
    <button class="sub-tab" :class="{ on: tab === 'pending' }" @click="tab = 'pending'">
      待审批 <span class="badge pending-badge">{{ pendingList.length }}</span>
    </button>
    <button class="sub-tab" :class="{ on: tab === 'approved' }" @click="tab = 'approved'">
      已审批 <span class="badge">{{ approvedList.length }}</span>
    </button>
    <button class="refresh" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '↻ 刷新' }}</button>
  </div>

  <div v-if="errorMsg" class="error">{{ errorMsg }}</div>

  <!-- 待审批 -->
  <div v-if="tab === 'pending'">
    <div v-if="loading && pendingList.length === 0" class="empty">加载中…</div>
    <div v-else-if="pendingList.length === 0" class="empty">暂无待审批项</div>
    <ul v-else class="list">
      <li v-for="item in pendingList" :key="item.id" class="item">
        <div class="item-head" @click="goDetail(item)">
          <span class="item-name">{{ item.name || `Skill #${item.skillId}` }}</span>
          <span class="item-status pending">待审批</span>
        </div>
        <div class="item-meta">
          <span>提交人:{{ item.submitter }}</span>
          <span>时间:{{ item.createdAt }}</span>
          <span v-if="item.targetName">维度:{{ item.targetName }}</span>
          <span v-if="item.category">分类:{{ item.category }}</span>
        </div>
        <p v-if="item.description" class="item-desc">{{ item.description }}</p>

        <!-- 内联审批操作 -->
        <div class="inline-actions">
          <textarea
            class="comment-input"
            placeholder="审批意见(退回必填)"
            rows="2"
            :value="getComment(item.id)"
            @input="setComment(item.id, ($event.target as HTMLTextAreaElement).value)"
          ></textarea>
          <div class="btns">
            <button
              class="approve"
              :disabled="actionLoading.has(item.id)"
              @click="doApprove(item)"
            >通过</button>
            <button
              class="reject"
              :disabled="actionLoading.has(item.id)"
              @click="doReject(item)"
            >退回</button>
            <button class="goto-detail" @click="goDetail(item)">查看详情 →</button>
          </div>
          <div v-if="actionError[item.id]" class="action-error">{{ actionError[item.id] }}</div>
          <div v-if="actionDone[item.id]" class="action-done">{{ actionDone[item.id] }}</div>
        </div>
      </li>
    </ul>
  </div>

  <!-- 已审批 -->
  <div v-if="tab === 'approved'">
    <div v-if="loading && approvedList.length === 0" class="empty">加载中…</div>
    <div v-else-if="approvedList.length === 0" class="empty">暂无已审批记录</div>
    <ul v-else class="list">
      <li v-for="item in approvedList" :key="item.id" class="item" @click="goDetail(item)">
        <div class="item-head">
          <span class="item-name">{{ item.name || `Skill #${item.skillId}` }}</span>
          <span class="item-status" :class="item.status.toLowerCase()">{{ item.status === 'APPROVED' ? '已通过' : '已退回' }}</span>
        </div>
        <div class="item-meta">
          <span>提交人:{{ item.submitter }}</span>
          <span v-if="item.approveTime">审批时间:{{ item.approveTime }}</span>
          <span v-if="item.targetName">维度:{{ item.targetName }}</span>
          <span v-if="item.category">分类:{{ item.category }}</span>
        </div>
        <p v-if="item.description" class="item-desc">{{ item.description }}</p>
        <p v-if="item.lastApprovalComment" class="item-comment">审批意见:{{ item.lastApprovalComment }}</p>
        <div class="item-go">查看详情 →</div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 8px; }
.sub-tabs { display: flex; gap: 8px; align-items: center; margin-bottom: 16px; }
.sub-tab {
  padding: 6px 16px;
  border-radius: 16px;
  border: 1px solid #cbd5e1;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #475569;
  transition: background-color 0.2s, color 0.2s, border-color 0.2s;
}
.sub-tab:hover { border-color: #93c5fd; color: #2563eb; }
.sub-tab.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.badge {
  display: inline-block;
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: #e2e8f0;
  color: #475569;
  font-size: 11px;
  margin-left: 4px;
}
/* 待审批数量用红色徽章突出 */
.badge.pending-badge { background: #dc2626; color: #fff; font-weight: 700; }
.sub-tab.on .badge { background: rgba(255,255,255,0.3); color: #fff; }
.sub-tab.on .pending-badge { background: #fff; color: #dc2626; }
.refresh {
  margin-left: auto;
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid #cbd5e1;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #475569;
}
.refresh:hover:not(:disabled) { border-color: #93c5fd; color: #2563eb; }
.refresh:disabled { opacity: 0.6; cursor: not-allowed; }

.error { color: #dc2626; font-size: 13px; margin-bottom: 12px; }
.empty { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }

.list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.item {
  padding: 14px 16px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.item:hover { border-color: #93c5fd; box-shadow: 0 2px 8px rgba(59, 130, 246, 0.12); }
.item-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; cursor: pointer; }
.item-name { font-weight: 600; font-size: 15px; color: #0f172a; }
.item-name:hover { color: #2563eb; }
.item-status {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.item-status.pending { background: #dbeafe; color: #1d4ed8; }
.item-status.approved { background: #d1fae5; color: #047857; }
.item-status.rejected { background: #fee2e2; color: #b91c1c; }
.item-meta { display: flex; gap: 16px; color: #64748b; font-size: 12px; flex-wrap: wrap; }
.item-desc { margin: 6px 0 0; color: #475569; font-size: 13px; line-height: 1.5; }
.item-comment { margin: 4px 0 0; color: #64748b; font-size: 12px; font-style: italic; }
.item-go { margin-top: 8px; color: #3b82f6; font-size: 13px; font-weight: 500; cursor: pointer; }

/* 内联审批操作区 */
.inline-actions {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
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
.btns { display: flex; gap: 10px; align-items: center; }
.approve {
  padding: 6px 18px;
  border-radius: 6px;
  border: 1px solid #16a34a;
  background: #16a34a;
  color: #fff;
  cursor: pointer;
  font-size: 13px;
}
.approve:hover:not(:disabled) { background: #15803d; }
.reject {
  padding: 6px 18px;
  border-radius: 6px;
  border: 1px solid #dc2626;
  background: #fff;
  color: #dc2626;
  cursor: pointer;
  font-size: 13px;
}
.reject:hover:not(:disabled) { background: #fef2f2; }
.btns button:disabled { opacity: 0.6; cursor: not-allowed; }
.goto-detail {
  margin-left: auto;
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
  cursor: pointer;
  font-size: 12px;
}
.goto-detail:hover { border-color: #93c5fd; color: #2563eb; }
.action-error { color: #dc2626; font-size: 12px; }
.action-done { color: #16a34a; font-size: 12px; }
</style>
