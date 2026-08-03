<template>
  <div class="event-detail-panel">
    <div v-if="!event && !span" class="empty-state">
      <el-empty description="点击 Span 或 Event 查看详情" :image-size="100" />
    </div>

    <template v-else>
      <!-- Span details (if a span is selected) -->
      <template v-if="span">
        <div class="section-title">Span 详情</div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="SpanId">
            <span class="mono-text">{{ span.spanId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            <span class="type-badge" :style="{ background: spanColor(span.spanType) }">
              {{ span.spanType }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="名称">{{ span.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(span.status)" size="small">{{ span.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="ParentSpanId">
            <span class="mono-text">{{ span.parentSpanId || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Depth">{{ span.depth }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatTime(span.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatTime(span.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">
            <span class="duration-text">{{ formatDuration(span.durationMs) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ span.source || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="span.model" label="模型">{{ span.model }}</el-descriptions-item>
          <el-descriptions-item v-if="span.tokenInput != null" label="输入Token">{{ span.tokenInput }}</el-descriptions-item>
          <el-descriptions-item v-if="span.tokenOutput != null" label="输出Token">{{ span.tokenOutput }}</el-descriptions-item>
          <el-descriptions-item v-if="span.cost != null" label="费用">${{ span.cost.toFixed(4) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="span.status === 'ERROR' && span.errorMessage"
          :title="'Span 异常'"
          type="error"
          :description="span.errorMessage"
          show-icon
          :closable="false"
          class="error-alert"
        />

        <!-- Input / Output JSON -->
        <el-tabs v-model="spanActiveTab" class="json-tabs" v-if="span.inputJson || span.outputJson">
          <el-tab-pane label="Input" name="input">
            <div v-if="span.inputJson" class="json-viewer">
              <VueJsonPretty :data="safeParse(span.inputJson)" />
            </div>
            <div v-else class="json-empty">无 Input 数据</div>
          </el-tab-pane>
          <el-tab-pane label="Output" name="output">
            <!-- LLM span 的 output 可能包含 thinking 和 text 字段，分开展示 -->
            <template v-if="hasThinkingOutput(span)">
              <el-tabs v-model="outputSubTab" type="card" class="output-sub-tabs">
                <el-tab-pane label="思考过程" name="thinking">
                  <div class="thinking-block">
                    {{ extractOutputField(span, 'thinking') || '(无思考内容)' }}
                  </div>
                </el-tab-pane>
                <el-tab-pane label="回复文本" name="text">
                  <div class="text-block">
                    {{ extractOutputField(span, 'text') || '(无回复文本)' }}
                  </div>
                </el-tab-pane>
                <el-tab-pane label="原始 JSON" name="raw">
                  <div class="json-viewer">
                    <VueJsonPretty :data="safeParse(span.outputJson)" />
                  </div>
                </el-tab-pane>
              </el-tabs>
            </template>
            <div v-else-if="span.outputJson" class="json-viewer">
              <VueJsonPretty :data="safeParse(span.outputJson)" />
            </div>
            <div v-else class="json-empty">无 Output 数据</div>
          </el-tab-pane>
        </el-tabs>
      </template>

      <!-- Event details -->
      <template v-if="event">
        <div class="section-title" :class="{ 'with-margin': span }">Event 详情</div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="EventType" :span="2">
            <el-tag size="small" type="warning">{{ event.eventType }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="EventName">{{ event.eventName }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ event.source }}</el-descriptions-item>
          <el-descriptions-item label="EventId">
            <span class="mono-text">{{ event.eventId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="SpanId">
            <span class="mono-text">{{ event.spanId || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="时间戳">{{ formatTime(event.timestamp) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatDuration(event.durationMs) }}</el-descriptions-item>
          <el-descriptions-item v-if="event.correlationId" label="CorrelationId" :span="2">
            <span class="mono-text">{{ event.correlationId }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-subtitle">Payload</div>
        <div class="json-viewer">
          <VueJsonPretty :data="safeParse(event.payloadJson)" />
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import VueJsonPretty from 'vue-json-pretty';
import 'vue-json-pretty/lib/styles.css';
import dayjs from 'dayjs';
import type { TraceEvent, TraceSpan, TraceStatus, SpanType } from '../../types/trace';
import { SPAN_TYPE_COLORS, STATUS_TAG_TYPE } from '../../types/trace';

defineProps<{
  event: TraceEvent | null;
  span: TraceSpan | null;
}>();

const spanActiveTab = ref<'input' | 'output'>('input');
const outputSubTab = ref<'thinking' | 'text' | 'raw'>('thinking');

/** 检查 span 的 outputJson 是否包含 thinking 字段（LLM_RESPONSE 格式）。 */
function hasThinkingOutput(span: TraceSpan): boolean {
  if (!span.outputJson) return false;
  try {
    const parsed = JSON.parse(span.outputJson);
    return parsed != null && typeof parsed === 'object' && 'thinking' in parsed;
  } catch {
    return false;
  }
}

/** 从 span 的 outputJson 中提取指定字段值。 */
function extractOutputField(span: TraceSpan, field: string): string {
  if (!span.outputJson) return '';
  try {
    const parsed = JSON.parse(span.outputJson);
    if (parsed != null && typeof parsed === 'object' && field in parsed) {
      const val = (parsed as Record<string, unknown>)[field];
      return typeof val === 'string' ? val : JSON.stringify(val, null, 2);
    }
  } catch {
    // ignore
  }
  return '';
}

function spanColor(type: SpanType): string {
  return SPAN_TYPE_COLORS[type] ?? '#909399';
}

function statusTagType(status: TraceStatus) {
  return STATUS_TAG_TYPE[status] ?? 'info';
}

function formatTime(ts: string): string {
  if (!ts) return '-';
  return dayjs(ts).format('YYYY-MM-DD HH:mm:ss.SSS');
}

function formatDuration(ms: number): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function safeParse(json: string | undefined): Record<string, unknown> | string | null {
  if (!json) return null;
  try {
    return JSON.parse(json);
  } catch {
    return json;
  }
}
</script>

<style scoped>
.event-detail-panel {
  height: 100%;
  overflow-y: auto;
  background: #fff;
  padding: 12px 16px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid #ebeef5;
}

.section-title.with-margin {
  margin-top: 20px;
}

.section-subtitle {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin: 12px 0 6px;
}

.mono-text {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  color: #606266;
}

.type-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
}

.duration-text {
  font-weight: 600;
  color: #409eff;
}

.error-alert {
  margin-top: 10px;
}

.json-tabs {
  margin-top: 12px;
}

.json-viewer {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
  max-height: 360px;
  overflow-y: auto;
  font-size: 12px;
}

.json-empty {
  color: #909399;
  font-size: 12px;
  padding: 16px;
  text-align: center;
}

.output-sub-tabs {
  margin-top: 4px;
}

.thinking-block {
  background: #fef9e7;
  border: 1px solid #f0e6c0;
  border-radius: 4px;
  padding: 10px 12px;
  max-height: 360px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #7d6608;
}

.text-block {
  background: #f8fafc;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  max-height: 360px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
</style>
