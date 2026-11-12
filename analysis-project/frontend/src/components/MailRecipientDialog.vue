<script setup lang="ts">
import OrderedRecipientList from './OrderedRecipientList.vue';
import { nextTick, onUnmounted, ref, watch } from 'vue';
import type { TooltipInstance } from 'element-plus';
import { searchSkillUsers } from '../api/skill';
import { lookupRecipients, lookupMessage } from '../api/recipientLookup';
type RecipientPerson = { userId: string; name: string; department?: string | null; positionRole?: string | null };
const props = withDefaults(
  defineProps<{
    open: boolean;
    to: string[];
    cc: string[];
    names: Record<string, string>;
    mode?: 'edit' | 'send' | 'batch';
    submitting?: boolean;
    error?: string;
    dirty?: boolean;
    searchUsers?: (keyword: string) => Promise<RecipientPerson[]>;
  }>(),
  { mode: 'edit', submitting: false, error: '', dirty: false }
);
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'confirm', value: { to: string[]; cc: string[]; names: Record<string, string> }): void;
}>();
const to = ref<string[]>([]),
  cc = ref<string[]>([]),
  names = ref<Record<string, string>>({});
const keyword = ref(''),
  busy = ref(false),
  message = ref('');
const results = ref<RecipientPerson[]>([]);
const panel = ref<HTMLElement | null>(null),
  queryInput = ref<HTMLTextAreaElement | null>(null);
const recipientHint = ref<TooltipInstance>(),
  queryHint = ref<TooltipInstance>();
let previousFocus: HTMLElement | null = null;
let generation = 0;
let initialRecipients = '';
const recipientSnapshot = () => JSON.stringify([to.value, cc.value]);
function requestClose() {
  if (props.submitting) return;
  if (busy.value || props.dirty || recipientSnapshot() !== initialRecipients) {
    message.value =
      props.mode !== 'edit'
        ? '通知内容或名单已调整，请点击“确认发送”提交；点击“取消”放弃本次调整。'
        : '名单尚未确认，请点击“确认”保留修改，再保存流程；点击“取消”放弃修改。';
    return;
  }
  close();
}
function restoreFocus() {
  previousFocus?.focus?.();
  previousFocus = null;
}
onUnmounted(() => {
  generation++;
  restoreFocus();
});
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    requestClose();
    return;
  }
  if (event.key !== 'Tab') return;
  const controls = panel.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), textarea:not(:disabled), [tabindex="0"]'
  );
  if (!controls?.length) return;
  const first = controls[0],
    last = controls[controls.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}
watch(
  () => props.open,
  (open) => {
    generation++;
    busy.value = false;
    if (!open) {
      restoreFocus();
      return;
    }
    previousFocus = typeof document === 'undefined' ? null : (document.activeElement as HTMLElement);
    const version = generation;
    nextTick(() => {
      if (version === generation) queryInput.value?.focus?.();
    });
    to.value = [...new Set(props.to)];
    cc.value = [...new Set(props.cc)].filter((id) => !to.value.includes(id));
    initialRecipients = recipientSnapshot();
    names.value = { ...props.names };
    keyword.value = '';
    results.value = [];
    message.value = '';
    if (props.searchUsers) void loadSource(version);
  },
  { immediate: true }
);
function searchPeople(keyword: string): Promise<RecipientPerson[]> {
  return props.searchUsers ? props.searchUsers(keyword) : searchSkillUsers(keyword);
}
async function loadSource(version: number) {
  busy.value = true;
  try {
    // 仅解析已保存的收件人用于展示；输入查询条件前不加载搜索结果。
    const ids = [...new Set([...to.value, ...cc.value])].filter((id) => !names.value[id]);
    const found = await Promise.allSettled(
      ids.map(async (id) => (await searchPeople(id)).find((person) => person.userId === id))
    );
    if (version !== generation) return;
    for (const result of found)
      if (result.status === 'fulfilled' && result.value) names.value[result.value.userId] = result.value.name;
  } catch {
    if (version === generation) message.value = '读取已选人员姓名失败，请输入姓名或认证号重试';
  } finally {
    if (version === generation) busy.value = false;
  }
}
function add(ids: string[], group: 'to' | 'cc') {
  const target = group === 'to' ? to : cc,
    other = group === 'to' ? cc : to;
  other.value = other.value.filter((id) => !ids.includes(id));
  target.value = [...new Set([...target.value, ...ids])];
}
async function search() {
  if (!keyword.value.trim() || busy.value) return;
  const version = generation;
  busy.value = true;
  message.value = '';
  results.value = [];
  try {
    const found = await lookupRecipients(keyword.value, searchPeople);
    if (version === generation) {
      results.value = found.items;
      message.value = lookupMessage(found);
    }
  } catch {
    if (version === generation) message.value = '搜索失败，请重试';
  } finally {
    if (version === generation) busy.value = false;
  }
}
async function batch(group: 'to' | 'cc') {
  const ids = [...new Set(keyword.value.split(/[\s,，;；、]+/).filter(Boolean))];
  if (!ids.length || busy.value) return;
  const version = generation;
  busy.value = true;
  message.value = '';
  results.value = [];
  try {
    const found = await lookupRecipients(keyword.value, searchPeople, true);
    if (version !== generation) return;
    for (const person of found.matched) names.value[person.userId] = person.name || person.userId;
    add(
      found.matched.map((person) => person.userId),
      group
    );
    results.value = found.items;
    message.value = lookupMessage(found, true);
  } catch {
    if (version === generation) message.value = '匹配失败，请重试';
  } finally {
    if (version === generation) busy.value = false;
  }
}
function select(item: { userId: string; name: string }, group: 'to' | 'cc') {
  names.value[item.userId] = item.name;
  add([item.userId], group);
}
function restoreDefaults() {
  to.value = [...new Set(props.to)];
  cc.value = [...new Set(props.cc)].filter((id) => !to.value.includes(id));
  names.value = { ...props.names };
  keyword.value = '';
  results.value = [];
  message.value = '';
}
function close() {
  if (props.submitting) return;
  generation++;
  emit('update:open', false);
}
function confirm() {
  if (busy.value || props.submitting || (props.mode !== 'edit' && !to.value.length)) return;
  emit('confirm', { to: [...to.value], cc: [...cc.value], names: { ...names.value } });
  if (props.mode !== 'send') close();
}
</script>
<template>
  <Teleport to="body">
    <div v-if="open" class="mail-mask" @click.self="requestClose">
      <section ref="panel" class="mail-dialog" @keydown="keydown" role="dialog" aria-modal="true" :aria-label="mode === 'send' ? '发送通知' : mode === 'batch' ? '调整本次收件人' : '管理邮件收件人'" :aria-busy="submitting">
        <header><div class="mail-heading"><span class="mail-eyebrow">邮件通知</span><h3>{{ mode === 'send' ? '发送通知' : mode === 'batch' ? '调整本次收件人' : '管理邮件收件人' }}</h3></div><button class="mail-close" type="button" aria-label="关闭" :disabled="submitting" @click="requestClose">×</button></header>
        <div class="mail-content"><p class="mail-description" v-if="mode === 'send'">默认显示长任务最新保存的名单。本次调整仅用于此次通知，不修改长任务配置。</p>
        <p class="mail-description" v-else-if="mode === 'batch'">本次调整只用于当前选中的团队报告，不修改任务配置。</p>
        <p class="mail-description" v-else>调整名单后请点击“确认”，再点击“保存流程”保存。</p>
        <fieldset :disabled="submitting || busy">
        <div class="mail-search"><label for="mail-recipient-query">搜索或批量加入姓名、统一认证号</label>
        <textarea ref="queryInput" id="mail-recipient-query" v-model="keyword" rows="2" placeholder="输入姓名或统一认证号，支持批量粘贴" />
        <small class="mail-help">多个姓名或账号可用逗号、分号、空格或换行分隔；同名人员请搜索后选择。</small><div class="mail-actions"><button class="search-button" type="button" :disabled="busy" @click="search">搜索</button><button type="button" :disabled="busy" @click="batch('to')">批量加入收件人</button><button type="button" :disabled="busy" @click="batch('cc')">批量加入抄送</button></div>
        </div><p class="mail-status" v-if="busy" role="status">正在匹配人员…</p><p class="mail-status" v-if="message" role="status">{{ message }}</p>
        <div v-if="mode !== 'edit'" class="mail-actions"><button type="button" @click="to = []; cc = []">全部清空</button><button type="button" @click="restoreDefaults">恢复默认名单</button></div>
        <div v-if="results.length" class="mail-results"><div v-for="item in results" :key="item.userId"><span class="result-person" :title="`${item.name || item.userId}(${item.userId}) ${item.department || ''}`"><strong>{{ item.name || item.userId }}({{ item.userId }})</strong> <small v-if="item.department">{{ item.department }}</small><small v-if="item.positionRole" class="role-badge">{{ item.positionRole }}</small></span><button type="button" @click="select(item, 'to')">加入收件人</button><button type="button" @click="select(item, 'cc')">加入抄送</button></div></div>
        <div class="mail-previews">
          <section class="recipient-card"><div class="recipient-card-heading"><h4>收件人 <span>TO</span><b>（{{ to.length }}）</b></h4><button v-if="mode !== 'edit'" type="button" @click="to = []">清空收件人</button></div><p class="recipient-empty" v-if="!to.length">{{ mode !== 'edit' ? '请至少添加一位收件人' : '未配置时通知流程触发人' }}</p><OrderedRecipientList v-model="to" label="收件人" :disabled="submitting || busy">
            <template #default="{ id }">{{ names[id] || id }}({{ id }})</template>
            <template #actions="{ id, index }"><button type="button" :aria-label="`将 ${id} 移至抄送`" @click="add([id], 'cc')">移至抄送</button><button type="button" :aria-label="`删除收件人 ${id}`" @click="to.splice(index, 1)">删除</button></template>
          </OrderedRecipientList></section>
          <section class="recipient-card"><div class="recipient-card-heading"><h4>抄送 <span>CC</span><b>（{{ cc.length }}）</b></h4><button v-if="mode !== 'edit'" type="button" @click="cc = []">清空抄送</button></div><p class="recipient-empty" v-if="!cc.length">暂无抄送人</p><OrderedRecipientList v-model="cc" label="抄送" :disabled="submitting || busy">
            <template #default="{ id }">{{ names[id] || id }}({{ id }})</template>
            <template #actions="{ id, index }"><button type="button" :aria-label="`将 ${id} 移至收件人`" @click="add([id], 'to')">移至收件人</button><button type="button" :aria-label="`删除抄送 ${id}`" @click="cc.splice(index, 1)">删除</button></template>
          </OrderedRecipientList></section>
        </div>
        <slot />
        </fieldset>
        <p v-if="error" role="alert">{{ error }}</p></div>
        <footer><span class="mail-footer-summary">已选收件人 {{ to.length }} 人 · 抄送 {{ cc.length }} 人</span><button type="button" :disabled="submitting" @click="close">取消</button><button type="button" :disabled="busy || submitting || (mode !== 'edit' && !to.length)" @click="confirm">{{ submitting ? '提交中…' : mode === 'send' ? `确认发送（收件人 ${to.length} 人，抄送 ${cc.length} 人）` : mode === 'batch' ? '确认调整' : '确认' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>
<style scoped>
.mail-mask{box-sizing:border-box;position:fixed;inset:0;background:rgba(15,23,42,.46);z-index:2200;display:flex;align-items:center;justify-content:center;padding:28px;overflow-y:auto;backdrop-filter:blur(3px)}
.mail-dialog{box-sizing:border-box;width:min(1040px,100%);max-height:calc(100vh - 56px);max-height:calc(100dvh - 56px);min-height:0;flex-shrink:0;display:flex;flex-direction:column;overflow:hidden;background:#fff;border:1px solid #e2e8f0;border-radius:16px;color:#243247;box-shadow:0 24px 80px #0f172a33;font-size:14px;line-height:1.6}
.mail-dialog header{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:22px 28px;border-bottom:1px solid #edf1f7;background:linear-gradient(110deg,#f8fbff,#fff)}
.mail-eyebrow{font-size:11px;color:#64748b;letter-spacing:2px}.mail-dialog h3{margin:3px 0 0;font-size:20px;font-weight:650;color:#172b4d}.mail-content{box-sizing:border-box;flex:1 1 auto;padding:20px 28px 24px;min-height:0;max-height:calc(100vh - 240px);max-height:calc(100dvh - 240px);overflow-y:auto;overflow-x:hidden;overscroll-behavior-y:contain;scrollbar-gutter:stable}.mail-description{margin:0 0 18px;color:#64748b;font-size:13px}
.mail-dialog fieldset{border:0;padding:0;margin:0;min-width:0}.mail-search{padding:16px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px}.mail-dialog label{display:block;margin-bottom:8px;font-weight:600;color:#334155}.mail-dialog textarea{box-sizing:border-box;width:100%;padding:11px 13px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;color:#1e293b;font:inherit;line-height:1.6;resize:vertical;min-height:76px;transition:border-color .15s,box-shadow .15s}.mail-dialog textarea::placeholder{color:#94a3b8}.mail-dialog textarea:focus{outline:0;border-color:#6095f5;box-shadow:0 0 0 3px #2563eb12}.mail-help{display:block;margin-top:6px;color:#7b8ba1;font-size:12px}
.mail-dialog button{border:1px solid #d8e1ed;border-radius:7px;background:#fff;color:#475569;padding:7px 12px;font:inherit;font-size:13px;cursor:pointer;white-space:nowrap;transition:background .15s,border-color .15s}.mail-dialog button:hover:not(:disabled){background:#eff6ff;border-color:#93b4ed;color:#2563eb}.mail-dialog button:focus-visible{outline:2px solid #2563eb;outline-offset:2px}.mail-dialog button:disabled{opacity:.45;cursor:default}.mail-dialog .mail-close{font-size:21px;padding:0;width:32px;height:32px;line-height:1;border-color:transparent;background:transparent;color:#64748b}.mail-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.mail-dialog .search-button{background:#2563eb;border-color:#2563eb;color:white}.mail-status{padding:9px 12px;border-radius:7px;background:#eff6ff;color:#315f9c;font-size:13px;margin:12px 0}
.mail-results{max-height:220px;overflow:auto;margin-top:14px;border:1px solid #e2e8f0;border-radius:9px}.mail-results>div{display:flex;align-items:center;gap:8px;padding:8px 12px;border-bottom:1px solid #edf2f7}.mail-results>div:last-child{border-bottom:0}.result-person{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.result-person strong{display:inline;font-weight:500}.result-person small{display:inline;margin-left:10px;color:#94a3b8;font-size:12px}
.mail-previews{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:20px}.recipient-card{border:1px solid #e2e8f0;border-radius:10px;padding:14px;min-width:0;background:#fff}.recipient-card-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;min-height:30px}.recipient-card h4{display:flex;gap:8px;align-items:center;margin:0;font-size:14px;color:#1e293b}.recipient-card h4 span{font-size:11px;color:#94a3b8;font-weight:500}.recipient-card h4 b{font-size:12px;background:#eff6ff;border-radius:6px;color:#2563eb;min-width:24px;text-align:center}.recipient-card-heading button{padding:3px 6px;font-size:12px;border:0;color:#64748b}.recipient-empty{margin:14px 0 6px;padding:18px 10px;background:#f8fafc;border:1px dashed #e2e8f0;border-radius:7px;color:#94a3b8;text-align:center;font-size:12px}.recipient-card :deep(.recipient-order-list){max-height:260px;overflow:auto}.recipient-card :deep(.recipient-row){background:#f8fafc;margin-top:8px;padding:8px;border:1px solid #edf2f7;border-radius:7px;font-size:12px}.recipient-card :deep(.recipient-row button){font-size:11px;padding:3px 6px}.recipient-card :deep(.recipient-label){flex:1 1 140px;color:#334155;font-weight:500}.recipient-card :deep(.order-help){font-size:11px;margin:8px 0;color:#94a3b8}
.mail-dialog [role="alert"]{padding:10px 12px;color:#b91c1c;background:#fef2f2;border-radius:7px}.mail-dialog footer{display:flex;align-items:center;justify-content:flex-end;gap:10px;padding:16px 28px;border-top:1px solid #e2e8f0;background:#fafcff;flex-shrink:0}.mail-footer-summary{margin-right:auto;font-size:12px;color:#64748b}.mail-dialog footer button:last-child{background:#2563eb;color:white;border-color:#2563eb;box-shadow:0 3px 8px #2563eb20}.mail-dialog footer button:last-child:hover:not(:disabled){background:#1d4ed8;color:#fff}
@media(max-width:700px){.mail-mask{padding:12px}.mail-dialog{max-height:calc(100vh - 24px);max-height:calc(100dvh - 24px)}.mail-dialog header{padding:16px}.mail-content{padding:16px;max-height:calc(100vh - 210px);max-height:calc(100dvh - 210px)}.mail-previews{grid-template-columns:1fr;gap:12px}.mail-dialog footer{padding:12px 16px;flex-wrap:wrap}.mail-footer-summary{width:100%}.mail-results>div{flex-wrap:wrap}.result-person{flex-basis:100%}}
</style>
