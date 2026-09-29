<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { listExecutionNotifications, resendExecutionNotification } from '../api/skillJob';
import { searchSkillUsers } from '../api/skill';
import type { SkillJobNotification } from '../types/skillJob';

const props = defineProps<{ open: boolean; execId: number | null; canResend?: boolean }>();
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'changed'): void }>();

const records = ref<SkillJobNotification[]>([]);
const loading = ref(false);
const resending = ref(false);
const error = ref('');
const receiverKeyword = ref('');
const receiverResults = ref<{ userId: string; name: string; department: string | null }[]>([]);
const selectedReceivers = ref<string[]>([]);
const receiverNames = ref<Record<string, string>>({});
let pollTimer: ReturnType<typeof setInterval> | undefined;

const hasPending = computed(() => records.value.some(r => r.status === 'PENDING' || r.status === 'SENDING'));

watch(() => props.open, open => {
  if (open && props.execId) load();
  else stopPolling();
});

async function load(silent = false) {
  if (!props.execId) return;
  if (!silent) loading.value = true;
  error.value = '';
  try {
    records.value = await listExecutionNotifications(props.execId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
  syncPolling();
}

function syncPolling() {
  if (hasPending.value && pollTimer == null) pollTimer = setInterval(() => load(true), 3000);
  if (!hasPending.value) stopPolling();
}

function stopPolling() {
  if (pollTimer != null) clearInterval(pollTimer);
  pollTimer = undefined;
}

async function resend() {
  if (!props.execId || resending.value) return;
  if (!selectedReceivers.value.length) { error.value = '请先从人员清单选择收件人'; return; }
  const notifyReceivers = [...selectedReceivers.value];
  resending.value = true;
  error.value = '';
  try {
    await resendExecutionNotification(props.execId, notifyReceivers);
    await load(true);
    emit('changed');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '补发失败';
  } finally {
    resending.value = false;
  }
}

async function searchReceivers() {
  const keyword = receiverKeyword.value.trim();
  if (!keyword) { receiverResults.value = []; return; }
  try {
    receiverResults.value = (await searchSkillUsers(keyword))
      .filter(item => !selectedReceivers.value.includes(item.userId));
  } catch { receiverResults.value = []; }
}

function addReceiver(item: { userId: string; name: string }) {
  if (selectedReceivers.value.includes(item.userId)) return;
  selectedReceivers.value = [...selectedReceivers.value, item.userId];
  receiverNames.value[item.userId] = item.name || item.userId;
  receiverResults.value = receiverResults.value.filter(row => row.userId !== item.userId);
}

function removeReceiver(userId: string) {
  selectedReceivers.value = selectedReceivers.value.filter(id => id !== userId);
}

function close() { emit('update:open', false); }
function fmtTime(value?: string) { return value ? value.replace('T', ' ').substring(0, 19) : '-'; }
function statusText(status: string) {
  return { PENDING: '等待发送', SENDING: '发送中', SUCCESS: '已提交通知平台', FAILED: '发送失败', SKIPPED: '通知未启用' }[status] || status;
}
function requestText(type: string) { return type === 'RESEND' ? '人工补发' : '首次发送'; }

onUnmounted(stopPolling);
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" @click.self="close">
        <section class="drawer" aria-label="通知记录">
          <header class="drawer-header">
            <div>
              <h3>通知记录</h3>
              <span class="exec-id">执行 #{{ execId }}</span>
            </div>
            <button class="drawer-close" aria-label="关闭" @click="close">×</button>
          </header>

          <div class="toolbar">
            <button class="btn ghost" :disabled="loading" @click="load()">刷新</button>
            <button v-if="canResend" class="btn primary" :disabled="resending" @click="resend">
              {{ resending ? '发送中…' : '发送通知' }}
            </button>
          </div>

          <div class="drawer-body">
            <div v-if="error" class="error-banner">{{ error }}</div>
            <div v-if="canResend" class="send-box">
              <div class="send-help">从人员清单选择本次通知收件人</div>
              <div class="receiver-search">
                <input v-model="receiverKeyword" placeholder="输入姓名或统一认证号" @keyup.enter="searchReceivers" />
                <button class="btn ghost" type="button" @click="searchReceivers">搜索</button>
              </div>
              <div v-if="receiverResults.length" class="receiver-results">
                <button v-for="item in receiverResults" :key="item.userId" type="button" @click="addReceiver(item)">
                  {{ item.name || item.userId }} ({{ item.userId }})
                </button>
              </div>
              <div class="receiver-chips">
                <span v-for="userId in selectedReceivers" :key="userId" class="receiver-chip">
                  {{ receiverNames[userId] || userId }} ({{ userId }})
                  <button type="button" @click="removeReceiver(userId)">×</button>
                </span>
              </div>
            </div>
            <div v-if="loading" class="empty">加载中…</div>
            <div v-else-if="records.length === 0" class="empty">暂无通知记录</div>
            <div v-else class="record-list">
              <article v-for="record in records" :key="record.id" class="record-item">
                <div class="record-head">
                  <div class="record-title">
                    <strong>{{ requestText(record.requestType) }}</strong>
                    <span>#{{ record.id }}</span>
                  </div>
                  <span class="status" :class="`status-${record.status.toLowerCase()}`">{{ statusText(record.status) }}</span>
                </div>
                <dl>
                  <div><dt>请求时间</dt><dd>{{ fmtTime(record.requestedAt) }}</dd></div>
                  <div><dt>完成时间</dt><dd>{{ fmtTime(record.completedAt) }}</dd></div>
                  <div><dt>收件人</dt><dd>{{ record.recipientSummary || '-' }}</dd></div>
                  <div><dt>发送器</dt><dd>{{ record.senderName || '-' }}</dd></div>
                  <div v-if="record.fileName"><dt>报告</dt><dd>{{ record.fileName }}</dd></div>
                  <div v-if="record.errorMsg">
                    <dt>{{ record.status === 'SKIPPED' ? '未启用原因' : '结果说明' }}</dt>
                    <dd :class="record.status === 'FAILED' ? 'error-text' : 'reason-text'">{{ record.errorMsg }}</dd>
                  </div>
                </dl>
                <details v-if="record.content">
                  <summary>通知内容</summary>
                  <pre>{{ record.content }}</pre>
                </details>
              </article>
            </div>
          </div>
        </section>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; z-index: 1100; display: flex; justify-content: flex-end; background: rgba(15, 23, 42, .45); }
.drawer { width: 600px; max-width: 94vw; height: 100%; display: flex; flex-direction: column; background: #fff; box-shadow: -8px 0 24px rgba(15, 23, 42, .14); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-header h3 { margin: 0; color: #0f172a; font-size: 18px; }
.exec-id { display: block; margin-top: 3px; color: #94a3b8; font-size: 12px; }
.drawer-close { width: 32px; height: 32px; border: 0; border-radius: 4px; background: transparent; color: #64748b; font-size: 24px; cursor: pointer; }
.drawer-close:hover { background: #f1f5f9; }
.toolbar { display: flex; justify-content: flex-end; gap: 8px; padding: 10px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.record-list { display: flex; flex-direction: column; gap: 10px; }
.record-item { border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.record-head { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; background: #f8fafc; border-bottom: 1px solid #e2e8f0; }
.record-title { display: flex; gap: 8px; align-items: baseline; color: #0f172a; font-size: 13px; }
.record-title span { color: #94a3b8; font-size: 12px; }
.status { padding: 2px 7px; border-radius: 4px; font-size: 12px; font-weight: 700; }
.status-success { background: #dcfce7; color: #166534; }
.status-failed { background: #fee2e2; color: #991b1b; }
.status-pending, .status-sending { background: #fef3c7; color: #92400e; }
.status-skipped { background: #f1f5f9; color: #64748b; }
dl { margin: 0; padding: 9px 12px; }
dl > div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 8px; padding: 3px 0; font-size: 12px; }
dt { color: #64748b; font-weight: 600; }
dd { min-width: 0; margin: 0; color: #1e293b; overflow-wrap: anywhere; }
.error-text { color: #b91c1c; }
.reason-text { color: #475569; }
details { border-top: 1px solid #f1f5f9; padding: 8px 12px 10px; }
summary { color: #2563eb; font-size: 12px; cursor: pointer; }
pre { max-height: 240px; overflow: auto; margin: 8px 0 0; padding: 10px; background: #f8fafc; color: #334155; font: 12px/1.55 ui-monospace, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.btn { padding: 6px 13px; border: 1px solid #cbd5e1; border-radius: 5px; font-size: 13px; cursor: pointer; }
.btn.ghost { background: #fff; color: #475569; }
.btn.primary { border-color: #2563eb; background: #2563eb; color: #fff; }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.empty { padding: 40px 0; color: #94a3b8; text-align: center; font-size: 13px; }
.error-banner { margin-bottom: 12px; padding: 8px 10px; border: 1px solid #fecaca; background: #fef2f2; color: #b91c1c; font-size: 12px; }
.send-box { margin-bottom: 14px; padding: 10px; border: 1px solid #dbeafe; border-radius: 6px; background: #eff6ff; }
.send-help { margin-bottom: 8px; color: #1e40af; font-size: 12px; }
.receiver-search { display: flex; gap: 8px; }
.receiver-search input { flex: 1; min-width: 0; padding: 6px 8px; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 12px; }
.receiver-results { display: flex; flex-direction: column; gap: 3px; margin-top: 6px; }
.receiver-results button { padding: 6px 8px; border: 0; background: #fff; color: #1e293b; text-align: left; cursor: pointer; }
.receiver-results button:hover { background: #dbeafe; }
.receiver-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.receiver-chip { padding: 4px 7px; border-radius: 4px; background: #dbeafe; color: #1e3a8a; font-size: 12px; }
.receiver-chip button { margin-left: 4px; border: 0; background: transparent; color: #1e3a8a; cursor: pointer; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity .2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform .22s; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
