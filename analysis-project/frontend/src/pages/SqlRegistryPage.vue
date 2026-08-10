<script setup lang="ts">
/**
 * SQL 注册表管理页面
 *
 * 功能: 列表 / 新增 / 编辑 / 删除 + 表单内 SQL 测试
 *
 * params_schema 与测试参数均用原始 JSON 文本框编辑, 前端解析后发送:
 *   - params_schema: JSON 数组字符串 (后端按 String 接收, 与 SqlRegistryEntry 字段一致)
 *   - 测试参数 params: 前端 JSON.parse 为对象后发送, 后端按 Map<String,Object> 接收
 *     (与 SqlRegistryExecTool#sqlRegistryExec 的 params 入参一致, 后端按 params_schema
 *      校验白名单 + 必填, 多余参数拒执行防注入)
 *
 * SQL 测试只允许 SELECT, 禁止 DDL/DML.
 */
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listEntries, getEntry, createEntry, updateEntry, deleteEntry, testSql, setEntryEnabled } from '../api/sqlRegistry';
import type { SqlRegistryListItem, SqlRegistryInput, SqlTestResult, ParamSchemaItem } from '../types/sqlRegistry';

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

// 测试参数: 原始 JSON 文本, 前端解析为对象后发送 (后端 params: Map<String,Object>, 与 SqlRegistryExecTool 一致)
const formTestParamsJson = ref('{}');

function openCreate() {
  formMode.value = 'create';
  editId.value = 0;
  form.value = { sqlId: '', name: '', description: '', datasource: 'gauss', sqlTemplate: '', paramsSchema: '[]', enabled: 1 };
  formTestParamsJson.value = '{}';
  testStatus.value = 'idle';
  testResultData.value = null;
  showTestDetail.value = false;
  showSchemaExample.value = false;
  formVisible.value = true;
}

async function openEdit(row: SqlRegistryListItem) {
  formMode.value = 'edit';
  editId.value = row.id;
  formLoading.value = true;
  testStatus.value = 'idle';
  testResultData.value = null;
  showTestDetail.value = false;
  showSchemaExample.value = false;
  formVisible.value = true;
  try {
    const detail = await getEntry(row.id);
    form.value = {
      sqlId: detail.sqlId, name: detail.name, description: detail.description || '',
      datasource: detail.datasource, sqlTemplate: detail.sqlTemplate,
      paramsSchema: detail.paramsSchema || '[]', enabled: detail.enabled,
    };
    formTestParamsJson.value = '{}';
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
  if (!form.value.sqlId || !form.value.name || !form.value.datasource || !form.value.sqlTemplate) {
    ElMessage.warning('请填写 sql_id / name / datasource / sql_template');
    return;
  }
  // params_schema 现为原始 JSON 文本框, 保存前校验合法 JSON 数组
  const schema = tryParseJson(form.value.paramsSchema);
  if (schema === null) {
    ElMessage.error('参数定义不是合法 JSON');
    return;
  }
  if (!Array.isArray(schema)) {
    ElMessage.error('参数定义必须是 JSON 数组');
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
    // 只传 enabled, 复用后端选择性更新; 不重发 sql_template, 否则空串会被当作"模板改为空"触发校验
    await setEntryEnabled(row.id, newEnabled);
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

/**
 * 从参数定义 (params_schema) 生成测试参数模板, 按 type 给默认值, 减少手写.
 * 生成的 JSON 对象前端原样发送, 后端按 Map<String,Object> 接收.
 */
function fillTestParamsSkeleton() {
  const schema = tryParseJson(form.value.paramsSchema);
  if (schema === null) {
    ElMessage.error('参数定义不是合法 JSON, 无法生成模板');
    return;
  }
  if (!Array.isArray(schema)) {
    ElMessage.error('参数定义必须是 JSON 数组');
    return;
  }
  const skeleton: Record<string, any> = {};
  for (const p of schema as ParamSchemaItem[]) {
    if (!p || !p.name) continue;
    switch (p.type) {
      case 'int': skeleton[p.name] = 0; break;
      case 'boolean': skeleton[p.name] = false; break;
      case 'array':
      case 'int[]':
      case 'string[]':
      case 'date[]': skeleton[p.name] = []; break;
      default: skeleton[p.name] = ''; // string / date / 未知类型当字符串
    }
  }
  formTestParamsJson.value = JSON.stringify(skeleton, null, 2);
}

/** 格式化 JSON 文本框内容 (pretty-print), 非法 JSON 时提示. */
function formatJson(field: 'schema' | 'params') {
  const text = field === 'schema' ? form.value.paramsSchema : formTestParamsJson.value;
  const parsed = tryParseJson(text);
  if (parsed === null) {
    ElMessage.error('不是合法 JSON, 无法格式化');
    return;
  }
  const formatted = JSON.stringify(parsed, null, 2);
  if (field === 'schema') form.value.paramsSchema = formatted;
  else formTestParamsJson.value = formatted;
}

async function runFormTest() {
  if (!form.value.sqlTemplate || !form.value.datasource) {
    ElMessage.warning('请先填写 SQL 模板和数据源');
    return;
  }

  // 测试参数: 前端解析为对象后发送, 与 SqlRegistryExecTool 的 params: Map<String,Object> 一致
  const params = tryParseJson(formTestParamsJson.value);
  if (params === null) {
    ElMessage.error('测试参数不是合法 JSON');
    return;
  }
  if (typeof params !== 'object' || Array.isArray(params)) {
    ElMessage.error('测试参数必须是 JSON 对象, 如 {"userId":"alice"}');
    return;
  }

  testStatus.value = 'loading';
  testErrorMsg.value = '';
  testResultData.value = null;

  try {
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

// 展开/收起: 测试结果详情 / 参数定义示例
const showTestDetail = ref(false);
const showSchemaExample = ref(false);

// ==================== 样式 ====================
const S = {
  page: { padding: '20px', height: '100%', overflow: 'auto' } as any,
  header: { display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' as const } as any,
  title: { margin: 0, fontSize: '1.2rem', fontWeight: 700 } as any,
  jsonEditor: { fontFamily: 'monospace', fontSize: '13px' } as any,
  exampleCode: { fontSize: '0.72rem', color: '#475569', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '4px', padding: '8px', margin: '6px 0 0', whiteSpace: 'pre', overflowX: 'auto', fontFamily: 'monospace' } as any,
  testError: { padding: '12px', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: '6px', color: '#dc2626', fontSize: '0.85rem' } as any,
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
          <el-input v-model="form.sqlTemplate" type="textarea" :rows="8" :style="S.jsonEditor"
            placeholder="SELECT ... FROM ... WHERE dept = :dept  and version in (:version)..." />
        </el-form-item>
        <el-form-item label="参数定义">
          <div style="width: 100%">
            <el-input v-model="form.paramsSchema" type="textarea" :rows="6" :style="S.jsonEditor"
              placeholder='JSON 数组' />
            <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px">
              <el-button size="small" text @click="formatJson('schema')">格式化</el-button>
              <el-button size="small" text @click="showSchemaExample = !showSchemaExample">
                {{ showSchemaExample ? '收起示例' : '查看示例' }}
              </el-button>
            </div>
            <pre v-if="showSchemaExample" :style="S.exampleCode">
参数示例:
            [
                单值: {"name":"版本","type":"string","required":true,"description":"版本计划,如 "2026年8月份版本""},
                多值: {"name":"版本","type":"array","required":true,"description":"版本计划列表,如 ["2026年8月份版本","2026年9月份版本"]"}
            ]</pre>
          </div>
        </el-form-item>
        <el-form-item label="启用" v-if="formMode === 'edit'">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <!-- 测试参数 (原始 JSON 文本框, 前端解析为对象后发送, 后端按 Map 接收) -->
        <el-form-item label="测试参数">
          <div style="width: 100%">
            <el-input v-model="formTestParamsJson" type="textarea" :rows="6" :style="S.jsonEditor"
              placeholder='JSON 对象, 如 {"userId":"alice","limit":100}' />
            <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 4px">
              <span style="font-size: 0.75rem; color: #94a3b8">
                JSON 对象, 参数名必须在参数定义内 (多余参数后端拒执行防注入)
              </span>
              <div style="display: flex; gap: 8px">
                <el-button size="small" text @click="formatJson('params')">格式化</el-button>
                <el-button size="small" text @click="fillTestParamsSkeleton">从参数定义生成模板</el-button>
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
