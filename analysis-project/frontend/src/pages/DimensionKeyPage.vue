<script setup lang="ts">
/**
 * 维度键字典 (docs/history/skill-direct-exec-plan.md §9):
 * 内置 11 键只读 (提取走结构化规则); 用户新增键走词表命中提取 (alias contains → value),
 * 供 Skill 配置页 param_mapping 下拉选择。动态键仅当前问题提取, 不跨轮继承。
 * 口语值方案 §3.6 + 我X方案 §8.3: 白名单内置键开放词表编辑;
 * 词表行 type=regex 为正则条目 (alias=正则, value=用途标签), 供我X/角色提取动态配置。
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

/** 词表可维护的白名单内置键 (与后端 DimensionKeyConfigRepository.VOCAB_EDITABLE_BUILTIN_KEYS 同源,
 *  我X方案 §8.3: 8 键 — regex 型条目需页面可编辑, 副作用=同义词词表同步解锁) */
const VOCAB_EDITABLE_BUILTIN_KEYS = [
  'versionPlan', 'vestQuarter', 'deptName',
  'groupName', 'appName', 'productLine', 'perNo', 'perRole',
];

/** regex 型条目的 value 用途标签 (后端 PersonalDimensionResolver/DimensionStateManager 按标签分组编译) */
const REGEX_TAG_HINTS: Record<string, string> = {
  myDept: '命中 → 注入台账部门 (我部)',
  myGroup: '命中 → 注入台账统计组 (我的组)',
  myApp: '命中 → 注入台账应用 (我的应用)',
  myProductLine: '命中 → 注入台账产品线 (我的产品线)',
  myself: '命中 → 触发"我"自身代词解析 (perNo=当前用户)',
  extract: '第一捕获组 = 岗位角色值 (担任...) → 角色字典归一',
  block: '候选维度值命中此正则即拒收 (垃圾代词值屏蔽, 基线条目不可删)',
};

/** 白名单键的 value 分型提示 (后端保存期强校验, 此处仅引导) */
const VALUE_HINTS: Record<string, string> = {
  versionPlan: '版本标准值 (2026年10月份版本 / 2026年10月延后版) 或锚点 (THIS_V / YEAR_V / HALF_V:1|2 / LAST:3V / DELAYED)',
  vestQuarter: '季度标准值 (2026年4季度) 或锚点 THIS_Q',
  deptName: '部门标准名 (杭州开发一部 / 杭州服务支持部 / 杭州技术部 / 云计算实验室)',
};

/**
 * 内置键结构化规则可提取形态 (不走词表, 代码正则/锚点, 仅展示引导)。
 * 管理员据此了解每个键除词表外还能命中什么口语词 — 这些形态不出现在词表里,
 * 但 param_mapping 传参时同样生效 (与 DimensionStateManager/各 Resolver 同源维护)。
 */
const RULE_HINTS: Record<string, string> = {
  versionPlan: '标准值 2026年10月份版本、2026年10月延后版；口语 202610版本、十月版、4月、2026年10月；锚点 今年以来/全年/各月(YEAR_V)、上半年/下半年(HALF_V:1|2, 可带年份如 25年上半年→HALF_V:1@2025)、近N个版本/近N个月(LAST:{n}V, 按在建最后一版月序前推)、目前/当前/在建版本(THIS_V)、延后版(DELAYED)',
  vestQuarter: '标准值 2026年4季度；口语 Q1、一季度；锚点 当季/在建季度(THIS_Q)',
  deptName: '标准枚举 杭州开发一部~五部、杭州服务支持部、杭州技术部、云计算实验室；口语缩写 杭一/杭二部/杭开一 自动归一',
  timeRange: '本周/上周/今天/近N天/交付周到投产 等 (词表 + 时间锚点, 口径见时间维度方案)',
  groupName: '组名结构化识别 (XX组), 指标词表辅助修剪虚词',
  appName: '应用编号 F-xxx',
  productLine: '产品线词表识别',
  itemNo: '需求项编号 I20260208-0005',
  subItemNo: '子需求项编号',
  perName: '人员名识别',
  perNo: '裸"我"代词 + 我担任/我现任/我所担任 (正则 type=regex, value=myself)',
  perRole: '担任([一-龥]) 提取 (正则 type=regex, value=extract) + 角色字典扫描 (人锚共存)',
};

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

/** 词表是否可编辑: 非内置键, 或白名单内置键 (口语值方案 §3.6) */
const canEditAliases = computed(() =>
  !editing.value?.builtin || VOCAB_EDITABLE_BUILTIN_KEYS.includes(editing.value.key));

const valueHint = computed(() =>
  editing.value && VOCAB_EDITABLE_BUILTIN_KEYS.includes(editing.value.key)
    ? VALUE_HINTS[editing.value.key]
    : '');

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
  aliasRows.value = row.aliases.map(a => ({ alias: a.alias, value: a.value, type: a.type || null }));
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
        .map(a => ({
          alias: a.alias.trim(),
          value: a.value.trim(),
          type: a.type === 'regex' ? 'regex' : null,
        })),
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
      <el-table-column label="词表 (alias → 标准值) / 规则形态" min-width="420">
        <template #default="{ row }">
          <div>{{ row.aliases.map((a: DimensionKeyAlias) =>
            a.type === 'regex' ? `${a.alias}⇒${a.value}[正则]` : `${a.alias}→${a.value}`).join('、')
            || (row.builtin
              ? (VOCAB_EDITABLE_BUILTIN_KEYS.includes(row.key) ? '结构化规则 + 词表 (可维护, 当前未配置)' : '结构化规则提取 (不走词表)')
              : '-') }}</div>
          <div v-if="row.builtin && RULE_HINTS[row.key]" class="rule-hint">规则: {{ RULE_HINTS[row.key] }}</div>
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

    <el-dialog v-model="dialogVisible" :title="editing ? `维度键: ${editing.key}` : '新增维度键'" width="760px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-form-item label="维度键">
          <el-input v-model="form.key" :disabled="!!editing?.builtin" placeholder="字母开头的 camelCase, 如 qGate (内置键不可改)" maxlength="64" />
        </el-form-item>
        <el-form-item label="展示名"><el-input v-model="form.label" placeholder="如 卡口" maxlength="128" /></el-form-item>
        <el-form-item label="词表">
          <div class="alias-editor">
            <div v-if="canEditAliases">
              <div v-for="(row, idx) in aliasRows" :key="idx" class="alias-row">
                <el-select v-model="row.type" size="small" style="width: 96px" placeholder="同义词">
                  <el-option label="同义词" :value="null" />
                  <el-option label="正则" value="regex" />
                </el-select>
                <el-input v-model="row.alias"
                  :placeholder="row.type === 'regex' ? '正则表达式, 如 我部|我们部门' : '问题中的词, 如 Q1卡口 / 杭一 / 今年以来'"
                  maxlength="128" style="width: 200px" />
                <span class="arrow">{{ row.type === 'regex' ? '⇒' : '→' }}</span>
                <el-input v-model="row.value"
                  :placeholder="row.type === 'regex'
                    ? '用途标签: myDept / myGroup / myApp / myProductLine / myself / extract'
                    : (valueHint ? `标准值, ${valueHint}` : '标准值, 如 Q1 或正则捕获模板 $1')"
                  maxlength="128" style="width: 280px" />
                <span v-if="row.type === 'regex' && REGEX_TAG_HINTS[row.value]" class="tag-hint">{{ REGEX_TAG_HINTS[row.value] }}</span>
                <el-button size="small" text type="danger" @click="aliasRows.splice(idx, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="aliasRows.push({ alias: '', value: '', type: null })">+ 添加词表行</el-button>
            </div>
            <template v-else>
              <div v-for="(row, idx) in aliasRows" :key="idx" class="alias-row">
                <el-input :model-value="`${row.alias} → ${row.value}`" disabled style="width: 380px" />
              </div>
              <div class="hint">内置键提取走结构化规则, 词表只读</div>
            </template>
            <div class="hint">
              避免配置裸短词 (如裸 Q1), 防止在其他语境误命中; 触发词条件行为后续版本规划
              <template v-if="valueHint">; 锚点值 (THIS_V/YEAR_V 等) 执行期按火车日历动态展开, 具体版本以日历为准</template>
            </div>
            <div class="hint">
              正则捕获: 标准值含 $1~$9 时, 触发词按正则匹配并用捕获组替换产出维度值。
              例: 触发词 流转次数\s*>\s*(\d+), 标准值 $1 → 「流转次数>3」提取出 3
            </div>
            <div class="hint">
              正则条目 (type=正则): alias 必须是合法正则串 (保存时校验), value 是用途标签而非参数值 —
              myDept/myGroup/myApp/myProductLine 控制我部/我的组等口语词命中, myself 控制自身代词变体,
              extract 第一捕获组作为岗位角色值。命中即叠加硬编码规则 (§8.2), 删除条目重启会自动补回基线。
            </div>
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
.alias-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.alias-row .arrow { color: #64748b; }
.tag-hint { color: #94a3b8; font-size: 0.72rem; }
.hint { color: #94a3b8; font-size: 0.75rem; margin-top: 4px; }
.rule-hint { color: #94a3b8; font-size: 0.72rem; margin-top: 2px; line-height: 1.5; white-space: normal; }
</style>
