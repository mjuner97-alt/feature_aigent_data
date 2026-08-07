<script setup lang="ts">
/**
 * SkillJob 创建/编辑表单 Drawer
 *
 * editId == null -> 创建模式;editId != null -> 编辑模式
 * outputPath 前端传入，默认 /data/skill-files/{userId}/
 * questionTemplate 只需填核心问题，{skill_name} 和 MD写入指令由后端自动拼接
 */
import { ref, watch, computed } from 'vue';
import { getJob, createJob, updateJob } from '../api/skillJob';
import { listSkills, currentUserId } from '../api/skill';
import type { SkillJobInput, SkillJobUpdateInput } from '../types/skillJob';
import type { SkillListItem } from '../types/skill';

const props = defineProps<{ open: boolean; editId: number | null }>();
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void;
  (e: 'saved'): void;
}>();

const isEdit = computed(() => props.editId != null);

const form = ref<SkillJobInput>({ name: '', skillId: 0, questionTemplate: '' });
const outputPath = ref('');
const formLoading = ref(false);
const saving = ref(false);
const formError = ref('');
const skills = ref<SkillListItem[]>([]);

/** 默认输出路径：/data/skill-files/{实际userId}/ */
const defaultOutputPath = computed(() => `/data/skill-files/${currentUserId()}/`);

/** 加载"我使用的" Skill 列表供选择 */
async function loadSkills() {
  try {
    skills.value = await listSkills({ view: 'used', limit: 200 });
  } catch {
    skills.value = [];
  }
}

function resetForm() {
  form.value = { name: '', skillId: 0, questionTemplate: '' };
  outputPath.value = '';
  formError.value = '';
}

watch(() => props.open, (open) => {
  if (!open) return;
  resetForm();
  loadSkills();
  if (props.editId != null) {
    loadForEdit(props.editId);
  }
});

async function loadForEdit(id: number) {
  formLoading.value = true;
  try {
    const job = await getJob(id);
    form.value = {
      name: job.name ?? '',
      skillId: job.skillId ?? 0,
      questionTemplate: job.questionTemplate ?? '',
      enabled: job.enabled,
    };
    outputPath.value = job.outputPath ?? '';
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    formLoading.value = false;
  }
}

async function submit() {
  formError.value = '';
  if (!form.value.name.trim()) { formError.value = '请填写任务名称'; return; }
  if (!form.value.skillId) { formError.value = '请选择关联 Skill'; return; }
  if (!form.value.questionTemplate.trim()) { formError.value = '请填写提问内容'; return; }

  saving.value = true;
  try {
    if (props.editId != null) {
      // 编辑：skillId / createdBy 不可变，不提交 skillId
      const payload: SkillJobUpdateInput = {
        name: form.value.name,
        questionTemplate: form.value.questionTemplate,
        outputPath: outputPath.value || defaultOutputPath.value,
        enabled: form.value.enabled,
      };
      await updateJob(props.editId, payload);
    } else {
      const payload: SkillJobInput = {
        ...form.value,
        outputPath: outputPath.value || defaultOutputPath.value,
      };
      await createJob(payload);
    }
    setTimeout(() => { close(); emit('saved'); }, 600);
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function close() { emit('update:open', false); }
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" @click.self="close">
        <div class="drawer">
          <div class="drawer-header">
            <h3>{{ isEdit ? '编辑定时任务' : '创建定时任务' }}</h3>
            <button class="drawer-close" @click="close" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
            <div v-if="formLoading" class="loading">加载中…</div>
            <div v-else>
              <form class="form" @submit.prevent="submit">
                <label class="field">
                  <span class="label">任务名称 *</span>
                  <input v-model="form.name" placeholder="如 daily_quality_report" />
                </label>
                <label class="field">
                  <span class="label">关联 Skill *</span>
                  <select v-model.number="form.skillId" :disabled="isEdit">
                    <option :value="0" disabled>请选择 Skill</option>
                    <option v-for="s in skills" :key="s.id" :value="s.id">{{ s.name }}</option>
                  </select>
                  <span v-if="isEdit" class="tip">Skill 不可修改，如需更换请删除后重建</span>
                </label>
                <label class="field">
                  <span class="label">提问内容 *</span>
                  <textarea v-model="form.questionTemplate" rows="4" placeholder="分析今日数据质量" />
                  <span class="tip">只需填写核心问题，系统会自动拼接"调用{Skill名称}"前缀和"将结果以Markdown格式写入{输出路径}"后缀</span>
                </label>
                <label class="field">
                  <span class="label">输出路径</span>
                  <input :value="outputPath || defaultOutputPath" readonly class="readonly-path" />
                  <span class="tip">路径由系统自动生成，不可修改</span>
                </label>
                <div v-if="formError" class="error">{{ formError }}</div>
              </form>
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="close">取消</button>
            <button type="button" class="btn primary" :disabled="saving || formLoading" @click="submit">
              {{ saving ? '保存中…' : (isEdit ? '保存' : '创建') }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.45); display: flex; justify-content: flex-end; z-index: 1000; }
.drawer { width: 520px; max-width: 90vw; height: 100%; background: #fff; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
.drawer-close { border: none; background: transparent; font-size: 24px; line-height: 1; color: #64748b; cursor: pointer; padding: 0 4px; border-radius: 4px; }
.drawer-close:hover { background: #f1f5f9; color: #0f172a; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.drawer-footer { display: flex; gap: 8px; justify-content: flex-end; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.loading { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }
.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.form input, .form select, .form textarea { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; font-family: inherit; background: #fff; }
.form textarea { resize: vertical; }
.readonly-path { background: #f1f5f9; color: #64748b; cursor: default; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; user-select: all; }
.tip { font-size: 12px; color: #94a3b8; }
.error { color: #dc2626; font-size: 13px; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
