<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getSkill, createSkill, updateSkill, currentUserId } from '../../api/skill';
import type { SkillInput } from '../../types/skill';

const route = useRoute();
const router = useRouter();

// /skills/new       -> 无 id 参数 -> 创建
// /skills/:id/edit  -> 有 id 参数 -> 编辑
const editId = computed(() => (route.params.id != null ? Number(route.params.id) : null));
const isEdit = computed(() => editId.value != null);

const form = ref<SkillInput>({ name: '', description: '', content: '', category: '', tags: '' });
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const notOwner = ref(false);

const categories = ['数据', '办公', '研发', '业务'];

onMounted(async () => {
  if (editId.value == null) return; // 创建模式,无需加载
  loading.value = true;
  try {
    const s = await getSkill(editId.value);
    form.value = {
      name: s.name ?? '',
      description: s.description ?? '',
      content: s.content ?? '',
      category: s.category ?? '',
      tags: s.tags ?? '',
    };
    // 后端 update 做 owner 校验,前端先挡一道:非所有者只读
    if (s.ownerUserId !== currentUserId()) notOwner.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
});

async function submit() {
  error.value = '';
  if (!form.value.name.trim()) {
    error.value = '请填写 Skill 名称';
    return;
  }
  saving.value = true;
  try {
    if (editId.value != null) {
      await updateSkill(editId.value, form.value);
      router.push(`/skills/${editId.value}`);
    } else {
      const created = await createSkill(form.value);
      router.push(`/skills/${created.id}`);
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
        <span class="label">分类</span>
        <select v-model="form.category" :disabled="notOwner">
          <option value="">未分类</option>
          <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
        </select>
      </label>
      <label class="field">
        <span class="label">标签</span>
        <input v-model="form.tags" :disabled="notOwner" placeholder="逗号分隔,如 query,quality" />
      </label>
      <label class="field">
        <span class="label">描述</span>
        <textarea v-model="form.description" :disabled="notOwner" rows="2" placeholder="一句话说明这个 Skill 做什么" />
      </label>
      <label class="field">
        <span class="label">内容</span>
        <textarea v-model="form.content" :disabled="notOwner" rows="12" class="content" placeholder="SKILL.md 正文(Markdown)" />
      </label>
      <div v-if="error" class="error">{{ error }}</div>
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
.actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
</style>
