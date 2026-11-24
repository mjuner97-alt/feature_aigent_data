<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { listPptTitleScanRules, pptManualRecognitionOptions, pptRecognitionModes as modes,
  type PptRecognitionScope, type PptTitleScanRule } from '../../api/pptAnomaly';

const props = defineProps<{ originalTitle?: string; initialRules?: PptTitleScanRule[]; editable: boolean }>();
const scope = defineModel<PptRecognitionScope>({ required: true });

type ScanRule = PptTitleScanRule;
type ScanRuleDraft = Omit<ScanRule, 'keywords'> & { keywords: string[] };
const maxKeywordLength = 8192;
const recognitionMethod = ref<'default' | 'rules'>('default');
const scanRules = ref<ScanRuleDraft[]>([]);
const rulesLoading = ref(false);
const rulesReady = ref(false);
const rulesLoadError = ref('');
const rulesValidationError = ref('');
const originalRules = ref('[]');

function serializeScanRules(): ScanRule[] {
  return scanRules.value.map(rule => ({
    ...rule,
    keywords: rule.keywords.map(keyword => keyword.trim()).filter(Boolean).join('\n'),
  }));
}

function addScanRule() {
  const names = new Set(scanRules.value.map(rule => rule.name));
  let number = 1;
  const ruleName = (value: number) => `规则 ${String(value).padStart(2, '0')}`;
  while (names.has(ruleName(number))) number += 1;
  scanRules.value.push({ name: ruleName(number), keywords: [''], mode: 'FULL' });
}

async function loadScanRules() {
  if (!props.originalTitle || rulesLoading.value || rulesReady.value) return;
  rulesLoading.value = true;
  rulesLoadError.value = '';
  try {
    initializeRules(await listPptTitleScanRules(props.originalTitle));
  } catch (error) {
    rulesLoadError.value = String(error);
  } finally {
    rulesLoading.value = false;
  }
}

function scanRuleError(rules: ScanRule[]) {
  const conditions = new Set<string>();
  for (const [index, rule] of rules.entries()) {
    const prefix = `第 ${index + 1} 条规则`;
    const terms = rule.keywords.split('\n').map(term => term.replace(/[\s\p{Zs}]/gu, '').toLowerCase()).filter(Boolean);
    const condition = [...new Set(terms)].sort().join('\n');
    if (!condition) return `${prefix}请至少填写一个关键词`;
    if (rule.keywords.length > maxKeywordLength) return `${prefix}的关键词总长度不能超过 ${maxKeywordLength} 个字符`;
    if (conditions.has(condition)) return `${prefix}的关键词条件已存在`;
    if (rule.mode === 'UPPER_TABLE_ONLY' && rule.lastRow != null && rule.lastRow < (rule.firstRow ?? 1)) {
      return `${prefix}的结束行不能小于起始行`;
    }
    conditions.add(condition);
  }
  return '';
}

function initializeRules(result: ScanRule[]) {
  scanRules.value = result.map(rule => ({ ...rule, keywords: rule.keywords.split(/\r\n?|\n/) }));
  originalRules.value = JSON.stringify(serializeScanRules());
  recognitionMethod.value = result.length ? 'rules' : 'default';
  rulesReady.value = true;
}

onMounted(() => {
  if (props.initialRules || !props.originalTitle) initializeRules(props.initialRules ?? []);
  else void loadScanRules();
});

function prepareSave() {
  if (rulesLoading.value) return null;
  const rules = rulesReady.value ? (recognitionMethod.value === 'rules' ? serializeScanRules() : []) : undefined;
  if (recognitionMethod.value === 'rules' && rules?.length === 0) {
    rulesValidationError.value = '请至少添加一条关键词规则';
    return null;
  }
  const changedRules = rules && JSON.stringify(rules) !== originalRules.value ? rules : undefined;
  if (changedRules) {
    rulesValidationError.value = scanRuleError(changedRules);
    if (rulesValidationError.value) return null;
  }
  return { rules, changedRules };
}

defineExpose({ loading: rulesLoading, prepareSave });
</script>

<template>
  <el-form-item label="识别方式">
    <el-radio-group v-if="rulesReady" v-model="recognitionMethod" aria-label="识别方式" @change="rulesValidationError = ''">
      <el-radio-button value="default">默认识别</el-radio-button>
      <el-radio-button value="rules">规则识别</el-radio-button>
    </el-radio-group>
    <span v-else-if="rulesLoading" role="status">正在加载识别设置…</span>
    <template v-else-if="rulesLoadError">
      <el-alert :title="rulesLoadError" type="error" :closable="false" />
      <el-button link type="primary" @click="loadScanRules">重新加载识别设置</el-button>
    </template>
  </el-form-item>
  <template v-if="rulesReady && recognitionMethod === 'default'">
    <el-form-item label="默认识别范围" required>
      <el-select v-model="scope.scope">
        <el-option v-for="[value, label] in pptManualRecognitionOptions" :key="value" :label="label" :value="value" />
        <template #label>{{ modes[scope.scope] }}</template>
      </el-select>
      <p class="scan-scope-hint">该标题下的所有页面都使用这个范围。</p>
      <el-alert v-if="originalRules !== '[]'" title="保存后将清除现有关键词规则。" type="warning" :closable="false" />
    </el-form-item>
    <template v-if="scope.scope === 'UPPER_TABLE_ONLY'">
      <el-form-item label="表格顶边分界线比例（默认 0.5）"><el-input-number v-model="scope.upperLimitRatio" :min="0.01" :max="1" :step="0.05" /></el-form-item>
      <el-form-item label="起始行（默认第 1 行）"><el-input-number v-model="scope.firstRow" :min="1" /></el-form-item>
      <el-form-item label="结束行（留空读到末行）"><el-input-number v-model="scope.lastRow" :min="1" /></el-form-item>
    </template>
  </template>
  <section v-else-if="rulesReady" class="scan-settings-body" aria-label="关键词规则" @input="rulesValidationError = ''">
    <p class="scan-settings-hint">所有已填写的关键词都出现时，才应用该规则。未命中时使用{{ modes[scope.scope] }}。命中多条规则时，在上传确认页选择识别范围。</p>
    <el-alert v-if="rulesValidationError" :title="rulesValidationError" type="error" :closable="false" />
    <fieldset v-for="(rule, index) in scanRules" :key="rule.name" class="scan-rule">
      <legend>规则 {{ index + 1 }}</legend>
      <el-form-item label="命中规则时的识别范围" required>
        <el-select v-model="rule.mode">
          <el-option v-for="[value, label] in pptManualRecognitionOptions" :key="value" :label="label" :value="value" />
          <template #label>{{ modes[rule.mode] }}</template>
        </el-select>
      </el-form-item>
      <el-form-item v-for="(_, keywordIndex) in rule.keywords" :key="keywordIndex" :label="`关键词 ${keywordIndex + 1}`">
        <div class="scan-keyword-input">
          <el-input v-model="rule.keywords[keywordIndex]" :maxlength="maxKeywordLength"
            :aria-label="`关键词 ${keywordIndex + 1}`" placeholder="输入关键词或短语" />
          <el-button v-if="editable && rule.keywords.length > 1" link type="danger"
            :aria-label="`删除关键词 ${keywordIndex + 1}`" @click="rule.keywords.splice(keywordIndex, 1); rulesValidationError = ''">删除</el-button>
        </div>
      </el-form-item>
      <el-button v-if="editable" link type="primary" :icon="Plus" @click="rule.keywords.push('')">添加关键词</el-button>
      <div v-if="rule.mode === 'UPPER_TABLE_ONLY'" class="scan-rule-limits">
        <el-form-item label="分界线比例"><el-input-number v-model="rule.upperLimitRatio" :min="0.01" :max="1" :step="0.05" placeholder="0.5" /></el-form-item>
        <el-form-item label="起始行"><el-input-number v-model="rule.firstRow" :min="1" :precision="0" placeholder="第 1 行" /></el-form-item>
        <el-form-item label="结束行"><el-input-number v-model="rule.lastRow" :min="1" :precision="0" placeholder="末行" /></el-form-item>
      </div>
      <el-button v-if="editable" type="danger" link @click="scanRules.splice(index, 1); rulesValidationError = ''">删除规则</el-button>
    </fieldset>
    <el-button class="scan-add-rule" :icon="Plus" :disabled="scanRules.length >= 50"
      v-if="editable" @click="addScanRule">新增例外规则</el-button>
  </section>
</template>

<style scoped>
.scan-settings-body {
  display: grid;
  gap: 12px;
  margin-bottom: 20px;
  padding: 12px 14px 14px;
  border: 1px solid #dce5f3;
  border-radius: 12px;
  background: #f8faff;
}
.scan-scope-hint { margin: 6px 0 0; color: #586b86; font-size: 12px; line-height: 1.6; }
.scan-settings-hint { margin: 0; color: #586b86; line-height: 1.6; }
.scan-rule { min-width: 0; margin: 0; padding: 10px 12px; border: 1px solid #dce5f3; border-radius: 9px; background: #fff; }
.scan-rule legend { padding-inline: 5px; font-weight: 600; color: #283e62; }
.scan-keyword-input { display: flex; align-items: center; gap: 10px; width: 100%; }
.scan-rule-limits { display: grid; gap: 0 12px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.scan-rule-limits :deep(.el-input-number) { width: 100%; }
.scan-add-rule { justify-self: start; border-style: dashed; color: #315db5; }
@media (max-width: 560px) {
  .scan-rule-limits { grid-template-columns: minmax(0, 1fr); }
}
</style>
