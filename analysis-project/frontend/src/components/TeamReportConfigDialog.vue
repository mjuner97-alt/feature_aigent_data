<script setup lang="ts">
/** 报告配置草稿弹层；确认后回写父表单，最终随任务保存一起提交。 */
import { ref, watch } from 'vue';
import type { TeamReportConfig } from '../types/skillFlow';
import TeamReportConfigEditor from './TeamReportConfigEditor.vue';
import { defaultReportMailBody, defaultReportName, reportTargetContactError } from '../utils/flowCreation';

const props = defineProps<{ open: boolean; modelValue: TeamReportConfig; immutable: boolean }>();
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'update:modelValue', value: TeamReportConfig): void;
}>();
const draft = ref<TeamReportConfig | null>(null);
const error = ref('');
watch(
  () => props.open,
  (open) => {
    if (open) {
      error.value = '';
      // 每次打开重新复制，避免弹层内取消的编辑污染父表单。
      const config: TeamReportConfig = JSON.parse(JSON.stringify(props.modelValue));
      if (!config.departments?.length)
        config.departments = config.departmentId ? [{ id: config.departmentId, name: config.departmentName }] : [];
      config.targets.forEach((target) => {
        target.departmentId ||= config.departmentId;
        target.departmentName ||= config.departmentName;
        if (!target.reportName?.trim()) target.reportName = defaultReportName(config, target);
        if (!target.notifyBodyTemplate?.trim()) target.notifyBodyTemplate = defaultReportMailBody(config, target);
      });
      draft.value = config;
    }
  },
  { immediate: true }
);
/** 校验草稿完整性后回写父表单；取消关闭时不提交草稿。 */
function save() {
  if (!draft.value) return;
  if (draft.value.departments?.some((d) => !draft.value!.targets.some((t) => t.departmentId === d.id))) {
    error.value = '请为每个部门至少添加一个团队';
    return;
  }
  if (draft.value.targets.some((target) => !target.reportName?.trim())) {
    error.value = '请填写报告名称';
    return;
  }
  for (const target of draft.value.reportType === 'DEPARTMENT' ? draft.value.targets : []) {
    const contactError = reportTargetContactError(target);
    if (contactError) {
      error.value = `${target.teamName || target.departmentName || draft.value.departmentName}：${contactError}`;
      return;
    }
  }
  emit('update:modelValue', JSON.parse(JSON.stringify(draft.value)));
  emit('update:open', false);
}
</script>

<template>
  <Teleport to="body">
    <section v-if="open" class="config-overlay" role="dialog" aria-modal="true" aria-label="团队报告配置">
      <header
        ><h3>团队报告配置</h3
        ><button type="button" aria-label="关闭团队报告配置" @click="emit('update:open', false)">关闭</button></header
      >
      <main class="config-body"
        ><div class="config-page"> <TeamReportConfigEditor v-if="draft" v-model="draft" :immutable="immutable" /> </div
      ></main>
      <div class="config-footer">
        <p v-if="error" class="name-error" role="alert">{{ error }}</p>
        <span>配置随「保存流程」一起提交。</span>
        <button type="button" @click="emit('update:open', false)">取消</button>
        <button type="button" class="primary" @click="save">保存并返回</button>
      </div>
    </section>
  </Teleport>
</template>

<style scoped>
.name-error {
  color: #dc2626;
  font-size: 13px;
  margin: 0;
}
.config-overlay {
  position: fixed;
  inset: 0;
  z-index: 2100;
  display: flex;
  flex-direction: column;
  background: #f5f7fb;
  color: #0f172a;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 28px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}
h3 {
  margin: 0;
  font-size: 20px;
}
.config-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 24px;
}
.config-page {
  max-width: 1400px;
  margin: 0 auto;
}
.config-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 28px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}
.config-footer span {
  margin-right: auto;
  color: #64748b;
  font-size: 13px;
}
button {
  padding: 9px 18px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  cursor: pointer;
}
button.primary {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}
</style>
