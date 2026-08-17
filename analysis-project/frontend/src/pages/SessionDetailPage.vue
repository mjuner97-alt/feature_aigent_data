<template>
  <div class="session-detail">
    <!-- Header -->
    <div class="header">
      <div class="header-left">
        <el-button text @click="goBack" :icon="ArrowLeft">返回列表</el-button>
        <el-divider direction="vertical" />
        <span v-if="conversation" class="meta">
          <el-tag :type="statusTagType" size="small" effect="dark">{{ conversation.status || '-' }}</el-tag>
          <span class="meta-item">{{ formatDuration(conversation.durationMs) }}</span>
          <span class="meta-item">events: {{ conversation.eventCount }}</span>
          <span class="meta-item">tokens: {{ conversation.tokenInput }}/{{ conversation.tokenOutput }}</span>
          <span class="meta-item">model: {{ conversation.model || '-' }}</span>
        </span>
      </div>
      <div class="header-right">
        <span v-if="error" class="error">⚠ {{ error }}</span>
        <el-button size="small" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </div>

    <!-- 工具栏：全部展开/收起 -->
    <div v-if="steps.length > 0" class="toolbar">
      <el-button text size="small" @click="expandAll(true)">全部展开</el-button>
      <el-button text size="small" @click="expandAll(false)">全部收起</el-button>
      <span class="toolbar-count">{{ steps.length }} 个步骤</span>
    </div>

    <!-- 流程卡片流 -->
    <div class="timeline" v-loading="loading">
      <div v-if="steps.length === 0 && !loading" class="empty">暂无事件数据</div>

      <div
        v-for="(step, idx) in steps"
        :key="step.id"
        class="step"
        :class="['step-' + step.kind]"
        @click="onStepClick(step)"
      >
        <div class="step-marker">
          <span class="marker-dot" :style="{ background: step.color }"></span>
          <span v-if="idx < steps.length - 1" class="marker-line"></span>
        </div>
        <div class="step-card">
          <div class="step-head">
            <el-icon class="step-icon"><component :is="step.icon" /></el-icon>
            <span class="step-title">{{ step.title }}</span>
            <span class="step-time">{{ formatTime(step.createdAt) }}</span>
            <el-tag v-if="step.subtitle" size="small" type="info" effect="plain">{{ step.subtitle }}</el-tag>
          </div>
          <div v-if="step.body" class="step-body" :class="{ collapsed: !isExpanded(step) && step.collapsible }">
            <pre v-if="step.kind === 'tool' || step.kind === 'tool-result'" class="code">{{ step.body }}</pre>
            <div v-else class="text">{{ step.body }}</div>
          </div>
          <div v-if="step.collapsible" class="step-toggle" @click.stop="toggleExpand(step)">
            {{ isExpanded(step) ? '收起 ▴' : '展开 ▾' }}
          </div>
        </div>
      </div>
    </div>

    <!-- 选中事件原始 JSON 弹窗 -->
    <el-dialog
      v-model="rawDialogOpen"
      :title="rawEventTitle"
      width="70%"
      :close-on-click-modal="true"
    >
      <pre class="raw-json">{{ rawEventJson }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, reactive, type Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, ChatDotRound, ChatLineRound, Document, Operation, Tools, User, Warning } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { getTraceDetail } from '../api/trace';
import type { AgentEvent, ConversationSummary, TraceDetailResponse } from '../types/trace';

const route = useRoute();
const router = useRouter();
const conversationId = computed(() => route.params.id as string);

const loading = ref(false);
const error = ref<string | null>(null);
const conversation = ref<ConversationSummary | null>(null);
const rawEvents = ref<AgentEvent[]>([]);

const rawDialogOpen = ref(false);
const rawEventJson = ref('');
const rawEventTitle = ref('事件 JSON');

// ============== 展开状态管理 ==============
const expandedMap = reactive<Record<string, boolean>>({});

/** 默认展开的 kind - thinking 必须默认展开，用户需要看到模型思考过程 */
const DEFAULT_EXPANDED = new Set(['user', 'thinking', 'answer']);

function isExpanded(step: Step): boolean {
  if (step.id in expandedMap) return expandedMap[step.id];
  if (step.kind === 'tool-result') return true;
  return DEFAULT_EXPANDED.has(step.kind);
}

function toggleExpand(step: Step) {
  const current = isExpanded(step);
  expandedMap[step.id] = !current;
}

function expandAll(value: boolean) {
  for (const step of steps.value) {
    expandedMap[step.id] = value;
  }
}

// ============== 时间线构建 ==============

interface Step {
  id: string;
  kind: 'user' | 'thinking' | 'answer' | 'tool' | 'tool-result' | 'agent' | 'error' | 'other';
  icon: Component;
  title: string;
  subtitle?: string;
  body?: string;
  createdAt: string;
  color: string;
  collapsible: boolean;
  /** 原始事件（点击卡片其他区域时弹窗展示完整 JSON）。 */
  raw?: AgentEvent;
}

const KIND_META: Record<Step['kind'], { icon: Component; color: string }> = {
  user:          { icon: User, color: '#3B82F6' },
  agent:         { icon: Operation, color: '#6366F1' },
  thinking:      { icon: ChatDotRound, color: '#A855F7' },
  answer:        { icon: ChatLineRound, color: '#10B981' },
  tool:          { icon: Tools, color: '#F59E0B' },
  'tool-result': { icon: Document, color: '#0EA5E9' },
  error:         { icon: Warning, color: '#EF4444' },
  other:         { icon: Document, color: '#94A3B8' },
};

/**
 * 把 Hook 事件 JSON 折叠成步骤流。事件类型（由后端 TraceCollectorHook 产出）：
 *   USER_INPUT     -> user 卡片
 *   PRE_REASONING  -> LLM 输入卡片（input_messages）
 *   POST_REASONING -> thinking 卡片（reasoning_message 的 ThinkingBlock）+ answer 卡片（TextBlock）
 *   PRE_ACTING     -> 工具调用卡片（tool_use.input）
 *   POST_ACTING    -> 工具输出卡片（tool_result.output）
 *   POST_CALL      -> Agent 最终回复卡片（final_message）
 *   ERROR          -> 异常卡片
 * 每个操作一条事件、完整 payload，无需累积 delta。
 */
const steps = computed<Step[]>(() => {
  const out: Step[] = [];
  let idx = 0;
  const nextId = (prefix: string) => `${prefix}-${idx++}`;

  for (const e of rawEvents.value) {
    const t = (e.type || '').toUpperCase();
    const createdAt = e.createdAt || '';
    switch (t) {
      case 'USER_INPUT': {
        out.push({
          id: e.id || nextId('user'),
          kind: 'user',
          ...KIND_META.user,
          title: '用户输入',
          body: (e.text as string) || '',
          createdAt,
          collapsible: false,
          raw: e,
        });
        break;
      }
      case 'PRE_REASONING': {
        // 系统提示词单独走 system_message（框架 getSystemMessage()），不在 input_messages 里
        const sysText = extractMsgText(e.system_message as any);
        const inputBody = formatMessages(e.input_messages);
        const body = [sysText ? `[SYSTEM]\n${sysText}` : '', inputBody]
          .filter(Boolean)
          .join('\n\n');
        out.push({
          id: e.id || nextId('pre-reason'),
          kind: 'other',
          ...KIND_META.other,
          title: 'LLM 输入',
          subtitle: (e.model_name as string) || undefined,
          body,
          createdAt,
          collapsible: !!body && body.length > 80,
          raw: e,
        });
        break;
      }
      case 'POST_REASONING': {
        const blocks = (e.reasoning_message as any)?.content as any[] | undefined;
        const thinking = collectBlockText(blocks, 'thinking', 'thinking');
        const text = collectBlockText(blocks, 'text', 'text');
        if (thinking) {
          out.push({
            id: e.id || nextId('think'),
            kind: 'thinking',
            ...KIND_META.thinking,
            title: '模型思考',
            body: thinking,
            createdAt,
            collapsible: thinking.length > 80,
            raw: e,
          });
        }
        if (text) {
          out.push({
            id: nextId('answer'),
            kind: 'answer',
            ...KIND_META.answer,
            title: '模型回答',
            body: text,
            createdAt,
            collapsible: text.length > 80,
            raw: e,
          });
        }
        // tool_use blocks 略过：工具调用由紧随的 PRE_ACTING 事件展示
        break;
      }
      case 'PRE_ACTING': {
        const tu = e.tool_use as any;
        const name = tu?.name || 'tool';
        const body = formatToolInput(tu?.input);
        out.push({
          id: e.id || nextId('tool'),
          kind: 'tool',
          ...KIND_META.tool,
          title: `工具调用 · ${name}`,
          subtitle: tu?.id as string | undefined,
          body,
          createdAt,
          collapsible: !!body && body.length > 80,
          raw: e,
        });
        break;
      }
      case 'POST_ACTING': {
        const tr = e.tool_result as any;
        const name = tr?.name || (e.tool_use as any)?.name || 'tool';
        const body = extractOutputText(tr?.output);
        out.push({
          id: e.id || nextId('tool-result'),
          kind: 'tool-result',
          ...KIND_META['tool-result'],
          title: `工具输出 · ${name}`,
          subtitle: tr?.id as string | undefined,
          body,
          createdAt,
          collapsible: !!body && body.length > 80,
          raw: e,
        });
        break;
      }
      case 'POST_CALL': {
        const text = extractMsgText(e.final_message as any);
        if (text) {
          out.push({
            id: e.id || nextId('final'),
            kind: 'answer',
            ...KIND_META.answer,
            title: 'Agent 最终回复',
            body: text,
            createdAt,
            collapsible: text.length > 80,
            raw: e,
          });
        }
        break;
      }
      case 'ERROR': {
        out.push({
          id: e.id || nextId('error'),
          kind: 'error',
          ...KIND_META.error,
          title: '异常',
          subtitle: (e.error_class as string) || undefined,
          body: (e.error_message as string) || '',
          createdAt,
          collapsible: false,
          raw: e,
        });
        break;
      }
      default:
        // PRE_CALL / MODEL_CALL_END 等不展示为卡片
        break;
    }
  }
  return out;
});

// ============== payload 格式化 ==============

/** 把 input_messages（Msg 数组）格式化为可读文本。 */
function formatMessages(msgs: unknown): string {
  if (!Array.isArray(msgs)) return '';
  const lines: string[] = [];
  for (const m of msgs as any[]) {
    const role = String(m?.role || '?').toUpperCase();
    const text = extractMsgText(m);
    if (text) lines.push(`[${role}]\n${text}`);
  }
  return lines.join('\n\n');
}

/** 从 Msg.content blocks 提取文本（优先 text，其次 thinking，最后块摘要）。 */
function extractMsgText(msg: any): string {
  if (!msg) return '';
  return collectBlockText(msg.content, 'text', 'text')
    || collectBlockText(msg.content, 'thinking', 'thinking')
    || summarizeBlocks(msg.content);
}

/** 收集指定类型 block 的指定文本字段，拼接返回。 */
function collectBlockText(blocks: any[] | undefined, blockType: string, field: string): string {
  if (!Array.isArray(blocks)) return '';
  return blocks
    .filter((b) => b?.type === blockType)
    .map((b) => String(b?.[field] ?? ''))
    .filter(Boolean)
    .join('\n');
}

/** 对非文本块做占位摘要（避免完全丢失结构信息）。 */
function summarizeBlocks(blocks: any[] | undefined): string {
  if (!Array.isArray(blocks)) return '';
  const parts: string[] = [];
  for (const b of blocks) {
    if (b?.type === 'text' && b.text) parts.push(b.text);
    else if (b?.type === 'thinking' && b.thinking) parts.push(`(思考) ${b.thinking}`);
    else if (b?.type === 'tool_use') parts.push(`(工具调用: ${b?.name})`);
    else if (b?.type === 'tool_result') parts.push('(工具结果)');
  }
  return parts.join('\n');
}

/** 从 ToolResultBlock.output（ContentBlock 数组）提取文本。 */
function extractOutputText(output: unknown): string {
  return collectBlockText(output as any[] | undefined, 'text', 'text')
    || summarizeBlocks(output as any[] | undefined);
}

/** 工具入参（Map）格式化为 JSON。 */
function formatToolInput(input: unknown): string {
  if (input == null) return '';
  try {
    return typeof input === 'string' ? input : JSON.stringify(input, null, 2);
  } catch {
    return String(input);
  }
}

// ============== UI helpers ==============

const statusTagType = computed<'success' | 'danger' | 'warning' | 'info'>(() => {
  const s = conversation.value?.status;
  if (s === 'SUCCESS') return 'success';
  if (s === 'ERROR') return 'danger';
  if (s === 'TIMEOUT') return 'warning';
  return 'info';
});

function formatDuration(ms: number): string {
  if (!ms) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatTime(ts: string): string {
  if (!ts) return '';
  return dayjs(ts).format('HH:mm:ss.SSS');
}

function onStepClick(step: Step) {
  if (!step.raw) return;
  rawEventTitle.value = `${step.title} · ${step.raw.type}`;
  rawEventJson.value = JSON.stringify(step.raw, null, 2);
  rawDialogOpen.value = true;
}

function goBack() {
  router.back();
}

async function loadData() {
  if (!conversationId.value) return;
  loading.value = true;
  error.value = null;
  rawEvents.value = [];
  conversation.value = null;
  Object.keys(expandedMap).forEach(k => delete expandedMap[k]);
  try {
    const resp: TraceDetailResponse = await getTraceDetail(conversationId.value);
    conversation.value = resp.conversation;
    rawEvents.value = (resp.events || []).map(s => {
      try { return JSON.parse(s) as AgentEvent; }
      catch { return { id: 'parse-error', type: 'PARSE_ERROR', createdAt: '', raw: s } as unknown as AgentEvent; }
    });
  } catch (e: any) {
    error.value = e?.message || '加载 Trace 失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);
</script>

<style scoped>
.session-detail {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8fafc;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}
.header-left { display: flex; align-items: center; gap: 8px; }
.header-right { display: flex; align-items: center; gap: 8px; }
.meta { display: inline-flex; align-items: center; gap: 12px; }
.meta-item { font-size: 12px; color: #64748b; }
.error { color: #ef4444; font-size: 12px; }

.toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 24px;
  background: #f1f5f9;
  border-bottom: 1px solid #e5e7eb;
  max-width: 980px;
  margin: 0 auto;
  width: 100%;
}
.toolbar-count {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}

.timeline {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  max-width: 980px;
  margin: 0 auto;
  width: 100%;
}
.empty { text-align: center; color: #94a3b8; padding: 40px 0; }

.step {
  display: flex;
  gap: 14px;
  margin-bottom: 18px;
}
.step-marker {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 16px;
  padding-top: 14px;
}
.marker-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 2px rgba(15, 23, 42, 0.06);
  z-index: 1;
}
.marker-line {
  flex: 1;
  width: 2px;
  background: #e2e8f0;
  margin-top: 4px;
}
.step-card {
  flex: 1;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 16px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  transition: box-shadow 0.15s, transform 0.15s;
}
.step-card:hover {
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}
.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}
.step-icon { font-size: 16px; }
.step-title { flex: 1; }
.step-time { font-size: 11px; color: #94a3b8; font-weight: 400; }
.step-body {
  margin-top: 8px;
  font-size: 13px;
  color: #334155;
}
.step-body.text { white-space: pre-wrap; word-break: break-word; line-height: 1.6; }
.step-body.collapsed {
  max-height: 120px;
  overflow: hidden;
  position: relative;
}
/* 只在内容真正超过 120px 时才加渐隐遮罩, 避免短内容被遮看不见 */
.step-body.collapsed::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 40px;
  background: linear-gradient(to bottom, transparent, #fff 80%);
  pointer-events: none;
}
.step-tool .step-body.collapsed::after,
.step-tool-result .step-body.collapsed::after {
  background: linear-gradient(to bottom, transparent, #fffbeb 80%);
}
.step-thinking .step-body.collapsed::after {
  background: linear-gradient(to bottom, transparent, #faf5ff 80%);
}
.step-body .code {
  font-family: 'Cascadia Code', 'Consolas', monospace;
  font-size: 12px;
  background: #0f172a;
  color: #e2e8f0;
  padding: 10px 12px;
  border-radius: 6px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 500px;
  margin: 0;
}
.step-toggle {
  margin-top: 6px;
  font-size: 12px;
  color: #2563eb;
  cursor: pointer;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 6px;
  border-radius: 4px;
  transition: background 0.15s;
}
.step-toggle:hover {
  background: rgba(37, 99, 235, 0.08);
}

.step-user .step-card { border-left: 3px solid #3B82F6; }
.step-thinking .step-card { border-left: 3px solid #A855F7; background: #faf5ff; }
.step-answer .step-card { border-left: 3px solid #10B981; }
.step-tool .step-card { border-left: 3px solid #F59E0B; background: #fffbeb; }
.step-tool-result .step-card { border-left: 3px solid #0EA5E9; background: #f0f9ff; }
.step-agent .step-card { border-left: 3px solid #6366F1; }
.step-error .step-card { border-left: 3px solid #EF4444; background: #fef2f2; }
.step-other .step-card { border-left: 3px solid #94A3B8; background: #f8fafc; }

.raw-json {
  font-family: 'Cascadia Code', 'Consolas', monospace;
  font-size: 12px;
  background: #0f172a;
  color: #e2e8f0;
  padding: 12px;
  border-radius: 6px;
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
</style>
