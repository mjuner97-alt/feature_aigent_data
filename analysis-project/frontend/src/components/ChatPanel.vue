<template>
  <div :style="S.root">
    <div class="chat-body-row" :style="S.bodyRow">
      <div ref="threadRef" class="message-thread" :style="S.thread">
        <div v-if="messages.length === 0" :style="S.empty">
          发送消息开始对话。<br />
          尝试：
          <code :style="{ background: '#e2e8f0', padding: '1px 6px', borderRadius: 4 }">
            分析2026年Q1各部门质量分趋势，生成详细报告
          </code>
        </div>
        <div
          v-for="m in messages"
          :key="m.id"
          class="message-bubble"
          :style="{ ...S.bubble, ...bubbleStyle(m) }"
        >
          <!-- Subagent label -->
          <div v-if="m.source" :style="S.subagentLabel">
            <span :style="S.subagentDot"></span>
            <span>{{ m.source }}</span>
          </div>
          <!-- Tool blocks -->
          <div v-if="m.tools.length > 0" :style="{ marginBottom: m.text ? 10 : 0 }">
            <ToolCallBlock
              v-for="t in m.tools"
              :key="t.id"
              :tool-name="t.name"
              :tool-call-id="t.id"
              :result="t.result"
            />
          </div>
          <!-- User input remains plain; every backend response uses Markdown. -->
          <template v-if="m.role === 'user'">
            {{ m.text || (m.pending ? '…' : '') }}
          </template>
          <!-- Assistant messages -->
          <template v-else>
            <details
              v-if="m.thinking"
              class="thinking-panel"
              :open="m.pending && !m.text"
            >
              <summary class="thinking-summary">
                <span class="thinking-indicator" :class="{ active: m.pending && !m.text }"></span>
                <span>思考过程</span>
                <span class="thinking-state">{{ m.pending && !m.text ? '思考中' : '已完成' }}</span>
              </summary>
              <div class="thinking-content">
                <Markdown :text="m.thinking" />
              </div>
            </details>
            <Markdown v-if="m.text" :text="m.text" />
            <span v-else-if="m.pending && !m.thinking" :style="{ color: '#94a3b8' }">…</span>
          </template>
        </div>
      </div>
      <div class="activity-column" :style="S.activityCol">
        <ActivityFeed :events="activityEvents" :active="busy" />
      </div>
    </div>
    <div class="chat-composer" :style="S.composer">
      <textarea
        ref="inputRef"
        :style="S.textarea"
        :value="input"
        @input="input = ($event.target as HTMLTextAreaElement).value"
        @keydown="handleKeyDown"
        :placeholder="busy ? '处理中…' : '输入消息，Enter 发送 / Shift+Enter 换行'"
        rows="1"
        autofocus
        :disabled="busy"
      />
      <button
        :style="{ ...S.send, ...(canSend ? {} : S.sendDisabled) }"
        @click="handleSend"
        :disabled="!canSend"
      >
        {{ busy ? '…' : '发送' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { streamChat, type ChatRequest, type ProcessEvent } from '../api/chat';
import { triggerInterrupt as apiTriggerInterrupt } from '../api/interrupt';
import type { SubagentPlanState } from '../types/sessionState';
import ToolCallBlock from './ToolCallBlock.vue';
import ActivityFeed from './ActivityFeed.vue';
import Markdown from './Markdown.vue';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Message {
  id: string;
  role: Role;
  text: string;
  thinking?: string;
  tools: ToolEntry[];
  pending?: boolean;
  source?: string | null;
}

const props = defineProps<{
  userId: string;
  conversationId: string | null;
  onUserMessage?: (text: string) => void;
  onSubagentPlanChange?: (plans: Record<string, SubagentPlanState>, todoCounts: Record<string, number>) => void;
  onStreamDone?: () => void;
}>();

const emit = defineEmits<{
  (e: 'conversation-id', id: string): void;
  (e: 'registerInterrupt', handle: { busy: boolean; interrupt: () => Promise<void> } | null): void;
}>();

const messages = ref<Message[]>([]);
const input = ref('');
const busy = ref(false);
const hasInFlight = ref(false);
const activityEvents = ref<ProcessEvent[]>([]);
const scriptOutput = ref('');
const threadRef = ref<HTMLDivElement | null>(null);
const inputRef = ref<HTMLTextAreaElement | null>(null);

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;
const logRetry = (ctx: string, msg: string) => console.log(`[ChatPanel] ${ctx}: retrying after error: ${msg}`);

// Subagent plan/todo state
const subagentPlans = ref<Record<string, SubagentPlanState>>({});
const subagentTodoCounts = ref<Record<string, number>>({});

// Stream abort control
let streamAbortCtrl: AbortController | null = null;
let streamEpoch = 0;

// Auto-scroll on new messages
watch(
  () => messages.value.map(m => `${m.text.length}:${m.thinking?.length ?? 0}`).join('|'),
  async () => {
    await nextTick();
    threadRef.value?.scrollTo({ top: threadRef.value.scrollHeight, behavior: 'smooth' });
  },
);

const canSend = computed(() => !busy.value && input.value.trim().length > 0);

// Expose interrupt handle to parent
defineExpose({
  busy: computed(() => hasInFlight.value),
  interrupt: () => triggerInterrupt(),
});

watch(
  () => hasInFlight.value,
  () => {
    emit('registerInterrupt', {
      busy: hasInFlight.value,
      interrupt: () => triggerInterrupt(),
    });
  },
  { immediate: true },
);

function bubbleStyle(m: Message) {
  if (m.role === 'user') return S.user;
  if (m.role === 'system') return S.system;
  if (m.source) return S.subagent;
  return S.assistant;
}

async function handleSend() {
  if (!canSend.value) return;
  const text = input.value.trim();
  input.value = '';
  await sendMessage(text);
}

async function sendMessage(text: string) {
  let convId = props.conversationId;
  if (!convId) {
    convId = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    emit('conversation-id', convId);
  }

  busy.value = true;
  hasInFlight.value = true;
  props.onUserMessage?.(text);

  const userMsg: Message = { id: nextId(), role: 'user' as Role, text, tools: [] };
  const replyMsg: Message = {
    id: nextId(), role: 'assistant' as Role, text: '', thinking: '', tools: [], pending: true,
  };
  messages.value = [...messages.value, userMsg, replyMsg];

  const subagentMsgIds: Record<string, string> = {};

  // Reset activity and subagent state
  activityEvents.value = [];
  scriptOutput.value = '';
  subagentPlans.value = {};
  subagentTodoCounts.value = {};
  props.onSubagentPlanChange?.({}, {});

  const myEpoch = ++streamEpoch;

  const abortCtrl = new AbortController();
  streamAbortCtrl = abortCtrl;

  const req: ChatRequest = {
    question: text,
    conversationId: convId,
    user_id: props.userId,
  };

  try {
    const evts = streamChat(req, abortCtrl.signal);
    for await (const evt of evts) {
      if (myEpoch !== streamEpoch) return; // superseded

      if (evt.type === 'think') {
        if (evt.action !== '已执行' && evt.chunk.length > 0) {
          messages.value = messages.value.map(m => m.id === replyMsg.id
            ? { ...m, thinking: (m.thinking ?? '') + evt.chunk }
            : m);
        }
      } else if (evt.type === 'text') {
        messages.value = messages.value.map(m => m.id === replyMsg.id
          ? { ...m, text: m.text + evt.chunk, pending: !evt.finish }
          : m);
        // Keep consuming the stream after text finishes: script_output and
        // tool_output SSE events can arrive during tool PostActing afterward.
      } else if (evt.type === 'process') {
        activityEvents.value = [...activityEvents.value, evt.process];

        const p = evt.process;
        if (p.eventType === 'tool_call_start' && p.source) {
          if (p.toolCallName === 'plan_enter') {
            subagentPlans.value = { ...subagentPlans.value, [p.source]: { agentName: p.source, planActive: true, planContent: null } };
            props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
          } else if (p.toolCallName === 'plan_exit') {
            subagentPlans.value = { ...subagentPlans.value, [p.source]: { agentName: p.source, planActive: false, planContent: null } };
            props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
          } else if (p.toolCallName === 'plan_write') {
            const existing = subagentPlans.value[p.source];
            if (existing) {
              subagentPlans.value = { ...subagentPlans.value, [p.source]: { ...existing, planContent: p.toolInput ?? null } };
              props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
            }
          } else if (p.toolCallName === 'todo_write') {
            subagentTodoCounts.value = { ...subagentTodoCounts.value, [p.source]: (subagentTodoCounts.value[p.source] ?? 0) + 1 };
            props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
          }
        }
      } else if (evt.type === 'scriptOutput') {
        if (!evt.output) continue;
        scriptOutput.value = scriptOutput.value ? `${scriptOutput.value}\n\n${evt.output}` : evt.output;
        const idx = evt.toolCallId
          ? activityEvents.value.findIndex(e => e.toolCallId === evt.toolCallId)
          : [...activityEvents.value].map((e, i) => ({ e, i })).reverse().find(({ e }) => e.toolCallName === 'script_exec')?.i ?? -1;
        if (idx >= 0) {
          activityEvents.value[idx] = { ...activityEvents.value[idx], toolOutput: scriptOutput.value };
        } else {
          activityEvents.value = [...activityEvents.value, {
            eventType: 'script_output',
            message: '完成：script_exec (SUCCESS)',
            source: null,
            toolCallId: evt.toolCallId,
            toolCallName: 'script_exec',
            toolCallState: 'SUCCESS',
            toolOutput: scriptOutput.value,
          }];
        }
      } else if (evt.type === 'toolOutputUpdate') {
        const update = evt.process;
        activityEvents.value = activityEvents.value.map(e =>
          e.toolCallId === update.toolCallId ? { ...e, toolOutput: update.toolOutput } : e,
        );
      } else if (evt.type === 'error') {
        const subIds = Object.values(subagentMsgIds);
        messages.value = messages.value.map(m =>
          m.id === replyMsg.id
            ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${evt.error}` }
            : subIds.includes(m.id) ? { ...m, pending: false } : m,
        );
      }
    }
  } catch (e: unknown) {
    if (e instanceof DOMException && e.name === 'AbortError') return;
    if (myEpoch !== streamEpoch) return;
    const msg = e instanceof Error ? e.message : 'stream failed';
    const isRetryable = msg.includes('429') || msg.includes('500') || msg.includes('Too Many Requests');
    if (isRetryable && myEpoch === streamEpoch) {
      logRetry('sendMessage', msg);
      await new Promise(r => setTimeout(r, 2000));
      if (myEpoch !== streamEpoch) return;
      try {
        const retryEvts = streamChat(req);
        for await (const evt of retryEvts) {
          if (myEpoch !== streamEpoch) return;
          // Process retry events (simplified — same logic as above)
          if (evt.type === 'think') {
            if (evt.action !== '已执行' && evt.chunk.length > 0) {
              messages.value = messages.value.map(m => m.id === replyMsg.id
                ? { ...m, thinking: (m.thinking ?? '') + evt.chunk }
                : m);
            }
          } else if (evt.type === 'text') {
            messages.value = messages.value.map(m => m.id === replyMsg.id
              ? { ...m, text: m.text + evt.chunk, pending: !evt.finish }
              : m);
            // Keep consuming trailing tool output events after text finishes.
          } else if (evt.type === 'process') {
            activityEvents.value = [...activityEvents.value, evt.process];
            const p = evt.process;
            if (p.eventType === 'tool_call_start' && p.source) {
              if (p.toolCallName === 'plan_enter') {
                subagentPlans.value = { ...subagentPlans.value, [p.source]: { agentName: p.source, planActive: true, planContent: null } };
                props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
              } else if (p.toolCallName === 'plan_exit') {
                subagentPlans.value = { ...subagentPlans.value, [p.source]: { agentName: p.source, planActive: false, planContent: null } };
                props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
              } else if (p.toolCallName === 'plan_write') {
                const existing = subagentPlans.value[p.source];
                if (existing) {
                  subagentPlans.value = { ...subagentPlans.value, [p.source]: { ...existing, planContent: p.toolInput ?? null } };
                  props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
                }
              } else if (p.toolCallName === 'todo_write') {
                subagentTodoCounts.value = { ...subagentTodoCounts.value, [p.source]: (subagentTodoCounts.value[p.source] ?? 0) + 1 };
                props.onSubagentPlanChange?.(subagentPlans.value, subagentTodoCounts.value);
              }
            }
          } else if (evt.type === 'toolOutputUpdate') {
            const update = evt.process;
            activityEvents.value = activityEvents.value.map(e => e.toolCallId === update.toolCallId ? { ...e, toolOutput: update.toolOutput } : e);
          }
        }
        return;
      } catch (retryErr: unknown) {
        const retryMsg = retryErr instanceof Error ? retryErr.message : 'retry failed';
        const subIds = Object.values(subagentMsgIds);
        messages.value = messages.value.map(m =>
          m.id === replyMsg.id
            ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${msg} (重试也失败: ${retryMsg})` }
            : subIds.includes(m.id) ? { ...m, pending: false } : m,
        );
        return;
      }
    }

    const subIds = Object.values(subagentMsgIds);
    messages.value = messages.value.map(m =>
      m.id === replyMsg.id
        ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${msg}` }
        : subIds.includes(m.id) ? { ...m, pending: false } : m,
    );
  } finally {
    if (streamAbortCtrl?.signal === abortCtrl.signal) {
      streamAbortCtrl = null;
    }
    if (myEpoch === streamEpoch) {
      messages.value = messages.value.map(m => m.id === replyMsg.id ? { ...m, pending: false } : m);
      busy.value = false;
      hasInFlight.value = false;
      inputRef.value?.focus();
      props.onStreamDone?.();
    }
  }
}

async function triggerInterrupt() {
  if (!busy.value || !hasInFlight.value) return;
  if (streamAbortCtrl) {
    streamAbortCtrl.abort();
    streamAbortCtrl = null;
  }
  streamEpoch++;
  try {
    await apiTriggerInterrupt({
      user_id: props.userId,
      conversationId: props.conversationId!,
    });
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'interrupt failed';
    messages.value = [...messages.value, { id: nextId(), role: 'system', text: `[interrupt error] ${msg}`, tools: [] }];
  }
  busy.value = false;
  hasInFlight.value = false;
  props.onStreamDone?.();
  inputRef.value?.focus();
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
}

const S = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#f8fafc' },
  bodyRow: { flex: 1, display: 'flex', minHeight: 0 },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
  activityCol: { width: 340, flexShrink: 0, borderLeft: '1px solid #e2e8f0', display: 'flex', flexDirection: 'column', minHeight: 0 },
  empty: { color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center', marginTop: 100 },
  bubble: { maxWidth: '78%', padding: '14px 18px', borderRadius: 14, fontSize: '0.95rem', lineHeight: 1.6, wordBreak: 'break-word' },
  user: { alignSelf: 'flex-end', background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)', color: '#ffffff', boxShadow: '0 2px 6px rgba(99,102,241,0.25)' },
  assistant: { alignSelf: 'flex-start', background: '#ffffff', color: '#0f172a', border: '1px solid #e2e8f0', boxShadow: '0 1px 2px rgba(15,23,42,0.04)' },
  subagent: { alignSelf: 'flex-start', background: '#eef2ff', color: '#0f172a', border: '1px solid #c7d2fe', boxShadow: '0 1px 2px rgba(99,102,241,0.10)' },
  subagentLabel: { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, paddingBottom: 6, borderBottom: '1px solid #c7d2fe', fontSize: '0.78rem', color: '#4338ca', fontWeight: 600, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
  subagentDot: { width: 6, height: 6, borderRadius: '50%', background: '#6366f1', flexShrink: 0 },
  system: { alignSelf: 'center', background: 'transparent', color: '#94a3b8', fontSize: '0.85rem', fontStyle: 'italic' },
  composer: { borderTop: '1px solid #e2e8f0', padding: '18px 28px', display: 'flex', gap: 12, background: '#ffffff' },
  textarea: { flex: 1, padding: '12px 16px', background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 10, color: '#0f172a', fontSize: '0.95rem', resize: 'none', minHeight: 48, maxHeight: 200, lineHeight: 1.55 },
  send: { padding: '0 24px', background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)', color: '#ffffff', border: 'none', borderRadius: 10, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600, boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)' },
  sendDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
};
</script>

<style scoped>
.thinking-panel {
  margin: 0 0 12px;
  overflow: hidden;
  border: 1px solid #dbe3ee;
  border-radius: 7px;
  background: #f8fafc;
}

.thinking-summary {
  min-height: 38px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

.thinking-indicator {
  width: 7px;
  height: 7px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #94a3b8;
}

.thinking-indicator.active {
  background: #2563eb;
  animation: thinking-pulse 1.2s ease-in-out infinite;
}

.thinking-state {
  margin-left: auto;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 500;
}

.thinking-content {
  max-height: 360px;
  padding: 10px 14px 14px;
  overflow: auto;
  border-top: 1px solid #e2e8f0;
  color: #475569;
}

@keyframes thinking-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

@media (max-width: 1100px) {
  .activity-column { display: none !important; }
}

@media (max-width: 720px) {
  .chat-body-row :deep(*) { min-width: 0; }
  .message-thread { padding: 20px 14px !important; gap: 14px !important; }
  .message-bubble { max-width: 92% !important; padding: 12px 14px !important; }
  .chat-composer { padding: 12px !important; gap: 8px !important; }
}
</style>
