# 配置广场长任务配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有 `skill_flow` 长任务配置增加类型、标签、优先级、管理员管理接口、稳定顺序调度和兼容迁移。

**Architecture:** 在现有 Gauss `skill_flow` 配置链路上增量增加 `long_task_type`、`long_task_tag` 及关系表，并为 `skill_flow` 增加 `type_id`、`priority`。类型/配置/标签由独立服务负责校验和权限，自动触发查询只返回启用类型与启用配置，排序逻辑由纯 Java 调度器按类型顺序、任务优先级、创建时间和 id 稳定排序。

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, openGauss Flyway migrations, JUnit 5/Mockito.

## Global Constraints

- 仅支持一个长任务类型绑定到一个长任务配置；标签可多选，标签不参与调度顺序。
- 类型名称必填且唯一；类型排序值必须有效；被引用类型禁止删除。
- 长任务配置必须绑定启用类型；停用类型和停用配置不参与自动触发。
- 自动触发排序：类型 `sort_order ASC`，同类型任务 `priority ASC`，再按 `created_at ASC`，最后按 `id ASC` 保证稳定。
- 旧配置迁移必须幂等，不覆盖管理员后续修改。
- 沿用现有配置广场管理员写权限规则；非管理员保持只读。

### Task 1: Add schema migration and domain models

**Files:**
- Create: `src/main/resources/db/migration/gauss/V20261028.1__long_task_config_types_tags.sql`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/LongTaskType.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/LongTaskTag.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/LongTaskScheduleItem.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillFlow.java`

**Interfaces:**
- `LongTaskType`: id, name, description, sortOrder, enabled, createdAt, updatedAt.
- `LongTaskTag`: id, name, createdAt, updatedAt.
- `LongTaskScheduleItem`: flow, type, tags; immutable record used by scheduler.
- Add `typeId`, `priority` and `createdAt` remains source of creation ordering in `SkillFlow`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/agentscopea2a/v2/skillManager/LongTaskDomainValidationTest.java` asserting type names are trimmed/nonblank and sort order is nonnegative, and the flow model carries typeId/priority.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LongTaskDomainValidationTest test`
Expected: FAIL because the new domain types and fields do not exist.

- [ ] **Step 3: Write minimal implementation**

Add Lombok entities with constructor validation; add nullable `typeId` and defaultable `priority` to `SkillFlow`. Add migration with:
`long_task_type` unique name, `long_task_tag` unique name, `skill_flow_tag` unique pair, `skill_flow.type_id`, `skill_flow.priority`, foreign keys/indexes, default “其他” type and idempotent backfill for existing flows.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=LongTaskDomainValidationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/main/resources/db/migration/gauss/V20261028.1__long_task_config_types_tags.sql src/main/java/com/agentscopea2a/v2/skillManager/entity src/test/java/com/agentscopea2a/v2/skillManager/LongTaskDomainValidationTest.java && git commit -m "feat: add long task type and tag schema"`

### Task 2: Add mapper persistence and DTOs

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/LongTaskConfigMapper.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/LongTaskTypeRequest.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/LongTaskTypeResponse.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/LongTaskTagRequest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml`

**Interfaces:**
- Mapper methods: `selectTypes(Boolean enabled)`, `selectTypeById(Long id)`, `selectTypeByName(String name)`, `insertType(LongTaskType)`, `updateType(LongTaskType)`, `updateTypeEnabled(Long,boolean)`, `deleteType(Long)`, `countFlowsByTypeId(Long)`, `updateTypeSortOrders(List<Long>)`, `selectTagsByFlowId(Long)`, `replaceFlowTags(Long,List<Long>)`.
- Flow list/detail SQL exposes `type_id`, `priority`; auto-trigger SQL joins enabled type and orders by type/priority/creation/id.
- Tag writes use unique constraints and normalize names in service.

- [ ] **Step 1: Write the failing test**

Extend `LongTaskConfigMapperTest` with SQL string assertions for type/priority columns, enabled-type auto-trigger filters, and tag relation replacement.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LongTaskConfigMapperTest test`
Expected: FAIL because mapper methods/XML are absent.

- [ ] **Step 3: Write minimal implementation**

Implement mapper interface, XML result maps, column fragments, insert/update/select statements, and DTO records. Keep existing method signatures backward-compatible by using nullable fields/default priority.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=LongTaskConfigMapperTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/main/java/com/agentscopea2a/v2/skillManager/mapper src/main/java/com/agentscopea2a/v2/skillManager/dto src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml && git commit -m "feat: persist long task types priorities and tags"`

### Task 3: Add type/tag management service and controller

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/LongTaskConfigService.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/controller/LongTaskConfigController.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/LongTaskConfigServiceTest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDefinitionRequest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDto.java`

**Interfaces:**
- REST: `GET/POST/PUT /api/long-task-types`, `PATCH /api/long-task-types/{id}/enabled`, `PUT /api/long-task-types/order`, `DELETE /api/long-task-types/{id}`; tags list/create and flow tag binding endpoints.
- Service validates admin write access using existing `AdminRoleService`; delete returns `LongTaskTypeInUse` with reference count.
- Flow create/update validates type exists and enabled, normalizes priority and tags, and preserves old records with migrated default type.

- [ ] **Step 1: Write the failing test**

Add service tests for duplicate type names, invalid sort order, deleting referenced type, disabled type binding rejection, tag normalization, and non-admin read-only behavior.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LongTaskConfigServiceTest test`
Expected: FAIL because service/controller and validation are absent.

- [ ] **Step 3: Write minimal implementation**

Implement transaction boundaries, explicit error messages, admin guards, and flow validation before calling existing definition persistence. Expose tags and type metadata in list/detail DTOs.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=LongTaskConfigServiceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/main/java/com/agentscopea2a/v2/skillManager/service src/main/java/com/agentscopea2a/v2/skillManager/controller src/main/java/com/agentscopea2a/v2/skillManager/dto src/test/java/com/agentscopea2a/v2/skillManager/LongTaskConfigServiceTest.java && git commit -m "feat: manage long task types and tags"`

### Task 4: Add deterministic scheduler and wire auto-trigger selection

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/LongTaskScheduler.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/LongTaskSchedulerTest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowExecutionService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml`

**Interfaces:**
- `List<SkillFlow> orderForAutoTrigger(List<SkillFlow> flows, Map<Long,LongTaskType> types)`.
- Scheduler filters disabled flows/types and sorts by type.sortOrder ASC, flow.priority ASC, flow.createdAt ASC, flow.id ASC.
- Existing execution/retry behavior remains unchanged; scheduler only creates ordered candidate sets.

- [ ] **Step 1: Write the failing test**

Create tests for type order, same-type priority, creation-time tie, disabled type/config skipping, and stable id tie-break.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LongTaskSchedulerTest test`
Expected: FAIL because scheduler is absent.

- [ ] **Step 3: Write minimal implementation**

Implement pure comparator/filter and update auto-trigger SQL ordering/filtering. Keep SQL ordering identical to comparator for deterministic DB and in-memory paths.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=LongTaskSchedulerTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/main/java/com/agentscopea2a/v2/skillManager/service/LongTaskScheduler.java src/test/java/com/agentscopea2a/v2/skillManager/LongTaskSchedulerTest.java src/main/java/com/agentscopea2a/v2/skillManager/service/FlowExecutionService.java src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml && git commit -m "feat: order long task auto triggers by type and priority"`

### Task 5: Verify migration compatibility and regression coverage

**Files:**
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/LongTaskMigrationTest.java`
- Modify: `src/test/java/com/agentscopea2a/v2/skillManager/SkillFlowMigrationTest.java`
- Modify: `src/main/resources/db/migration/gauss/V20261028.1__long_task_config_types_tags.sql`

- [ ] **Step 1: Write the failing test**

Assert migration contains idempotent default-type creation, only-null backfill, foreign keys, unique constraints, and no destructive updates.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LongTaskMigrationTest test`
Expected: FAIL until migration is complete.

- [ ] **Step 3: Write minimal implementation**

Finalize SQL guards and comments, then verify existing flow tests and targeted scheduler/service tests.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest='*LongTask*' test` and `mvn test -DskipTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/main/resources/db/migration/gauss/V20261028.1__long_task_config_types_tags.sql src/test/java/com/agentscopea2a/v2/skillManager && git commit -m "test: verify long task config migration compatibility"`

