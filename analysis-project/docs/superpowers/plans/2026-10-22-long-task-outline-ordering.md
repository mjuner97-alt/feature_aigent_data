# 长任务目录顺序调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为长任务报告目录增加一级标题拖拽排序，并为所有层级标题增加同级范围内的上下调整按钮，同时保持子标题和节点归属不变。

**Architecture:** 继续使用扁平 `OutlineRow[]` 作为编辑器状态，每行通过 `level` 表示层级、通过 `nodeKeys` 保存节点归属和章节内顺序。新增纯函数负责识别目录子树、移动完整目录块和判断同级移动边界；Vue 编辑器只负责把拖拽和按钮事件转换为这些纯函数调用。一级标题拖拽只允许一级标题之间调整，所有标题的上下按钮只允许在同一父级下移动。

**Tech Stack:** Vue 3、TypeScript、Node `node:test`、Vite、现有 Element Plus/原生按钮与 Drag and Drop API。

## Global Constraints

- 保持 `OutlineRow { id, title, level, nodeKeys }` 数据结构和现有后端接口不变。
- 目录顺序由 `OutlineRow[]` 顺序决定，节点归属和章节内节点顺序由 `nodeKeys` 决定。
- 移动标题必须移动完整目录子树，不能只交换标题行。
- 一级标题拖拽只能在一级标题之间进行；二级及以下标题不支持拖拽。
- 上移/下移只能在同一父级下交换相邻同级目录块，不能跨父级。
- 移动操作返回新数组或等价的不可变结果，不能修改调用方传入的原数组。
- 第一项和最后一项的对应方向按钮必须禁用；跨父级或不存在同级目标时也必须禁用。
- 不改变标题文本、层级、节点配置、章节内节点顺序和已有删除/新增行为。
- 先写纯函数失败测试，再接入编辑器，最后运行完整前端校验。

---

### Task 1: 目录块移动纯函数与测试

**Files:**
- Create: `frontend/src/utils/outlineRows.js`
- Modify: `frontend/src/utils/reportOutline.ts`
- Create: `frontend/src/utils/reportOutline.test.js`

**Interfaces:**

- `getOutlineSubtreeRange(rows: OutlineRow[], rowIndex: number): { start: number; end: number }`：返回目标行及其连续子树范围。
- `canMoveOutlineRow(rows: OutlineRow[], rowId: string, direction: -1 | 1): boolean`：判断相邻同级目录块是否存在且同父级。
- `moveOutlineRow(rows: OutlineRow[], rowId: string, direction: -1 | 1): OutlineRow[]`：交换相邻同级完整目录块。
- `moveTopLevelOutlineRow(rows: OutlineRow[], rowId: string, targetRowId: string): OutlineRow[]`：将一级目录完整块移到另一个一级目录块之前。
- `insertOutlineRow(rows, afterId, row, options)` 保持现有调用约定，并确保新行的 `nodeKeys` 独立。

**Steps:**

- [ ] 写失败测试：覆盖一级完整块移动、二级同父级移动、跨父级禁止、三级子树跟随、一级拖拽目标校验、边界按钮判定、输入不可变和插入数组独立性。
- [ ] 运行 `node --test frontend/src/utils/reportOutline.test.js`，确认因函数不存在而失败。
- [ ] 实现子树范围、父级判定、同级移动和一级拖拽移动，并复制行对象及 `nodeKeys`。
- [ ] 在 `reportOutline.ts` 重新导出新增函数，保持 Vue 组件从 TypeScript 模块引用。
- [ ] 重新运行 `node --test frontend/src/utils/reportOutline.test.js`，确认全部通过。
- [ ] 提交：`git add frontend/src/utils/outlineRows.js frontend/src/utils/reportOutline.ts frontend/src/utils/reportOutline.test.js; git commit -m "feat: add outline subtree ordering helpers"`。

### Task 2: 接入标题上下调整按钮

**Files:**
- Modify: `frontend/src/components/SkillFlowEditorDrawer.vue`

**Steps:**

- [ ] 导入 `canMoveOutlineRow`、`moveOutlineRow`、`moveTopLevelOutlineRow`，增加 `moveOutlineChapter(row, direction)`。
- [ ] 在每个标题操作区增加“↑ 上移”和“↓ 下移”，用 `canMoveOutlineRow` 控制禁用状态。
- [ ] 仅给一级标题设置 `draggable`，记录源一级标题和目标一级标题；拖过二级及以下标题时忽略。
- [ ] 释放时调用 `moveTopLevelOutlineRow`，结束后清理拖拽状态，不影响节点卡片拖拽。
- [ ] 移动成功后调用现有 `syncNodesToOutline()`，保留新增和删除行为。
- [ ] 运行 `npm --prefix frontend run lint` 和 `npm --prefix frontend run build`。
- [ ] 提交：`git add frontend/src/components/SkillFlowEditorDrawer.vue; git commit -m "feat: add long task outline reorder controls"`。

### Task 3: 兼容入口与回归验证

**Files:**
- Modify only if required: `frontend/src/pages/ReportProcessPage.vue`
- Modify only if required: `frontend/src/components/SkillFlowEditorDrawer.vue`
- Test only if required: `frontend/src/utils/reportOutline.test.js`

**Steps:**

- [ ] 验证旧流程生成的“未配置章节”仍是一级标题且可参与排序。
- [ ] 验证新增同级、添加子级、删除带子标题目录块后，节点绑定行为不变。
- [ ] 运行 `node --test frontend/src/utils/*.test.js`、`npm --prefix frontend run lint`、`npm --prefix frontend run build`。
- [ ] 手动验证一级拖拽、子树跟随、同级按钮边界、保存后重新打开和节点归属。
- [ ] 检查最终 diff 只包含目录工具、长任务编辑器及必要测试文件。

## 验收标准

- 一级标题可拖拽到其他一级标题前后，结果按完整目录块移动。
- 二级及以下标题不能拖拽。
- 所有层级标题都有上移和下移按钮，且不会跨父级移动。
- 目录移动不会造成节点错挂、重复绑定或章节内节点顺序改变。
- 新增、删除、保存、重新打开和旧数据兼容行为正常。
- 纯函数测试、前端 lint 和生产构建全部通过。
