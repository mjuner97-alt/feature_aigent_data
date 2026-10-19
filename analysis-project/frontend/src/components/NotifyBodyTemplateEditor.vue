<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';

const props = withDefaults(defineProps<{ modelValue: string; flowName?: string; reportLinkTitle?: string; disabled?: boolean }>(), { flowName: '', disabled: false });
const emit = defineEmits<{ (event: 'update:modelValue', value: string): void }>();
const body = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) });
const input = ref<HTMLTextAreaElement | null>(null);
const placeholder = '{链接}';
function insert(token: string, target = input.value) {
  if (props.disabled) return;
  const text = props.modelValue;
  const start = target?.selectionStart ?? text.length, end = target?.selectionEnd ?? start;
  emit('update:modelValue', text.slice(0, start) + token + text.slice(end));
  nextTick(() => { target?.focus?.(); target?.setSelectionRange?.(start + token.length, start + token.length); });
}
function keydown(event: KeyboardEvent) {
  if (event.key !== 'Tab' || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey || props.disabled) return;
  event.preventDefault();
  event.stopPropagation();
  insert('\t', event.target as HTMLTextAreaElement);
}
function escape(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
const linkTitle = computed(() => {
  if (props.reportLinkTitle != null) return props.reportLinkTitle;
  const now = new Date(), pad = (n: number) => String(n).padStart(2, '0');
  return `${props.flowName || '任务名'}(${now.getFullYear()}年${pad(now.getMonth() + 1)}月${pad(now.getDate())}日)`;
});
const preview = computed(() => {
  if (!props.modelValue.trim()) return '';
  // Encode whitespace before inserting markup, matching the email renderer.
  let html = escape(props.modelValue).replace(/ /g, '&nbsp;').replace(/\t/g, '&nbsp;'.repeat(4)).replace(/\r\n|\r|\n/g, '<br>');
  const link = `<span class="preview-link">${escape(linkTitle.value)}</span>`;
  return html.includes(placeholder) ? html.split(placeholder).join(link) : html + `<br>${link}`;
});
const hint = computed(() => !props.modelValue.trim()
  ? '非必填，留空时使用后台原有的通知邮件拼接模板。'
  : props.modelValue.includes(placeholder)
    ? `发送时 ${placeholder} 将替换为报告下载链接（${linkTitle.value}）。`
    : `正文未包含 ${placeholder}，报告链接将追加在正文末尾。`);
</script>

<template>
  <section class="notify-body-config">
    <h4>通知邮件正文（非必填）</h4>
    <p>可用占位符 <code>{链接}</code>，发送时替换为报告下载链接。保留空格和换行，Tab 插入缩进，Shift+Tab 移出输入框。</p>
    <div><button type="button" :disabled="disabled" @click="insert(placeholder)">插入链接占位符</button></div>
    <textarea ref="input" v-model="body" :disabled="disabled" aria-label="通知邮件正文模板" rows="5" placeholder="非必填，留空时使用后台原有的通知邮件拼接模板。" @keydown="keydown"></textarea>
    <small>{{ hint }}</small>
    <div v-if="preview" class="body-template-preview" aria-label="邮件正文预览">
      <span>邮件预览</span><p v-html="preview"></p>
    </div>
  </section>
</template>

<style scoped>
.notify-body-config{display:grid;gap:10px;margin-top:14px;padding:14px;border:1px solid #dbeafe;border-radius:8px}.notify-body-config h4,.notify-body-config p{margin:0}.notify-body-config>p,.notify-body-config small{font-size:12px;color:#64748b;line-height:1.6}.notify-body-config code{padding:1px 5px;border-radius:4px;background:#eff6ff;color:#2563eb}.notify-body-config textarea{width:100%;padding:9px 11px;border:1px solid #cbd5e1;border-radius:6px;font:inherit;line-height:1.7;resize:vertical;box-sizing:border-box;tab-size:4}.notify-body-config button{border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc;padding:6px 10px;cursor:pointer}.notify-body-config button:disabled{opacity:.5;cursor:default}.body-template-preview{display:grid;gap:6px;padding:10px 12px;border:1px dashed #cbd5e1;border-radius:6px;background:#f8fafc;overflow-wrap:anywhere}.body-template-preview>span{font-size:11px;color:#94a3b8}.body-template-preview p{font-size:13px;color:#0f172a;line-height:1.8}.body-template-preview :deep(.preview-link){color:#2563eb;text-decoration:underline}
</style>
