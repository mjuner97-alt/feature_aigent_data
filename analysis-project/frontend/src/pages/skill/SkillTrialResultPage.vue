<script setup lang="ts">
/**
 * Skill 试运行结果独立页(路由 /skills/:id/trial-results)
 *
 * 审批页"试运行结果"按钮与详情页嵌入面板共用 SkillTrialResultPanel;
 * 本页负责加载 skill 基础信息(标题 + 最后修改时间),结果展示全部由 Panel 承担。
 */
import { ref, watch, computed } from 'vue';
import { useRoute } from 'vue-router';
import { getSkill } from '../../api/skill';
import type { SkillDetail } from '../../types/skill';
import SkillTrialResultPanel from '../../components/SkillTrialResultPanel.vue';

const route = useRoute();

const skill = ref<SkillDetail | null>(null);
const loadError = ref('');

const skillId = computed(() => Number(route.params.id) || 0);

async function load() {
  skill.value = null;
  loadError.value = '';
  if (!skillId.value) return;
  try {
    skill.value = await getSkill(skillId.value);
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载 Skill 信息失败';
  }
}

watch(skillId, load, { immediate: true });
</script>

<template>
  <div class="trial-result-page">
    <h2 class="page-title">试运行结果 · {{ skill?.name || (skillId ? `Skill #${skillId}` : '') }}</h2>
    <div v-if="loadError" class="page-error">{{ loadError }}</div>
    <SkillTrialResultPanel
      v-if="skillId"
      :skill-id="skillId"
      :skill-updated-at="skill?.updatedAt"
    />
  </div>
</template>

<style scoped>
.trial-result-page { padding: 4px 0; }
.page-title {
  font-weight: 700;
  font-size: 20px;
  color: #0f172a;
  margin: 0 0 14px;
}
.page-error { color: #dc2626; font-size: 13px; margin-bottom: 10px; }
</style>
