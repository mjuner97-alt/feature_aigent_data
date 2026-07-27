<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getSkill, createSkill, updateSkill, currentUserId, getPublishTargets, submitPublish, getSkillPublishes } from '../../api/skill';
import type { SkillInput, PublishTargetGroup } from '../../types/skill';
import DimensionCascader from '../../components/DimensionCascader.vue';

const route = useRoute();
const router = useRouter();

// /skills/new       -> 无 id 参数 -> 创建
// /skills/:id/edit  -> 有 id 参数 -> 编辑
const editId = computed(() => (route.params.id != null ? Number(route.params.id) : null));
const isEdit = computed(() => editId.value != null);

const form = ref<SkillInput>({ name: '', description: '', content: '' });
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const notOwner = ref(false);

// 维度多选(创建/编辑时选择目标维度,保存后提交发布申请走审批流)
const publishTargetGroups = ref<PublishTargetGroup[]>([]);
// 已选中的维度目标,key 格式 "orgType:orgId",支持同维度多选
const selectedTargets = ref<Set<string>>(new Set());
// 编辑模式下已存在的发布记录(用于展示当前维度状态)
const existingDimensionLabel = ref('个人');
// 维度提交结果反馈
const publishResult = ref('');

onMounted(async () => {
  // 加载可选发布目标(维度列表)
  try {
    publishTargetGroups.value = await getPublishTargets();
  } catch (e) {
    // 获取失败不阻塞表单,但维度选择区会空
  }

  if (editId.value == null) return; // 创建模式,无需加载
  loading.value = true;
  try {
    const s = await getSkill(editId.value);
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
    };
    // 后端 update 做 owner 校验,前端先挡一道:非所有者只读
    if (s.ownerUserId !== currentUserId()) notOwner.value = true;
    // 加载已有发布记录,展示当前维度
    try {
      const publishes = await getSkillPublishes(editId.value);
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
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
});

async function submit() {
  error.value = '';
  publishResult.value = '';
  if (!form.value.name.trim()) {
    error.value = '请填写 Skill 名称';
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
    }
    // 保存成功后,提交维度发布申请(如有选择)
    if (selectedTargets.value.size > 0) {
      const allTargets = publishTargetGroups.value.flatMap(g => g.targets);
      const targets = allTargets.filter(t => selectedTargets.value.has(`${t.orgType}:${t.orgId}`));
      const failed: string[] = [];
      for (const t of targets) {
        try {
          await submitPublish(skillId, t.orgType, t.orgId, t.fullLabel);
        } catch (e) {
          const msg = e instanceof Error ? e.message : '失败';
          failed.push(`${t.fullLabel}:${msg}`);
        }
      }
      if (failed.length > 0) {
        publishResult.value = `Skill 已保存,但部分维度申请提交失败:${failed.join('; ')}`;
      } else {
        publishResult.value = `Skill 已保存,${targets.length} 条维度发布申请已提交(待审批)。`;
      }
    }
    // 无 publishResult 时直接跳转;有反馈时停留让用户看到
    if (!publishResult.value || !publishResult.value.includes('失败')) {
      setTimeout(() => router.push(`/skills/${skillId}`), 1200);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function cancel() {
  router.back();
}
</script>

<template>
  <h2>{{ isEdit ? '编辑 Skill' : '创建 Skill' }}</h2>
  <div v-if="loading">加载中…</div>
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
        <textarea v-model="form.content" :disabled="notOwner" rows="12" class="content" placeholder="SKILL.md 正文(Markdown)" />
      </label>
      <!-- 维度级联选择器 -->
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
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="publishResult" class="publish-result">{{ publishResult }}</div>
      <div class="actions">
        <button type="button" class="btn ghost" @click="cancel">取消</button>
        <button type="submit" class="btn primary" :disabled="saving || notOwner">
          {{ saving ? '保存中…' : (isEdit ? '保存' : '创建') }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 12px; }
.form { display: flex; flex-direction: column; gap: 12px; max-width: 720px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
input, select, textarea {
  padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px;
  font-size: 14px; font-family: inherit; background: #fff;
}
textarea { resize: vertical; }
.content { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
input:disabled, select:disabled, textarea:disabled { background: #f1f5f9; color: #94a3b8; }
.warn { background: #fef3c7; border: 1px solid #f59e0b; color: #92400e; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
.error { color: #dc2626; font-size: 13px; }
.publish-result { color: #047857; font-size: 13px; background: #d1fae5; padding: 8px 12px; border-radius: 6px; }
.dim-current { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 6px 10px; border-radius: 6px; margin-bottom: 6px; }
.dim-tip { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
</style>
