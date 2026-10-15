# 章节节点归属与顺序调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复新增章节后节点错误显示到下一章节的问题，并支持章节整体上移/下移而不改变章节内节点绑定和顺序。

**Architecture:** 将章节行插入与移动规则放入可测试的纯 JavaScript 工具，TypeScript 大纲工具重新导出并复用它。章节行作为包含 `nodeKeys` 的完整单元移动；编辑器只按显式 `nodeKeys` 绑定节点，不按行位置推断归属。

**Tech Stack:** Vue 3、TypeScript、Node `node:test`、Vite、ESLint。

## Global Constraints

- 保持 `OutlineRow { id, title, level, nodeKeys }` 数据结构和现有后端接口不变。
- 新章节默认同级追加；章节移动只允许同级交换，避免破坏父子层级。
- 节点归属只由 `nodeKeys` 决定，保存继续使用现有校验。
- 先写失败测试，再写实现。

---

### Task 1: 章节行操作与回归测试

**Files:**
- Create: `frontend/src/utils/outlineRows.js`
- Modify: `frontend/src/utils/reportOutline.ts`
- Create: `frontend/src/utils/reportOutline.test.js`

**Interfaces:** `insertOutlineRow(rows, afterId, row)` 与 `moveOutlineRow(rows, rowId, direction)` 均返回新数组，不修改输入行。

- [ ] 写测试：覆盖“第二章有节点后新增第三、第四章仍归属第二章”；覆盖同级章节上移/下移整行且保留 `nodeKeys`；覆盖子级不能跨父级移动。
- [ ] 运行 `node --test frontend/src/utils/reportOutline.test.js`，确认因导出函数不存在而失败。
- [ ] 在 `outlineRows.js` 实现两个纯函数：插入时复制行及 `nodeKeys`，未指定 `afterId` 时追加；移动时只交换相邻且 `level` 相同的完整行。
- [ ] 在 `reportOutline.ts` 重新导出这两个函数，保持组件从 TypeScript 模块引用。
- [ ] 重新运行上述测试，确认全部通过。
- [ ] 提交：`git add frontend/src/utils/outlineRows.js frontend/src/utils/reportOutline.ts frontend/src/utils/reportOutline.test.js; git commit -m "test: cover outline row ownership and ordering"`。

### Task 2: 接入编辑器

**Files:** `frontend/src/components/SkillFlowEditorDrawer.vue`

- [ ] 导入 `insertOutlineRow`、`moveOutlineRow`；让 `addOutlineRow` 通过纯函数插入新行，避免直接按位置重建或共享绑定数组。
- [ ] 添加 `moveOutlineChapter(row, direction)`，调用纯函数移动整行，再调用现有 `syncNodesToOutline()`；增加同级可移动判断供按钮禁用状态使用。
- [ ] 在章节标题操作区增加“↑ 上移”“↓ 下移”按钮，绑定章节移动函数；保留添加同级、添加子级、删除章节行为。
- [ ] 运行 `npm --prefix frontend run lint` 与 `npm --prefix frontend run build`，确认通过。
- [ ] 提交：`git add frontend/src/components/SkillFlowEditorDrawer.vue; git commit -m "fix: preserve chapter node ownership and reorder chapters"`。

### Task 3: 完整验证

**Files:** 无（除非验证发现具体问题）。

- [ ] 运行 `node --test frontend/src/utils/*.test.js`，确认所有工具测试通过。
- [ ] 再运行前端 lint 和生产构建，确认无新增错误。
- [ ] 手动执行：第二章添加节点，再添加第三、第四章，确认节点仍在第二章；移动第二章上下，确认整章和节点一起移动且绑定不变。
- [ ] 检查最终 diff，仅包含上述三个文件及必要测试文件。
