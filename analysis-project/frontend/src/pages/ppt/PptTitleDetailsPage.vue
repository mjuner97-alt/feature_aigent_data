<script setup lang="ts">
/** 标题明细链接维护页，按明细分组编辑跳转地址。 */

import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { listPptTitleDetails, savePptTitleDetails, type PptTitleDetail } from '../../api/pptAnomaly';

const route = useRoute();
const title = computed(() => route.path === '/ppt-anomaly/keyword-config/title-details'
  ? String(window.history.state?.pptDetailTitle ?? '') : '');
const details = ref<PptTitleDetail[]>([]);
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');

// 标题变化后加载对应明细链接，忽略已离开标题的过期响应。
watch(title, async (name, _, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  ready.value = false;
  error.value = '';
  details.value = [];
  loading.value = !!name;
  if (!name) return;
  try {
    const result = await listPptTitleDetails(name);
    if (active) {
      details.value = result;
      ready.value = true;
    }
  } catch {
    if (active) error.value = '明细加载失败，请重新打开此页面。';
  } finally {
    if (active) loading.value = false;
  }
}, { immediate: true });

/** 保存当前标题的明细分组及链接配置。 */
async function save() {
  if (!ready.value || saving.value) return;
  error.value = '';
  for (const detail of details.value) {
    if (!detail.name.trim() || !detail.url.trim()) {
      error.value = '请填写每条明细的链接展示名称和链接地址。';
      return;
    }
  }
  saving.value = true;
  const name = title.value;
  try {
    await savePptTitleDetails(name, details.value);
    if (title.value === name) ElMessage.success('明细已保存');
  } catch (failure) {
    if (title.value === name) error.value = failure instanceof Error ? failure.message : '明细保存失败。';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="title-details" v-loading="loading">
    <RouterLink to="/ppt-anomaly/keyword-config">返回标题配置</RouterLink>
    <h2>配置明细</h2>
    <p v-if="title">{{ title }}</p>
    <p v-else>请从标题配置选择要维护的页面标题。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="ready" :disabled="saving" label-position="top" @submit.prevent="save">
      <fieldset v-for="(detail, index) in details" :key="index" class="link-row">
        <el-form-item label="链接展示名称">
          <el-input v-model="detail.name" maxlength="255" />
        </el-form-item>
        <el-form-item label="链接地址">
          <el-input v-model="detail.url" maxlength="2048" placeholder="https://" />
        </el-form-item>
        <el-button link type="danger" @click="details.splice(index, 1)">移除明细</el-button>
      </fieldset>
      <el-button @click="details.push({ name: '', url: '' })">添加明细</el-button>
      <el-button native-type="submit" type="primary" :loading="saving">保存</el-button>
    </el-form>
  </section>
</template>

<style scoped>
.title-details { max-width: 960px; padding: 24px; }
.link-row { min-width: 0; border: 1px solid var(--el-border-color); border-radius: 6px; margin: 12px 0; padding: 12px; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto; gap: 12px; align-items: end; }
.link-row .el-form-item { margin-bottom: 0; }
@media (max-width: 700px) { .link-row { grid-template-columns: 1fr; gap: 8px; } .link-row .el-button { justify-self: start; } }
</style>
