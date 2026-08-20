<script setup lang="ts">
/**
 * Skill 详情页
 *
 * 职责:
 *  - 展示 Skill 信息、点赞、引用、发布记录、描述/内容
 *  - 内联展示"当前 Skill 的"发布审批详情(待审/已审记录 + 通过/退回操作)
 *  - 审批列表已迁移至独立页面 /skills/approvals
 *  - 编辑走 /skills/:id/edit 全页面表单
 */
import { ref, watch, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Markdown from '../../components/Markdown.vue';
import {
  getSkill,
  getLikeStatus,
  likeSkill,
  unlikeSkill,
  referenceSkill,
  unreferenceSkill,
  deleteSkill,
  getReferencers,
  currentUserId,
  getSkillPublishes,
  listPendingPublishes,
  listApprovedPublishes,
  approvePublish,
  rejectPublish,
  getSkillFiles,
  fetchFileBlob,
} from '../../api/skill';
import type { SkillDetail, LikeStatus, SkillPublishRecord, PublishPendingItem, SkillFileReferenceItem } from '../../types/skill';
import SkillGrantEditor from '../../components/SkillGrantEditor.vue';

const route = useRoute();
const router = useRouter();

// ============ Skill 详情状态 ============
const skill = ref<SkillDetail | null>(null);
const like = ref<LikeStatus>({ liked: false, likeCount: 0 });
const referenced = ref(false);
const referencerCount = ref(0);
const deleting = ref(false);
const deleteError = ref('');

// —— 可见性 + 私有授权(owner 面板) ——
const isPrivate = computed(() => !!skill.value && skill.value.visibility === 'PRIVATE');
// 维度/发布相关状态(只读展示,维度切换走编辑表单)
const publishes = ref<SkillPublishRecord[]>([]);
const publishLoading = ref(false);
const publishError = ref('');

// 仅所有者可编辑/删除,且审批中的 Skill 不可编辑/删除(后端做双重校验,前端先门控)
const canManage = computed(() =>
  !!skill.value && skill.value.ownerUserId === currentUserId() && !hasPendingPublish.value
);

// 编辑:跳转全页面表单
function goEdit() {
  if (!skill.value) return;
  router.push(`/skills/${skill.value.id}/edit`);
}

// 维度类型 -> 前缀(COMPANY 特殊处理为空前缀,直接显示 targetName)
const TYPE_LABEL: Record<string, string> = {
  GROUP: '小组',
  DEPARTMENT: '部门',
  PRODUCT_LINE: '产品线',
  COMPANY: '',           // 杭研直接显示,不加前缀
};

// 拼接维度展示文本(如"小组:开发一组"、"杭研")
// targetName 为组织显示名称(如"开发一组"),按维度类型拼接一次前缀
function formatDimension(p: SkillPublishRecord): string {
  const label = TYPE_LABEL[p.targetType] ?? '';
  return label ? `${label}:${p.targetName}` : p.targetName;
}

// 按 targetType+targetId 去重(同一维度可能有多条 APPROVED 记录,只保留一条用于展示)
function dedupByDimension<T extends { targetType: string; targetId: string }>(list: T[]): T[] {
  const seen = new Set<string>();
  return list.filter(item => {
    const key = `${item.targetType}:${item.targetId}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// 派生:当前维度标签
// 优先展示已审批通过的维度;若无 APPROVED 但有 PENDING,展示 PENDING 中的维度(审批中)
// 完全无发布记录时才显示"个人"(个人维度无需审批)
// 同一维度可能有多条 APPROVED/PENDING 记录(如先退回再通过),按维度去重避免重复显示
const dimensionLabel = computed(() => {
  const approved = dedupByDimension(publishes.value.filter(p => p.status === 'APPROVED'));
  if (approved.length > 0) {
    return approved.map(formatDimension).join('、');
  }
  const pending = dedupByDimension(publishes.value.filter(p => p.status === 'PENDING'));
  if (pending.length > 0) {
    return pending.map(formatDimension).join('、');
  }
  return '个人';
});

// 派生:是否有审批中的申请
const hasPendingPublish = computed(() =>
  publishes.value.some(p => p.status === 'PENDING')
);

// 派生:是否已发布到非个人维度(APPROVED)。
// 维度共享后,skill_reference 中的显式引用数不再代表真实使用量(同维度用户默认可用,
// 但不写入引用表),故对非个人维度的 Skill 隐藏"被 N 人引用",避免显示"几千人引用"之类的噪声。
// publishLoading 期间一并隐藏,避免发布记录未就绪时闪现。
const isDimensionShared = computed(() =>
  publishes.value.some(p => p.status === 'APPROVED')
);

// 已审批通过的维度展示文本(如"部门:研发部、杭研"),用于删除提示等。
// 按维度去重,避免同一维度多条 APPROVED 记录导致重复显示
const approvedDimensionLabel = computed(() =>
  dedupByDimension(publishes.value.filter(p => p.status === 'APPROVED')).map(formatDimension).join('、')
);

// ============ 审批详情(当前 Skill 的待审/已审记录) ============
const publishPending = ref<PublishPendingItem | null>(null);
const publishHistory = ref<PublishPendingItem | null>(null);
const approvalActionLoading = ref(false);
const approvalActionError = ref('');
const approvalActionDone = ref('');
const approvalComment = ref('');

const isPending = computed(() => !!publishPending.value);

// ============ Markdown 渲染 ============
// 使用零依赖 Markdown 组件，兼容所有浏览器

// ============ 附件列表 ============

const skillFiles = ref<SkillFileReferenceItem[]>([]);
const filesLoading = ref(false);

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

function fileIcon(fileType: string): string {
  switch (fileType) {
    case 'PYTHON': return '🐍';
    case 'SQL': return '🗃️';
    default: return '📄';
  }
}

async function downloadFile(fileId: number) {
  try {
    const { blob, filename } = await fetchFileBlob(fileId);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (e) {
    console.error('download failed', e);
  }
}

async function loadSkillFiles(id: number) {
  filesLoading.value = true;
  try {
    skillFiles.value = await getSkillFiles(id);
  } catch {
    skillFiles.value = [];
  } finally {
    filesLoading.value = false;
  }
}

// ============ 数据加载 ============
async function load() {
  const id = Number(route.params.id);
  if (!id) return;
  skill.value = await getSkill(id);
  like.value = await getLikeStatus(id);
  try {
    const refs = await getReferencers(id);
    referencerCount.value = refs.length;
    // 当前用户在引用者列表中即视为已引用(含创建时默认自引用),同步按钮初始状态。
    //
    // 注意:referenced 只代表 skill_reference 表里"显式引用"记录,与列表页的 used 徽章含义不同--
    // used 还包含"自己创建"和"所属维度已发布(默认可用)"。所以一个维度内的 skill 可能:
    //   列表显示"已使用"(维度默认可用),但这里按钮显示"引用"(无显式引用记录)。
    // 点"引用"会写入显式引用记录;点"取消引用"只删该记录,若维度/owner 仍成立,列表仍显示"已使用"。
    referenced.value = refs.includes(currentUserId());
  } catch {
    referencerCount.value = 0;
    referenced.value = false;
  }
  loadPublishes(id);
  loadApprovalDetail(id);
  loadSkillFiles(id);
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

// 加载当前 Skill 的审批详情(待审 + 已审记录)
async function loadApprovalDetail(id: number) {
  publishPending.value = null;
  publishHistory.value = null;
  const results = await Promise.allSettled([listPendingPublishes(), listApprovedPublishes()]);
  if (results[0].status === 'fulfilled') {
    publishPending.value = results[0].value.find((p) => p.skillId === id) ?? null;
  }
  if (results[1].status === 'fulfilled') {
    publishHistory.value = results[1].value.find((p) => p.skillId === id) ?? null;
  }
}

// ============ 详情页操作 ============
async function toggleLike() {
  if (!skill.value) return;
  like.value = like.value.liked
    ? await unlikeSkill(skill.value.id)
    : await likeSkill(skill.value.id);
}
async function toggleReference() {
  if (!skill.value) return;
  // 引用/取消引用 toggle:乐观更新引用状态与引用人数,失败回滚
  // (后端 reference/unreference 均幂等,且 API 返回 void,故前端本地维护计数)
  //
  // 这里操作的是"显式引用"(skill_reference 表),不是列表 used 徽章。
  // 取消引用后,若该 skill 仍属于当前用户所属维度或当前用户是 owner,
  // 列表页 used 仍为 true(显示"已使用");只有都不是时才会变"未使用"。
  // 因此前端这里的 referenced 状态与列表 used 不一定同步。
  const before = { referenced: referenced.value, count: referencerCount.value };
  referenced.value = !referenced.value;
  referencerCount.value += referenced.value ? 1 : -1;
  try {
    if (referenced.value) {
      await referenceSkill(skill.value.id);
    } else {
      await unreferenceSkill(skill.value.id);
    }
  } catch {
    referenced.value = before.referenced; // 回滚
    referencerCount.value = before.count;
  }
}
async function doDelete() {
  if (!skill.value || deleting.value) return;
  let msg = `确定删除 Skill "${skill.value.name}"?此操作不可撤销。`;
  if (isDimensionShared.value) {
    // 维度共享:按维度提示(显式引用数不代表真实使用量,不再取 referencerCount)
    msg = `该 Skill 已发布到 ${approvedDimensionLabel.value} 维度,删除后同维度用户将无法使用。\n${msg}`;
  } else {
    // 个人维度:删除前重新获取引用数(避免使用过期数据),被引用时提醒用户
    let refCount = 0;
    try {
      const refs = await getReferencers(skill.value.id);
      refCount = refs.length;
    } catch {
      refCount = referencerCount.value;
    }
    if (refCount > 0) {
      msg = `该 Skill 正在被 ${refCount} 人引用,删除后这些用户将无法继续使用。\n${msg}`;
    }
  }
  if (!confirm(msg)) return;
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

// ============ 审批操作 ============
async function doApprove() {
  if (approvalActionLoading.value || !publishPending.value) return;
  approvalActionLoading.value = true;
  approvalActionError.value = '';
  approvalActionDone.value = '';
  try {
    await approvePublish(publishPending.value.id, approvalComment.value || '通过');
    approvalActionDone.value = '已通过该发布审批';
    approvalComment.value = '';
    const id = Number(route.params.id);
    await loadApprovalDetail(id);
    await loadPublishes(id);
  } catch (e) {
    approvalActionError.value = e instanceof Error ? e.message : '审批通过失败';
  } finally {
    approvalActionLoading.value = false;
  }
}

async function doReject() {
  if (approvalActionLoading.value || !publishPending.value) return;
  if (!approvalComment.value.trim()) {
    approvalActionError.value = '退回请填写原因';
    return;
  }
  approvalActionLoading.value = true;
  approvalActionError.value = '';
  approvalActionDone.value = '';
  try {
    await rejectPublish(publishPending.value.id, approvalComment.value);
    approvalActionDone.value = '已退回该发布审批';
    approvalComment.value = '';
    const id = Number(route.params.id);
    await loadApprovalDetail(id);
    await loadPublishes(id);
  } catch (e) {
    approvalActionError.value = e instanceof Error ? e.message : '审批退回失败';
  } finally {
    approvalActionLoading.value = false;
  }
}

// 路由 id 变化 -> 重新加载详情
watch(() => route.params.id, () => {
  load();
}, { immediate: true });
</script>

<template>
  <div v-if="skill">
    <h2 class="skill-title">{{ skill.name }} <span class="cnt"><svg class="thumb-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="currentColor" style="vertical-align:-2px"><path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/></svg> {{ like.likeCount }}</span></h2>
    <div class="meta">{{ skill.ownerUserId }} · 状态 {{ skill.status }}
      <span v-if="isPrivate" class="vis-badge private">私有</span>
      <span v-else-if="skill.visibility === 'PUBLIC'" class="vis-badge public">公开</span>
      <span v-else class="vis-badge personal">个人</span>
    </div>

    <!-- 私有可见范围(详情页只读展示;增删授权走"编辑"表单页) -->
    <div v-if="isPrivate" class="grant-panel">
      <SkillGrantEditor :skill-id="skill.id" :editable="false" />
    </div>
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
      <button class="edit" @click="goEdit">✎ 编辑</button>
      <button class="del" :disabled="deleting" @click="doDelete">{{ deleting ? '删除中…' : '🗑 删除' }}</button>
    </div>
    <div v-if="deleteError" class="del-error">{{ deleteError }}</div>
    <div class="actions">
      <button class="like" :class="{ on: like.liked }" @click="toggleLike">
        <svg class="thumb-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="currentColor">
          <path d="M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 7.59 6.59C7.22 6.95 7 7.45 7 8v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/>
        </svg>
        <span>{{ like.liked ? '已点赞' : '点赞' }}</span>
        <span class="ripple"></span>
      </button>
      <button class="ref" @click="toggleReference">{{ referenced ? '取消引用' : '引用' }}</button>
      <span v-if="!publishLoading && !isDimensionShared" class="referencer-count">被 {{ referencerCount }} 人引用</span>
    </div>
    <section class="block">
      <h3 class="block-title"><span class="bar"></span>描述</h3>
      <p class="desc">{{ skill.description }}</p>
    </section>
    <section class="block">
      <h3 class="block-title"><span class="bar"></span>内容</h3>
      <Markdown v-if="skill.content" :text="skill.content" theme="dark" />
    </section>

    <!-- 附件列表 -->
    <section v-if="skillFiles.length > 0 || filesLoading" class="block">
      <h3 class="block-title"><span class="bar"></span>附件 ({{ skillFiles.length }})</h3>
      <div v-if="filesLoading" class="loading">加载中…</div>
      <div v-else class="attachment-list">
        <div v-for="f in skillFiles" :key="f.id" class="attachment-item">
          <span class="att-icon">{{ fileIcon(f.fileType) }}</span>
          <span class="att-name" :title="f.filename">{{ f.filename }}</span>
          <span class="att-size">{{ formatFileSize(f.fileSize) }}</span>
          <button class="att-download" @click="downloadFile(f.id)" title="下载">⬇ 下载</button>
        </div>
      </div>
    </section>

    <!-- ============ 审批详情(当前 Skill 的待审/已审记录 + 审批操作) ============ -->
    <section class="block approval-section">
      <h3 class="block-title">
        <span class="bar"></span>发布审批
        <span v-if="publishPending" class="type-badge pending">待审批</span>
        <span v-else-if="publishHistory" class="type-badge" :class="publishHistory.status.toLowerCase()">
          {{ publishHistory.status === 'APPROVED' ? '已通过' : '已退回' }}
        </span>
      </h3>

      <!-- 待审发布详情 -->
      <div v-if="publishPending" class="info">
        <div class="info-row"><label>提交人</label><span>{{ publishPending.submitter }}</span></div>
        <div class="info-row"><label>提交时间</label><span>{{ publishPending.createdAt }}</span></div>
        <div class="info-row"><label>目标维度</label><span>{{ publishPending.targetName }}</span></div>
        <div v-if="publishPending.description" class="info-row"><label>描述</label><span>{{ publishPending.description }}</span></div>
      </div>
      <!-- 已审记录详情 -->
      <div v-else-if="publishHistory" class="info">
        <div class="info-row"><label>提交人</label><span>{{ publishHistory.submitter }}</span></div>
        <div class="info-row"><label>审批结果</label><span>{{ publishHistory.status === 'APPROVED' ? '通过' : '退回' }}</span></div>
        <div v-if="publishHistory.approveTime" class="info-row"><label>审批时间</label><span>{{ publishHistory.approveTime }}</span></div>
        <div class="info-row"><label>目标维度</label><span>{{ publishHistory.targetName }}</span></div>
        <div v-if="publishHistory.lastApprovalComment" class="info-row"><label>审批意见</label><span>{{ publishHistory.lastApprovalComment }}</span></div>
      </div>
      <div v-else class="no-pending">该 Skill 当前无发布审批记录。</div>

      <!-- 审批操作(仅待审状态可操作) -->
      <div v-if="isPending" class="approval-actions">
        <textarea v-model="approvalComment" class="comment-input" placeholder="审批意见(退回必填)" rows="3"></textarea>
        <div class="btns">
          <button class="approve" :disabled="approvalActionLoading" @click="doApprove">通过</button>
          <button class="reject" :disabled="approvalActionLoading" @click="doReject">退回</button>
        </div>
        <div v-if="approvalActionError" class="action-error">{{ approvalActionError }}</div>
        <div v-if="approvalActionDone" class="action-done">{{ approvalActionDone }}</div>
      </div>
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
.meta { color: #94a3b8; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }
.vis-badge { padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.vis-badge.private { background: #fef3c7; color: #b45309; }
.vis-badge.public { background: #f1f5f9; color: #64748b; }
.vis-badge.personal { background: #f5f3ff; color: #6d28d9; }
.grant-panel {
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 10px 14px; margin-bottom: 14px;
}
.dimension-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; font-size: 13px; }
.dim-label { color: #475569; font-weight: 600; }
.dim-value { font-weight: 600; color: #1e293b; }
.dim-pending { background: #fef3c7; color: #b45309; padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.dim-error { color: #dc2626; font-size: 13px; margin-bottom: 8px; }
.publish-list { margin: 12px 0 16px; font-size: 13px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; }
.publish-list summary { cursor: pointer; color: #334155; font-weight: 600; }
.publish-item { display: flex; gap: 10px; padding: 8px 0; align-items: center; list-style: none; border-bottom: 1px solid #f1f5f9; }
.publish-item:last-child { border-bottom: none; }
.p-target { font-weight: 600; color: #1e293b; font-size: 13px; }
.p-status { padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.p-status.approved { background: #d1fae5; color: #047857; }
.p-status.pending { background: #fef3c7; color: #b45309; }
.p-status.rejected { background: #fee2e2; color: #b91c1c; }
.p-meta { color: #64748b; font-size: 12px; }
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
.edit { padding: 6px 14px; border-radius: 16px; border: 1px solid #cbd5e1; background: #fff; font-size: 13px; color: #475569; text-decoration: none; cursor: pointer; transition: border-color 0.2s, color 0.2s; }
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
/* ===== 附件列表样式 ===== */
.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.attachment-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
}
.att-icon {
  font-size: 14px;
  flex-shrink: 0;
}
.att-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1e293b;
  font-weight: 500;
}
.att-size {
  color: #94a3b8;
  font-size: 12px;
  flex-shrink: 0;
}
.att-download {
  border: 1px solid #cbd5e1;
  background: #fff;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  color: #3b82f6;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
  flex-shrink: 0;
}
.att-download:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}

/* ===== 审批详情区 ===== */
.approval-section { margin-top: 20px; }
.type-badge {
  -webkit-text-fill-color: initial;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}
.type-badge.pending { background: #dbeafe; color: #1d4ed8; }
.type-badge.approved { background: #d1fae5; color: #047857; }
.type-badge.rejected { background: #fee2e2; color: #b91c1c; }

.info { display: flex; flex-direction: column; gap: 6px; }
.info-row { display: flex; gap: 8px; font-size: 13px; }
.info-row label { width: 80px; color: #475569; font-weight: 600; flex-shrink: 0; }
.info-row span { color: #1e293b; }

.no-pending {
  margin-top: 8px;
  padding: 12px 14px;
  border-radius: 6px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
}

.approval-actions { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
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
