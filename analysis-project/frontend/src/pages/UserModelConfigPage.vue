<script setup lang="ts">
/**
 * 用户模型配置管理页面 (内部使用)
 *
 * 功能: 列表 / 新增 / 编辑 / 删除 + 表单内「测试连接」
 *
 * 列表 token 已脱敏; 编辑弹窗通过详情接口取回完整 token 预填。
 * 「测试连接」测的是弹窗里当前(可能未保存)的 请求地址/key/模型, 先测后存。
 */
import { ref, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listConfigs, getConfig, createConfig, updateConfig, deleteConfig, testConnection } from '../api/modelConfig';
import type { UserModelConfig } from '../types/modelConfig';

// ==================== 列表 ====================
const items = ref<UserModelConfig[]>([]);
const loading = ref(false);
const keyword = ref('');

const filteredItems = computed(() => {
  const kw = keyword.value.toLowerCase();
  if (!kw) return items.value;
  return items.value.filter(it =>
    it.userId.toLowerCase().includes(kw) ||
    (it.modelName || '').toLowerCase().includes(kw) ||
    (it.requestUrl || '').toLowerCase().includes(kw) ||
    (it.provider || '').toLowerCase().includes(kw)
  );
});

async function loadList() {
  loading.value = true;
  try {
    items.value = await listConfigs();
  } catch (e: any) {
    ElMessage.error(e.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

loadList();

// ==================== 新增/编辑弹窗 ====================
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formLoading = ref(false);
const form = ref<UserModelConfig>({
  userId: '', provider: 'openai', token: '', modelName: '', requestUrl: '',
});
const editUserId = ref('');

function openCreate() {
  formMode.value = 'create';
  editUserId.value = '';
  form.value = { userId: '', provider: 'openai', token: '', modelName: '', requestUrl: '' };
  resetTestState();
  formVisible.value = true;
}

async function openEdit(row: UserModelConfig) {
  formMode.value = 'edit';
  editUserId.value = row.userId;
  formLoading.value = true;
  resetTestState();
  formVisible.value = true;
  try {
    const detail = await getConfig(row.userId);
    form.value = {
      userId: detail.userId, provider: detail.provider || 'openai', token: detail.token || '',
      modelName: detail.modelName || '', requestUrl: detail.requestUrl || '',
    };
  } catch (e: any) {
    ElMessage.error(e.message || '加载详情失败');
  } finally {
    formLoading.value = false;
  }
}

async function saveForm() {
  if (!form.value.userId) {
    ElMessage.warning('请填写用户 ID');
    return;
  }
  if (!form.value.requestUrl || !form.value.token || !form.value.modelName) {
    ElMessage.warning('请填写请求地址 / 请求 key / 模型名称');
    return;
  }
  formLoading.value = true;
  try {
    if (formMode.value === 'create') {
      await createConfig(form.value);
      ElMessage.success('新增成功');
    } else {
      await updateConfig(editUserId.value, form.value);
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
async function handleDelete(row: UserModelConfig) {
  try {
    await ElMessageBox.confirm(`确定删除用户 "${row.userId}" 的模型配置?`, '删除确认', { type: 'warning' });
    await deleteConfig(row.userId);
    ElMessage.success('删除成功');
    loadList();
  } catch (e: any) {
    if (e !== 'cancel' && e?.toString() !== 'cancel') {
      ElMessageBox.alert(e.message || '删除失败', '操作失败', { type: 'error' });
    }
  }
}

// ==================== 表单内测试连接 ====================
const testStatus = ref<'idle' | 'loading' | 'success' | 'fail'>('idle');
const testMessage = ref('');
const testLatencyMs = ref(0);

function resetTestState() {
  testStatus.value = 'idle';
  testMessage.value = '';
  testLatencyMs.value = 0;
}

async function runTest() {
  if (!form.value.requestUrl || !form.value.token || !form.value.modelName) {
    ElMessage.warning('请先填写请求地址 / 请求 key / 模型名称');
    return;
  }
  testStatus.value = 'loading';
  testMessage.value = '';
  testLatencyMs.value = 0;
  try {
    const result = await testConnection({
      provider: form.value.provider,
      requestUrl: form.value.requestUrl,
      token: form.value.token,
      modelName: form.value.modelName,
    });
    testStatus.value = result.success ? 'success' : 'fail';
    testLatencyMs.value = result.latencyMs;
    testMessage.value = result.success
      ? (result.message || '连接成功')
      : (result.message || '连接失败');
  } catch (e: any) {
    testStatus.value = 'fail';
    testMessage.value = e.message || '测试失败';
  }
}

// ==================== 样式 ====================
const S = {
  page: { padding: '20px', height: '100%', overflow: 'auto' } as any,
  header: { display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' as const } as any,
  title: { margin: 0, fontSize: '1.2rem', fontWeight: 700 } as any,
  testHint: { fontSize: '0.75rem', color: '#94a3b8', marginTop: '4px' } as any,
  testError: { padding: '10px', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: '6px', color: '#dc2626', fontSize: '0.85rem', marginTop: '8px', wordBreak: 'break-all' as const } as any,
  testOk: { padding: '10px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '6px', color: '#16a34a', fontSize: '0.85rem', marginTop: '8px', wordBreak: 'break-all' as const } as any,
};
</script>

<template>
  <div :style="S.page">
    <!-- 顶部栏 -->
    <div :style="S.header">
      <h2 :style="S.title">用户模型配置 (内部)</h2>
      <el-input v-model="keyword" placeholder="搜索 user_id / 模型 / 地址" style="width: 240px" clearable size="small" />
      <el-button type="primary" size="small" @click="openCreate">＋ 新增配置</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="filteredItems" v-loading="loading" stripe border size="small" style="width: 100%">
      <el-table-column prop="userId" label="用户 ID" width="140" />
      <el-table-column prop="provider" label="Provider" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.provider === 'glm' ? 'warning' : 'success'">
            {{ row.provider || 'openai' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型" width="160" show-overflow-tooltip />
      <el-table-column prop="requestUrl" label="请求地址" show-overflow-tooltip />
      <el-table-column prop="token" label="请求 Key" width="150">
        <template #default="{ row }">
          <code style="font-size: 0.78rem">{{ row.token }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="160" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增模型配置' : '编辑模型配置'" width="640px" destroy-on-close>
      <el-form v-loading="formLoading" label-width="100px" size="small">
        <el-form-item label="用户 ID" required>
          <el-input v-model="form.userId" :disabled="formMode === 'edit'" placeholder="统一认证号 / user_id" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="form.provider" style="width: 200px">
            <el-option label="OpenAI 兼容" value="openai" />
            <el-option label="GLM" value="glm" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求地址" required>
          <el-input v-model="form.requestUrl" placeholder="如 https://api.deepseek.com/v1 或 https://open.bigmodel.cn/api/paas/v4/" />
        </el-form-item>
        <el-form-item label="请求 Key" required>
          <el-input v-model="form.token" placeholder="API Key (Bearer token)" show-password />
        </el-form-item>
        <el-form-item label="模型名称" required>
          <el-input v-model="form.modelName" placeholder="如 deepseek-chat / glm-5.2" />
        </el-form-item>

        <!-- 测试连接 -->
        <el-form-item label="测试连接">
          <div style="width: 100%">
            <el-button type="primary" size="small" :loading="testStatus === 'loading'" @click="runTest">
              测试连接
            </el-button>
            <span v-if="testStatus === 'success'" style="color: #16a34a; font-weight: 600; font-size: 0.9rem; margin-left: 12px">
              ✅ 测试通过 <span style="color:#64748b; font-weight:400; font-size:0.8rem">({{ testLatencyMs }} ms)</span>
            </span>
            <span v-else-if="testStatus === 'fail'" style="color: #dc2626; font-weight: 600; font-size: 0.9rem; margin-left: 12px">
              ❌ 测试失败
            </span>
            <div v-if="testStatus === 'success'" :style="S.testOk">{{ testMessage }}</div>
            <div v-if="testStatus === 'fail'" :style="S.testError">{{ testMessage }}</div>
            <div :style="S.testHint">使用当前表单填写的地址 / key / 模型发起一次最小请求, 验证是否正确。</div>
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
