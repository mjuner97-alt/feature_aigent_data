<script setup lang="ts">
/**
 * Skill 创建/编辑全页面表单
 *
 * 职责:
 *  - 通过路由 /skills/new (创建) 和 /skills/:id/edit (编辑) 进入
 *  - editId == null -> 创建模式;editId != null -> 编辑模式(拉取已有 Skill)
 *  - 保存后提交维度发布申请(如有选择)走审批流
 *  - 成功后跳转详情页
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import DimensionCascader from '../../components/DimensionCascader.vue';
import SkillFileAttachment from '../../components/SkillFileAttachment.vue';
import {
  getSkill,
  createSkill,
  updateSkill,
  currentUserId,
  getPublishTargets,
  submitPublish,
  getSkillPublishes,
} from '../../api/skill';
import type { SkillInput, PublishTargetGroup } from '../../types/skill';

const route = useRoute();
const router = useRouter();

const editId = computed(() => {
  const id = route.params.id;
  return id ? Number(id) : null;
});
const isEdit = computed(() => editId.value != null);

const form = ref<SkillInput>({ name: '', description: '', content: '' });
const formLoading = ref(false);
const saving = ref(false);
const formError = ref('');
const notOwner = ref(false);
const attachmentRef = ref<InstanceType<typeof SkillFileAttachment> | null>(null);

// 维度多选(创建/编辑时选择目标维度,保存后提交发布申请走审批流)
const publishTargetGroups = ref<PublishTargetGroup[]>([]);
const selectedTargets = ref<Set<string>>(new Set());
const existingDimensionLabel = ref('个人');
const publishResult = ref('');

const targetsLoaded = ref(false);
async function ensurePublishTargets() {
  if (targetsLoaded.value) return;
  try {
    publishTargetGroups.value = await getPublishTargets();
  } catch {
    // 获取失败不阻塞表单
  }
  targetsLoaded.value = true;
}

function resetForm() {
  form.value = { name: '', description: '', content: '' };
  formError.value = '';
  publishResult.value = '';
  notOwner.value = false;
  existingDimensionLabel.value = '个人';
  selectedTargets.value = new Set();
}

async function loadForEdit(id: number) {
  formLoading.value = true;
  try {
    const s = await getSkill(id);
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
    };
    if (s.ownerUserId !== currentUserId()) notOwner.value = true;
    try {
      const publishes = await getSkillPublishes(id);
      const approved = publishes.filter(p => p.status === 'APPROVED');
      if (approved.length > 0) {
        const TYPE_LABEL: Record<string, string> = {
          GROUP: '小组', DEPARTMENT: '部门', PRODUCT_LINE: '产品线', COMPANY: '',
        };
        existingDimensionLabel.value = approved.map(p => {
          const label = TYPE_LABEL[p.targetType] ?? '';
          return label ? `${label}:${p.targetName}` : p.targetName;
        }).join('、');
      }
    } catch {
      // 维度信息加载失败不阻塞
    }
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    formLoading.value = false;
  }
}

onMounted(async () => {
  resetForm();
  await ensurePublishTargets();
  if (editId.value != null) {
    loadForEdit(editId.value);
  }
});

async function submit() {
  formError.value = '';
  publishResult.value = '';
  if (!form.value.name.trim()) {
    formError.value = '请填写 Skill 名称';
    return;
  }
  saving.value = true;
  try {
    let skillId: number;
    if (editId.value != null) {
      await updateSkill(editId.value, form.value);
      skillId = editId.value;
    } else {
      const created = await createSkill(form.value);
      skillId = created.id;
      // 创建模式: 将暂存的附件关联到新创建的 skill
      if (attachmentRef.value) {
        await attachmentRef.value.attachPendingFiles(skillId);
      }
    }
    if (selectedTargets.value.size > 0) {
      const allTargets = publishTargetGroups.value.flatMap(g => g.targets);
      const targets = allTargets.filter(t => selectedTargets.value.has(`${t.orgType}:${t.orgId}`));
      const failed: string[] = [];
      for (const t of targets) {
        try {
          await submitPublish(skillId, t.orgType, t.orgId, t.displayName);
        } catch (e) {
          const msg = e instanceof Error ? e.message : '失败';
          failed.push(`${t.displayName}:${msg}`);
        }
      }
      if (failed.length > 0) {
        publishResult.value = `Skill 已保存,但部分维度申请提交失败:${failed.join('; ')}`;
      } else {
        publishResult.value = `Skill 已保存,${targets.length} 条维度发布申请已提交(待审批)。`;
      }
    }
    if (!publishResult.value || !publishResult.value.includes('失败')) {
      setTimeout(() => {
        router.push(`/skills/${skillId}`);
      }, 1200);
    }
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function goBack() {
  // 有历史则后退,否则跳回列表
  if (window.history.length > 1) {
    router.back();
  } else {
    router.push('/skills');
  }
}
</script>

<template>
  <div class="form-page">
    <div class="form-header">
      <button class="back-btn" @click="goBack" aria-label="返回">← 返回</button>
      <h2>{{ isEdit ? '编辑 Skill' : '创建 Skill' }}</h2>
    </div>
    <div class="form-body">
      <div v-if="formLoading" class="loading">加载中…</div>
      <div v-else>
        <div v-if="notOwner" class="warn">⚠ 你不是此 Skill 的所有者,仅可查看,无法保存修改。</div>
        <form class="form" @submit.prevent="submit">
          <label class="field">
            <span class="label">名称 *</span>
            <input v-model="form.name" :disabled="notOwner" placeholder="如 query_q1_quality" />
          </label>
          <label class="field">
            <span class="label">描述</span>
            <textarea v-model="form.description" :disabled="notOwner" rows="2" placeholder="一句话说明这个 Skill 做什么" />
          </label>
          <label class="field">
            <span class="label">内容</span>
            <textarea v-model="form.content" :disabled="notOwner" rows="16" class="content" placeholder="SKILL.md 正文(Markdown)" />
          </label>
          <SkillFileAttachment
            ref="attachmentRef"
            :skill-id="editId"
            :disabled="notOwner"
          />
          <div class="field">
            <span class="label">维度标签</span>
            <div v-if="isEdit && existingDimensionLabel !== '个人'" class="dim-current">
              当前维度:{{ existingDimensionLabel }}(下方新选维度将追加提交审批)
            </div>
            <DimensionCascader
              v-model="selectedTargets"
              :groups="publishTargetGroups"
              :disabled="notOwner"
            />
            <div class="dim-tip">不选 = 个人维度(仅自己可见)。维度间互斥,只能选一种维度类型(如选了"小组"就不能再选"部门"),同维度内可多选。提交后各维度独立走审批。</div>
          </div>
          <div v-if="formError" class="error">{{ formError }}</div>
          <div v-if="publishResult" class="publish-result">{{ publishResult }}</div>
        </form>
      </div>
    </div>
    <div class="form-footer">
      <button type="button" class="btn ghost" @click="goBack">取消</button>
      <button type="button" class="btn primary" :disabled="saving || notOwner || formLoading" @click="submit">
        {{ saving ? '保存中…' : (isEdit ? '保存' : '创建') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.form-page {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 0px);
  max-width: 820px;
  margin: 0 auto;
}
.form-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0 12px;
  border-bottom: 1px solid #e2e8f0;
  margin-bottom: 20px;
}
.form-header h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}
.back-btn {
  border: 1px solid #cbd5e1;
  background: #fff;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;
}
.back-btn:hover { border-color: #93c5fd; color: #2563eb; }

.form-body {
  flex: 1;
}
.loading { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }

.form { display: flex; flex-direction: column; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.form input, .form textarea {
  padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px;
  font-size: 14px; font-family: inherit; background: #fff;
  width: 100%; box-sizing: border-box;
}
.form textarea { resize: vertical; }
.form .content { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
.form input:disabled, .form textarea:disabled { background: #f1f5f9; color: #94a3b8; }
.warn { background: #fef3c7; border: 1px solid #f59e0b; color: #92400e; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
.error { color: #dc2626; font-size: 13px; }
.publish-result { color: #047857; font-size: 13px; background: #d1fae5; padding: 8px 12px; border-radius: 6px; }
.dim-current { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 6px 10px; border-radius: 6px; margin-bottom: 6px; }
.dim-tip { font-size: 12px; color: #94a3b8; margin-top: 4px; }

.form-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 16px 0;
  border-top: 1px solid #e2e8f0;
  margin-top: 20px;
}
.btn { padding: 8px 24px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.btn.ghost:hover { border-color: #93c5fd; color: #2563eb; }
</style>
