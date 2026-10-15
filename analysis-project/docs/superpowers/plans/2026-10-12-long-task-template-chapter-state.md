# 长任务编辑器模板与章节状态修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让长任务流程编辑器在引入模板、撤销模板、添加/删除章节和节点时始终保持节点、章节及绑定关系一致，避免数据丢失或产生未绑定节点。

**Architecture:** 在前端编辑器中把节点与大纲草稿视为一个完整编辑状态；模板引入前保存完整快照，撤销恢复该快照。章节删除采用显式节点处理策略，默认保留节点并移入未绑定区，保存前继续校验未绑定节点。

**Tech Stack:** Vue 3、TypeScript、Vitest/现有前端测试工具。

## Global Constraints

- 模板引入只修改当前编辑会话，保存时才提交长任务流程。
- 撤销模板恢复最近一次引入前的完整编辑状态。
- 连续引入只保留最近一次撤销点。
- 删除含节点章节不得静默删除节点。

---

### Task 1: 抽取并测试编辑状态快照工具

**Files:**
- Create: `frontend/src/utils/longTaskEditorState.ts`
- Create: `frontend/src/utils/longTaskEditorState.test.js`

- [ ] 写测试覆盖完整快照深拷贝、恢复后不共享引用、节点绑定和标题均保留。
- [ ] 运行测试确认先失败。
- [ ] 实现 `captureEditorState`、`restoreEditorState` 和 `cloneEditorState`。
- [ ] 运行测试确认通过。

### Task 2: 接入模板引入与撤销

**Files:**
- Modify: `frontend/src/components/SkillFlowEditorDrawer.vue`

- [ ] 用统一快照替代分散的 nodes/outline/title 快照。
- [ ] 引入前先同步当前编辑状态，再保存最近一次快照。
- [ ] 撤销时恢复节点、章节、绑定、标题和模板状态。
- [ ] 连续引入覆盖旧撤销点，保存/关闭时清理撤销点。
- [ ] 增加模板引入、撤销和恢复流程的回归测试或可测试纯函数覆盖。

### Task 3: 修复章节删除语义

**Files:**
- Modify: `frontend/src/components/SkillFlowEditorDrawer.vue`
- Modify: `frontend/src/utils/longTaskEditorState.test.js`

- [ ] 删除空章节直接删除。
- [ ] 删除含节点章节时保留节点、清理章节绑定并提示用户节点进入未绑定区。
- [ ] 删除章节及子章节时一次性处理所有绑定。
- [ ] 确认已有节点不会被静默删除，保存校验继续提示未绑定节点。

### Task 4: 验证交互回归

**Files:**
- Modify if needed: `frontend/src/components/SkillFlowEditorDrawer.vue`
- Test: existing frontend utility/component tests

- [ ] 验证“添加章节→引入模板→撤销”恢复原章节。
- [ ] 验证“引入模板→新增章节/节点→撤销”恢复引入前状态。
- [ ] 验证“删除含节点章节”不会丢节点。
- [ ] 运行前端测试、类型检查和构建。
