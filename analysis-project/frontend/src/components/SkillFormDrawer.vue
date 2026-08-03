<script setup lang="ts">
/**
 * Skill 创建/编辑表单 Drawer(共享组件)
 *
 * 职责:
 *  - editId == null -> 创建模式;editId != null -> 编辑模式(拉取已有 Skill)
 *  - 保存后提交维度发布申请(如有选择)走审批流
 *  - 成功后 emit saved(skillId) 供父组件做后续(列表页跳详情 / 详情页重载)
 *
 * 由 SkillListPage(创建)与 SkillDetailPage(编辑)共用,替代各自内联 Drawer。
 */
import { ref, watch, computed } from 'vue';
import DimensionCascader from './DimensionCascader.vue';
import SkillFileAttachment from './SkillFileAttachment.vue';
import {
  getSkill,
  createSkill,
  updateSkill,
  currentUserId,
  getPublishTargets,
  submitPublish,
  getSkillPublishes,
} from '../api/skill';
import type { SkillInput, PublishTargetGroup } from '../types/skill';

const props = defineProps<{ open: boolean; editId: number | null }>();
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void;
  (e: 'saved', skillId: number): void;
}>();

const isEdit = computed(() => props.editId != null);

const form = ref<SkillInput>({ name: '', description: '', content: '' });
const formLoading = ref(false);
const saving = ref(false);
const formError = ref('');
const notOwner = ref(false);
const attachmentRef = ref<InstanceType<typeof SkillFileAttachment> | null>(null);

// 维度多选(创建/编辑时选择目标维度,保存后提交发布申请走审批流)
const publishTargetGroups = ref<PublishTargetGroup[]>([]);
// 已选中的维度目标,key 格式 "orgType:orgId",支持同维度多选
const selectedTargets = ref<Set<string>>(new Set());
// 编辑模式下已存在的发布记录(用于展示当前维度状态)
const existingDimensionLabel = ref('个人');
// 维度提交结果反馈
const publishResult = ref('');

// 首次打开表单时加载可选发布目标(全局只加载一次)
const targetsLoaded = ref(false);
async function ensurePublishTargets() {
  if (targetsLoaded.value) return;
  try {
    publishTargetGroups.value = await getPublishTargets();
  } catch {
    // 获取失败不阻塞表单,但维度选择区会空
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

// Drawer 打开时按 editId 初始化:编辑模式拉取已有数据,创建模式仅重置
watch(() => props.open, (open) => {
  if (!open) return;
  resetForm();
  ensurePublishTargets();
  if (props.editId != null) {
    loadForEdit(props.editId);
  }
});

// 加载已有 Skill 数据(编辑模式)
async function loadForEdit(id: number) {
  formLoading.value = true;
  try {
    const s = await getSkill(id);
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
    };
    // 后端 update 做 owner 校验,前端先挡一道:非所有者只读
    if (s.ownerUserId !== currentUserId()) notOwner.value = true;
    // 加载已有发布记录,展示当前维度
    try {
      const publishes = await getSkillPublishes(id);
      const approved = publishes.filter(p => p.status === 'APPROVED');
      if (approved.length > 0) {
        const TYPE_LABEL: Record<string, string> = {
          GROUP: '小组', DEPARTMENT: '部门', PRODUCT_LINE: '产品线', COMPANY: '',
        };
        existingDimensionLabel.value = approved.map(p => {
          const label = TYPE_LABEL[p.targetType] ?? '';
          // targetName 已是组织显示名称(如"开发一组"),按维度类型拼接一次前缀即可
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
    if (props.editId != null) {
      await updateSkill(props.editId, form.value);
      skillId = props.editId;
    } else {
      const created = await createSkill(form.value);
      skillId = created.id;
      // 创建模式: 将暂存的附件关联到新创建的 skill
      if (attachmentRef.value) {
        await attachmentRef.value.attachPendingFiles(skillId);
      }
    }
    // 保存成功后,提交维度发布申请(如有选择)
    if (selectedTargets.value.size > 0) {
      const allTargets = publishTargetGroups.value.flatMap(g => g.targets);
      const targets = allTargets.filter(t => selectedTargets.value.has(`${t.orgType}:${t.orgId}`));
      const failed: string[] = [];
      for (const t of targets) {
        try {
          // 存储组织显示名称(如"开发一组"),不含维度前缀,展示时再按维度类型拼接
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
    // 无 publishResult 或无失败时,延迟关闭并通知父组件;有失败反馈时停留让用户看到
    if (!publishResult.value || !publishResult.value.includes('失败')) {
      setTimeout(() => {
        close();
        emit('saved', skillId);
      }, 1200);
    }
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function close() {
  emit('update:open', false);
}
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" @click.self="close">
        <div class="drawer">
          <div class="drawer-header">
            <h3>{{ isEdit ? '编辑 Skill' : '创建 Skill' }}</h3>
            <button class="drawer-close" @click="close" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
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
                  <textarea v-model="form.content" :disabled="notOwner" rows="12" class="content" placeholder="SKILL.md 正文(Markdown)" />
                </label>
                <SkillFileAttachment
                  ref="attachmentRef"
                  :skill-id="editId"
                  :disabled="notOwner"
                />
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
                <div v-if="formError" class="error">{{ formError }}</div>
                <div v-if="publishResult" class="publish-result">{{ publishResult }}</div>
              </form>
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="close">取消</button>
            <button type="button" class="btn primary" :disabled="saving || notOwner || formLoading" @click="submit">
              {{ saving ? '保存中…' : (isEdit ? '保存' : '创建') }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 1000;
}
.drawer {
  width: 520px;
  max-width: 90vw;
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12);
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #e2e8f0;
}
.drawer-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
.drawer-close {
  border: none;
  background: transparent;
  font-size: 24px;
  line-height: 1;
  color: #64748b;
  cursor: pointer;
  padding: 0 4px;
  border-radius: 4px;
}
.drawer-close:hover { background: #f1f5f9; color: #0f172a; }
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}
.drawer-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #e2e8f0;
}
.loading { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }

.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.form input, .form select, .form textarea {
  padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px;
  font-size: 14px; font-family: inherit; background: #fff;
}
.form textarea { resize: vertical; }
.form .content { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
.form input:disabled, .form select:disabled, .form textarea:disabled { background: #f1f5f9; color: #94a3b8; }
.warn { background: #fef3c7; border: 1px solid #f59e0b; color: #92400e; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
.error { color: #dc2626; font-size: 13px; }
.publish-result { color: #047857; font-size: 13px; background: #d1fae5; padding: 8px 12px; border-radius: 6px; }
.dim-current { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 6px 10px; border-radius: 6px; margin-bottom: 6px; }
.dim-tip { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }

/* Drawer 进出动画 */
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
