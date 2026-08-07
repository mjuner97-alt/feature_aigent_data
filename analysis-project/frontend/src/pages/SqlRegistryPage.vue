<script setup lang="ts">
/**
 * SQL 注册表管理页面
 *
 * 功能: 列表 / 新增 / 编辑 / 删除
 * 表单内嵌"测试连接"按钮, 只显示通过✅/失败❌状态
 * SQL 测试只允许 SELECT, 禁止 DDL/DML
 */
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listEntries, getEntry, createEntry, updateEntry, deleteEntry, testSql } from '../api/sqlRegistry';
import type { SqlRegistryEntry, SqlRegistryListItem, SqlRegistryInput, SqlTestResult, ParamSchemaItem } from '../types/sqlRegistry';

// ==================== 列表 ====================
const items = ref<SqlRegistryListItem[]>([]);
const loading = ref(false);
const datasourceFilter = ref('');
const createdByFilter = ref('');
const keyword = ref('');

const filteredItems = computed(() => {
  let list = items.value;
  if (keyword.value) {
    const kw = keyword.value.toLowerCase();
    list = list.filter(it =>
      it.sqlId.toLowerCase().includes(kw) ||
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

// ==================== 新增/编辑弹窗 ====================
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formLoading = ref(false);
const form = ref<SqlRegistryInput>({
  sqlId: '', name: '', description: '', datasource: 'gauss',
  sqlTemplate: '', paramsSchema: '[]', enabled: 1,
});
const editId = ref<number>(0);

// params_schema 结构化编辑
const paramRows = ref<ParamSchemaItem[]>([]);

function parseParamsSchema(json: string): ParamSchemaItem[] {
  try {
    const arr = JSON.parse(json || '[]');
    // 参数只要添加即为必填, 不再支持可选
    return (arr as ParamSchemaItem[]).map(p => ({ ...p, required: true }));
  } catch {
    return [];
  }
}

function syncParamsToJson() {
  form.value.paramsSchema = JSON.stringify(paramRows.value);
}

function addParamRow() {
  paramRows.value.push({ name: '', type: 'string', required: true, description: '' });
  syncParamsToJson();
}

function removeParamRow(index: number) {
  paramRows.value.splice(index, 1);
  syncParamsToJson();
}

function onParamRowChange() {
  syncParamsToJson();
}

function openCreate() {
  formMode.value = 'create';
  editId.value = 0;
  form.value = { sqlId: '', name: '', description: '', datasource: 'gauss', sqlTemplate: '', paramsSchema: '[]', enabled: 1 };
  paramRows.value = [];
  testStatus.value = 'idle'; // 重置测试状态
  formVisible.value = true;
}

async function openEdit(row: SqlRegistryListItem) {
  formMode.value = 'edit';
  editId.value = row.id;
  formLoading.value = true;
  testStatus.value = 'idle';
  formVisible.value = true;
  try {
    const detail = await getEntry(row.id);
    form.value = {
      sqlId: detail.sqlId, name: detail.name, description: detail.description || '',
      datasource: detail.datasource, sqlTemplate: detail.sqlTemplate,
      paramsSchema: detail.paramsSchema || '[]', enabled: detail.enabled,
    };
    paramRows.value = parseParamsSchema(detail.paramsSchema);
  } catch (e: any) {
    ElMessage.error(e.message || '加载详情失败');
  } finally {
    formLoading.value = false;
  }
}

async function saveForm() {
  if (!form.value.sqlId || !form.value.name || !form.value.datasource || !form.value.sqlTemplate) {
    ElMessage.warning('请填写 sql_id / name / datasource / sql_template');
    return;
  }
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
async function handleDelete(row: SqlRegistryListItem) {
  try {
    await ElMessageBox.confirm(`确定删除 "${row.name}" (${row.sqlId})?`, '删除确认', { type: 'warning' });
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
async function toggleEnabled(row: SqlRegistryListItem) {
  const newEnabled = row.enabled === 1 ? 0 : 1;
  try {
    await updateEntry(row.id, {
      sqlId: row.sqlId, name: row.name, description: row.description || '',
      datasource: row.datasource, sqlTemplate: '',
      paramsSchema: row.paramsSchema || '[]', enabled: newEnabled,
    });
    row.enabled = newEnabled;
    ElMessage.success(newEnabled === 1 ? '已启用' : '已禁用');
  } catch (e: any) {
    ElMessageBox.alert(e.message || '操作失败', '操作失败', { type: 'error' });
  }
}

// ==================== 表单内测试连接 ====================
const testStatus = ref<'idle' | 'loading' | 'success' | 'fail'>('idle');
const testErrorMsg = ref('');
const testResultData = ref<SqlTestResult | null>(null);

async function runFormTest() {
  if (!form.value.sqlTemplate || !form.value.datasource) {
    ElMessage.warning('请先填写 SQL 模板和数据源');
    return;
  }

  testStatus.value = 'loading';
  testErrorMsg.value = '';
  testResultData.value = null;

  try {
    // 用 params_schema 里的参数构造测试参数 (用空值占位)
    const schema = parseParamsSchema(form.value.paramsSchema);
    const params: Record<string, any> = {};
    // 尝试从已有 testParamsMap 取用户填过的值, 没有就填示例
    for (const p of schema) {
      const val = formTestParams.value[p.name];
      if (val === undefined || val === '') continue;
      // 数组类型 (int[] / string[] / date[]): 按逗号拆分并转成对应元素类型
      if (p.type.endsWith('[]')) {
        const elemType = p.type.slice(0, -2);
        const arr = String(val)
          .split(',')
          .map(s => s.trim())
          .filter(s => s !== '')
          .map(s => castArrayElem(s, elemType));
        if (arr.length > 0) params[p.name] = arr;
      } else {
        params[p.name] = val;
      }
    }

    testResultData.value = await testSql({
      sqlTemplate: form.value.sqlTemplate,
      datasource: form.value.datasource,
      paramsSchema: form.value.paramsSchema || '[]',
      params,
    });
    testStatus.value = testResultData.value.success ? 'success' : 'fail';
    if (!testResultData.value.success) {
      testErrorMsg.value = testResultData.value.error || '未知错误';
    }
  } catch (e: any) {
    testStatus.value = 'fail';
    testErrorMsg.value = e.message || '测试失败';
  }
}

// 测试参数输入 (表单内的参数填写区)
const formTestParams = ref<Record<string, any>>({});
const formTestParamSchema = computed<ParamSchemaItem[]>(() => {
  return parseParamsSchema(form.value.paramsSchema);
});

/** 数组类型参数的输入提示 */
function arrayPlaceholder(type: string): string {
  const elem = type.endsWith('[]') ? type.slice(0, -2) : type;
  if (elem === 'int') return '逗号分隔, 如 1,2,3';
  if (elem === 'date') return '逗号分隔, 如 2024-01-01,2024-02-01';
  return '逗号分隔, 如 a,b,c';
}

/** 将数组元素的字符串原值转成对应类型 (int 转数字, 其余保持字符串) */
function castArrayElem(raw: string, elemType: string): any {
  if (elemType === 'int') {
    const n = Number(raw);
    return Number.isFinite(n) ? n : raw;
  }
  return raw;
}

// 监听 params_schema 变化, 初始化参数表单
watch(() => form.value.paramsSchema, (newVal) => {
  const schema = parseParamsSchema(newVal);
  const params: Record<string, any> = {};
  schema.forEach(p => {
    const prev = formTestParams.value[p.name];
    if (multi.has(p.name)) {
      // 多值: 保证为数组
      if (Array.isArray(prev)) params[p.name] = prev;
      else if (prev !== undefined && prev !== null && prev !== '') params[p.name] = [prev];
      else params[p.name] = [];
    } else {
      // 单值: 若旧值是数组则取首项, 否则保留原值
      params[p.name] = Array.isArray(prev) ? (prev[0] ?? '') : (prev ?? '');
    }
  });
  formTestParams.value = params;
}, { immediate: true });

// 展开/收起测试结果详情
const showTestDetail = ref(false);

// ==================== 样式 ====================
const S = {
  page: { padding: '20px', height: '100%', overflow: 'auto' } as any,
  header: { display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' as const } as any,
  title: { margin: 0, fontSize: '1.2rem', fontWeight: 700 } as any,
  sqlEditor: { fontFamily: 'monospace', fontSize: '13px' } as any,
  paramRow: { display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '8px' } as any,
  testResultInfo: { marginBottom: '12px', color: '#475569', fontSize: '0.85rem' } as any,
  testError: { padding: '12px', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: '6px', color: '#dc2626', fontSize: '0.85rem' } as any,
  testArea: { marginTop: '8px', padding: '12px', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '6px' } as any,
};
</script>

<template>
  <div :style="S.page">
    <!-- 顶部栏 -->
    <div :style="S.header">
      <h2 :style="S.title">SQL 注册表</h2>
      <el-input v-model="keyword" placeholder="搜索 sql_id / 名称" style="width: 200px" clearable size="small" />
      <el-select v-model="datasourceFilter" placeholder="全部数据源" style="width: 140px" size="small" clearable>
        <el-option label="全部" value="" />
        <el-option label="MySQL" value="mysql" />
        <el-option label="GaussDB" value="gauss" />
        <el-option label="ClickHouse" value="clickhouse" />
      </el-select>
      <el-input v-model="createdByFilter" placeholder="创建人" style="width: 140px" size="small" clearable />
      <el-button type="primary" size="small" @click="openCreate">＋ 新增 SQL</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="filteredItems" v-loading="loading" stripe border size="small" style="width: 100%">
      <el-table-column prop="sqlId" label="sql_id" width="220" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column prop="datasource" label="数据源" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.datasource === 'clickhouse' ? 'warning' : row.datasource === 'gauss' ? 'success' : 'info'">
            {{ row.datasource }}
          </el-tag>
        </template>
      </el-table-column>
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
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增 SQL' : '编辑 SQL'" width="760px" destroy-on-close>
      <el-form v-loading="formLoading" label-width="100px" size="small">
        <el-form-item label="sql_id" required>
          <el-input v-model="form.sqlId" :disabled="formMode === 'edit'" placeholder="snake_case, 如 trace_stats_by_user" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="中文名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用途说明" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="form.datasource" style="width: 200px">
            <el-option label="MySQL" value="mysql" />
            <el-option label="GaussDB" value="gauss" />
            <el-option label="ClickHouse" value="clickhouse" />
          </el-select>
        </el-form-item>
        <el-form-item label="SQL 模板" required>
          <el-input v-model="form.sqlTemplate" type="textarea" :rows="8" :style="S.sqlEditor"
            placeholder="SELECT ... FROM ... WHERE col = :param ..." />
        </el-form-item>
        <el-form-item label="参数定义">
          <div style="width: 100%">
            <div v-for="(p, idx) in paramRows" :key="idx" :style="S.paramRow">
              <el-input v-model="p.name" placeholder="参数名" style="width: 120px" @input="onParamRowChange" />
              <el-select v-model="p.type" style="width: 110px" @change="onParamRowChange">
                <el-option label="string" value="string" />
                <el-option label="int" value="int" />
                <el-option label="date" value="date" />
                <el-option label="boolean" value="boolean" />
                <el-option label="int[]" value="int[]" />
                <el-option label="string[]" value="string[]" />
                <el-option label="date[]" value="date[]" />
              </el-select>
              <el-input v-model="p.description" placeholder="说明" style="flex: 1" @input="onParamRowChange" />
              <el-button type="danger" size="small" circle @click="removeParamRow(idx)">×</el-button>
            </div>
            <el-button size="small" @click="addParamRow">＋ 添加参数</el-button>
          </div>
        </el-form-item>
        <el-form-item label="启用" v-if="formMode === 'edit'">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <!-- 测试参数填写 (放在测试连接上方, 更醒目) -->
        <el-form-item v-if="formTestParamSchema.length > 0" label="测试参数">
          <div style="width: 100%; padding: 10px 12px; background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 6px">
            <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px">
              <div v-for="p in formTestParamSchema" :key="p.name" style="display: flex; align-items: center; gap: 6px">
                <span style="font-size: 0.82rem; color: #0369a1; min-width: 70px; font-weight: 500">{{ p.name }}{{ p.required ? '*' : '' }}</span>
                <el-date-picker v-if="p.type === 'date'" v-model="formTestParams[p.name]" type="date"
                  value-format="YYYY-MM-DD" placeholder="选择日期" size="small" style="flex: 1" />
                <el-input-number v-else-if="p.type === 'int'" v-model="formTestParams[p.name]" :controls="false"
                  placeholder="整数" size="small" style="flex: 1" />
                <el-input v-else-if="p.type.endsWith('[]')" v-model="formTestParams[p.name]"
                  :placeholder="arrayPlaceholder(p.type)" size="small" style="flex: 1" />
                <el-input v-else v-model="formTestParams[p.name]" :placeholder="p.description || p.name"
                  size="small" style="flex: 1" />
              </div>
            </div>
          </div>
        </el-form-item>

        <!-- 测试连接 -->
        <el-form-item label="测试连接">
          <div style="width: 100%">
            <div style="display: flex; align-items: center; gap: 12px">
              <el-button type="primary" size="small" :loading="testStatus === 'loading'" @click="runFormTest">
                测试连接
              </el-button>
              <!-- 状态指示 -->
              <span v-if="testStatus === 'success'" style="color: #16a34a; font-weight: 600; font-size: 0.9rem">
                ✅ 测试通过
                <span v-if="testResultData" style="color: #64748b; font-weight: 400; font-size: 0.8rem">
                  ({{ testResultData.totalRows }} 行, {{ testResultData.elapsedMs }} ms)
                </span>
                <el-button type="text" size="small" @click="showTestDetail = !showTestDetail" style="margin-left: 4px">
                  {{ showTestDetail ? '收起结果' : '查看结果' }}
                </el-button>
              </span>
              <span v-if="testStatus === 'fail'" style="color: #dc2626; font-weight: 600; font-size: 0.9rem">
                ❌ 测试失败
                <el-button type="text" size="small" @click="showTestDetail = !showTestDetail" style="margin-left: 4px">
                  {{ showTestDetail ? '收起' : '查看详情' }}
                </el-button>
              </span>
            </div>
            <!-- 失败详情 -->
            <div v-if="testStatus === 'fail' && showTestDetail" :style="S.testError" style="margin-top: 8px">
              {{ testErrorMsg }}
            </div>
            <!-- 成功结果预览 -->
            <div v-if="testStatus === 'success' && testResultData && testResultData.rows.length > 0 && showTestDetail" style="margin-top: 8px">
              <div style="font-size: 0.8rem; color: #64748b; margin-bottom: 4px">
                查询结果 (共 {{ testResultData.totalRows }} 行，默认预览前 10 行)
              </div>
              <el-table :data="testResultData.rows" stripe border size="small" max-height="300" style="width: 100%">
                <el-table-column v-for="col in testResultData.columns" :key="col" :prop="col" :label="col"
                  show-overflow-tooltip />
              </el-table>
            </div>
          </div>
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
