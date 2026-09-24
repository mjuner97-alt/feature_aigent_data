<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { resendSkillFlowExecutionNotification } from '../api/skillFlow';
import { searchSkillUsers } from '../api/skill';

const props = defineProps<{ open: boolean; executionId: number | null }>();
const emit = defineEmits<{ (event: 'update:open', value: boolean): void; (event: 'sent'): void }>();
const keyword = ref('');
const results = ref<{ userId: string; name: string; department: string | null }[]>([]);
const selected = ref<{ userId: string; name: string }[]>([]);
const sending = ref(false);
const error = ref('');
const successMessage = ref('');
let successTimer: ReturnType<typeof setTimeout> | undefined;

watch(() => props.open, open => {
  if (open) { keyword.value = ''; results.value = []; selected.value = []; error.value = ''; }
});

async function search() {
  const value = keyword.value.trim();
  if (!value) { results.value = []; return; }
  try { results.value = (await searchSkillUsers(value)).filter(item => !selected.value.some(row => row.userId === item.userId)); }
  catch { results.value = []; }
}
function add(item: { userId: string; name: string }) { selected.value = [...selected.value, { userId: item.userId, name: item.name || item.userId }]; results.value = results.value.filter(row => row.userId !== item.userId); }
function remove(userId: string) { selected.value = selected.value.filter(item => item.userId !== userId); }
function close() { if (!sending.value) emit('update:open', false); }
async function send() {
  if (!props.executionId || !selected.value.length) { error.value = '请至少选择一位收件人'; return; }
  sending.value = true; error.value = '';
  try {
    await resendSkillFlowExecutionNotification(props.executionId, selected.value.map(item => item.userId));
    successMessage.value = '发送成功';
    if (successTimer) clearTimeout(successTimer);
    successTimer = setTimeout(() => { successMessage.value = ''; }, 3000);
    emit('sent');
    emit('update:open', false);
  }
  catch (e) { error.value = e instanceof Error ? e.message : '发送通知失败'; }
  finally { sending.value = false; }
}
onUnmounted(() => { if (successTimer) clearTimeout(successTimer); });
</script>

<template>
  <Teleport to="body">
    <div v-if="successMessage" class="success-toast">{{ successMessage }}</div>
    <div v-if="open" class="modal-mask" @click.self="close">
      <section class="modal" role="dialog" aria-modal="true" aria-label="发送通知">
        <header><h3>发送通知</h3><button class="close" :disabled="sending" @click="close">×</button></header>
        <main>
          <p class="hint">请选择本次通知的收件人</p>
          <div class="search"><input v-model="keyword" placeholder="输入姓名或统一认证号" @keyup.enter="search" /><button @click="search">搜索</button></div>
          <div v-if="results.length" class="results"><button v-for="item in results" :key="item.userId" @click="add(item)">{{ item.name || item.userId }}（{{ item.userId }}）</button></div>
          <div class="chips"><span v-for="item in selected" :key="item.userId">{{ item.name }}（{{ item.userId }}）<button @click="remove(item.userId)">×</button></span></div>
          <p v-if="error" class="error">{{ error }}</p>
        </main>
        <footer><button class="cancel" :disabled="sending" @click="close">取消</button><button class="confirm" :disabled="sending" @click="send">{{ sending ? '发送中…' : '确认发送' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-mask { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; background: rgb(15 23 42 / 45%); }
.success-toast { position: fixed; top: 20px; left: 50%; z-index: 2000; transform: translateX(-50%); padding: 8px 20px; border-radius: 8px; background: #16a34a; box-shadow: 0 4px 12px rgb(0 0 0 / 15%); color: #fff; font-size: 14px; }
.modal { width: min(520px, 92vw); border-radius: 8px; background: #fff; box-shadow: 0 12px 35px rgb(15 23 42 / 22%); }
header, footer { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid #e2e8f0; } footer { justify-content: flex-end; gap: 8px; border-top: 1px solid #e2e8f0; border-bottom: 0; } h3 { margin: 0; color: #0f172a; font-size: 16px; } .close { border: 0; background: none; color: #64748b; font-size: 22px; cursor: pointer; } main { padding: 18px; } .hint { margin: 0 0 10px; color: #475569; font-size: 13px; } .search { display: flex; gap: 8px; } input { flex: 1; padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 5px; } button { cursor: pointer; } .search button, .cancel, .confirm { padding: 7px 14px; border-radius: 5px; } .search button, .cancel { border: 1px solid #cbd5e1; background: #fff; color: #475569; } .confirm { border: 1px solid #2563eb; background: #2563eb; color: #fff; } button:disabled { opacity: .55; cursor: not-allowed; } .results { display: grid; gap: 3px; margin-top: 8px; } .results button { padding: 8px; border: 0; background: #f8fafc; color: #1e293b; text-align: left; } .results button:hover { background: #dbeafe; } .chips { display: flex; flex-wrap: wrap; gap: 6px; min-height: 36px; margin-top: 12px; } .chips span { padding: 5px 8px; border-radius: 4px; background: #dbeafe; color: #1e3a8a; font-size: 12px; } .chips button { margin-left: 5px; border: 0; background: none; color: #1e3a8a; } .error { margin: 10px 0 0; color: #b91c1c; font-size: 12px; }
</style>
