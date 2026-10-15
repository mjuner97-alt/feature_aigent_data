# 报告流程复用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 抽取可复用的“报告流程”资源，支持个人创建、复制、编辑，并让 Skill、长任务流程和独立任务保存报告流程快照。

**Architecture:** 新增报告流程定义表及节点/大纲 JSON 存储，复用现有 Skill Flow 的节点和大纲 DTO/校验逻辑。使用方保存来源 ID、版本和快照；执行时继续读取使用方快照，通知使用执行记录的触发人。

**Tech Stack:** Spring Boot/MyBatis/PostgreSQL(or Gauss)；Vue 3/TypeScript；现有 Skill Flow API 和组件。

## Global Constraints

- 报告流程名称全局唯一。
- 只有创建人可以编辑、删除、启停；复制创建独立副本。
- 第一阶段选择器只展示当前用户自己的报告流程。
- 使用方保存完整报告流程快照，来源更新不影响既有配置。
- 不改变现有普通 Skill、script_exec 和长任务执行行为。

### Task 1: 报告流程持久化模型与查询

**Files:**
- Create: `src/main/resources/db/migration/gauss/V20261009.1__report_process.sql`
- Create/modify: `src/main/java/com/agentscopea2a/v2/skillManager/entity/ReportProcess.java`
- Create/modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/ReportProcessMapper.java`
- Create: `src/main/resources/mybatis/mapper/gauss/ReportProcessMapper.xml`

- [ ] 建立报告流程主表，保存名称、编码、描述、节点 JSON、大纲 JSON、版本、创建人、复制来源和启用状态。
- [ ] 增加名称唯一约束和按创建人/启用状态查询。
- [ ] 增加创建、更新、复制、软删除和详情查询。

### Task 2: 报告流程服务与接口

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/ReportProcessService.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/controller/ReportProcessController.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/ReportProcessDto.java`

- [ ] 复用现有节点和大纲校验规则。
- [ ] 实现 owner 编辑权限和复制逻辑。
- [ ] 提供列表、详情、创建、更新、复制、删除、启停接口。

### Task 3: 使用方快照字段与接口适配

**Files:**
- Modify: `skill_flow` migration/entity/dto/mapper/service。
- Modify: `skill_job` migration/entity/dto/mapper/service。
- Modify: Skill 保存模型及相关 DTO。

- [ ] 增加来源报告流程 ID、版本和快照字段。
- [ ] 保存时写入完整快照。
- [ ] 执行侧优先使用快照，旧数据保持兼容。

### Task 4: 前端报告流程管理与选择

**Files:**
- Create: `frontend/src/api/reportProcess.ts`
- Create: `frontend/src/types/reportProcess.ts`
- Create: `frontend/src/pages/ReportProcessPage.vue`
- Create: `frontend/src/components/ReportProcessEditorDrawer.vue`
- Modify: Skill、长任务流程和独立任务表单。

- [ ] 完成列表、复制、编辑、启停和删除。
- [ ] 在三个使用方表单中增加“我的报告流程”选择器。
- [ ] 引入后允许继续修改当前副本。

### Task 5: 验证

- [ ] 运行后端编译与相关测试。
- [ ] 运行前端类型检查/构建。
- [ ] 验证普通长任务和 `flowTask:` 路径未受影响。
