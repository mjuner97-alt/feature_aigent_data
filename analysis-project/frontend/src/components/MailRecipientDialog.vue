<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue';
import { batchUserNames, searchSkillUsers } from '../api/skill';
const props = defineProps<{ open: boolean; to: string[]; cc: string[]; names: Record<string, string> }>();
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'confirm', value: { to: string[]; cc: string[]; names: Record<string, string> }): void }>();
const to = ref<string[]>([]), cc = ref<string[]>([]), names = ref<Record<string, string>>({});
const keyword = ref(''), busy = ref(false), message = ref('');
const results = ref<{ userId: string; name: string }[]>([]);
const panel = ref<HTMLElement | null>(null), queryInput = ref<HTMLTextAreaElement | null>(null);
let previousFocus: HTMLElement | null = null;
let generation = 0;
function restoreFocus() { previousFocus?.focus?.(); previousFocus = null; }
onUnmounted(restoreFocus);
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return; }
  if (event.key !== 'Tab') return;
  const controls = panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), textarea, [tabindex="0"]');
  if (!controls?.length) return;
  const first = controls[0], last = controls[controls.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
watch(() => props.open, open => {
  generation++;
  busy.value = false;
  if (!open) { restoreFocus(); return; }
  previousFocus = typeof document === 'undefined' ? null : document.activeElement as HTMLElement;
  const version = generation;
  nextTick(() => { if (version === generation) queryInput.value?.focus?.(); });
  to.value = [...new Set(props.to)];
  cc.value = [...new Set(props.cc)].filter(id => !to.value.includes(id));
  names.value = { ...props.names };
  keyword.value = ''; results.value = []; message.value = '';
}, { immediate: true });
function add(ids: string[], group: 'to' | 'cc') {
  const target = group === 'to' ? to : cc, other = group === 'to' ? cc : to;
  other.value = other.value.filter(id => !ids.includes(id));
  target.value = [...new Set([...target.value, ...ids])];
}
async function search() {
  if (!keyword.value.trim() || busy.value) return;
  const version = generation;
  busy.value = true; message.value = ''; results.value = [];
  try {
    const found = await searchSkillUsers(keyword.value.trim());
    if (version === generation) { results.value = found; if (!found.length) message.value = '未找到匹配人员'; }
  } catch { if (version === generation) message.value = '搜索失败，请重试'; }
  finally { if (version === generation) busy.value = false; }
}
async function batch(group: 'to' | 'cc') {
  const ids = [...new Set(keyword.value.split(/[\s,，;；、]+/).filter(Boolean))];
  if (!ids.length || busy.value) return;
  const version = generation;
  busy.value = true; message.value = ''; results.value = [];
  try {
    const found = await batchUserNames(ids);
    if (version !== generation) return;
    const matched = ids.filter(id => Object.hasOwn(found, id));
    names.value = { ...names.value, ...found }; add(matched, group);
    const missing = ids.filter(id => !Object.hasOwn(found, id));
    message.value = missing.length ? `已加入 ${matched.length} 人；未匹配：${missing.join('、')}（请检查后重试）` : `已加入 ${matched.length} 人`;
  } catch { if (version === generation) message.value = '匹配失败，请重试'; }
  finally { if (version === generation) busy.value = false; }
}
function select(item: { userId: string; name: string }, group: 'to' | 'cc') { names.value[item.userId] = item.name; add([item.userId], group); }
function close() { generation++; emit('update:open', false); }
function confirm() { emit('confirm', { to: [...to.value], cc: [...cc.value], names: { ...names.value } }); close(); }
</script>
<template>
  <Teleport to="body">
    <div v-if="open" class="mail-mask" @click.self="close">
      <section ref="panel" class="mail-dialog" @keydown="keydown" role="dialog" aria-modal="true" aria-label="管理邮件收件人">
        <header><h3>管理邮件收件人</h3><button type="button" aria-label="关闭" @click="close">×</button></header>
        <label for="mail-recipient-query">搜索姓名或粘贴统一认证号</label>
        <textarea ref="queryInput" id="mail-recipient-query" v-model="keyword" rows="3" placeholder="输入姓名搜索，或粘贴统一认证号（逗号、分号、空格、换行分隔）" />
        <div class="mail-actions"><button type="button" :disabled="busy" @click="search">搜索</button><button type="button" :disabled="busy" @click="batch('to')">批量加入收件人</button><button type="button" :disabled="busy" @click="batch('cc')">批量加入抄送</button></div>
        <p v-if="busy" role="status">正在匹配人员…</p><p v-if="message" role="status">{{ message }}</p>
        <div class="mail-results"><div v-for="item in results" :key="item.userId"><span>{{ item.name || item.userId }}({{ item.userId }})</span><button type="button" @click="select(item, 'to')">加入收件人</button><button type="button" @click="select(item, 'cc')">加入抄送</button></div></div>
        <div class="mail-previews">
          <section><h4>收件人 TO（{{ to.length }}）</h4><p v-if="!to.length">未配置时通知流程触发人</p><ol><li v-for="(id, index) in to" :key="id"><span>{{ names[id] || id }}({{ id }})</span><button type="button" :aria-label="`将 ${id} 移至抄送`" @click="add([id], 'cc')">移至抄送</button><button type="button" :aria-label="`删除收件人 ${id}`" @click="to.splice(index, 1)">删除</button></li></ol></section>
          <section><h4>抄送 CC（{{ cc.length }}）</h4><p v-if="!cc.length">暂无抄送人</p><ol><li v-for="(id, index) in cc" :key="id"><span>{{ names[id] || id }}({{ id }})</span><button type="button" :aria-label="`将 ${id} 移至收件人`" @click="add([id], 'to')">移至收件人</button><button type="button" :aria-label="`删除抄送人 ${id}`" @click="cc.splice(index, 1)">删除</button></li></ol></section>
        </div>
        <footer><button type="button" @click="close">取消</button><button type="button" :disabled="busy" @click="confirm">确认</button></footer>
      </section>
    </div>
  </Teleport>
</template>
<style scoped>
.mail-mask{position:fixed;inset:0;background:#0007;z-index:2200;display:flex;align-items:center;justify-content:center;padding:24px}.mail-dialog{background:white;border-radius:12px;padding:24px;width:min(900px,100%);max-height:85vh;overflow:auto;color:#243247;box-shadow:0 20px 60px #0003}.mail-dialog header,.mail-dialog footer,.mail-actions,.mail-results>div,.mail-previews li{display:flex;align-items:center;gap:10px}.mail-dialog header{justify-content:space-between}.mail-dialog h3{margin:0 0 20px}.mail-dialog label{display:block;margin-bottom:8px}.mail-dialog textarea{box-sizing:border-box;width:100%;padding:10px;border:1px solid #cbd5e1;border-radius:6px;font:inherit}.mail-actions{flex-wrap:wrap;margin-top:10px}.mail-dialog button{border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc;padding:6px 10px;cursor:pointer;white-space:nowrap}.mail-dialog button:disabled{opacity:.5;cursor:wait}.mail-previews{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:20px}.mail-previews section{border:1px solid #e2e8f0;border-radius:8px;padding:12px;min-width:0}.mail-previews h4{margin:0 0 12px}.mail-previews ol{padding:0;list-style:none;margin:0}.mail-previews li{flex-wrap:wrap;margin:8px 0}.mail-previews li span,.mail-results span{flex:1;overflow-wrap:anywhere}.mail-results>div{padding:8px 0}.mail-dialog footer{justify-content:flex-end;margin-top:24px}.mail-dialog footer button:last-child{background:#2563eb;color:white;border-color:#2563eb}@media(max-width:650px){.mail-previews{grid-template-columns:1fr}.mail-mask{padding:12px}}
</style>
