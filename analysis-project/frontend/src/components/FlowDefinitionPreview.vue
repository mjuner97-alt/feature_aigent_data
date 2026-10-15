<script setup lang="ts">
import { computed } from 'vue';
import { flattenOutline } from '../utils/reportOutline';
import type { ReportOutline } from '../types/skillFlow';
const props = defineProps<{ open: boolean; title: string; description?: string; createdBy?: string; nodes: unknown[]; outline?: unknown }>();
const emit = defineEmits<{ 'update:open': [open: boolean] }>();
const outline = computed(() => props.outline as ReportOutline | null | undefined);
const chapters = computed(() => flattenOutline(outline.value ?? undefined));
const nodes = computed(() => props.nodes.filter(node => node != null && typeof node === 'object').map(node => node as Record<string, unknown>));
function format(value: unknown) {
  if (value == null || value === '') return '';
  if (typeof value !== 'string') return JSON.stringify(value, null, 2);
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}
</script>
<template>
  <el-dialog :model-value="open" :title="title + ' · 查看'" width="min(1000px,94vw)" @update:model-value="emit('update:open', $event)">
    <div class="definition-preview">
      <p v-if="description">{{ description }}</p>
      <p class="creator">创建人：{{ createdBy || '-' }} · 只读查看</p>
      <h3>报告大纲</h3><p v-if="outline?.title">{{ outline.title }}</p>
      <p v-if="!chapters.length" class="empty">未配置报告大纲</p>
      <div v-for="chapter in chapters" :key="chapter.id" class="chapter" :style="{ paddingLeft: `${(chapter.level - 1) * 18}px` }">
        <strong>{{ chapter.title }}</strong><span v-if="chapter.nodeKeys.length"> · 节点：{{ chapter.nodeKeys.join('、') }}</span>
      </div>
      <h3>执行节点</h3><p v-if="!nodes.length" class="empty">暂无节点</p>
      <article v-for="(node, index) in nodes" :key="String(node.nodeKey || index)" class="node">
        <strong>{{ index + 1 }}. {{ node.nodeName || node.skillName || node.nodeKey }}</strong>
        <p>节点标识：{{ node.nodeKey }}</p>
        <p v-if="node.scriptId">Python 脚本：{{ node.scriptId }}</p>
        <p v-else-if="node.skillId">Skill：{{ node.skillName || node.skillId }}</p>
        <p v-if="node.questionTemplate">问题模板：{{ node.questionTemplate }}</p>
        <pre v-if="node.scriptParamsJson || node.scriptParams">{{ format(node.scriptParamsJson || node.scriptParams) }}</pre>
      </article>
    </div>
    <template #footer><el-button @click="emit('update:open', false)">关闭</el-button></template>
  </el-dialog>
</template>
<style scoped>
/* 预览内容区域；前面的点号表示匹配 class="definition-preview" 的元素。 */
.definition-preview {
  max-height: 65vh; /* 高度最多占浏览器可视高度的 65%；vh 是可视高度单位。 */
  overflow: auto; /* 内容超过区域大小时自动出现滚动条，避免弹窗撑出屏幕。 */
  color: #334155; /* 正文文字使用深灰蓝色；# 开头的是十六进制颜色值。 */
}

/* 逗号表示两种元素共用这些样式：创建人说明和空内容提示。 */
.creator, .empty {
  color: #64748b; /* 辅助文字使用较浅的灰色，突出主要内容。 */
}

/* 每一行报告章节；margin 是元素外侧的留白。 */
.chapter {
  margin: 8px 0; /* 两个值依次表示：上下留白 8 像素、左右留白 0。 */
}

/* 空格表示后代元素：这里只影响章节行里面的 span 标签。 */
.chapter span {
  color: #64748b; /* 节点绑定说明使用辅助文字颜色。 */
  font-size: 13px; /* 文字大小为 13 像素，比章节标题小一些。 */
}

/* 每个执行节点的卡片。 */
.node {
  margin: 12px 0; /* 卡片上下相隔 12 像素，左右不额外留白。 */
  padding: 12px; /* padding 是内部留白：内容与卡片边框之间留 12 像素。 */
  border: 1px solid #e2e8f0; /* 边框依次指定：1 像素粗、实线、浅灰色。 */
  border-radius: 7px; /* 将卡片四个直角改成半径为 7 像素的圆角。 */
}

/* 节点卡片里面的段落，统一控制段落之间的距离。 */
.node p {
  margin: 7px 0; /* 每个段落上下留白 7 像素、左右留白 0。 */
}

/* pre 标签用来展示脚本参数等保留格式的文本；scoped 让样式只作用于本组件。 */
pre {
  white-space: pre-wrap; /* 保留原有空格和换行；文字超过宽度时也允许自动换行。 */
  overflow-wrap: anywhere; /* 很长的参数值或连续字符串也可换行，避免横向撑开弹窗。 */
  background: #f8fafc; /* 使用浅灰背景，将参数区域与普通正文区分开。 */
  padding: 10px; /* 参数文字与背景区域边缘之间留 10 像素。 */
}
</style>
