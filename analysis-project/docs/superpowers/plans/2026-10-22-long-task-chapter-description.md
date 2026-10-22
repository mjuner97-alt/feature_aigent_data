# 长任务章节描述 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在长任务/报告流程章节编辑器中录入章节描述，并在汇总报告章节标题下显示该描述。

**Architecture:** 扩展前后端共享的大纲项 JSON，新增可选 `description` 字段；编辑器从 JSON 展开/保存该字段；ReportOutlineComposer 在章节标题后输出安全转义的正文段落。旧数据缺少字段时保持原行为。

**Tech Stack:** Vue 3 + TypeScript、Java Spring、Jackson、Maven。

## Global Constraints

- 按钮文案必须为“添加描述”。
- 描述为空时不输出空段落。
- 兼容已有无 description 字段的大纲 JSON。

---

### Task 1: 扩展大纲数据结构与前端编辑器

**Files:** `frontend/src/types/skillFlow.ts`, `frontend/src/utils/reportOutline.ts`, `frontend/src/pages/ReportProcessPage.vue`, `frontend/src/components/SkillFlowEditorDrawer.vue`

- [ ] 为大纲项类型和编辑扁平行增加可选 `description`。
- [ ] flatten/build 保留并序列化描述。
- [ ] 章节标题旁增加“添加描述”按钮，点击后显示文本输入，沿用正文输入样式；已有描述直接显示可编辑输入。
- [ ] 长任务编辑器与报告流程编辑页均使用同一字段。

### Task 2: 后端 DTO 与汇总报告渲染

**Files:** `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDefinitionRequest.java`, `src/main/java/com/agentscopea2a/v2/skillManager/report/ReportOutlineComposer.java`

- [ ] 为 ReportOutlineItem 增加可选 description 字段并保持 Jackson 旧数据兼容。
- [ ] Composer 在章节标题后输出 `<p class="report-outline-description">...</p>`，空描述不输出，HTML 特殊字符转义。

### Task 3: 验证

**Files:** `src/test/java/.../ReportOutlineComposerTest.java`（若已有测试目录则扩展现有测试）

- [ ] 增加描述渲染、空描述和旧 JSON 兼容测试。
- [ ] 运行后端相关测试及前端类型检查/构建。
