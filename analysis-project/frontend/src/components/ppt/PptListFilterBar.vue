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
.toolbar-controls { display: flex; align-items: center; gap: 8px; }
.ppt-list-toolbar { flex-wrap: wrap; min-width: 0; }
.toolbar-leading { min-width: 0; }
.toolbar-controls {
  flex: 1 1 auto;
  flex-wrap: nowrap;
  justify-content: safe flex-end;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
}
.toolbar-controls :deep(.ppt-filter-search) { flex: 0 1 240px; width: 240px; min-width: 112px; }
.toolbar-controls :deep(.ppt-filter-select) {
  flex: 0 1 var(--ppt-filter-width, 240px);
  width: var(--ppt-filter-width, 240px);
  min-width: 112px;
}
.toolbar-controls :deep(.el-button) { flex: none; white-space: nowrap; }
.toolbar-controls :deep(.el-button + .el-button) { margin-inline-start: 0; }
</style>
