<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listSkillRouting, listSkillTags, saveSkillRouting, saveSkillTag, setSkillRoutingActive } from '../api/skillRouting';
import { routingOverlapSummary } from '../api/routingOverlap';
import { useRouter } from 'vue-router';
import type { ToolRoutingTag } from '../types/toolRouting';
import type { SkillRoutingInput, SkillRoutingMetadata } from '../types/skillRouting';
import { isAdmin } from '../utils/auth';

type SkillTagType = 'DOMAIN' | 'TOPIC';

const router = useRouter();
const rows = ref<SkillRoutingMetadata[]>([]);
const highOverlap = ref<Record<string, number>>({});
const loading = ref(false);
const keyword = ref('');
const activeFilter = ref<string>('');
// 我的/全部 范围切换: 管理员默认'全部', 普通用户默认'我的', 后端按 creator = 当前用户过滤
const scope = ref<'mine' | 'all'>(isAdmin() ? 'all' : 'mine');
const dialogVisible = ref(false);
const saving = ref(false);
const current = ref<SkillRoutingMetadata | null>(null);
const form = ref<SkillRoutingInput>(emptyInput());
const keywordsText = ref('');
const domainTags = ref<ToolRoutingTag[]>([]);
const topicTags = ref<ToolRoutingTag[]>([]);
const tagDialogVisible = ref(false);
const tagSaving = ref(false);
const tagType = ref<SkillTagType>('DOMAIN');
const tagName = ref('');
const tagDescription = ref('');
const currentUserId = localStorage.getItem('skill-user-id') || 'demo-user';
const canEdit = (row: SkillRoutingMetadata) => isAdmin() || (!!row.creator && row.creator === currentUserId);

const domainOptions = computed(() => [...new Set([...domainTags.value.map(tag => tag.tagName), ...form.value.domainTags])]);
const topicOptions = computed(() => [...new Set([...topicTags.value.map(tag => tag.tagName), ...form.value.topicTags])]);

function emptyInput(): SkillRoutingInput {
  return { shortSummary: '', keywords: [], domainTags: [], topicTags: [], active: true };
}
function tags(values: string[]): string { return (values || []).join(', '); }
function split(value: string): string[] {
  return value.split(/[,，、\r\n]+/).map(v => v.trim()).filter(v => v.length > 0);
}
function viewOverlap(skillName: string) {
  router.push({ path: '/script-registry/overlap', query: { skillName } });
}
function openEdit(row: SkillRoutingMetadata) {
  current.value = row;
  form.value = {
    shortSummary: row.shortSummary || '', keywords: [...row.keywords], domainTags: [...row.domainTags],
    topicTags: [...row.topicTags], active: row.active,
  };
  keywordsText.value = tags(form.value.keywords);
  dialogVisible.value = true;
}
// ==================== 查看弹窗 (所有人可见, 只读) ====================
const viewVisible = ref(false);
const viewRow = ref<SkillRoutingMetadata | null>(null);
function openView(row: SkillRoutingMetadata) {
  viewRow.value = row;
  viewVisible.value = true;
}
async function load() {
  loading.value = true;
  try {
    const [skills, domains, topics, overlap] = await Promise.all([
      listSkillRouting(keyword.value || undefined,
      activeFilter.value === '' ? undefined : activeFilter.value === 'true',
      scope.value === 'mine'),
      listSkillTags('DOMAIN'), listSkillTags('TOPIC'),
      routingOverlapSummary().catch(() => null),
    ]);
    rows.value = skills;
    highOverlap.value = overlap?.highBySkill || {};
    domainTags.value = domains;
    topicTags.value = topics;
  }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
}
function openTag(type: SkillTagType) {
  tagType.value = type;
  tagName.value = '';
  tagDescription.value = '';
  tagDialogVisible.value = true;
}
async function createTag() {
  if (!tagName.value.trim()) return ElMessage.warning('请输入标签名称');
  tagSaving.value = true;
  try {
    await saveSkillTag(tagType.value, tagName.value.trim(), tagDescription.value.trim());
    tagDialogVisible.value = false;
    await load();
    ElMessage.success('标签已保存');
  } catch (e: any) { ElMessage.error(e.message || '保存失败'); }
  finally { tagSaving.value = false; }
}
async function save() {
  if (!current.value) return;
  saving.value = true;
  try {
    form.value.keywords = split(keywordsText.value);
    const result = await saveSkillRouting(current.value.skillName, form.value);
    const index = rows.value.findIndex(r => r.skillName === result.skillName);
    if (index >= 0) rows.value[index] = { ...rows.value[index], ...result, configured: true };
    dialogVisible.value = false;
    ElMessage.success('保存成功');
  } catch (e: any) { ElMessageBox.alert(e.message || '保存失败', '操作失败', { type: 'error' }); }
  finally { saving.value = false; }
}
async function toggle(row: SkillRoutingMetadata) {
  const next = !row.active;
  try { const result = await setSkillRoutingActive(row.skillName, next); Object.assign(row, result); ElMessage.success(next ? '已启用' : '已停用'); }
  catch (e: any) { ElMessage.error(e.message || '操作失败'); }
}
watch([keyword, activeFilter, scope], load);
load();
</script>

<template>
  <div class="page">
    <div class="header">
      <h2>Skill 配置</h2>
      <el-input v-model="keyword" placeholder="搜索名称 / 描述 / 创建人" clearable size="small" style="width: 240px" />
      <el-select v-model="activeFilter" placeholder="全部状态" clearable size="small" style="width: 120px">
        <el-option label="全部" value="" /><el-option label="已启用" value="true" /><el-option label="已停用" value="false" />
      </el-select>
      <span class="hint">仅配置路由元数据，Skill 正文请在 Skill 广场维护</span>
      <el-radio-group v-model="scope" size="small">
        <el-radio-button label="mine">我的</el-radio-button>
        <el-radio-button label="all">全部</el-radio-button>
      </el-radio-group>
    </div>
    <el-tabs>
      <el-tab-pane label="Skill 配置">
    <el-table :data="rows" v-loading="loading" stripe border size="small">
      <el-table-column prop="skillName" label="Skill 名称" width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.skillName }}</span>
          <el-tag v-if="highOverlap[row.skillName]" type="danger" size="small" class="overlap-badge"
            title="与其他工具存在 HIGH 级别能力重叠，点击查看"
            @click="viewOverlap(row.skillName)">重叠 {{ highOverlap[row.skillName] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="已有描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="领域标签" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.domainTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="业务主题" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.topicTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="关键词" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.keywords.join('、') || '-' }}</template></el-table-column>
      <el-table-column prop="creator" label="创建人" width="130" show-overflow-tooltip><template #default="{ row }">{{ row.creator || '-' }}</template></el-table-column>
      <el-table-column label="状态" width="80" align="center"><template #default="{ row }"><el-switch v-if="canEdit(row)" :model-value="row.active" size="small" @change="toggle(row)" /><span v-else>{{ row.active ? '已启用' : '已停用' }}</span></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><!-- 查看所有人可见; 配置仅本人 --><el-button size="small" @click="openView(row)">查看</el-button><el-button v-if="canEdit(row)" size="small" @click="openEdit(row)">配置</el-button></template></el-table-column>
    </el-table>
      </el-tab-pane>
      <!-- 标签词典仅管理员可见/可新增 (数据加载保留, 配置弹窗下拉依赖词典接口) -->
      <el-tab-pane v-if="isAdmin()" label="标签词典">
        <div class="dictionary">
          <section><div class="section-head"><h3>领域字典</h3><el-button size="small" @click="openTag('DOMAIN')">新增</el-button></div><el-tag v-for="tag in domainTags" :key="tag.tagName" class="tag" type="success">{{ tag.tagName }}</el-tag><span v-if="!domainTags.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>主题词典</h3><el-button size="small" @click="openTag('TOPIC')">新增</el-button></div><el-tag v-for="tag in topicTags" :key="tag.tagName" class="tag" type="warning">{{ tag.tagName }}</el-tag><span v-if="!topicTags.length" class="empty">暂无标签</span></section>
        </div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogVisible" :title="`配置 Skill: ${current?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="Skill 名称"><el-input :model-value="current?.skillName" disabled /></el-form-item>
        <el-form-item label="已有描述"><el-input :model-value="current?.description || '-'" type="textarea" :rows="3" disabled /></el-form-item>
        <el-form-item label="领域标签"><el-select v-model="form.domainTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in domainOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="业务主题"><el-select v-model="form.topicTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in topicOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="关键词"><el-input v-model="keywordsText" placeholder="逗号、顿号或换行分隔，如 达标率、打分率" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <!-- 查看弹窗 (只读, 所有人可见) -->
    <el-dialog v-model="viewVisible" :title="`查看 Skill: ${viewRow?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small" :disabled="true">
        <el-form-item label="Skill 名称"><el-input :model-value="viewRow?.skillName" /></el-form-item>
        <el-form-item label="已有描述"><el-input :model-value="viewRow?.description || '-'" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="领域标签"><el-input :model-value="viewRow?.domainTags.join('、') || '-'" /></el-form-item>
        <el-form-item label="业务主题"><el-input :model-value="viewRow?.topicTags.join('、') || '-'" /></el-form-item>
        <el-form-item label="关键词"><el-input :model-value="viewRow?.keywords.join('、') || '-'" /></el-form-item>
        <el-form-item label="启用"><span>{{ viewRow?.active ? '已启用' : '已停用' }}</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="tagDialogVisible" :title="tagType === 'DOMAIN' ? '新增领域标签' : '新增业务主题'" width="480px">
      <el-form label-width="84px"><el-form-item label="标签名称" required><el-input v-model="tagName" maxlength="64" /></el-form-item><el-form-item label="说明"><el-input v-model="tagDescription" type="textarea" :rows="3" maxlength="500" /></el-form-item></el-form>
      <template #footer><el-button @click="tagDialogVisible = false">取消</el-button><el-button type="primary" :loading="tagSaving" @click="createTag">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.2rem; }
.hint { color: #64748b; font-size: 0.8rem; margin-left: auto; }
.muted { color: #94a3b8; }
.dictionary { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin-top: 4px; }
.dictionary section { background: #fff; border: 1px solid #e2e8f0; padding: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.section-head h3 { margin: 0; font-size: 1rem; }
.tag { margin: 0 8px 8px 0; }
.overlap-badge { margin-left: 6px; cursor: pointer; }
.empty { color: #64748b; font-size: 0.8rem; }
</style>
