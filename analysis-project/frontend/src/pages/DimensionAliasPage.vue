<script setup lang="ts">
/**
 * 维度同义词配置: 小组 / 产品线 / 应用 三个维度的口语化别名表。
 *
 * 后端 /v2/dimension/alias (docs/dimension-alias-config-plan.md §7):
 * 删除为软删 (enabled=false); 改动后需刷新快照立即生效, 否则等 TTL 5 分钟。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createDimensionAlias,
  deleteDimensionAlias,
  listDimensionAlias,
  reloadDimensionAlias,
  updateDimensionAlias,
} from '../api/dimensionAlias';
import type { DimensionAlias, DimensionAliasInput, DimensionPath, PeerDimension } from '../types/dimensionAlias';
import { isAdmin } from '../utils/auth';

interface DimensionTab { path: DimensionPath; enum: PeerDimension; label: string; hint: string; }

const DIMENSIONS: DimensionTab[] = [
  { path: 'team', enum: 'TEAM', label: '小组', hint: '如: 军队组 -> 特种业务组' },
  { path: 'product-line', enum: 'PRODUCT_LINE', label: '产品线', hint: '如: 风险组 -> 全球市场风险管理应用 (一对多会触发反问)' },
  { path: 'application', enum: 'APPLICATION', label: '应用', hint: '如: 三农政法 -> FS-LFS-FARM / FS-GBCP-EPL' },
];

const active = ref<DimensionTab>(DIMENSIONS[0]);
const admin = isAdmin();
const loading = ref(false);
const saving = ref(false);
const rows = ref<DimensionAlias[]>([]);
const keyword = ref('');
const showDisabled = ref(false);

const dialogVisible = ref(false);
const editing = ref<DimensionAlias | null>(null);
const form = ref<DimensionAliasInput>(emptyInput());

function emptyInput(): DimensionAliasInput {
  return { alias: '', standardName: '', triggerKeyword: '', enabled: true, remark: '' };
}

const filteredRows = computed(() => rows.value.filter(row => {
  const matchKeyword = !keyword.value
    || [row.alias, row.standardName, row.triggerKeyword || '', row.remark || ''].join(' ').toLowerCase().includes(keyword.value.toLowerCase());
  const matchEnabled = showDisabled.value || row.enabled;
  return matchKeyword && matchEnabled;
}));
const disabledCount = computed(() => rows.value.filter(row => !row.enabled).length);

async function load() {
  loading.value = true;
  try {
    rows.value = await listDimensionAlias(active.value.path);
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

function switchDimension(tab: DimensionTab) {
  active.value = tab;
  load();
}

function openCreate() {
  editing.value = null;
  form.value = emptyInput();
  dialogVisible.value = true;
}

function openEdit(row: DimensionAlias) {
  editing.value = row;
  form.value = {
    dimension: row.dimension,
    alias: row.alias,
    standardName: row.standardName,
    triggerKeyword: row.triggerKeyword || '',
    enabled: row.enabled,
    remark: row.remark || '',
  };
  dialogVisible.value = true;
}

async function save() {
  if (!form.value.alias.trim() || !form.value.standardName.trim()) {
    ElMessage.warning('口语词 / 标准名不能为空');
    return;
  }
  saving.value = true;
  try {
    const input: DimensionAliasInput = {
      ...form.value,
      alias: form.value.alias.trim(),
      standardName: form.value.standardName.trim(),
      triggerKeyword: form.value.triggerKeyword?.trim() || null,
      remark: form.value.remark?.trim() || null,
    };
    if (editing.value && editing.value.id != null) {
      await updateDimensionAlias(editing.value.id, { ...input, dimension: active.value.enum });
    } else {
      await createDimensionAlias(active.value.path, input);
    }
    await reloadDimensionAlias().catch(() => undefined);
    dialogVisible.value = false;
    await load();
    ElMessage.success('已保存并刷新解析快照');
  } catch (error: any) {
    ElMessageBox.alert(error.message || '保存失败', '操作失败', { type: 'error' });
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: DimensionAlias) {
  const enabled = !row.enabled;
  try {
    await updateDimensionAlias(row.id!, {
      dimension: row.dimension,
      alias: row.alias,
      standardName: row.standardName,
      triggerKeyword: row.triggerKeyword,
      enabled,
      remark: row.remark,
    });
    await reloadDimensionAlias().catch(() => undefined);
    await load();
    ElMessage.success(enabled ? '已启用' : '已停用');
  } catch (error: any) {
    ElMessage.error(error.message || '更新失败');
  }
}

async function remove(row: DimensionAlias) {
  try {
    await ElMessageBox.confirm(
      `确认停用并归档「${row.alias} -> ${row.standardName}」? (软删, 可由管理员在库中重新启用)`,
      '删除确认',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await deleteDimensionAlias(row.id!);
    await reloadDimensionAlias().catch(() => undefined);
    await load();
    ElMessage.success('已删除');
  } catch (error: any) {
    ElMessage.error(error.message || '删除失败');
  }
}

async function refreshSnapshot() {
  try {
    await reloadDimensionAlias();
    ElMessage.success('已立即失效缓存快照 (否则最长等 TTL 5 分钟)');
  } catch (error: any) {
    ElMessage.error(error.message || '刷新失败');
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="header">
      <div>
        <h2>维度同义词</h2>
        <div class="subtitle">口语化表述 -> 标准名映射, 解析快照 TTL 5 分钟</div>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="admin" size="small" type="warning" plain @click="refreshSnapshot">刷新解析快照</el-button>
      <el-button v-if="admin" size="small" type="primary" @click="openCreate">新增{{ active.label }}同义词</el-button>
    </div>

    <el-alert
      :title="admin
        ? `${active.label}: ${active.hint}。当前停用 ${disabledCount} 行 (默认隐藏)。`
        : `维度同义词是全局口径配置，仅管理员可编辑；如需新增/调整请联系管理员。`"
      :type="admin ? 'info' : 'warning'" :closable="false" show-icon class="notice" />

    <div class="filters">
      <el-radio-group :model-value="active.path" size="small" @update:model-value="switchDimension(DIMENSIONS.find(d => d.path === $event)!)">
        <el-radio-button v-for="d in DIMENSIONS" :key="d.path" :label="d.path">{{ d.label }}</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" placeholder="搜索口语词 / 标准名 / 触发词 / 备注" clearable size="small" style="width: 260px" />
      <el-checkbox v-model="showDisabled" size="small">显示已停用</el-checkbox>
    </div>

    <el-table :data="filteredRows" v-loading="loading" stripe border size="small">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="alias" label="口语词" min-width="140">
        <template #default="{ row }">
          <span>{{ row.alias }}</span>
          <el-tag v-if="row.triggerKeyword" type="warning" size="small" style="margin-left: 6px"
            :title="`触发词「${row.triggerKeyword}」出现时本行才参与, 否则落其他维度`">触发: {{ row.triggerKeyword }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="standardName" label="标准名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-switch v-if="admin" :model-value="row.enabled" size="small" @change="toggleEnabled(row)" />
          <span v-else>{{ row.enabled ? '启用' : '停用' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="admin" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? `编辑${active.label}同义词 #${editing.id}` : `新增${active.label}同义词`" width="560px" destroy-on-close>
      <el-form label-width="96px" size="small">
        <el-form-item label="维度">
          <el-input :model-value="active.label" disabled />
        </el-form-item>
        <el-form-item label="口语词" required>
          <el-input v-model="form.alias" maxlength="128" placeholder="用户提问中的说法, 如: 军队" />
        </el-form-item>
        <el-form-item label="标准名" required>
          <el-input v-model="form.standardName" maxlength="256" placeholder="系统注入的标准名, 如: 企业资金管理系统" />
        </el-form-item>
        <el-form-item label="触发词">
          <el-input v-model="form.triggerKeyword" maxlength="128" placeholder="可选; 填了则仅当问题含该完整词时本行参与, 如: 军队组" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" />
          <span class="field-hint">停用行不参与解析</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header, .filters { display: flex; align-items: center; gap: 12px; }
.header { margin-bottom: 16px; }
h2 { margin: 0; font-size: 1.2rem; }
.subtitle, .field-hint { color: #64748b; font-size: .8rem; }
.header .el-button:last-child { margin-left: auto; }
.notice { margin-bottom: 16px; }
.filters { margin-bottom: 12px; }
</style>
