<script setup lang="ts">
/**
 * 维度键字典 (docs/history/skill-direct-exec-plan.md §9):
 * 内置 8 键只读 (提取走结构化规则); 用户新增键走词表命中提取 (alias contains → value),
 * 供 Skill 配置页 param_mapping 下拉选择。动态键仅当前问题提取, 不跨轮继承。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createDimensionKey,
  deleteDimensionKey,
  listDimensionKeys,
  reloadDimensionKeys,
  updateDimensionKey,
  type DimensionKey,
} from '../api/dimensionKey';
import type { DimensionKeyAlias } from '../types/dimensionKey';
import { isAdmin } from '../utils/auth';

const admin = isAdmin();
const loading = ref(false);
const saving = ref(false);
const rows = ref<DimensionKey[]>([]);
const keyword = ref('');
const showDisabled = ref(false);

const dialogVisible = ref(false);
const editing = ref<DimensionKey | null>(null);
const form = ref(emptyForm());
const aliasRows = ref<DimensionKeyAlias[]>([]);

function emptyForm() {
  return { key: '', label: '', enabled: true, remark: '' };
}

const filteredRows = computed(() => rows.value.filter(row => {
  const matchKeyword = !keyword.value
    || [row.key, row.label, row.remark || '',
        ...row.aliases.map(a => `${a.alias} ${a.value}`)].join(' ').toLowerCase().includes(keyword.value.toLowerCase());
  const matchEnabled = showDisabled.value || row.enabled;
  return matchKeyword && matchEnabled;
}));

async function load() {
  loading.value = true;
  try {
    rows.value = await listDimensionKeys();
  } catch (error: any) {
    ElMessage.error(error.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = emptyForm();
  aliasRows.value = [];
  dialogVisible.value = true;
}

function openEdit(row: DimensionKey) {
  editing.value = row;
  form.value = { key: row.key, label: row.label, enabled: row.enabled, remark: row.remark || '' };
  aliasRows.value = row.aliases.map(a => ({ alias: a.alias, value: a.value }));
  dialogVisible.value = true;
}

async function save() {
  if (!form.value.key.trim() || !form.value.label.trim()) {
    ElMessage.warning('维度键 / 展示名不能为空');
    return;
  }
  saving.value = true;
  try {
    const input = {
      key: form.value.key.trim(),
      label: form.value.label.trim(),
      aliases: aliasRows.value.filter(a => a.alias.trim() && a.value.trim())
        .map(a => ({ alias: a.alias.trim(), value: a.value.trim() })),
      enabled: form.value.enabled,
      remark: form.value.remark.trim() || null,
    };
    if (editing.value) {
      await updateDimensionKey(editing.value.id, input);
    } else {
      await createDimensionKey(input);
    }
    await reloadDimensionKeys().catch(() => undefined);
    dialogVisible.value = false;
    await load();
    ElMessage.success('已保存并刷新解析快照');
  } catch (error: any) {
    ElMessageBox.alert(error.message || '保存失败', '操作失败', { type: 'error' });
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: DimensionKey) {
  const enabled = !row.enabled;
  try {
    await updateDimensionKey(row.id, {
      key: row.key, label: row.label, aliases: row.aliases, enabled, remark: row.remark,
    });
    await reloadDimensionKeys().catch(() => undefined);
    await load();
    ElMessage.success(enabled ? '已启用' : '已停用');
  } catch (error: any) {
    ElMessage.error(error.message || '更新失败');
  }
}

async function remove(row: DimensionKey) {
  try {
    await ElMessageBox.confirm(
      `确认停用并归档维度键「${row.key} (${row.label})」? (软删, 可由管理员在库中重新启用)`,
      '删除确认',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await deleteDimensionKey(row.id);
    await reloadDimensionKeys().catch(() => undefined);
    await load();
    ElMessage.success('已删除');
  } catch (error: any) {
    ElMessage.error(error.message || '删除失败');
  }
}

async function refreshSnapshot() {
  try {
    await reloadDimensionKeys();
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
        <h2>维度键字典</h2>
        <div class="subtitle">Skill 直接执行参数映射的维度键全集; 新增键走词表命中提取 (alias 出现即取标准值), 快照 TTL 5 分钟</div>
      </div>
      <el-input v-model="keyword" placeholder="搜索 key / 展示名 / 词表" clearable size="small" style="width: 220px" />
      <el-checkbox v-model="showDisabled" label="显示已停用" size="small" />
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="admin" size="small" type="warning" plain @click="refreshSnapshot">刷新解析快照</el-button>
      <el-button v-if="admin" size="small" type="primary" @click="openCreate">新增维度键</el-button>
    </div>
    <el-table :data="filteredRows" v-loading="loading" stripe border size="small">
      <el-table-column prop="key" label="维度键" width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.key }}</span>
          <el-tag v-if="row.builtin" size="small" type="info" style="margin-left: 6px">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="label" label="展示名" width="140" show-overflow-tooltip />
      <el-table-column label="词表 (alias → 标准值)" min-width="360" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.aliases.map((a: DimensionKeyAlias) => `${a.alias}→${a.value}`).join('、') || (row.builtin ? '结构化规则提取 (不走词表)' : '-') }}
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-switch v-if="admin" :model-value="row.enabled" size="small" @change="toggleEnabled(row)" />
          <span v-else>{{ row.enabled ? '已启用' : '已停用' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">查看</el-button>
          <el-button v-if="admin" size="small" type="primary" plain @click="openEdit(row)">配置</el-button>
          <el-button v-if="admin && !row.builtin" size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? `维度键: ${editing.key}` : '新增维度键'" width="640px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="维度键">
          <el-input v-model="form.key" :disabled="!!editing?.builtin" placeholder="字母开头的 camelCase, 如 qGate (内置键不可改)" maxlength="64" />
        </el-form-item>
        <el-form-item label="展示名"><el-input v-model="form.label" placeholder="如 卡口" maxlength="128" /></el-form-item>
        <el-form-item label="词表">
          <div class="alias-editor">
            <div v-if="!editing?.builtin">
              <div v-for="(row, idx) in aliasRows" :key="idx" class="alias-row">
                <el-input v-model="row.alias" placeholder="问题中的词, 如 Q1卡口" maxlength="128" style="width: 220px" />
                <span class="arrow">→</span>
                <el-input v-model="row.value" placeholder="标准值, 如 Q1" maxlength="128" style="width: 160px" />
                <el-button size="small" text type="danger" @click="aliasRows.splice(idx, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="aliasRows.push({ alias: '', value: '' })">+ 添加词表行</el-button>
            </div>
            <template v-else>
              <div v-for="(row, idx) in aliasRows" :key="idx" class="alias-row">
                <el-input :model-value="`${row.alias} → ${row.value}`" disabled style="width: 380px" />
              </div>
              <div class="hint">内置键提取走结构化规则, 词表只读</div>
            </template>
            <div class="hint">避免配置裸短词 (如裸 Q1), 防止在其他语境误命中; 触发词条件行为后续版本规划</div>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" maxlength="255" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="editing?.builtin" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="admin" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; height: 100%; overflow: auto; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.2rem; }
.subtitle { color: #64748b; font-size: 0.8rem; }
.alias-editor { width: 100%; }
.alias-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.alias-row .arrow { color: #64748b; }
.hint { color: #94a3b8; font-size: 0.75rem; margin-top: 4px; }
</style>
