<template>
  <div class="ppt-list-toolbar">
    <div v-if="$slots.leading" class="toolbar-leading"><slot name="leading" /></div>
    <el-config-provider size="default">
      <div class="toolbar-controls"><slot /></div>
    </el-config-provider>
  </div>
</template>

<style scoped>
.ppt-list-toolbar,
.toolbar-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.toolbar-leading { min-width: 0; }
.toolbar-controls { justify-content: flex-end; margin-inline-start: auto; max-width: 100%; }
.toolbar-controls :deep(.ppt-filter-search) { width: min(240px, 100%); }
.toolbar-controls :deep(.ppt-filter-select) { width: min(var(--ppt-filter-width, 240px), 100%); }
.toolbar-controls :deep(.el-button + .el-button) { margin-inline-start: 0; }
</style>
