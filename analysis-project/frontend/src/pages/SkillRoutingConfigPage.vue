<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listSkillRouting, saveSkillRouting, setSkillRoutingActive } from '../api/skillRouting';
import { listTags, saveTag } from '../api/toolRouting';
import type { TagType, ToolRoutingTag } from '../types/toolRouting';
import type { SkillRoutingInput, SkillRoutingMetadata } from '../types/skillRouting';

const rows = ref<SkillRoutingMetadata[]>([]);
const loading = ref(false);
const keyword = ref('');
const activeFilter = ref<string>('');
const dialogVisible = ref(false);
const saving = ref(false);
const current = ref<SkillRoutingMetadata | null>(null);
const form = ref<SkillRoutingInput>(emptyInput());
const domainTags = ref<ToolRoutingTag[]>([]);
const topicTags = ref<ToolRoutingTag[]>([]);
const metricTags = ref<ToolRoutingTag[]>([]);
const tagDialogVisible = ref(false);
const tagSaving = ref(false);
const tagType = ref<TagType>('METRIC');
const tagName = ref('');
const tagDescription = ref('');

const domainOptions = computed(() => [...new Set([...domainTags.value.map(tag => tag.tagName), ...form.value.domainTags])]);
const topicOptions = computed(() => [...new Set([...topicTags.value.map(tag => tag.tagName), ...form.value.topicTags])]);
const metricOptions = computed(() => [...new Set([...metricTags.value.map(tag => tag.tagName), ...form.value.metricTags])]);

function emptyInput(): SkillRoutingInput {
  return { shortSummary: '', keywords: [], domainTags: [], topicTags: [], metricTags: [], priority: 0, active: true };
}
function tags(values: string[]): string { return (values || []).join(', '); }
function openEdit(row: SkillRoutingMetadata) {
  current.value = row;
  form.value = {
    shortSummary: row.shortSummary || '', keywords: [...row.keywords], domainTags: [...row.domainTags],
    topicTags: [...row.topicTags], metricTags: [...row.metricTags], priority: row.priority, active: row.active,
  };
  dialogVisible.value = true;
}
async function load() {
  loading.value = true;
  try {
    const [skills, domains, topics, metrics] = await Promise.all([
      listSkillRouting(keyword.value || undefined,
      activeFilter.value === '' || activeFilter.value === 'mine' ? undefined : activeFilter.value === 'true',
      activeFilter.value === 'mine'),
      listTags('DOMAIN'), listTags('TOPIC'), listTags('METRIC'),
    ]);
    rows.value = skills;
    domainTags.value = domains;
    topicTags.value = topics;
    metricTags.value = metrics;
  }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
}
function openTag(type: TagType) {
  tagType.value = type;
  tagName.value = '';
  tagDescription.value = '';
  tagDialogVisible.value = true;
}
async function createTag() {
  if (!tagName.value.trim()) return ElMessage.warning('请输入标签名称');
  tagSaving.value = true;
  try {
    await saveTag(tagType.value, tagName.value.trim(), tagDescription.value.trim());
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
watch([keyword, activeFilter], load);
load();
</script>

<template>
  <div class="page">
    <div class="header">
      <h2>Skill 配置</h2>
      <el-input v-model="keyword" placeholder="搜索名称 / 描述 / 创建人" clearable size="small" style="width: 240px" />
      <el-select v-model="activeFilter" placeholder="全部状态" clearable size="small" style="width: 120px">
        <el-option label="全部" value="" /><el-option label="已启用" value="true" /><el-option label="已停用" value="false" /><el-option label="我的" value="mine" />
      </el-select>
      <span class="hint">仅配置路由元数据，Skill 正文请在 Skill 广场维护</span>
    </div>
    <el-tabs>
      <el-tab-pane label="Skill 配置">
    <el-table :data="rows" v-loading="loading" stripe border size="small">
      <el-table-column prop="skillName" label="Skill 名称" width="260" show-overflow-tooltip />
      <el-table-column prop="description" label="已有描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="领域标签" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.domainTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="业务主题" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.topicTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="指标标签" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.metricTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="关键词" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.keywords.join('、') || '-' }}</template></el-table-column>
      <el-table-column prop="creator" label="创建人" width="130" show-overflow-tooltip><template #default="{ row }">{{ row.creator || '-' }}</template></el-table-column>
      <el-table-column prop="priority" label="优先级" width="80" align="center" />
      <el-table-column label="状态" width="80" align="center"><template #default="{ row }"><el-switch :model-value="row.active" size="small" @change="toggle(row)" /></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button size="small" @click="openEdit(row)">配置</el-button></template></el-table-column>
    </el-table>
      </el-tab-pane>
      <el-tab-pane label="标签词典">
        <div class="dictionary">
          <section><div class="section-head"><h3>领域字典</h3><el-button size="small" type="primary" @click="openTag('DOMAIN')">新增领域</el-button></div><el-tag v-for="tag in domainTags" :key="tag.tagName" class="tag" type="success">{{ tag.tagName }}</el-tag><span v-if="!domainTags.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>主题词典</h3><el-button size="small" type="primary" @click="openTag('TOPIC')">新增主题</el-button></div><el-tag v-for="tag in topicTags" :key="tag.tagName" class="tag" type="warning">{{ tag.tagName }}</el-tag><span v-if="!topicTags.length" class="empty">暂无标签</span></section>
          <section><div class="section-head"><h3>指标字典</h3><el-button size="small" type="primary" @click="openTag('METRIC')">新增指标</el-button></div><el-tag v-for="tag in metricTags" :key="tag.tagName" class="tag">{{ tag.tagName }}</el-tag><span v-if="!metricTags.length" class="empty">暂无标签</span></section>
        </div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogVisible" :title="`配置 Skill: ${current?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="Skill 名称"><el-input :model-value="current?.skillName" disabled /></el-form-item>
        <el-form-item label="已有描述"><el-input :model-value="current?.description || '-'" type="textarea" :rows="3" disabled /></el-form-item>
        <el-form-item label="领域标签"><el-select v-model="form.domainTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in domainOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="业务主题"><el-select v-model="form.topicTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in topicOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="指标标签"><el-select v-model="form.metricTags" multiple filterable style="width: 100%" placeholder="从标签词典选择"><el-option v-for="tag in metricOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
        <el-form-item label="关键词"><el-input :model-value="tags(form.keywords)" @update:model-value="v => form.keywords = split(v)" placeholder="逗号、顿号或换行分隔，如 达标率、打分率" /></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="-1000" :max="1000" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="tagDialogVisible" :title="tagType === 'DOMAIN' ? '新增领域标签' : tagType === 'TOPIC' ? '新增业务主题' : '新增指标标签'" width="480px">
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
.empty { color: #64748b; font-size: 0.8rem; }
</style>
