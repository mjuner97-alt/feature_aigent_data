<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';

const route = useRoute();
const router = useRouter();

// 比对结果详情数据接口定义 (参考开发文档第6条)
interface CompareResultDetail {
  id: number;
  taskId: number;
  stdCode: string;
  recordId: string;
  owner: string;
  answerType: string;
  similarity: number;
  threshold: number;
  passed: number;
  detail: string;
  diffSummary: string;
  comparedAt: string;
  expectAnswer: string;
  realAnswer: string;
  expectCaller: string;
  realCaller: string;
  question: string;
}

const loading = ref(false);
const error = ref('');
const resultData = ref<CompareResultDetail | null>(null);

// 获取路由中的 compareResultID
const compareResultID = route.params.id as string;

// 格式化时间
function formatTime(timeStr: string) {
  if (!timeStr) return '-';
  return timeStr.replace('T', ' ').substring(0, 19);
}

// 获取数据
async function fetchDetail() {
  if (!compareResultID) {
    error.value = '缺少 compareResultID 参数';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    // 根据开发文档第6条调用接口
    const response = await fetch('http://122.244.14.11:2021/skillRecord/getCompareResult', {
      method: 'POST', // 文档未明确是 GET 还是 POST，通常传参用 POST 或 GET+Query，这里假设为 POST JSON
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      // 假设入参格式如文档所示
      body: JSON.stringify({ compareResultID })
    });

    if (!response.ok) throw new Error(`请求失败: ${response.status}`);
    const res = await response.json();

    // 兼容 { result: "success", data: {...} } 或直接返回对象
    const data = res.data || res;

    if (!data || !data.id) {
      throw new Error(res.message || '未查询到比对结果数据');
    }
    resultData.value = data;
  } catch (e: any) {
    error.value = e?.message || '加载比对详情失败';
    ElMessage.error(error.value);
  } finally {
    loading.value = false;
  }
}

// 返回上一页
function goBack() {
  if (window.history.length > 1) {
    router.back();
  } else {
    router.push({ path: '/history', query: { tab: 'cases' } }); // 兜底返回历史页
  }
}

onMounted(() => {
  fetchDetail();
});
</script>

<template>
  <div class="compare-page">
    <!-- 头部 -->
    <div class="page-header">
      <div class="header-left">
        <button class="back-btn" @click="goBack">← 返回</button>
        <h2 class="title">对比结果通知</h2>
        <span class="subtitle">Skill执行细节比对</span>
      </div>
      <div v-if="resultData" class="header-right">
        比对时间: {{ formatTime(resultData.comparedAt) }}
      </div>
    </div>

    <div v-if="loading" class="loading-box">正在加载比对详情...</div>
    <div v-else-if="error" class="error-box">{{ error }}</div>

    <!-- 内容区 -->
    <div v-else-if="resultData" class="result-container">

      <!-- 1. 状态横幅 -->
      <div class="status-banner" :class="resultData.passed === 1 ? 'is-pass' : 'is-fail'">
        <span class="status-icon">{{ resultData.passed === 1 ? '✔' : '✖' }}</span>
        <span class="status-text">{{ resultData.passed === 1 ? '比对通过' : '比对不通过' }}</span>
      </div>

      <!-- 2. 基础信息面板 -->
      <div class="info-panel">
        <div class="info-row">
          <div class="info-item">
            <label>会话ID</label>
            <span class="val-mono">{{ resultData.taskId || '-' }}</span>
          </div>
          <div class="info-item">
            <label>执行记录ID</label>
            <span class="val-mono">{{ resultData.recordId || '-' }}</span>
          </div>
          <div class="info-item">
            <label>所有者</label>
            <span>{{ resultData.owner || '-' }}</span>
          </div>
          <div class="info-item">
            <label>标准编码</label>
            <span>{{ resultData.stdCode || '-' }}</span>
          </div>
        </div>
        <div class="info-row">
          <div class="info-item">
            <label>答案类型</label>
            <span>{{ resultData.answerType || '-' }}</span>
          </div>
          <div class="info-item">
            <label>相似度</label>
            <span class="val-bold" :class="resultData.passed === 1 ? 'text-green' : 'text-red'">{{ resultData.similarity }}</span>
          </div>
          <div class="info-item">
            <label>阈值</label>
            <span>{{ resultData.threshold }}</span>
          </div>
          <div class="info-item">
            <label>比对结论</label>
            <span>{{ resultData.passed === 1 ? '通过' : '不通过' }}</span>
          </div>
        </div>
      </div>

      <!-- 3. 问题描述 -->
      <div class="section-block">
        <div class="section-title">提问话术</div>
        <div class="content-box">{{ resultData.question || '暂无内容' }}</div>
      </div>

      <!-- 4. 调用方比对 -->
      <div class="section-block">
        <div class="section-title">调用方比对</div>
        <div class="compare-grid">
          <div class="compare-col">
            <div class="col-header">预期调用方</div>
            <div class="code-box">{{ resultData.expectCaller || '无' }}</div>
          </div>
          <div class="compare-col">
            <div class="col-header">实际调用方</div>
            <div class="code-box">{{ resultData.realCaller || '无' }}</div>
          </div>
        </div>
      </div>

      <!-- 5. 详情与差异 -->
      <div class="section-block">
        <div class="section-title">比对详情与差异描述</div>
        <div class="detail-box">
          <div class="detail-item">
            <span class="label">详情:</span>
            <span class="value">{{ resultData.detail || '无' }}</span>
          </div>
          <div class="detail-item warning-bg">
            <span class="label">差异摘要:</span>
            <span class="value">{{ resultData.diffSummary || '无' }}</span>
          </div>
        </div>
      </div>

      <!-- 6. 答案比对 (预期 vs 实际) -->
      <div class="section-block">
        <div class="section-title">答案比对</div>
        <div class="compare-grid">
          <div class="compare-col">
            <div class="col-header">预期答案</div>
            <pre class="answer-box">{{ resultData.expectAnswer || '无' }}</pre>
          </div>
          <div class="compare-col">
            <div class="col-header">实际答案</div>
            <pre class="answer-box">{{ resultData.realAnswer || '无' }}</pre>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
.compare-page {
  padding: 16px 24px;
  background: #f8fafc;
  /* 👇 修复：让内容可以正常撑开并出现内部滚动条 */
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

/* 头部 */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 20px;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 12px;
}
.header-left { display: flex; align-items: center; gap: 12px; }
.back-btn {
  background: #fff; border: 1px solid #cbd5e1; padding: 4px 12px;
  border-radius: 6px; cursor: pointer; color: #475569; font-size: 13px;
}
.back-btn:hover { border-color: #93c5fd; color: #2563eb; }
.title { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.subtitle { font-size: 12px; color: #94a3b8; margin-left: 4px; }
.header-right { font-size: 13px; color: #64748b; }

.loading-box, .error-box {
  padding: 40px; text-align: center; background: #fff; border-radius: 8px;
  font-size: 14px; color: #64748b;
}
.error-box { color: #dc2626; }

.result-container {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
  border: 1px solid #e2e8f0;
  overflow: hidden;
  /* 底部留一点空隙，避免滚动到底部太贴边 */
  margin-bottom: 24px;
}

/* 状态横幅 */
.status-banner {
  padding: 16px 24px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
}
.status-banner.is-pass { background: #f0fdf4; color: #166534; border-bottom: 1px solid #bbf7d0; }
.status-banner.is-fail { background: #fef2f2; color: #991b1b; border-bottom: 1px solid #fecaca; }
.status-icon { font-size: 20px; }

/* 信息面板 */
.info-panel {
  padding: 20px 24px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.info-row { display: flex; gap: 48px; }
.info-item { display: flex; flex-direction: column; gap: 4px; min-width: 120px; }
.info-item label { font-size: 12px; color: #94a3b8; font-weight: 500; }
.info-item span { font-size: 14px; color: #1e293b; }
.val-mono { font-family: ui-monospace, monospace; }
.val-bold { font-weight: 700; font-size: 16px; }
.text-green { color: #16a34a; }
.text-red { color: #dc2626; }

/* 通用区块 */
.section-block {
  padding: 20px 24px;
  border-bottom: 1px solid #e2e8f0;
}
.section-block:last-child { border-bottom: none; }
.section-title {
  font-size: 14px; font-weight: 700; color: #0f172a; margin-bottom: 12px;
  display: flex; align-items: center;
}
.section-title::before {
  content: ''; display: inline-block; width: 3px; height: 14px;
  background: #3b82f6; border-radius: 2px; margin-right: 8px;
}
.content-box {
  padding: 12px 16px; background: #f8fafc; border: 1px solid #e2e8f0;
  border-radius: 6px; font-size: 14px; color: #334155; line-height: 1.6;
}

/* 并排对比结构 */
.compare-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.compare-col { display: flex; flex-direction: column; gap: 8px; min-width: 0; /* 防止 flex/grid 子项被内容撑破 */ }
.col-header { font-size: 13px; font-weight: 600; color: #475569; }
.code-box {
  padding: 12px; background: #f1f5f9; border-radius: 6px;
  font-family: ui-monospace, monospace; font-size: 13px; color: #334155;
  border: 1px solid #e2e8f0; min-height: 40px; white-space: pre-wrap; word-break: break-all;
}
.answer-box {
  padding: 12px; background: #f8fafc; border: 1px solid #e2e8f0;
  border-radius: 6px; font-family: ui-monospace, monospace; font-size: 13px;
  color: #1e293b; white-space: pre-wrap; word-break: break-all; margin: 0;
  max-height: 400px; overflow-y: auto;
}

/* 详情列表 */
.detail-box { display: flex; flex-direction: column; gap: 8px; }
.detail-item { display: flex; gap: 8px; font-size: 13px; padding: 8px 12px; border-radius: 6px; }
.detail-item.warning-bg { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
.detail-item .label { font-weight: 600; white-space: nowrap; }
.detail-item .value { word-break: break-all; }
</style>