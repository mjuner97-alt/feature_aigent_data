<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listSkillRouting, saveSkillRouting, setSkillRoutingActive } from '../api/skillRouting';
import type { SkillRoutingInput, SkillRoutingMetadata } from '../types/skillRouting';

const rows = ref<SkillRoutingMetadata[]>([]);
const loading = ref(false);
const keyword = ref('');
const activeFilter = ref<string>('');
const dialogVisible = ref(false);
const saving = ref(false);
const current = ref<SkillRoutingMetadata | null>(null);
const form = ref<SkillRoutingInput>(emptyInput());

function emptyInput(): SkillRoutingInput {
  return { shortSummary: '', aliases: [], keywords: [], metricTags: [], domainTags: [], dataSourceTags: [], priority: 0, active: true };
}
function tags(values: string[]): string { return (values || []).join(', '); }
function split(value: string): string[] { return value.split(/[,，、\n]/).map(v => v.trim()).filter(Boolean).filter((v, i, a) => a.indexOf(v) === i); }
function openEdit(row: SkillRoutingMetadata) {
  current.value = row;
  form.value = { shortSummary: row.shortSummary || '', aliases: [...row.aliases], keywords: [...row.keywords], metricTags: [...row.metricTags], domainTags: [...row.domainTags], dataSourceTags: [...row.dataSourceTags], priority: row.priority, active: row.active };
  dialogVisible.value = true;
}
async function load() {
  loading.value = true;
  try { rows.value = await listSkillRouting(keyword.value || undefined, activeFilter.value === '' ? undefined : activeFilter.value === 'true'); }
  catch (e: any) { ElMessage.error(e.message || '加载失败'); }
  finally { loading.value = false; }
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
      <el-input v-model="keyword" placeholder="搜索 Skill 名称 / 描述" clearable size="small" style="width: 240px" />
      <el-select v-model="activeFilter" placeholder="全部状态" clearable size="small" style="width: 120px">
        <el-option label="全部" value="" /><el-option label="已启用" value="true" /><el-option label="已停用" value="false" />
      </el-select>
      <span class="hint">仅配置路由元数据，Skill 正文请在 Skill 广场维护</span>
    </div>
    <el-table :data="rows" v-loading="loading" stripe border size="small">
      <el-table-column prop="skillName" label="Skill 名称" width="260" show-overflow-tooltip />
      <el-table-column prop="description" label="已有描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="领域标签" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.domainTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="指标标签" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.metricTags.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="关键词" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.keywords.join('、') || '-' }}</template></el-table-column>
      <el-table-column label="别名" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.aliases.join('、') || '-' }}</template></el-table-column>
      <el-table-column prop="priority" label="优先级" width="80" align="center" />
      <el-table-column label="状态" width="80" align="center"><template #default="{ row }"><el-switch :model-value="row.active" size="small" @change="toggle(row)" /></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button size="small" @click="openEdit(row)">配置</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="`配置 Skill: ${current?.skillName || ''}`" width="680px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="Skill 名称"><el-input :model-value="current?.skillName" disabled /></el-form-item>
        <el-form-item label="路由摘要"><el-input v-model="form.shortSummary" type="textarea" :rows="3" maxlength="3000" show-word-limit placeholder="默认使用 Skill 已有描述" /></el-form-item>
        <el-form-item label="别名"><el-input :model-value="tags(form.aliases)" @update:model-value="v => form.aliases = split(v)" placeholder="逗号、顿号或换行分隔，如 q2_1, Q2-1" /></el-form-item>
        <el-form-item label="关键词"><el-input :model-value="tags(form.keywords)" @update:model-value="v => form.keywords = split(v)" placeholder="逗号、顿号或换行分隔，如 达标率、打分率" /></el-form-item>
        <el-form-item label="指标标签"><el-input :model-value="tags(form.metricTags)" @update:model-value="v => form.metricTags = split(v)" placeholder="逗号、顿号或换行分隔" /></el-form-item>
        <el-form-item label="领域标签"><el-input :model-value="tags(form.domainTags)" @update:model-value="v => form.domainTags = split(v)" placeholder="逗号、顿号或换行分隔" /></el-form-item>
        <el-form-item label="数据源标签"><el-input :model-value="tags(form.dataSourceTags)" @update:model-value="v => form.dataSourceTags = split(v)" placeholder="逗号、顿号或换行分隔，如 gauss" /></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="-1000" :max="1000" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.2rem; }
.hint { color: #64748b; font-size: 0.8rem; margin-left: auto; }
.muted { color: #94a3b8; }
</style>
