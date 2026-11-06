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
    mode?: 'edit' | 'send';
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
      props.mode === 'send'
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
  if (busy.value || props.submitting || (props.mode === 'send' && !to.value.length)) return;
  emit('confirm', { to: [...to.value], cc: [...cc.value], names: { ...names.value } });
  if (props.mode !== 'send') close();
}
</script>
<template>
  <Teleport to="body">
    <div v-if="open" class="mail-mask" @click.self="requestClose">
      <section
        ref="panel"
        class="mail-dialog"
        @keydown="keydown"
        role="dialog"
        aria-modal="true"
        :aria-label="mode === 'send' ? '发送通知' : '管理邮件收件人'"
        :aria-busy="submitting"
      >
        <header
          ><h3>{{ mode === 'send' ? '发送通知' : '管理邮件收件人' }}</h3
          ><button type="button" aria-label="关闭" :disabled="submitting" @click="requestClose">×</button></header
        >
        <div class="recipient-hint">
          <span>{{
            mode === 'send' ? '默认使用已保存名单，本次调整仅对本次发送生效。' : '调整后请确认，再保存流程。'
          }}</span>
          <el-tooltip
            ref="recipientHint"
            :trigger="['hover', 'focus']"
            placement="bottom"
            :z-index="2400"
            :popper-style="{ maxWidth: '320px', whiteSpace: 'pre-line', lineHeight: '1.7' }"
            :content="
              searchUsers
                ? '支持按姓名或统一认证号搜索并添加其他人员。\n收件人、抄送人及顺序均可调整。'
                : '支持按姓名或统一认证号搜索并添加人员。\n收件人、抄送人及顺序均可调整。'
            "
          >
            <button type="button" class="hint-icon" aria-label="查看名单调整说明" @click="recipientHint?.onOpen()"
              >ⓘ</button
            >
          </el-tooltip>
        </div>
        <fieldset :disabled="submitting || busy">
          <div class="recipient-query-label">
            <label for="mail-recipient-query">搜索或批量添加人员</label>
            <el-tooltip
              ref="queryHint"
              :trigger="['hover', 'focus']"
              placement="bottom"
              :z-index="2400"
              :popper-style="{ maxWidth: '320px', whiteSpace: 'pre-line', lineHeight: '1.7' }"
              content="多个条件可用逗号、分号、空格或换行分隔；同名人员请搜索后选择。"
            >
              <button type="button" class="hint-icon" aria-label="查看批量输入说明" @click="queryHint?.onOpen()"
                >ⓘ</button
              >
            </el-tooltip>
          </div>
          <textarea
            ref="queryInput"
            id="mail-recipient-query"
            v-model="keyword"
            rows="3"
            placeholder="输入姓名或统一认证号，支持批量粘贴"
          />
          <div class="mail-actions"
            ><button type="button" :disabled="busy" @click="search">搜索</button
            ><button type="button" :disabled="busy" @click="batch('to')">批量加入收件人</button
            ><button type="button" :disabled="busy" @click="batch('cc')">批量加入抄送</button></div
          >
          <p v-if="busy" role="status">正在匹配人员…</p><p v-if="message" role="status">{{ message }}</p>
          <div v-if="mode === 'send'" class="mail-actions"
            ><button
              type="button"
              @click="
                to = [];
                cc = [];
              "
              >全部清空</button
            ><button type="button" @click="restoreDefaults">恢复已保存名单</button></div
          >
          <div class="mail-results"
            ><div v-for="item in results" :key="item.userId"
              ><span
                >{{ item.name || item.userId }}({{ item.userId }}) {{ item.department || '' }}
                <small v-if="item.positionRole" class="role-badge">{{ item.positionRole }}</small></span
              ><button type="button" @click="select(item, 'to')">加入收件人</button
              ><button type="button" @click="select(item, 'cc')">加入抄送</button></div
            ></div
          >
          <div class="mail-previews">
            <section
              ><h4>收件人 TO（{{ to.length }}）</h4
              ><button v-if="mode === 'send'" type="button" @click="to = []">清空收件人</button
              ><p v-if="!to.length">{{ mode === 'send' ? '请至少添加一位收件人后发送' : '未配置时通知流程触发人' }}</p
              ><OrderedRecipientList v-model="to" label="收件人" :disabled="submitting || busy">
                <template #default="{ id }">{{ names[id] || id }}({{ id }})</template>
                <template #actions="{ id, index }"
                  ><button type="button" :aria-label="`将 ${id} 移至抄送`" @click="add([id], 'cc')">移至抄送</button
                  ><button type="button" :aria-label="`删除收件人 ${id}`" @click="to.splice(index, 1)"
                    >删除</button
                  ></template
                >
              </OrderedRecipientList></section
            >
            <section
              ><h4>抄送 CC（{{ cc.length }}）</h4
              ><button v-if="mode === 'send'" type="button" @click="cc = []">清空抄送</button
              ><p v-if="!cc.length">暂无抄送人</p
              ><OrderedRecipientList v-model="cc" label="抄送" :disabled="submitting || busy">
                <template #default="{ id }">{{ names[id] || id }}({{ id }})</template>
                <template #actions="{ id, index }"
                  ><button type="button" :aria-label="`将 ${id} 移至收件人`" @click="add([id], 'to')">移至收件人</button
                  ><button type="button" :aria-label="`删除抄送 ${id}`" @click="cc.splice(index, 1)"
                    >删除</button
                  ></template
                >
              </OrderedRecipientList></section
            >
          </div>
          <slot />
        </fieldset>
        <p v-if="error" role="alert">{{ error }}</p>
        <footer
          ><button type="button" :disabled="submitting" @click="close">取消</button
          ><button type="button" :disabled="busy || submitting || (mode === 'send' && !to.length)" @click="confirm">{{
            submitting
              ? '提交中…'
              : mode === 'send'
                ? `确认发送（收件人 ${to.length} 人，抄送 ${cc.length} 人）`
                : '确认'
          }}</button></footer
        >
      </section>
    </div>
  </Teleport>
</template>
<style scoped>
.recipient-hint,
.recipient-query-label {
  display: flex;
  align-items: center;
  gap: 6px;
}
.recipient-hint {
  margin: 0 0 16px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}
.recipient-query-label {
  margin-bottom: 8px;
}
.recipient-query-label label {
  margin-bottom: 0;
}
.mail-dialog .hint-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #64748b;
  font-size: 17px;
  line-height: 1;
  cursor: help;
}
.mail-dialog .hint-icon:hover,
.mail-dialog .hint-icon:focus-visible {
  background: #eff6ff;
  color: #2563eb;
}
.mail-dialog .hint-icon:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 2px;
}
.role-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 2px 7px;
  border-radius: 4px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
}
.mail-dialog fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.mail-dialog [role='alert'] {
  color: #b91c1c;
}
.mail-mask {
  box-sizing: border-box;
  position: fixed;
  inset: 0;
  background: #0007;
  z-index: 2200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 24px 24px 248px;
}
.mail-dialog {
  box-sizing: border-box;
  background: white;
  border-radius: 12px;
  padding: 24px;
  width: min(1320px, 100%);
  max-height: 92vh;
  overflow: auto;
  color: #243247;
  box-shadow: 0 20px 60px #0003;
}
.mail-dialog header,
.mail-dialog footer,
.mail-actions,
.mail-results > div {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mail-dialog header {
  justify-content: space-between;
}
.mail-dialog h3 {
  margin: 0 0 20px;
}
.mail-dialog label {
  display: block;
  margin-bottom: 8px;
}
.mail-dialog textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
}
.mail-actions {
  flex-wrap: wrap;
  margin-top: 10px;
}
.mail-dialog button {
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  background: #f8fafc;
  padding: 6px 10px;
  cursor: pointer;
  white-space: nowrap;
}
.mail-dialog button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.mail-previews {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 20px;
}
.mail-previews section {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
}
.mail-previews h4 {
  margin: 0 0 12px;
}
.mail-results span {
  flex: 1;
  overflow-wrap: anywhere;
}
.mail-results > div {
  padding: 8px 0;
}
.mail-dialog footer {
  justify-content: flex-end;
  margin-top: 24px;
}
.mail-dialog footer button:last-child {
  background: #2563eb;
  color: white;
  border-color: #2563eb;
}
@media (max-width: 1100px) {
  .mail-previews {
    grid-template-columns: 1fr;
  }
  .mail-mask {
    padding: 16px;
  }
  .mail-dialog {
    max-height: calc(100dvh - 32px);
  }
}
</style>
