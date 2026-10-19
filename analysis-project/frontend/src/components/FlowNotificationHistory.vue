<script setup lang="ts">
import { computed, ref } from 'vue';
import type { SkillFlowNotification } from '../types/skillFlow';
const props = defineProps<{ notifications: SkillFlowNotification[] }>();
const expanded = ref(false);
const contentOpen = ref(new Set<SkillFlowNotification>());
const bodyOpen = ref(new Set<SkillFlowNotification>());
const reasonOpen = ref(new Set<SkillFlowNotification>());
function toggle(set: Set<SkillFlowNotification>, row: SkillFlowNotification) {
  if (set.has(row)) set.delete(row); else set.add(row);
}
function parse(value?: string | null): Record<string, unknown> {
  try { const result = JSON.parse(value || '{}'); return result && typeof result === 'object' && !Array.isArray(result) ? result : {}; }
  catch { return {}; }
}
function request(row: SkillFlowNotification) { return parse(row.requestJson); }
function string(value: unknown) { return typeof value === 'string' ? value.trim() : ''; }
function people(value: unknown): string[] {
  return Array.isArray(value) ? value.map(string).filter(Boolean) : string(value).split(/[,，、;；]/).map(v => v.trim()).filter(Boolean);
}
function recipients(row: SkillFlowNotification) {
  const saved = people(request(row).toUserList);
  return saved.length ? saved : people(row.recipient || row.recipientSummary);
}
function summary(row: SkillFlowNotification) {
  const list = recipients(row);
  return list.length > 1 ? `${list[0]}等 ${list.length} 人` : list[0] || '未记录';
}
function status(row: SkillFlowNotification) {
  if (row.status === 'SIMULATED') return '模拟完成';
  // Legacy SENT only confirms a sender return; it does not prove successful delivery.
  return ({ PENDING: '待发送', SENDING: '发送中', ACCEPTED: '已提交发送',
    FAILED: '发送失败', SENT: '历史记录（发送结果未确认）' } as Record<string, string>)[row.status] || '状态未知';
}
function time(row: SkillFlowNotification) { return row.sentAt || row.createdAt || ''; }
function timestamp(row: SkillFlowNotification) { const n = Date.parse(time(row)); return Number.isFinite(n) ? n : 0; }
const sorted = computed(() => [...props.notifications].sort((a, b) => timestamp(b) - timestamp(a) || (b.id || 0) - (a.id || 0)));
const visible = computed(() => expanded.value ? sorted.value : sorted.value.slice(0, 3));
function preview(row: SkillFlowNotification) {
  return '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src data:; form-action \'none\'; base-uri \'none\'">'
    + string(request(row).content);
}
</script>

<template>
  <section class="notification-history">
    <h4>通知记录（共 {{ notifications.length }} 次）</h4>
    <p v-if="!notifications.length" class="hint">暂无通知记录</p>
    <article v-for="(row, index) in visible" :key="row.id ?? index">
      <div class="heading">
        <span>发送时间：{{ time(row).replace('T', ' ').slice(0, 19) || '未记录' }}</span>
        <span>收件人：{{ summary(row) }}</span>
        <strong>{{ status(row) }}</strong>
        <button type="button" :aria-expanded="contentOpen.has(row)" @click="toggle(contentOpen, row)">{{ contentOpen.has(row) ? '收起内容' : '查看内容' }}</button>
        <button v-if="row.status === 'FAILED'" type="button" :aria-expanded="reasonOpen.has(row)" @click="toggle(reasonOpen, row)">{{ reasonOpen.has(row) ? '收起原因' : '查看原因' }}</button>
      </div>
      <p v-if="reasonOpen.has(row)" class="error">发送未成功，请稍后重试；如仍失败，请联系管理员。</p>
      <div v-if="contentOpen.has(row)" class="content">
        <p>主送：{{ recipients(row).join('、') || '未记录' }}</p>
        <p v-if="people(request(row).ccUserList).length">抄送：{{ people(request(row).ccUserList).join('、') }}</p>
        <p>标题：{{ string(request(row).title) || '历史记录未保存标题' }}</p>
        <template v-if="string(request(row).content)">
          <button type="button" :aria-expanded="bodyOpen.has(row)" @click="toggle(bodyOpen, row)">{{ bodyOpen.has(row) ? '收起正文' : '展开正文' }}</button>
          <iframe v-if="bodyOpen.has(row)" :srcdoc="preview(row)" sandbox="" referrerpolicy="no-referrer" title="通知邮件正文预览"></iframe>
        </template>
        <p v-else class="hint">历史记录未保存正文。</p>
      </div>
    </article>
    <button v-if="notifications.length > 3" type="button" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起记录' : '查看全部' }}</button>
  </section>
</template>

<style scoped>
h4 { margin: 0 0 10px; font-size: 14px; color: #0f172a; }
article { border-top: 1px solid #e2e8f0; padding: 12px 0; font-size: 12px; color: #334155; overflow-wrap: anywhere; }
.heading { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.hint, .heading span { color: #64748b; }
.error { color: #b91c1c; }
button { border: 0; background: none; padding: 0; color: #2563eb; cursor: pointer; font-size: 12px; }
.content { margin-top: 8px; }
iframe { display: block; width: 100%; min-height: 240px; border: 1px solid #e2e8f0; background: white; margin: 12px 0; }
</style>
