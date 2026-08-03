<template>
  <div class="trace-timeline-panel">
    <div v-if="!conversation" class="empty-state">
      <el-empty description="选择左侧会话查看 Trace" :image-size="100" />
    </div>

    <template v-else>
      <!-- Tabs: 时间轴 / 耗时分析 -->
      <el-tabs v-model="activeTab" class="timeline-tabs">
        <el-tab-pane label="时间轴" name="waterfall">
          <WaterfallView
            :spans="spans"
            :conversation="conversation"
            :selected-span-id="selectedSpanId"
            @select-span="(id) => $emit('select-span', id)"
          />
        </el-tab-pane>
        <el-tab-pane label="耗时分析" name="latency">
          <LatencyAnalysis :analysis="analysis" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import WaterfallView from './WaterfallView.vue';
import LatencyAnalysis from './LatencyAnalysis.vue';
import type { TraceConversation, TraceSpan, LatencyAnalysis as LatencyAnalysisType } from '../../types/trace';

const props = defineProps<{
  conversation: TraceConversation | null;
  spans: TraceSpan[];
  analysis: LatencyAnalysisType | null;
  selectedSpanId?: string;
}>();

defineEmits<{
  (e: 'select-span', spanId: string): void;
}>();

const activeTab = ref<'waterfall' | 'latency'>('waterfall');

// Reset to waterfall tab when conversation changes
watch(
  () => props.conversation?.conversationId,
  () => {
    activeTab.value = 'waterfall';
  },
);
</script>

<style scoped>
.trace-timeline-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  overflow: hidden;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

.timeline-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0 16px;
}

.timeline-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
}

.timeline-tabs :deep(.el-tab-pane) {
  height: 100%;
}
</style>
