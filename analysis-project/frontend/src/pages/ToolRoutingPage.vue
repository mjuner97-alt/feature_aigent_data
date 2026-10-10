<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getToolRoutingStatus, listTags, listToolRoutingAll, saveTag, saveToolRouting, scanToolRouting, setToolRoutingEnabled } from '../api/toolRouting';
import { routingOverlapSummary, toolToolOverlapSummary } from '../api/routingOverlap';
import { canEditConfig, isAdmin } from '../utils/auth';
import { useRouter } from 'vue-router';
import type { TagType, ToolRoutingInput, ToolRoutingMetadata, ToolRoutingScanCandidate, ToolRoutingStatus, ToolRoutingTag } from '../types/toolRouting';

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const rows = ref<ToolRoutingScanCandidate[]>([]);
const highOverlap = ref<Record<string, number>>({});
// 同 toolId 跨类型注册冲突 (DUPLICATE_TOOL_ID): 只出提示角标, 不置灰启用开关——
// 元数据一行可指向其中一个类型启用, 路由目录仍只有一条, 无选型风险
const dupToolId = ref<Record<string, number>>({});
// Skill↔Tool HIGH 重叠 (整改动作是退役 Skill, 不影响工具启用开关)
const skillHigh = ref<Record<string, number>>({});
const configurations = ref<Record<string, ToolRoutingMetadata>>({});
const topics = ref<ToolRoutingTag[]>([]);
const metrics = ref<ToolRoutingTag[]>([]);
const dimensions = ref<ToolRoutingTag[]>([]);
const status = ref<ToolRoutingStatus | null>(null);
const keyword = ref('');
const typeFilter = ref('');
const currentPage = ref(1); const pageSize = ref(100); const total = ref(0);
// 我的/全部 范围切换: 管理员默认'全部', 普通用户默认'我的', 按创建人字段 (ownerUserId) = 当前用户过滤
const scope = ref<'mine' | 'all'>(isAdmin() ? 'all' : 'mine');
const dialogVisible = ref(false);
const current = ref<ToolRoutingScanCandidate | null>(null);
const form = ref<ToolRoutingInput>(emptyInput());
const tagDialogVisible = ref(false);
const tagType = ref<TagType>('METRIC');
const tagName = ref('');
const tagDescription = ref('');
const currentUserId = localStorage.getItem('skill-user-id') || 'demo-user';
// 生产环境 (app.env.production=true) 下非管理员只读, 即使是本人创建的条目
const canEdit = (row: ToolRoutingScanCandidate) => canEditConfig() && (!!row.ownerUserId && row.ownerUserId === currentUserId || isAdmin());

function emptyInput(): ToolRoutingInput { return { toolType: 'SQL', description: '', topicTags: [], metricTags: [], dimensionTags: [], priority: 0, enabled: false }; }
// 元数据主键是 (tool_id, tool_type): 同 toolId 的 SCRIPT/SQL 是两条独立配置、各自有开关
function configKey(row: { toolId: string; toolType: string }) { return row.toolId + '|' + row.toolType; }
// keyword/typeFilter 已下沉到后端 /scan 分页前过滤 (搜索要覆盖所有分页); 前端只剩 我的/全部 范围过滤
const filteredRows = computed(() => rows.value.filter(row => scope.value === 'all' || canEdit(row)));
watch([keyword, typeFilter], () => { currentPage.value = 1; load(); });
const topicOptions = computed(() => topics.value.map(tag => tag.tagName));
const metricOptions = computed(() => metrics.value.map(tag => tag.tagName));
const dimensionOptions = computed(() => dimensions.value.map(tag => tag.tagName));

async function load() {
  loading.value = true;
  try {
    const [scanned, configured, currentStatus, topicTags, metricTags, dimensionTags] = await Promise.all([
      scanToolRouting(currentPage.value, pageSize.value, keyword.value.trim(), typeFilter.value),
      listToolRoutingAll(), getToolRoutingStatus(), listTags('TOPIC'), listTags('METRIC'), listTags('DIMENSION'),
    ]);
    rows.value = scanned.items; total.value = scanned.total;
    configurations.value = Object.fromEntries(configured.map(item => [item.toolId + '|' + item.toolType, item]));
    status.value = currentStatus;
    topics.value = topicTags;
    metrics.value = metricTags;
    dimensions.value = dimensionTags;
  } catch (error: any) { ElMessage.error(error.message || '加载失败'); }
  finally { loading.value = false; }
  // Tool↔Tool 重叠统计是派生数据且可能较慢, 不阻塞列表渲染; 失败时保留旧值不清空,
  // 避免后端拦截依赖的前端置灰状态闪没 (启用最终由服务端 assertNotHighOverlap 强制)
  toolToolOverlapSummary()
    .then(overlap => {
      highOverlap.value = overlap?.highByTool || {};
      dupToolId.value = overlap?.duplicateByTool || {};
    })
    .catch(() => { });
  routingOverlapSummary()
    .then(overlap => { skillHigh.value = overlap?.highByTool || {}; })
    .catch(() => { });
}
function changePage(page: number) { currentPage.value = page; load(); }
function changePageSize(size: number) { pageSize.value = size; currentPage.value = 1; load(); }

async function openConfig(row: ToolRoutingScanCandidate) {
  current.value = row;
  const existing = configurations.value[configKey(row)];
  form.value = existing ? { ...existing, topicTags: [...existing.topicTags], metricTags: [...existing.metricTags], dimensionTags: [...existing.dimensionTags] }
    : { ...emptyInput(), toolType: row.toolType, description: row.description };
  dialogVisible.value = true;
}

// ==================== 查看弹窗 (所有人可见, 只读) ====================
const viewVisible = ref(false);
const viewRow = ref<ToolRoutingScanCandidate | null>(null);
const viewConfig = computed(() => (viewRow.value ? configurations.value[configKey(viewRow.value)] : undefined));
function openView(row: ToolRoutingScanCandidate) {
  viewRow.value = row;
  viewVisible.value = true;
}

async function saveConfig() {
  if (!current.value) return;
  saving.value = true;
  try {
    const saved = await saveToolRouting(current.value.toolId, form.value);
    configurations.value[configKey(saved)] = saved;
    dialogVisible.value = false;
    await load();
    ElMessage.success(saved.enabled ? '已保存并纳入路由目录' : '已保存为未启用配置');
  } catch (error: any) { ElMessageBox.alert(error.message || '保存失败', '操作失败', { type: 'error' }); }
  finally { saving.value = false; }
}

async function toggleRoute(row: ToolRoutingScanCandidate) {
  const existing = configurations.value[configKey(row)];
  if (!existing) {
    ElMessage.warning('请先配置工具的业务主题和指标标签');
    return;
  }
  const enabled = !existing.enabled;
  try {
    const saved = await setToolRoutingEnabled(row.toolId, existing, enabled);
    const wasEnabled = existing.enabled;
    configurations.value[configKey(saved)] = saved;
    row.configured = true;
    row.routeEnabled = saved.enabled;
    // 启用互斥 (同 toolId 仅一个类型启用): 后端 disableOtherTypes 已停其他类型,
    // 本地同步翻转其他行的开关, 避免下次刷新前显示陈旧的"已启用"
    if (saved.enabled) {
      for (const r of rows.value) {
        if (r.toolId === row.toolId && r.toolType !== row.toolType) {
          r.routeEnabled = false;
          const other = configurations.value[configKey(r)];
          if (other) { configurations.value[configKey(r)] = { ...other, enabled: false }; }
        }
      }
    }
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
function viewToolToolOverlap(toolId: string) {
  router.push({ path: '/script-registry/overlap', query: { view: 'tool-tool', toolId } });
}
function viewSkillOverlap(toolId: string) {
  router.push({ path: '/script-registry/overlap', query: { toolId } });
}
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
      <el-radio-group v-model="scope" size="small">
        <el-radio-button label="mine">我的</el-radio-button>
        <el-radio-button label="all">全部</el-radio-button>
      </el-radio-group>
    </div>
    <el-alert v-if="status" :title="`当前已配置 ${status.configuredTools} 个工具，其中 ${status.enabledRoutes} 个进入路由目录。`" type="info" :closable="false" show-icon class="notice" />
    <el-tabs>
      <el-tab-pane label="工具配置">
        <div class="filters">
          <el-input v-model="keyword" placeholder="搜索工具 ID、名称、描述、创建人" clearable size="small" style="width: 260px" />
          <el-select v-model="typeFilter" placeholder="全部类型" clearable size="small" style="width: 120px"><el-option label="SQL" value="SQL" /><el-option label="API" value="API" /><el-option label="Python 脚本" value="SCRIPT" /></el-select></div>
        <el-table :data="filteredRows" v-loading="loading" stripe border size="small">
          <el-table-column prop="toolId" label="工具 ID" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag v-if="highOverlap[row.toolId] || dupToolId[row.toolId]" type="danger" effect="dark" size="small" style="margin-right: 6px; cursor: pointer; flex-shrink: 0"
                :title="highOverlap[row.toolId] ? '与其他工具存在 HIGH 级别能力重叠，启用受限，点击查看' : '同一 toolId 以多种类型重复注册（HIGH），请重命名独立 toolId 或退役其一；启用一个类型会自动停用其他类型，点击查看'"
                @click="viewToolToolOverlap(row.toolId)">重叠 {{ highOverlap[row.toolId] || dupToolId[row.toolId] }}</el-tag>
              <el-tag v-if="dupToolId[row.toolId]" type="warning" effect="dark" size="small" style="margin-right: 6px; cursor: pointer; flex-shrink: 0"
                title="同一 toolId 以多种类型重复注册（与类型无关的注册冲突），点击查看"
                @click="viewToolToolOverlap(row.toolId)">ID重复</el-tag>
              <el-tag v-if="skillHigh[row.toolId]" type="warning" effect="dark" size="small" style="margin-right: 6px; cursor: pointer; flex-shrink: 0"
                title="与 Skill 存在 HIGH 级别能力重叠（建议退役重复 Skill），点击查看"
                @click="viewSkillOverlap(row.toolId)">Skill重叠 {{ skillHigh[row.toolId] }}</el-tag>
              <span :style="highOverlap[row.toolId] ? 'color:#dc2626;font-weight:600' : ''">{{ row.toolId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="toolType" label="类型" width="90" align="center" />
          <el-table-column prop="description" label="来源描述" min-width="260" show-overflow-tooltip />
          <el-table-column prop="creator" label="创建人" width="130" show-overflow-tooltip><template #default="{ row }">{{ row.creator || '-' }}</template></el-table-column>
          <el-table-column label="业务主题" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ configurations[configKey(row)]?.topicTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="指标标签" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ configurations[configKey(row)]?.metricTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="维度标签" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ configurations[configKey(row)]?.dimensionTags?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="可用性" width="100" align="center"><template #default="{ row }"><el-tag :type="row.sourceAvailable ? 'success' : 'danger'" size="small">{{ row.sourceAvailable ? '可用' : '不可用' }}</el-tag></template></el-table-column>
          <el-table-column label="状态" width="110" align="center"><template #default="{ row }"><span v-if="canEdit(row)" :title="highOverlap[row.toolId] ? '与同类工具存在 HIGH 级重叠，同一候选集内至多启用一个' : (dupToolId[row.toolId] ? '同一 toolId 多类型重复注册：启用本类型会自动停用其他类型' : '')"><el-switch :model-value="row.configured && row.routeEnabled" size="small" :disabled="row.configured && !row.routeEnabled && !!highOverlap[row.toolId]" @click="!row.configured && openConfig(row)" @change="row.configured && toggleRoute(row)" /></span><span v-else>{{ row.configured && row.routeEnabled ? '已启用' : '已停用' }}</span></template></el-table-column>
          <el-table-column label="扫描问题" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ issues(row) }}</template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><!-- 查看所有人可见; 配置仅本人 --><el-button size="small" @click="openView(row)">查看</el-button><el-button v-if="canEdit(row)" size="small" @click="openConfig(row)">配置</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changePageSize" />
      </el-tab-pane>
      <!-- 标签词典仅管理员可见/可新增 (数据加载保留, 配置弹窗下拉依赖词典接口) -->
      <el-tab-pane v-if="isAdmin()" label="标签词典">
        <div class="dictionary"><section><div class="section-head"><h3>业务主题</h3><el-button size="small" @click="openTag('TOPIC')">新增</el-button></div><el-tag v-for="tag in topics" :key="tag.tagName" class="tag" type="warning">{{ tag.tagName }}</el-tag><span v-if="!topics.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>指标标签</h3><el-button size="small" @click="openTag('METRIC')">新增</el-button></div><el-tag v-for="tag in metrics" :key="tag.tagName" class="tag">{{ tag.tagName }}</el-tag><span v-if="!metrics.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>维度标签</h3><el-button size="small" @click="openTag('DIMENSION')">新增</el-button></div><el-tag v-for="tag in dimensions" :key="tag.tagName" class="tag" type="success">{{ tag.tagName }}</el-tag><span v-if="!dimensions.length" class="empty">暂无标签</span></section></div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogVisible" :title="`配置工具：${current?.toolId || ''}`" width="680px" destroy-on-close><el-form label-width="96px" size="small"><el-form-item label="工具类型"><el-input :model-value="form.toolType" disabled /></el-form-item><el-form-item label="路由描述"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="3000" show-word-limit /></el-form-item><el-form-item label="业务主题" required><el-select v-model="form.topicTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in topicOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="指标标签" required><el-select v-model="form.metricTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in metricOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="维度标签"><el-select v-model="form.dimensionTags" multiple filterable style="width: 100%" placeholder="从规范词典选择"><el-option v-for="tag in dimensionOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item><el-form-item label="优先级"><el-input-number v-model="form.priority" :min="-1000" :max="1000" /></el-form-item><el-form-item label="状态"><el-switch v-model="form.enabled" /><span class="field-hint">仅控制是否出现在 tool_index 目录，不影响固定 toolId 的调用。</span></el-form-item></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button></template></el-dialog>
    <!-- 查看弹窗 (只读, 所有人可见) -->
    <el-dialog v-model="viewVisible" :title="`查看工具：${viewRow?.toolId || ''}`" width="680px" destroy-on-close>
      <el-form label-width="96px" size="small" :disabled="true">
        <el-form-item label="工具类型"><el-input :model-value="viewRow?.toolType" /></el-form-item>
        <el-form-item label="路由描述"><el-input :model-value="viewConfig?.description || viewRow?.description || '-'" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="业务主题"><el-input :model-value="viewConfig?.topicTags?.join('、') || '-'" /></el-form-item>
        <el-form-item label="指标标签"><el-input :model-value="viewConfig?.metricTags?.join('、') || '-'" /></el-form-item>
        <el-form-item label="维度标签"><el-input :model-value="viewConfig?.dimensionTags?.join('、') || '-'" /></el-form-item>
        <el-form-item label="优先级"><span>{{ viewConfig?.priority ?? '-' }}</span></el-form-item>
        <el-form-item label="状态"><span>{{ viewConfig?.enabled ? '已启用' : '未启用' }}</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="tagDialogVisible" :title="tagType === 'TOPIC' ? '新增业务主题' : tagType === 'METRIC' ? '新增指标标签' : '新增维度标签'" width="480px"><el-form label-width="84px"><el-form-item label="标签名称" required><el-input v-model="tagName" maxlength="64" /></el-form-item><el-form-item label="说明"><el-input v-model="tagDescription" type="textarea" :rows="3" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="tagDialogVisible = false">取消</el-button><el-button type="primary" @click="createTag">保存</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; } .header,.filters,.section-head { display:flex; align-items:center; gap:12px; } .header { margin-bottom:16px; } h2,h3 { margin:0; } h2 { font-size:1.2rem; } h3 { font-size:1rem; } .subtitle,.hint,.field-hint,.empty { color:#64748b; font-size:.8rem; } .hint { margin-left:auto; } .field-hint { margin-left:10px; } .notice { margin-bottom:16px; } .filters { margin-bottom:12px; } .dictionary { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:16px; } .dictionary section { background:#fff; border:1px solid #e2e8f0; padding:16px; } .section-head { justify-content:space-between; margin-bottom:12px; } .tag { margin:0 8px 8px 0; }
</style>
