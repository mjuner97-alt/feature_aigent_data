<script setup lang="ts">
/**
 * python 脚本注册表管理页面
 *
 * 功能: 列表 / 新增 / 编辑 / 删除 / 启停
 *
 * 与 SqlRegistryPage 同构, 但:
 *   - 字段为 script_id / script_path / datasources(多选) / params_schema / timeout_seconds
 *   - 不含「试运行」(脚本执行留给 agent 工具 script_exec)
 *
 * params_schema 用原始 JSON 文本框编辑, 保存前校验合法 JSON 数组.
 * datasources 是 JSON 数组字符串 (如 ["gauss","mysql"]), 前端用多选维护, 保存时 JSON.stringify.
 */
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listEntries, getEntry, createEntry, updateEntry, deleteEntry, setEntryEnabled } from '../api/scriptRegistry';
import type { ScriptRegistryListItem, ScriptRegistryInput } from '../types/scriptRegistry';

// ==================== 列表 ====================
const items = ref<ScriptRegistryListItem[]>([]);
const loading = ref(false);
const datasourceFilter = ref('');
const createdByFilter = ref('');
const keyword = ref('');

const filteredItems = computed(() => {
  let list = items.value;
  if (keyword.value) {
    const kw = keyword.value.toLowerCase();
    list = list.filter(it =>
      it.scriptId.toLowerCase().includes(kw) ||
      it.name.toLowerCase().includes(kw) ||
      (it.description || '').toLowerCase().includes(kw)
    );
  }
  return list;
});

async function loadList() {
  loading.value = true;
  try {
    items.value = await listEntries(datasourceFilter.value || undefined, createdByFilter.value || undefined);
  } catch (e: any) {
    ElMessage.error(e.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

watch(datasourceFilter, () => loadList());

// 创建人筛选为输入框, 防抖 800ms 避免逐字触发请求
let createdByTimer: ReturnType<typeof setTimeout> | undefined;
watch(createdByFilter, () => {
  clearTimeout(createdByTimer);
  createdByTimer = setTimeout(() => loadList(), 800);
});

loadList();

// ==================== 数据源数组解析 (展示用) ====================
function parseDatasources(s: string): string[] {
  if (!s) return [];
  try {
    const a = JSON.parse(s);
    return Array.isArray(a) ? a.map(String) : [];
  } catch {
    return [];
  }
}

// ==================== 新增/编辑弹窗 ====================
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formLoading = ref(false);
const form = ref<ScriptRegistryInput>({
  scriptId: '', name: '', description: '', scriptPath: '',
  datasources: '["gauss"]', paramsSchema: '[]', timeoutSeconds: 60,
});
const editId = ref<number>(0);

// 数据源多选: 维护数组, 保存时 JSON.stringify 到 form.datasources
const dsArr = ref<string[]>(['gauss']);

function openCreate() {
  formMode.value = 'create';
  editId.value = 0;
  form.value = {
    scriptId: '', name: '', description: '', scriptPath: '',
    datasources: '["gauss"]', paramsSchema: '[]', timeoutSeconds: 60,
  };
  dsArr.value = ['gauss'];
  showSchemaExample.value = false;
  formVisible.value = true;
}

async function openEdit(row: ScriptRegistryListItem) {
  formMode.value = 'edit';
  editId.value = row.id;
  formLoading.value = true;
  showSchemaExample.value = false;
  formVisible.value = true;
  try {
    const detail = await getEntry(row.id);
    form.value = {
      scriptId: detail.scriptId, name: detail.name, description: detail.description || '',
      scriptPath: detail.scriptPath, datasources: detail.datasources || '["gauss"]',
      paramsSchema: detail.paramsSchema || '[]', timeoutSeconds: detail.timeoutSeconds ?? 60,
      enabled: detail.enabled,
    };
    dsArr.value = parseDatasources(detail.datasources);
    if (dsArr.value.length === 0) dsArr.value = ['gauss'];
  } catch (e: any) {
    ElMessage.error(e.message || '加载详情失败');
  } finally {
    formLoading.value = false;
  }
}

/** 安全 JSON.parse: 失败返回 null (不抛) */
function tryParseJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function saveForm() {
  if (!form.value.scriptId || !form.value.name || !form.value.scriptPath) {
    ElMessage.warning('请填写 script_id / name / script_path');
    return;
  }
  if (dsArr.value.length === 0) {
    ElMessage.warning('请至少选择一个数据源');
    return;
  }
  // params_schema 校验合法 JSON 数组
  const schema = tryParseJson(form.value.paramsSchema);
  if (schema === null) {
    ElMessage.error('参数定义不是合法 JSON');
    return;
  }
  if (!Array.isArray(schema)) {
    ElMessage.error('参数定义必须是 JSON 数组');
    return;
  }
  // datasources 数组 -> JSON 字符串
  form.value.datasources = JSON.stringify(dsArr.value);
  // timeout_seconds 校验
  const t = Number(form.value.timeoutSeconds);
  if (!Number.isFinite(t) || t <= 0) {
    ElMessage.warning('超时秒数必须为正整数');
    return;
  }
  form.value.timeoutSeconds = Math.min(Math.floor(t), 300);

  formLoading.value = true;
  try {
    if (formMode.value === 'create') {
      await createEntry(form.value);
      ElMessage.success('新增成功');
    } else {
      await updateEntry(editId.value, form.value);
      ElMessage.success('修改成功');
    }
    formVisible.value = false;
    loadList();
  } catch (e: any) {
    ElMessageBox.alert(e.message || '保存失败', '操作失败', { type: 'error' });
  } finally {
    formLoading.value = false;
  }
}

// ==================== 删除 ====================
async function handleDelete(row: ScriptRegistryListItem) {
  try {
    await ElMessageBox.confirm(`确定删除 "${row.name}" (${row.scriptId})?`, '删除确认', { type: 'warning' });
    await deleteEntry(row.id);
    ElMessage.success('删除成功');
    loadList();
  } catch (e: any) {
    if (e !== 'cancel' && e?.toString() !== 'cancel') {
      ElMessageBox.alert(e.message || '删除失败', '操作失败', { type: 'error' });
    }
  }
}

// ==================== enabled 切换 ====================
async function toggleEnabled(row: ScriptRegistryListItem) {
  const newEnabled = row.enabled === 1 ? 0 : 1;
  try {
    // 只传 enabled, 复用后端选择性更新; 不重发 script_path / params_schema
    await setEntryEnabled(row.id, newEnabled);
    row.enabled = newEnabled;
    ElMessage.success(newEnabled === 1 ? '已启用' : '已禁用');
  } catch (e: any) {
    ElMessageBox.alert(e.message || '操作失败', '操作失败', { type: 'error' });
  }
}

// ==================== 参数定义格式化 / 示例 ====================
function formatJson() {
  const parsed = tryParseJson(form.value.paramsSchema);
  if (parsed === null) {
    ElMessage.error('不是合法 JSON, 无法格式化');
    return;
  }
  form.value.paramsSchema = JSON.stringify(parsed, null, 2);
}

const showSchemaExample = ref(false);

// ==================== 样式 ====================
const S = {
  page: { padding: '20px', height: '100%', overflow: 'auto' } as any,
  header: { display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' as const } as any,
  title: { margin: 0, fontSize: '1.2rem', fontWeight: 700 } as any,
  jsonEditor: { fontFamily: 'monospace', fontSize: '13px' } as any,
  exampleCode: { fontSize: '0.72rem', color: '#475569', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '4px', padding: '8px', margin: '6px 0 0', whiteSpace: 'pre', overflowX: 'auto', fontFamily: 'monospace' } as any,
};
</script>

<template>
  <div :style="S.page">
    <!-- 顶部栏 -->
    <div :style="S.header">
      <h2 :style="S.title">python 脚本注册表</h2>
      <el-input v-model="keyword" placeholder="搜索 script_id / 名称" style="width: 220px" clearable size="small" />
      <el-select v-model="datasourceFilter" placeholder="全部数据源" style="width: 140px" size="small" clearable>
        <el-option label="全部" value="" />
        <el-option label="MySQL" value="mysql" />
        <el-option label="GaussDB" value="gauss" />
        <el-option label="ClickHouse" value="clickhouse" />
      </el-select>
      <el-input v-model="createdByFilter" placeholder="创建人" style="width: 140px" size="small" clearable />
      <el-button type="primary" size="small" @click="openCreate">＋ 新增脚本</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="filteredItems" v-loading="loading" stripe border size="small" style="width: 100%">
      <el-table-column prop="scriptId" label="script_id" width="220" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column prop="scriptPath" label="脚本路径" width="220" show-overflow-tooltip />
      <el-table-column label="数据源" width="180">
        <template #default="{ row }">
          <el-tag v-for="ds in parseDatasources(row.datasources)" :key="ds" size="small"
            :type="ds === 'clickhouse' ? 'warning' : ds === 'gauss' ? 'success' : 'info'"
            style="margin-right: 4px">
            {{ ds }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="timeoutSeconds" label="超时(秒)" width="90" align="center" />
      <el-table-column label="启用" width="70" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" size="small" @change="toggleEnabled(row)" />
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="90" />
      <el-table-column prop="updatedAt" label="更新时间" width="160" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增脚本' : '编辑脚本'" width="760px" destroy-on-close>
      <el-form v-loading="formLoading" label-width="100px" size="small">
        <el-form-item label="script_id" required>
          <el-input v-model="form.scriptId" :disabled="formMode === 'edit'" placeholder="snake_case, 如 q2_1_metrics_by_dept_version" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="中文名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用途说明" />
        </el-form-item>
        <el-form-item label="脚本路径" required>
          <el-input v-model="form.scriptPath" placeholder="相对 /app/workspace/scripts/ 的路径, 如 q2_1_metrics_by_dept_version.py" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="dsArr" multiple style="width: 100%" placeholder="选择脚本需要访问的数据源">
            <el-option label="MySQL" value="mysql" />
            <el-option label="GaussDB" value="gauss" />
            <el-option label="ClickHouse" value="clickhouse" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数定义">
          <div style="width: 100%">
            <el-input v-model="form.paramsSchema" type="textarea" :rows="6" :style="S.jsonEditor"
              placeholder='JSON 数组' />
            <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px">
              <el-button size="small" text @click="formatJson">格式化</el-button>
              <el-button size="small" text @click="showSchemaExample = !showSchemaExample">
                {{ showSchemaExample ? '收起示例' : '查看示例' }}
              </el-button>
            </div>
            <pre v-if="showSchemaExample" :style="S.exampleCode">
参数示例:
            [
                单值: {"name":"版本","type":"string","required":true,"description":"版本计划,如 \"2026年8月份版本\""},
                多值: {"name":"版本","type":"array","required":true,"description":"版本计划列表,如 [\"2026年8月份版本\",\"2026年9月份版本\"]"}
            ]</pre>
          </div>
        </el-form-item>
        <el-form-item label="超时(秒)" required>
          <el-input-number v-model="form.timeoutSeconds" :min="1" :max="300" controls-position="right" />
          <span style="margin-left: 8px; font-size: 0.75rem; color: #94a3b8">硬上限 300 秒</span>
        </el-form-item>
        <el-form-item label="启用" v-if="formMode === 'edit'">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formLoading" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.el-textarea__inner) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}
</style>
