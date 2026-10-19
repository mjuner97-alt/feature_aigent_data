<script setup lang="ts">
import type { SkillFlowNotification } from '../types/skillFlow';
defineProps<{ notifications: SkillFlowNotification[] }>();
function parse(value?: string | null): Record<string, unknown> {
  try { const result = JSON.parse(value || '{}'); return result && typeof result === 'object' ? result : {}; }
  catch { return {}; }
}
function request(row: SkillFlowNotification) { return parse(row.requestJson); }
function result(row: SkillFlowNotification) { return parse(row.responseJson); }
function people(value: unknown, fallback = '无') {
  return Array.isArray(value) ? value.join('、') || '无' : fallback;
}
function status(value: string) {
  return ({ PENDING: '待发送', SENDING: '发送中', SIMULATED: '模拟完成', ACCEPTED: '网关已接受',
    FAILED: '发送失败', SENT: '历史记录：发送器已返回' } as Record<string, string>)[value] || value;
}
function preview(row: SkillFlowNotification) {
  // Block scripts, network resources, forms and navigation; keep mail formatting visible.
  return '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src data:; form-action \'none\'; base-uri \'none\'">'
    + String(request(row).content || '');
}
</script>

<template>
  <section class="notification-history">
    <h4>通知发送详情</h4>
    <p v-if="!notifications.length" class="hint">暂无通知记录</p>
    <article v-for="row in notifications" :key="row.id">
      <div class="heading"><strong>{{ status(row.status) }}</strong><span>{{ row.channel === 'SIMULATE' ? '模拟模式' : row.channel === 'REAL' ? '真实模式' : '历史记录' }}</span><span>#{{ row.id }} · {{ row.createdAt || '-' }}</span></div>
      <p v-if="row.channel === 'SIMULATE'" class="hint">仅生成并保存通知请求，未调用真实发送接口。</p>
      <p>主送：{{ people(request(row).toUserList, row.recipient || row.recipientSummary || '无') }}</p>
      <p>抄送：{{ people(request(row).ccUserList, '未记录') }}</p>
      <p v-if="row.errorMessage" class="error">{{ row.errorMessage }}</p>
      <details>
        <summary>查看发送内容与结果</summary>
        <p>标题：{{ request(row).title || '历史记录未保存标题' }}</p>
        <p>通知 ID：{{ row.id }} · 执行 ID：{{ row.flowExecutionId || '-' }}</p>
        <p v-if="result(row).reportUrl">报告链接：{{ result(row).reportUrl }}</p>
        <p>开始：{{ result(row).startedAt || '-' }} · 结束：{{ result(row).completedAt || '-' }}</p>
        <p>耗时：{{ result(row).durationMs ?? '-' }} ms</p>
        <iframe v-if="request(row).content" :srcdoc="preview(row)" sandbox="" referrerpolicy="no-referrer" title="通知邮件正文预览"></iframe>
        <p v-else class="hint">历史记录未保存最终邮件正文。</p>
        <details><summary>最终请求</summary><pre>{{ row.requestJson || '未记录' }}</pre></details>
        <details><summary>网关响应与时间记录</summary><pre>{{ row.responseJson || '尚无结果' }}</pre></details>
      </details>
    </article>
  </section>
</template>

<style scoped>
h4 { margin: 0 0 10px; font-size: 14px; color: #0f172a; }
article { border-top: 1px solid #e2e8f0; padding: 12px 0; font-size: 12px; color: #334155; overflow-wrap: anywhere; }
.heading { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.hint, .heading span { color: #64748b; }
.error { color: #b91c1c; }
summary, a { color: #2563eb; cursor: pointer; }
details { margin-top: 8px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 10px; background: #f8fafc; max-height: 280px; overflow: auto; }
iframe { display: block; width: 100%; min-height: 240px; border: 1px solid #e2e8f0; background: white; margin: 12px 0; }
</style>
