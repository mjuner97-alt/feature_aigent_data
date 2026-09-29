# Python 长任务编排 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有 Skill Flow 长任务页面和执行链路上增加 Python 脚本顺序编排、全量脚本选择、单节点单脚本强约束，以及可嵌套的自定义输出大纲，同时兼容旧 Skill 节点配置。

**Architecture:** 复用现有 Skill Flow 作为长任务容器；新增 Python 节点字段和 `report_outline` 模板树 JSON，旧节点继续按旧模式读取。前端从脚本注册表加载所有启用脚本，节点拖拽维护顺序；后端校验一节点只能有一个 `scriptId`，并通过现有脚本执行服务适配为顺序执行。已有的报告大纲草稿实现作为基础，但必须统一到本计划的兼容协议。

**Tech Stack:** Vue 3 + TypeScript + Element Plus；Spring Boot + MyBatis；现有 script registry/script_exec；JUnit/Vitest 或现有前端构建测试。

## Global Constraints

- 一个执行节点只能绑定一个 Python 脚本。
- 新长任务步骤按拖拽后的顺序执行，暂不支持步骤间输入引用。
- Python 脚本选择器展示所有已注册且启用的脚本，不按 Skill 所属关系过滤。
- 输出大纲使用可嵌套树结构，序号自动生成，不固定为三级。
- 旧 Skill Flow 配置必须继续可读取和执行。
- 不进行一次性数据迁移；新字段采用兼容扩展和惰性转换。
- 现有未提交的报告大纲改动视为本功能的工作区变更，执行时必须先审查并保留可复用部分，不得覆盖或重置。
- 现有旧 Skill Flow 是并行执行；只有 Python 节点模式切换为顺序执行，旧 Skill-only 流程行为不变。

---

### Task 1: 扩展前端领域模型和脚本目录 API

**Files:**
- Modify: `frontend/src/types/skillFlow.ts`
- Modify: `frontend/src/api/scriptRegistry.ts`
- Modify: `frontend/src/types/scriptRegistry.ts`

- [ ] **Step 1: Add Python node and outline types**

新增 `scriptId`, `scriptName`, `scriptParams`, `nodeType`，以及递归 `ReportOutlineItem` / `ReportOutline` 类型；保留现有 `skillId` 可选用于旧配置。参数字段名称必须与后端 `script_params_json` 和已有 Script Registry `paramsSchema` 对齐。

- [ ] **Step 2: Add all-script listing API**

新增 `listAllEnabledEntries(keyword?: string)`，调用 `/api/script-registry` 时不传 `createdBy` 和 `datasource`，前端只过滤 `enabled === 1`。

- [ ] **Step 3: Run frontend type check**

Run: `npm --prefix frontend run build`
Expected: 当前新增类型和 API 不引入 TypeScript 编译错误。

### Task 2: 改造长任务编辑器为 Python 顺序节点

**Files:**
- Modify: `frontend/src/components/SkillFlowEditorDrawer.vue`
- Modify: `frontend/src/pages/skill/SkillFlowFormPage.vue`

- [ ] **Step 1: Replace Skill option loading with all Python scripts**

选择器加载完整启用脚本列表，展示 scriptId、名称、描述和版本/更新时间；不再调用 `listSkills({ view: 'used' })` 作为节点选择来源。

- [ ] **Step 2: Enforce one script per node in the UI**

每个节点只提供一个脚本选择器和一个参数编辑区；新增节点默认没有脚本，保存校验要求恰好一个 `scriptId`。

- [ ] **Step 3: Preserve drag order and update copy**

继续复用现有拖拽排序逻辑，将说明改为“从上到下顺序执行”；保存时写入 `sortOrder`。

- [ ] **Step 4: Add outline tree editor**

新增树形大纲编辑区，支持新增同级/子级、删除、拖拽排序、编辑标题、节点类型和绑定执行节点；序号由渲染器根据层级自动生成。不得把层级硬编码为 1~3 级，至少支持当前数据和任意更深层级的递归结构。

- [ ] **Step 5: Add legacy normalization**

读取旧 `skillId` 节点时保留并以只读兼容形式显示；保存新配置时不覆盖旧字段，除非用户将节点切换为 Python 节点。

- [ ] **Step 6: Run frontend build and targeted UI tests**

Run: `npm --prefix frontend run build`
Expected: build succeeds; existing Skill Flow routes remain loadable.

### Task 3: 扩展后端 DTO、实体和持久化映射

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillFlowNode.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillFlow.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDefinitionRequest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDto.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java`
- Modify: `src/main/resources/mapper/skillManager/SkillFlowMapper.xml`
- Modify: database migration under `src/main/resources/`

- [ ] **Step 1: Add nullable compatibility columns**

Add nullable `node_type`, `script_id`, `script_params_json`, and flow-level `report_outline`; reuse the existing `report_outline` / `report_outline_snapshot` migration if present instead of adding a duplicate `template_json` column. Keep existing `skill_id`, `question_template`, and dependency columns unchanged.

- [ ] **Step 2: Map new fields through request/response DTOs**

Use nullable fields and default legacy values when absent. `templateJson` defaults to an empty template tree.

- [ ] **Step 3: Add persistence tests**

Test that a legacy node round-trips unchanged and a Python node persists exactly one `scriptId` and its parameter JSON.

- [ ] **Step 4: Run backend tests**

Run: `mvn test -DskipTests=false`
Expected: existing mapper/service tests pass plus new round-trip tests.

### Task 4: Enforce validation and implement sequential Python execution

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowExecutionService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCoordinator.java`
- Create or modify: Python flow execution adapter in `src/main/java/com/agentscopea2a/v2/skillManager/service/`
- Modify: related execution tests under `src/test/java/`

- [ ] **Step 1: Add hard validation**

Reject Python nodes with missing/blank `scriptId`, array-valued or duplicate script bindings, disabled/unregistered scripts, or malformed params JSON. Keep legacy Skill node validation unchanged.

- [ ] **Step 2: Add sequential execution path**

For flows containing Python nodes, execute nodes ordered by `sortOrder`, call the existing registered-script execution service (`ScriptExecTool` or its injectable service boundary) once per node, persist each result, then continue to the next node. Do not pass one node’s output as another node’s input.

- [ ] **Step 3: Preserve legacy execution path**

Flows containing only legacy Skill nodes continue using the existing behavior. Mixed flows are rejected with a clear validation error until an explicit compatibility rule is defined.

- [ ] **Step 4: Add execution tests**

Cover ordering, one-call-per-node, stop-on-failure behavior, disabled script rejection, and legacy flow compatibility.

### Task 5: Render custom outline templates in final aggregation

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCompletionService.java`
- Create: outline tree renderer under `src/main/java/com/agentscopea2a/v2/skillManager/service/`
- Modify: report/query DTOs if template data is exposed
- Add tests under `src/test/java/`

- [ ] **Step 1: Parse and validate template tree**

Accept arbitrary nesting depth, reject cycles/impossible JSON, and default missing templates to the existing summary behavior. Replace any existing 1~3-level clamp in `frontend/src/utils/reportOutline.ts` and `ReportOutlineComposer`.

- [ ] **Step 2: Generate automatic numbering**

Generate configured level styles (Chinese, Arabic, parenthesized, decimal) from sibling positions; never persist calculated numbers as source data.

- [ ] **Step 3: Place node results and artifacts**

Render title/text/result/table/file nodes using the completed step results without using those results as later script inputs.

- [ ] **Step 4: Test template rendering**

Cover arbitrary nesting, reordered nodes, empty sections, and legacy summary fallback.

### Task 6: Verification and compatibility review

**Files:**
- Modify: relevant docs and migration notes
- Test: frontend build, backend tests, existing Python script tests

- [ ] **Step 1: Run all targeted tests**

Run frontend build, Maven tests, and existing Python tests under `src/test/python`.

- [ ] **Step 2: Review API payloads**

Verify old Skill Flow JSON still loads and saves, while new Python workflow payloads contain only one script per node.

- [ ] **Step 3: Document compatibility behavior**

Record legacy adapter behavior, no one-shot migration, and the all-script selection rule.
