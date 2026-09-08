<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getToolRoutingStatus, listTags, listToolRouting, saveTag, saveToolRouting, scanToolRouting, setToolRoutingEnabled } from '../api/toolRouting';
import type { TagType, ToolRoutingInput, ToolRoutingMetadata, ToolRoutingScanCandidate, ToolRoutingStatus, ToolRoutingTag } from '../types/toolRouting';

const loading = ref(false);
const saving = ref(false);
const rows = ref<ToolRoutingScanCandidate[]>([]);
const configurations = ref<Record<string, ToolRoutingMetadata>>({});
const topics = ref<ToolRoutingTag[]>([]);
const metrics = ref<ToolRoutingTag[]>([]);
const dimensions = ref<ToolRoutingTag[]>([]);
const status = ref<ToolRoutingStatus | null>(null);
const keyword = ref('');
const typeFilter = ref('');
const ownerFilter = ref('');
const dialogVisible = ref(false);
const current = ref<ToolRoutingScanCandidate | null>(null);
const form = ref<ToolRoutingInput>(emptyInput());
const tagDialogVisible = ref(false);
const tagType = ref<TagType>('METRIC');
const tagName = ref('');
const tagDescription = ref('');

function emptyInput(): ToolRoutingInput { return { toolType: 'SQL', description: '', topicTags: [], metricTags: [], dimensionTags: [], priority: 0, enabled: false }; }
const filteredRows = computed(() => rows.value.filter(row => {
  const matchKeyword = !keyword.value || [row.toolId, row.name, row.description, row.creator].join(' ').toLowerCase().includes(keyword.value.toLowerCase());
  const me = localStorage.getItem('skill-user-id') || 'demo-user';
  const mine = !ownerFilter.value || (row.toolType !== 'API' && row.creator === me);
  return matchKeyword && (!typeFilter.value || row.toolType === typeFilter.value) && mine;
}));
const topicOptions = computed(() => topics.value.map(tag => tag.tagName));
const metricOptions = computed(() => metrics.value.map(tag => tag.tagName));
const dimensionOptions = computed(() => dimensions.value.map(tag => tag.tagName));

async function load() {
  loading.value = true;
  try {
    const [scanned, configured, currentStatus, topicTags, metricTags, dimensionTags] = await Promise.all([
      scanToolRouting(), listToolRouting(), getToolRoutingStatus(), listTags('TOPIC'), listTags('METRIC'), listTags('DIMENSION'),
    ]);
    rows.value = scanned;
    configurations.value = Object.fromEntries(configured.map(item => [item.toolId, item]));
    status.value = currentStatus;
    topics.value = topicTags;
    metrics.value = metricTags;
    dimensions.value = dimensionTags;
  } catch (error: any) { ElMessage.error(error.message || '加载失败'); }
  finally { loading.value = false; }
}

async function openConfig(row: ToolRoutingScanCandidate) {
  current.value = row;
  const existing = configurations.value[row.toolId];
  form.value = existing ? { ...existing, topicTags: [...existing.topicTags], metricTags: [...existing.metricTags], dimensionTags: [...existing.dimensionTags] }
    : { ...emptyInput(), toolType: row.toolType, description: row.description };
  dialogVisible.value = true;
}

async function saveConfig() {
  if (!current.value) return;
  saving.value = true;
  try {
    const saved = await saveToolRouting(current.value.toolId, form.value);
    configurations.value[saved.toolId] = saved;
    dialogVisible.value = false;
    await load();
    ElMessage.success(saved.enabled ? '已保存并纳入路由目录' : '已保存为未启用配置');
  } catch (error: any) { ElMessageBox.alert(error.message || '保存失败', '操作失败', { type: 'error' }); }
  finally { saving.value = false; }
}

async function toggleRoute(row: ToolRoutingScanCandidate) {
  const existing = configurations.value[row.toolId];
  if (!existing) {
    ElMessage.warning('请先配置工具的业务主题和指标标签');
    return;
  }
  const enabled = !existing.enabled;
  try {
    const saved = await setToolRoutingEnabled(row.toolId, existing, enabled);
    const wasEnabled = existing.enabled;
    configurations.value[saved.toolId] = saved;
    row.configured = true;
    row.routeEnabled = saved.enabled;
    if (status.value && wasEnabled !== saved.enabled) {
      status.value.enabledRoutes += saved.enabled ? 1 : -1;
    }
    ElMessage.success(enabled ? '已启用目录发现' : '已停用目录发现');
  } catch (error: any) { ElMessage.error(error.message || '更新工具状态失败'); }
}

function openTag(type: TagType) { tagType.value = type; tagName.value = ''; tagDescription.value = ''; tagDialogVisible.value = true; }
async function createTag() {
  if (!tagName.value.trim()) return ElMessage.warning('请输入标签名称');
  try {
    await saveTag(tagType.value, tagName.value.trim(), tagDescription.value.trim());
    tagDialogVisible.value = false;
    await load();
    ElMessage.success('标签已保存');
  } catch (error: any) { ElMessage.error(error.message || '保存失败'); }
}
function issues(row: ToolRoutingScanCandidate) { return row.issueCodes.length ? row.issueCodes.join('、') : '-'; }
load();
</script>

<template>
  <div class="page">
    <div class="header">
      <div><h2>工具路由</h2><div class="subtitle">统一管理 SQL、API 与 Python 脚本的发现元数据</div></div>
      <el-tag :type="status?.globallyEnabled ? 'success' : 'info'" effect="light">
        全局路由：{{ status?.globallyEnabled ? '已启用' : '未启用' }}
      </el-tag>
      <span class="hint">全局开关由应用配置和重启控制</span>
      <el-button size="small" :loading="loading" @click="load">扫描刷新</el-button>
    </div>
    <el-alert v-if="status" :title="`当前已配置 ${status.configuredTools} 个工具，其中 ${status.enabledRoutes} 个进入路由目录。`" type="info" :closable="false" show-icon class="notice" />
    <el-tabs>
      <el-tab-pane label="工具配置">
        <div class="filters"><el-input v-model="keyword" placeholder="搜索工具 ID、名称、描述、创建人" clearable size="small" style="width: 260px" />
          <el-select v-model="typeFilter" placeholder="全部类型" clearable size="small" style="width: 120px"><el-option label="SQL" value="SQL" /><el-option label="API" value="API" /><el-option label="Python 脚本" value="SCRIPT" /></el-select>
          <el-select v-model="ownerFilter" placeholder="全部范围" clearable size="small" style="width: 120px"><el-option label="全部" value="" /><el-option label="我的" value="mine" /></el-select></div>
        <el-table :data="filteredRows" v-loading="loading" stripe border size="small">
          <el-table-column prop="toolId" label="工具 ID" min-width="180" show-overflow-tooltip />
          <el-table-column prop="toolType" label="类型" width="90" align="center" />
          <el-table-column prop="description" label="来源描述" min-width="260" show-overflow-tooltip />
          <el-table-column prop="creator" label="创建人" width="130" show-overflow-tooltip><template #default="{ row }">{{ row.creator || '-' }}</template></el-table-column>
          <el-table-column label="业务主题" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ configurations[row.toolId]?.topicTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="指标标签" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ configurations[row.toolId]?.metricTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="维度标签" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ configurations[row.toolId]?.dimensionTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="可用性" width="100" align="center"><template #default="{ row }"><el-tag :type="row.sourceAvailable ? 'success' : 'danger'" size="small">{{ row.sourceAvailable ? '可用' : '不可用' }}</el-tag></template></el-table-column>
          <el-table-column label="状态" width="100" align="center"><template #default="{ row }"><el-switch :model-value="row.configured && row.routeEnabled" size="small" @click="!row.configured && openConfig(row)" @change="row.configured && toggleRoute(row)" /></template></el-table-column>
          <el-table-column label="扫描问题" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ issues(row) }}</template></el-table-column>
          <el-table-column label="操作" width="88" fixed="right"><template #default="{ row }"><el-button size="small" @click="openConfig(row)">配置</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="标签词典">
        <div class="dictionary"><section><div class="section-head"><h3>业务主题</h3><el-button size="small" type="primary" @click="openTag('TOPIC')">新增主题</el-button></div><el-tag v-for="tag in topics" :key="tag.tagName" class="tag" type="warning">{{ tag.tagName }}</el-tag><span v-if="!topics.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>指标标签</h3><el-button size="small" type="primary" @click="openTag('METRIC')">新增指标</el-button></div><el-tag v-for="tag in metrics" :key="tag.tagName" class="tag">{{ tag.tagName }}</el-tag><span v-if="!metrics.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>维度标签</h3><el-button size="small" type="primary" @click="openTag('DIMENSION')">新增维度</el-button></div><el-tag v-for="tag in dimensions" :key="tag.tagName" class="tag" type="success">{{ tag.tagName }}</el-tag><span v-if="!dimensions.length" class="empty">暂无标签</span></section></div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogVisible" :title="`配置工具：${current?.toolId || ''}`" width="680px" destroy-on-close><el-form label-width="96px" size="small"><el-form-item label="工具类型"><el-input :model-value="form.toolType" disabled /></el-form-item><el-form-item label="路由描述"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="3000" show-word-limit /></el-form-item><el-form-item label="业务主题" required><el-select v-model="form.topicTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in topicOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="指标标签" required><el-select v-model="form.metricTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in metricOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="维度标签"><el-select v-model="form.dimensionTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in dimensionOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="优先级"><el-input-number v-model="form.priority" :min="-1000" :max="1000" /></el-form-item><el-form-item label="状态"><el-switch v-model="form.enabled" /><span class="field-hint">仅控制是否出现在 tool_index 目录，不影响固定 toolId 的调用。</span></el-form-item></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button></template></el-dialog>
    <el-dialog v-model="tagDialogVisible" :title="tagType === 'TOPIC' ? '新增业务主题' : tagType === 'METRIC' ? '新增指标标签' : '新增维度标签'" width="480px"><el-form label-width="84px"><el-form-item label="标签名称" required><el-input v-model="tagName" maxlength="64" /></el-form-item><el-form-item label="说明"><el-input v-model="tagDescription" type="textarea" :rows="3" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="tagDialogVisible = false">取消</el-button><el-button type="primary" @click="createTag">保存</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; } .header,.filters,.section-head { display:flex; align-items:center; gap:12px; } .header { margin-bottom:16px; } h2,h3 { margin:0; } h2 { font-size:1.2rem; } h3 { font-size:1rem; } .subtitle,.hint,.field-hint,.empty { color:#64748b; font-size:.8rem; } .hint { margin-left:auto; } .field-hint { margin-left:10px; } .notice { margin-bottom:16px; } .filters { margin-bottom:12px; } .dictionary { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:16px; } .dictionary section { background:#fff; border:1px solid #e2e8f0; padding:16px; } .section-head { justify-content:space-between; margin-bottom:12px; } .tag { margin:0 8px 8px 0; }
</style>
