<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { Options } from '@popperjs/core';
import { listPptTitleHandlerSources, listPptTitleHandlers, type PptPerson } from '../../api/pptAnomaly';

const open = defineModel<boolean>({ required: true });
const props = defineProps<{ title: string; existingIds: ReadonlySet<string>; note: string; saving?: boolean; error?: string }>();
const emit = defineEmits<{ apply: [sourceTitle: string, people: PptPerson[]]; select: [] }>();
const sources = ref<string[]>([]);
const sourceTitle = ref('');
const sourceHandlers = ref<PptPerson[]>([]);
const loadingSources = ref(false);
const loadingHandlers = ref(false);
const loadError = ref('');
const sourceDropdownOffset = 6;
const sourceDropdownOptions: Partial<Options> = {
  modifiers: [{
    name: 'fitSourceDropdown',
    enabled: true,
    phase: 'beforeWrite',
    fn({ state }) {
      const bottom = state.elements.reference.getBoundingClientRect().bottom;
      const availableHeight = document.documentElement.clientHeight - bottom - sourceDropdownOffset - 10;
      Object.assign(state.styles.popper, {
        '--source-dropdown-height': `${Math.max(0, Math.floor(availableHeight))}px`,
      });
    },
  }],
};
const newHandlers = computed(() => [...new Map(sourceHandlers.value
  .filter(person => !props.existingIds.has(person.userId))
  .map(person => [person.userId, person])).values()]);

watch([open, () => props.title], async ([active, target], _, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  sources.value = [];
  sourceTitle.value = '';
  loadError.value = '';
  // 新增标题尚未有目标名称，也允许先选择来源标题并把人员带回创建表单。
  loadingSources.value = active;
  if (!loadingSources.value) return;
  try {
    const names = await listPptTitleHandlerSources();
    if (current) sources.value = names.filter(name => name !== target);
  } catch {
    if (current) loadError.value = '来源标题加载失败，请重新打开重试。';
  } finally {
    if (current) loadingSources.value = false;
  }
});

watch([open, sourceTitle], async ([active, name], _, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  sourceHandlers.value = [];
  loadingHandlers.value = active && !!name;
  if (!loadingHandlers.value) return;
  loadError.value = '';
  emit('select');
  try {
    const people = await listPptTitleHandlers(name);
    if (current) sourceHandlers.value = people;
  } catch {
    if (current) loadError.value = '来源经办人加载失败，请重新选择标题。';
  } finally {
    if (current) loadingHandlers.value = false;
  }
});
</script>

<template>
  <el-dialog v-model="open" title="复用其他标题经办人" class="ppt-dialog title-handler-reuse-dialog"
    width="min(640px, calc(100vw - 32px))" align-center append-to-body
    :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <div class="reuse-content">
      <p class="reuse-note">{{ note }}</p>
      <div class="reuse-source">
        <label for="handler-source-title">来源标题</label>
        <el-select id="handler-source-title" v-model="sourceTitle" filterable placeholder="搜索并选择来源标题"
          placement="bottom-start" :fallback-placements="[]" :offset="sourceDropdownOffset"
          popper-class="ppt-title-handler-source-dropdown" :popper-options="sourceDropdownOptions"
          :disabled="saving || loadingSources" :loading="loadingSources" no-data-text="暂无其他已配置经办人的标题">
          <el-option v-for="name in sources" :key="name" :label="name" :value="name" />
        </el-select>
      </div>
      <el-alert v-if="loadError || error" :title="loadError || error" type="error" :closable="false" />
      <section v-if="sourceTitle && !loadError" class="reuse-preview" aria-labelledby="handler-preview-title">
        <h3 id="handler-preview-title">经办人预览</h3>
        <el-table v-loading="loadingHandlers" :data="sourceHandlers" empty-text="来源标题暂无经办人">
          <el-table-column prop="department" label="部门" min-width="100" />
          <el-table-column label="姓名（认证号）" min-width="145">
            <template #default="{ row }">{{ row.name }}（{{ row.userId }}）</template>
          </el-table-column>
          <el-table-column label="处理" width="86" align="center">
            <template #default="{ row }">
              <el-tag :type="existingIds.has(row.userId) ? 'info' : 'success'" size="small" round>
                {{ existingIds.has(row.userId) ? '已存在' : '将追加' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loadingHandlers || !newHandlers.length"
        @click="emit('apply', sourceTitle, newHandlers)">
        {{ newHandlers.length ? `追加 ${newHandlers.length} 位经办人` : '追加经办人' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reuse-content { display: grid; gap: 16px; }
.reuse-note { margin: 0; padding: 10px 12px; border-radius: 8px; background: #f0f5fd; color: #344663; line-height: 1.6; }
.reuse-source { display: grid; gap: 8px; min-width: 0; }
.reuse-source label { color: #243b5d; font-weight: 600; }
.reuse-source :deep(.el-select__wrapper) { min-height: 40px; }
:global(.ppt-title-handler-source-dropdown .el-select-dropdown__wrap) {
  max-height: min(12rem, var(--source-dropdown-height, 12rem));
}
.reuse-preview { min-width: 0; }
.reuse-preview h3 { margin: 0 0 10px; color: #243b5d; font-size: 14px; font-weight: 600; }
.reuse-preview :deep(.el-table) { border: 1px solid #e3eaf4; border-radius: 8px; }
:global(.title-handler-reuse-dialog .el-dialog__footer) {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px var(--ppt-dialog-gutter);
  border-top: 1px solid #e3eaf4;
}
:global(.title-handler-reuse-dialog .el-dialog__footer .el-button + .el-button) { margin-left: 0; }
</style>
