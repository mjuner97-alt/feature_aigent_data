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
import SkillFileAttachment from '../../components/SkillFileAttachment.vue';
import DimensionCascader from '../../components/DimensionCascader.vue';
import {
  getSkill,
  createSkill,
  updateSkill,
  currentUserId,
  getSkillPublishes,
  getGrants,
  getPublishTargets,
  submitPublish,
} from '../../api/skill';
import type { SkillInput, SkillGrant, PublishTargetGroup } from '../../types/skill';
import SkillGrantEditor from '../../components/SkillGrantEditor.vue';

const route = useRoute();
const router = useRouter();

const editId = computed(() => {
  const id = route.params.id;
  return id ? Number(id) : null;
});
const isEdit = computed(() => editId.value != null);

const form = ref<SkillInput>({ name: '', description: '', content: '', visibility: 'PERSONAL' });
const formLoading = ref(false);
const saving = ref(false);
const formError = ref('');
const notOwner = ref(false);
const attachmentRef = ref<InstanceType<typeof SkillFileAttachment> | null>(null);

// 可见性三态:个人(PERSONAL,默认,仅自己) / 私有(PRIVATE,授权即时生效) / 公开(PUBLIC,选维度发布走审批)
const visibility = ref<'PUBLIC' | 'PRIVATE' | 'PERSONAL'>('PERSONAL');
const existingGrants = ref<SkillGrant[]>([]);
const grantEditorRef = ref<InstanceType<typeof SkillGrantEditor> | null>(null);

// 公开:维度多选(保存后提交发布申请走审批流),key 格式 "orgType:orgId"
const publishTargetGroups = ref<PublishTargetGroup[]>([]);
const selectedTargets = ref<Set<string>>(new Set());
const targetsLoaded = ref(false);
async function ensurePublishTargets() {
  if (targetsLoaded.value) return;
  try {
    publishTargetGroups.value = await getPublishTargets();
  } catch {
    // 获取失败不阻塞表单,维度选择区为空
  }
  targetsLoaded.value = true;
}

// 历史维度发布(仅编辑"已是非个人维度"的旧 skill 时残留展示;新建不展示)
const existingDimensionLabel = ref('个人');
const publishResult = ref('');

// 编辑模式:skill 是否已属于非个人维度(仅残留旧维度信息展示,不再新增维度审批)
const isLegacyDimensionShared = computed(() => existingDimensionLabel.value !== '个人');

// 获取已存在授权(编辑模式回显)
async function ensureExistingGrants(id: number) {
  try {
    existingGrants.value = await getGrants(id);
  } catch {
    existingGrants.value = [];
  }
}

function resetForm() {
  form.value = { name: '', description: '', content: '', visibility: 'PERSONAL' };
  visibility.value = 'PERSONAL';
  formError.value = '';
  publishResult.value = '';
  notOwner.value = false;
  existingDimensionLabel.value = '个人';
  existingGrants.value = [];
  selectedTargets.value = new Set();
}

async function loadForEdit(id: number) {
  formLoading.value = true;
  try {
    const s = await getSkill(id);
    const vis = s.visibility === 'PUBLIC' || s.visibility === 'PRIVATE' ? s.visibility : 'PERSONAL';
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
      visibility: vis,
    };
    visibility.value = vis;
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
    await ensureExistingGrants(id);
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    formLoading.value = false;
  }
}

onMounted(async () => {
  resetForm();
  ensurePublishTargets();
  if (editId.value != null) {
    await loadForEdit(editId.value);
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
    // visibility 作为 SkillInput 传给后端(PERSONAL/PRIVATE/PUBLIC)
    const payload: SkillInput = { ...form.value, visibility: visibility.value };
    let skillId: number;
    if (editId.value != null) {
      await updateSkill(editId.value, payload);
      skillId = editId.value;
      // 编辑模式:授权编辑器已即时生效(加/删都直接调后端,首个授权自动切 PRIVATE),
      // 无需在此二次同步;仅兜底保证 visibility 已按开关更新。
    } else {
      const created = await createSkill(payload);
      skillId = created.id;
      // 创建模式: 将暂存的附件关联到新创建的 skill
      if (attachmentRef.value) {
        await attachmentRef.value.attachPendingFiles(skillId);
      }
      // 创建模式: 把表单里加好的授权同步到新 skill(编辑器无 skillId 时只是暂存)
      await syncGrantsToServer(skillId);
    }
    // 公开:保存后提交所选维度的发布申请(走审批,通过后维度内可见)
    if (visibility.value === 'PUBLIC' && selectedTargets.value.size > 0) {
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

// 创建模式专用:把编辑器暂存的授权提交到新 skill(创建时拿到 skillId 后调用)。首个授权自动切 PRIVATE。
async function syncGrantsToServer(skillId: number) {
  if (grantEditorRef.value) {
    await grantEditorRef.value.commitPending(skillId);
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

          <!-- 可见性三态:个人(默认,全员可见,默认仅自己使用) / 公开(全员可见+维度发布走审批,同维度默认使用) / 私有(授权即时生效,仅授权范围可见) -->
          <div class="field">
            <span class="label">可见性</span>
            <div class="visibility-row">
              <label class="vis-opt">
                <input type="radio" v-model="visibility" value="PERSONAL" :disabled="notOwner" />
                <span>个人</span>
                <span class="vis-desc">全员可见,不审批,默认仅自己使用,他人需引用</span>
              </label>
              <label class="vis-opt">
                <input type="radio" v-model="visibility" value="PUBLIC" :disabled="notOwner" />
                <span>公开</span>
                <span class="vis-desc">全员可见,选择小组、部门、公司等发布维度,需要审批,审批通过后同维度默认使用</span>
              </label>
              <label class="vis-opt">
                <input type="radio" v-model="visibility" value="PRIVATE" :disabled="notOwner" />
                <span>私有</span>
                <span class="vis-desc">指定用户、部门、小组或虚拟组,不走发布审批,授权即时生效</span>
              </label>
            </div>
          </div>

          <!-- 公开:选维度(保存后提交发布申请走审批,审批通过后维度内可见) -->
          <div v-if="visibility === 'PUBLIC'" class="field">
            <span class="label">发布维度</span>
            <div v-if="isEdit && existingDimensionLabel !== '个人'" class="dim-current">
              当前已发布到:{{ existingDimensionLabel }}(下方新选维度将追加提交审批)
            </div>
            <DimensionCascader
              v-model="selectedTargets"
              :groups="publishTargetGroups"
              :disabled="notOwner"
              placeholder="请选择发布维度(不选=仅审批通过的原有维度)"
            />
            <div class="dim-tip">公开需要审批:保存后按所选维度提交发布申请,各维度独立审批,通过后同维度用户默认使用(不引用也计入"我使用的");其他用户可见但需手动引用。</div>
          </div>

          <!-- 私有时:授权编辑器(编辑模式即时生效;创建模式暂存,保存后再提交) -->
          <div v-if="visibility === 'PRIVATE' && !notOwner" class="field">
            <SkillGrantEditor
              ref="grantEditorRef"
              :skill-id="editId"
              :editable="true"
            />
          </div>

          <!-- 旧维度残留展示(仅历史已发布到非个人维度的 skill 只读提示,不再新增维度审批) -->
          <div v-if="isLegacyDimensionShared" class="field">
            <span class="label">历史维度发布</span>
            <div class="dim-current">当前已发布到:{{ existingDimensionLabel }}(旧审批维度,保留兼容;同维度用户仍可使用)。如需新授权请用上方"谁可以看"。</div>
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
.visibility-row { display: flex; flex-direction: column; gap: 6px; }
.vis-opt { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #1e293b; cursor: pointer; }
.vis-opt input { width: auto; }
.vis-desc { font-size: 12px; color: #94a3b8; }

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
