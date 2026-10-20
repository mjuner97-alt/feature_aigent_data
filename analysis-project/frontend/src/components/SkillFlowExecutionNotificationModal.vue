<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { getSkillFlowExecutionNotifySettings, resendSkillFlowExecutionNotification } from '../api/skillFlow';
import { batchUserNames } from '../api/skill';
import MailRecipientDialog from './MailRecipientDialog.vue';
import NotifyBodyTemplateEditor from './NotifyBodyTemplateEditor.vue';

const props = defineProps<{ open: boolean; executionId: number | null }>();
const emit = defineEmits<{ (event: 'update:open', value: boolean): void; (event: 'sent'): void }>();
const savedTo = ref<string[]>([]), savedCc = ref<string[]>([]), names = ref<Record<string, string>>({});
const savedTemplate = ref(''), bodyTemplate = ref(''), flowName = ref('');
const reportLinkTitle = ref<string | undefined>();
const loading = ref(false), ready = ref(false), sending = ref(false);
const error = ref(''), successMessage = ref('');
let generation = 0;
let successTimer: ReturnType<typeof setTimeout> | undefined;

async function loadDefaults() {
  const version = ++generation, id = props.executionId;
  ready.value = false; error.value = ''; savedTo.value = []; savedCc.value = []; names.value = {}; savedTemplate.value = ''; bodyTemplate.value = ''; flowName.value = ''; reportLinkTitle.value = undefined;
  if (!props.open || id == null) { loading.value = false; return; }
  loading.value = true;
  try {
    const settings = await getSkillFlowExecutionNotifySettings(id);
    if (version !== generation) return;
    const to = [...new Set(settings.notifyReceiversByTrigger?.AUTO_METRIC ?? settings.notifyReceivers ?? [])];
    const cc = [...new Set(settings.ccReceiversByTrigger?.AUTO_METRIC ?? settings.ccReceivers ?? [])].filter(uid => !to.includes(uid));
    // Display failures must never remove saved IDs from the actual recipients.
    const displayNames = await batchUserNames([...to, ...cc]).catch(() => ({}));
    if (version !== generation) return;
    savedTemplate.value = settings.notifyBodyTemplate ?? ''; bodyTemplate.value = savedTemplate.value; flowName.value = settings.flowName ?? ''; reportLinkTitle.value = settings.reportLinkTitle;
    savedTo.value = to; savedCc.value = cc; names.value = displayNames; ready.value = true;
  } catch (e) {
    if (version === generation) error.value = e instanceof Error ? e.message : '读取已保存名单失败，请重试';
  } finally { if (version === generation) loading.value = false; }
}
watch(() => [props.open, props.executionId] as const, () => {
  sending.value = false;
  if (props.open) {
    successMessage.value = '';
    if (successTimer) clearTimeout(successTimer);
  }
  void loadDefaults();
}, { immediate: true });
function close() { if (!sending.value) { generation++; emit('update:open', false); } }
async function send(value: { to: string[]; cc: string[] }) {
  if (sending.value || loading.value || !ready.value || !props.open || props.executionId == null) return;
  if (bodyTemplate.value.length > 2000) { error.value = '通知邮件正文模板超长（上限 2000 字符）'; return; }
  if (!value.to.length) { error.value = '请至少选择一位收件人'; return; }
  const version = generation, id = props.executionId;
  sending.value = true; error.value = '';
  try {
    await resendSkillFlowExecutionNotification(id, [...value.to], [...value.cc], bodyTemplate.value);
    if (version !== generation) return;
    successMessage.value = '通知已提交，请在任务详情查看结果';
    successTimer = setTimeout(() => { successMessage.value = ''; }, 3000);
    emit('sent');
    emit('update:open', false);
  } catch (e) {
    if (version === generation) error.value = e instanceof Error ? e.message : '发送通知失败';
  } finally { if (version === generation) sending.value = false; }
}
onUnmounted(() => { generation++; if (successTimer) clearTimeout(successTimer); });
</script>

<template>
  <MailRecipientDialog v-if="open && ready" mode="send" :open="open" :to="savedTo" :cc="savedCc" :names="names" :submitting="sending" :error="error" :dirty="bodyTemplate !== savedTemplate" @update:open="close" @confirm="send"><NotifyBodyTemplateEditor v-model="bodyTemplate" :flow-name="flowName" :report-link-title="reportLinkTitle" :disabled="sending" /></MailRecipientDialog>
  <Teleport to="body">
    <div v-if="successMessage" class="success-toast" role="status">{{ successMessage }}</div>
    <div v-if="open && !ready" class="loading-mask" @click.self="close">
      <section class="loading-dialog" role="dialog" aria-modal="true" aria-label="发送通知" :aria-busy="loading">
        <h3>发送通知</h3>
        <p v-if="loading" role="status">正在加载已保存名单…</p>
        <p v-else role="alert">{{ error }}</p>
        <footer><button type="button" @click="close">取消</button><button v-if="!loading" type="button" @click="loadDefaults">重试</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.loading-mask{box-sizing:border-box;position:fixed;inset:0;background:#0007;z-index:2200;display:flex;align-items:center;justify-content:center;padding:24px 24px 24px 248px}
.loading-dialog{box-sizing:border-box;background:white;border-radius:12px;padding:24px;width:min(1320px,100%);color:#243247;box-shadow:0 20px 60px #0003}.loading-dialog h3{margin:0 0 20px}.loading-dialog footer{display:flex;justify-content:flex-end;gap:10px;margin-top:24px}.loading-dialog button{border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc;padding:6px 10px;cursor:pointer}.loading-dialog [role="alert"]{color:#b91c1c}
.success-toast{position:fixed;top:20px;left:50%;z-index:2300;transform:translateX(-50%);padding:8px 20px;border-radius:8px;background:#16a34a;box-shadow:0 4px 12px #0003;color:#fff;font-size:14px}
@media(max-width:1100px){.loading-mask{padding:16px}}
</style>
