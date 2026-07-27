<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { listPendingDrafts, listPendingPublishes } from '../../api/skill';
import type { SkillDraft, PublishPendingItem } from '../../types/skill';

const router = useRouter();
const activeTab = ref<'publish' | 'draft'>('publish');

const publishList = ref<PublishPendingItem[]>([]);
const draftList = ref<SkillDraft[]>([]);
const loading = ref(false);
const error = ref('');

async function loadAll() {
  loading.value = true;
  error.value = '';
  // 两个列表并行加载,任一失败记录错误但不阻塞另一个
  const results = await Promise.allSettled([listPendingPublishes(), listPendingDrafts()]);
  if (results[0].status === 'fulfilled') {
    publishList.value = results[0].value;
  } else {
    error.value = '发布审批列表加载失败';
  }
  if (results[1].status === 'fulfilled') {
    draftList.value = results[1].value;
  } else {
    error.value = (error.value ? error.value + ';' : '') + '草稿审批列表加载失败';
  }
  loading.value = false;
}

function goPublish(item: PublishPendingItem) {
  router.push(`/skills/approvals/${item.skillId}`);
}
function goDraft(item: SkillDraft) {
  router.push(`/skills/approvals/${item.skillId}`);
}

onMounted(loadAll);
</script>

<template>
  <div class="approval-list">
    <h2 class="page-title">待我审批</h2>

    <div class="tabs">
      <button class="tab" :class="{ on: activeTab === 'publish' }" @click="activeTab = 'publish'">
        发布审批 <span class="badge">{{ publishList.length }}</span>
      </button>
      <button class="tab" :class="{ on: activeTab === 'draft' }" @click="activeTab = 'draft'">
        草稿审批 <span class="badge">{{ draftList.length }}</span>
      </button>
      <button class="refresh" :disabled="loading" @click="loadAll">{{ loading ? '刷新中…' : '↻ 刷新' }}</button>
    </div>

    <div v-if="error" class="error">{{ error }}</div>

    <!-- 发布审批 -->
    <div v-if="activeTab === 'publish'">
      <div v-if="loading && publishList.length === 0" class="empty">加载中…</div>
      <div v-else-if="publishList.length === 0" class="empty">暂无待审批的发布</div>
      <ul v-else class="list">
        <li v-for="item in publishList" :key="item.id" class="item" @click="goPublish(item)">
          <div class="item-head">
            <span class="item-name">{{ item.name }}</span>
            <span class="item-type">发布</span>
          </div>
          <div class="item-meta">
            <span>提交人:{{ item.createdBy }}</span>
            <span>时间:{{ item.createdAt }}</span>
            <span v-if="item.category">分类:{{ item.category }}</span>
          </div>
          <p v-if="item.description" class="item-desc">{{ item.description }}</p>
          <div class="item-go">去审批 →</div>
        </li>
      </ul>
    </div>

    <!-- 草稿审批 -->
    <div v-if="activeTab === 'draft'">
      <div v-if="loading && draftList.length === 0" class="empty">加载中…</div>
      <div v-else-if="draftList.length === 0" class="empty">暂无待审批的草稿</div>
      <ul v-else class="list">
        <li v-for="item in draftList" :key="item.id" class="item" @click="goDraft(item)">
          <div class="item-head">
            <span class="item-name">{{ item.name }}</span>
            <span class="item-type draft">草稿</span>
          </div>
          <div class="item-meta">
            <span>提交人:{{ item.createdBy }}</span>
            <span>时间:{{ item.createdAt }}</span>
            <span v-if="item.category">分类:{{ item.category }}</span>
          </div>
          <p v-if="item.description" class="item-desc">{{ item.description }}</p>
          <div class="item-go">去审批 →</div>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.approval-list { max-width: 900px; }
.page-title {
  font-weight: 700;
  font-size: 24px;
  background: linear-gradient(135deg, #6366f1 0%, #3b82f6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  margin: 0 0 16px;
}
.tabs { display: flex; gap: 8px; align-items: center; margin-bottom: 16px; }
.tab {
  padding: 6px 16px;
  border-radius: 16px;
  border: 1px solid #cbd5e1;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #475569;
  transition: background-color 0.2s, color 0.2s, border-color 0.2s;
}
.tab:hover { border-color: #93c5fd; color: #2563eb; }
.tab.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.tab .badge {
  display: inline-block;
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: #e2e8f0;
  color: #475569;
  font-size: 11px;
  margin-left: 4px;
}
.tab.on .badge { background: rgba(255,255,255,0.3); color: #fff; }
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
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.item:hover { border-color: #93c5fd; box-shadow: 0 2px 8px rgba(59, 130, 246, 0.12); }
.item-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.item-name { font-weight: 600; font-size: 15px; color: #0f172a; }
.item-type {
  padding: 1px 8px;
  border-radius: 10px;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 600;
}
.item-type.draft { background: #fef3c7; color: #b45309; }
.item-meta { display: flex; gap: 16px; color: #64748b; font-size: 12px; flex-wrap: wrap; }
.item-desc { margin: 6px 0 0; color: #475569; font-size: 13px; line-height: 1.5; }
.item-go { margin-top: 8px; color: #3b82f6; font-size: 13px; font-weight: 500; }
</style>
