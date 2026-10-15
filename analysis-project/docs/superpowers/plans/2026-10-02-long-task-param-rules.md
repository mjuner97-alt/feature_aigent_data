# 长任务参数规则兼容 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有直接填值的前提下，为长任务参数增加少量预设规则，并根据参数的单值/多值类型校验规则结果，避免自动生成数组传给单值参数导致执行失败。

**Architecture:** 保留现有参数值格式作为兼容路径；新增一个轻量的规则描述对象和预设规则目录。规则解析只负责把预设表达式展开成一个或多个完整业务表达式，例如 `近三个月份版本` 展开为多个 `2026年X月份版本`；参数类型校验负责在提交长任务前检查单值/多值契约。前端根据参数类型过滤可选规则，后端在最终执行前再次校验。

**Tech Stack:** Vue 3 + TypeScript（frontend）、现有 Java/Spring Boot API、项目已有 Vitest/Jest 风格前端测试与 Maven 测试。

## Global Constraints

- 现有直接填入的固定值必须继续可用。
- 用户不手写规则表达式，只能从预设规则下拉选择。
- 用户侧展示完整业务表达式，如 `2026年4月份版本`、`近三个月份版本`。
- 单值参数不能静默接收多个结果；多值参数允许接收一个或多个结果。
- 单个固定值传给多值参数时允许规范化为单元素数组。
- 第一版只实现预设规则，不引入通用脚本或自由表达式编辑器。

---

### Task 1: 梳理现有长任务参数契约并补充类型模型

**Files:**
- Inspect and modify: `frontend/src/utils/scriptParams.ts`
- Inspect and modify: `frontend/src/utils/scheduleRules.ts`
- Inspect and modify: `frontend/src/types/skillJob.ts`
- Inspect and modify: `frontend/src/types/skillFlow.ts`
- Test: `frontend/src/utils/scriptParams.test.ts`
- Test: `frontend/src/utils/scheduleRules.test.ts`

**Interfaces:**
- Produces `ParamCardinality = 'SINGLE' | 'MULTI'`。
- Produces `ParamValueMode = 'LITERAL' | 'PRESET_RULE'`。
- Produces a serializable preset rule shape containing `ruleId`, `label`, `cardinality`, and evaluation inputs.

- [ ] **Step 1: 读取现有参数序列化和调度规则测试，确认现有请求字段与默认行为。**
- [ ] **Step 2: 为参数增加单值/多值和固定值/预设规则的类型定义，默认旧数据解释为 `LITERAL + SINGLE`。**
- [ ] **Step 3: 编写类型归一化测试，覆盖字符串、字符串数组、空值和旧版缺失字段。**
- [ ] **Step 4: 运行 `npm test -- --runInBand`（在 `frontend` 目录）确认新增测试初始失败。**
- [ ] **Step 5: 实现最小归一化函数，使旧值保持原样、多值固定值转换为字符串数组。**
- [ ] **Step 6: 运行参数工具测试并提交：`feat: define long task parameter value contracts`。**

### Task 2: 实现有限的预设规则目录和展开器

**Files:**
- Modify: `frontend/src/utils/scheduleRules.ts`
- Modify: `frontend/src/utils/scriptParams.ts`
- Test: `frontend/src/utils/scheduleRules.test.ts`

**Interfaces:**
- `listPresetRules(cardinality)` 返回当前参数类型可用的规则选项。
- `evaluatePresetRule(ruleId, baseDate)` 返回完整业务表达式数组。
- `validateRuleResult(cardinality, values)` 返回可展示的类型错误或规范化结果。

- [ ] **Step 1: 编写规则展开测试，固定基准为 `2026-07`，覆盖当前月份版本、上/下个月份版本、近三个月份版本、当前/上/下季度、近三个季度和近半年。**
- [ ] **Step 2: 编写跨年测试，例如 `2026-01` 向前取三个月得到 `2025年11月` 至 `2026年1月`，并验证季度跨年。**
- [ ] **Step 3: 编写单值规则结果测试，确保单值规则只返回一个字符串；多值规则返回字符串数组。**
- [ ] **Step 4: 实现固定规则目录，不开放用户输入规则文本。**
- [ ] **Step 5: 实现月份、季度、半年计算和完整表达式格式化，保留现有中文格式。**
- [ ] **Step 6: 实现结果类型校验：单值收到多个结果时报错，多值收到单个字符串时包装成单元素数组。**
- [ ] **Step 7: 运行规则测试并提交：`feat: evaluate preset period rules`。**

### Task 3: 在长任务表单中接入下拉选择

**Files:**
- Modify: `frontend/src/pages/skill/SkillJobFormPage.vue`
- Modify: `frontend/src/pages/skill/SkillFlowFormPage.vue`
- Modify: `frontend/src/utils/scriptParams.ts`
- Test: `frontend/src/pages/skill/SkillJobFormPage.test.ts`

**Interfaces:**
- 表单继续支持原有固定值输入。
- 新增输入方式下拉：`直接填写`、`选择预设规则`。
- 规则下拉根据参数单值/多值类型过滤选项。

- [ ] **Step 1: 编写表单行为测试，验证旧参数仍显示直接填写控件。**
- [ ] **Step 2: 编写规则选择测试，验证多值参数可以看到 `近三个月份版本`，单值参数看不到该选项。**
- [ ] **Step 3: 增加输入方式切换控件，切换为规则时只显示预设规则下拉。**
- [ ] **Step 4: 增加规则结果预览，显示生成的完整表达式列表，不显示内部字段名。**
- [ ] **Step 5: 保留用户固定值切换路径，并在切换回固定值时不改变原有输入格式。**
- [ ] **Step 6: 运行前端表单测试和类型检查并提交：`feat: add preset rule selection to long task forms`。**

### Task 4: 接入长任务提交请求和后端兜底校验

**Files:**
- Inspect and modify: `frontend/src/api/skillJob.ts`
- Inspect and modify: `frontend/src/api/skillFlow.ts`
- Locate and modify: existing backend long-task request DTO/service used by `skillJob` and `skillFlow` APIs
- Test: existing backend controller/service test location for long-task submission

**Interfaces:**
- 请求同时支持旧的固定值字段和新增的规则字段。
- 后端收到规则时按基准时间展开；收到固定值时沿用旧逻辑。
- 后端统一执行单值/多值校验，错误返回稳定的参数错误码和字段信息。

- [ ] **Step 1: 为前端 API 请求编写兼容性测试，确认固定字符串请求 JSON 不变。**
- [ ] **Step 2: 为规则请求增加最小字段映射测试，确认规则 ID、基准时间和参数类型能传到后端。**
- [ ] **Step 3: 在后端 DTO 增加可选规则字段，缺失时走旧固定值路径。**
- [ ] **Step 4: 在服务层复用规则展开和类型校验契约，禁止多值结果写入单值参数。**
- [ ] **Step 5: 增加错误响应：参数名、期望类型、实际结果数量和修复提示。**
- [ ] **Step 6: 运行相关 Maven 测试和前端 API 测试并提交：`feat: validate long task rule payloads`。**

### Task 5: 联调和回归验证

**Files:**
- Modify if needed: `frontend/src/utils/*.test.ts`
- Modify if needed: existing long-task integration test files
- Create: `docs/superpowers/specs/long-task-parameter-rules.md`（记录最终规则目录和兼容约束）

- [ ] **Step 1: 验证旧单值固定输入可以成功提交。**
- [ ] **Step 2: 验证旧多值固定输入可以成功提交。**
- [ ] **Step 3: 验证多值参数选择 `近三个月份版本` 后生成数组。**
- [ ] **Step 4: 验证单值参数无法选择多值规则，并验证后端直接收到非法请求时返回参数错误。**
- [ ] **Step 5: 验证月份和季度跨年结果。**
- [ ] **Step 6: 运行前端完整测试、后端相关测试和构建。**
- [ ] **Step 7: 更新规则说明并提交：`test: verify long task parameter rule compatibility`。**

## Verification Checklist

- 固定表达式 `2026年4月份版本` 的旧请求格式不变。
- 规则只通过下拉选择，不存在自由文本规则输入框。
- `近三个月份版本` 只产生数组，不会传入单值参数。
- 单值固定值传给多值参数时规范化为单元素数组。
- 前端过滤和后端校验使用同一套规则 ID 与单值/多值契约。
- 规则展开结果在月份跨年、季度跨年时保持正确。
