# Skill Job Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让长任务支持从人员清单配置收件人、自动发送使用配置、手动发送可重新选人，并完成端到端校验。

**Architecture:** 复用 `notification_config`/`notification_recipient` 保存长任务配置，复用 `skill_job_notification` 记录每次 INITIAL/RESEND 投递；不修改流程通知链路。前端复用现有人员搜索组件的接口和后端 `NotificationRecipientService` 的人员校验。

**Tech Stack:** Spring Boot、MyBatis、PostgreSQL/Gauss migration、Vue 3、TypeScript、Maven、Vitest/项目现有测试框架。

## Global Constraints

- 收件人只能来自人员清单，后端校验是最终权威。
- 收件人最多 50 人，去重后保存。
- 空名单继续表示发送给创建人，兼容旧客户端。
- 手动发送只发送已有报告，不重新执行长任务。
- 不新增通知表，不修改 `skill_flow_notification`。

---

### Task 1: 完善长任务配置接口

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillJobCreateRequest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillJobUpdateRequest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillJobService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/controller/SkillJobController.java` only if request mapping needs adjustment
- Test: existing `src/test/java/com/agentscopea2a/v2/skillManager` service/controller tests, or add the nearest focused test class

**Interfaces:**
- Consume `List<String> notifyReceivers` from create/update requests.
- Produce the persisted `SKILL_JOB` recipient configuration through `NotificationRecipientService`.

- [ ] **Step 1: Write failing tests** covering create with valid recipients, update replacement, duplicate removal, invalid personnel rejection/filtering according to existing service contract, and empty-list fallback.
- [ ] **Step 2: Run the focused Maven test and verify it fails because request DTOs/service do not carry the field.**
- [ ] **Step 3: Add nullable `notifyReceivers` to create/update request records and thread it through `SkillJobService.create/update` after the job id exists.
- [ ] **Step 4: Reuse `NotificationRecipientService.replaceRecipients("SKILL_JOB", jobId, "DEFAULT", receivers, userId)`; keep existing `notify-settings` endpoints compatible and avoid writing a second persistence path.
- [ ] **Step 5: Run the focused tests and verify the configuration is saved and returned through the existing settings endpoint.

### Task 2: Verify automatic delivery recipient resolution

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillJobService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/scheduler/SkillJobScheduler.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/notification/NotificationService.java` only where recipient resolution is currently duplicated
- Test: focused scheduler/service notification tests

**Interfaces:**
- Consume `NotificationRecipientService.findConfiguredUserIds("SKILL_JOB", jobId, triggerType)`.
- Produce one `skill_job_notification` INITIAL row per automatic delivery with recipient snapshot and final status.

- [ ] **Step 1: Add failing tests for configured recipients, empty configured recipients, legacy field fallback, and automatic notification record creation.
- [ ] **Step 2: Run the focused tests and verify the configured-recipient cases fail or expose the current fallback behavior.
- [ ] **Step 3: Make automatic delivery read the generic recipient configuration first, preserving the existing distinction between absent configuration and intentionally empty configuration.
- [ ] **Step 4: Keep creator fallback only for an empty effective recipient list, and ensure the persisted `recipientSummary` reflects the actual snapshot used for delivery.
- [ ] **Step 5: Run the focused notification/scheduler tests and inspect the resulting status transitions for SUCCESS, FAILED, and SKIPPED.

### Task 3: Enforce manual send validation and recipient selection

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillJobService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/controller/SkillJobController.java` if error mapping or request validation needs tightening
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/dto/ManualNotificationSendRequest.java` only if a stronger request shape is needed
- Test: focused resend/service tests

**Interfaces:**
- Consume `POST /api/skill-jobs/executions/{execId}/notifications/resend` with `confirmed=true` and `notifyReceivers` user IDs.
- Produce a new `RESEND` notification record without creating a new execution.

- [ ] **Step 1: Add failing tests for missing confirmation, empty recipients, invalid recipients, missing report, unauthorized execution, and successful resend.
- [ ] **Step 2: Run focused tests and verify the missing validations are exposed.
- [ ] **Step 3: Validate supplied manual recipients through `NotificationRecipientService`/personnel lookup, deduplicate them, and reject a request that has no valid recipient after validation.
- [ ] **Step 4: Preserve existing permission and report-availability checks; ensure resend creates only `skill_job_notification` and never invokes the execution queue.
- [ ] **Step 5: Run focused tests and verify each rejected request leaves no orphaned pending notification record.

### Task 4: Restore long-task frontend recipient configuration

**Files:**
- Modify: `frontend/src/types/skillJob.ts`
- Modify: `frontend/src/api/skillJob.ts`
- Modify: `frontend/src/components/SkillJobFormDrawer.vue`
- Modify: parent long-task page/component that opens `SkillJobFormDrawer.vue`
- Reuse: `frontend/src/components/NotificationSettingsDrawer.vue` personnel search patterns
- Test: existing frontend component/API tests, or add focused tests beside the current frontend test setup

**Interfaces:**
- Send `notifyReceivers: string[]` in create/update requests.
- Display selected personnel by name while retaining user IDs for persistence.

- [ ] **Step 1: Add failing component/API tests for create payload, edit initialization, personnel search selection, removal, duplicate prevention, and 50-person limit.
- [ ] **Step 2: Run focused frontend tests and verify the hidden/unfinished controls are absent or fail the expected assertions.
- [ ] **Step 3: Add the field to `SkillJob`, `SkillJobInput`, and `SkillJobUpdateInput`, then wire it into form load/save payloads.
- [ ] **Step 4: Reuse the existing personnel lookup endpoint and display chips/table consistent with `NotificationSettingsDrawer.vue`; do not accept free-form IDs as the primary UI path.
- [ ] **Step 5: Run frontend type-check/build and focused tests.

### Task 5: Restore notification-record send action UI

**Files:**
- Modify: `frontend/src/components/SkillJobNotificationDrawer.vue`
- Modify: execution list/detail component that opens the drawer, if needed
- Modify: `frontend/src/api/skillJob.ts` only if request/error mapping needs adjustment
- Test: focused notification drawer tests

**Interfaces:**
- Consume `listExecutionNotifications(execId)` and `resendExecutionNotification(execId, notifyReceivers)`.
- Produce a visible “发送/通知” action with personnel selection and refresh the record list after acceptance.

- [ ] **Step 1: Add failing tests for opening the personnel selector, requiring at least one selected person, submitting confirmed IDs, showing pending/success/failure states, and refreshing records.
- [ ] **Step 2: Run focused tests and verify the current drawer lacks the required selection flow.
- [ ] **Step 3: Replace free-form recipient input with the personnel selector pattern, while keeping previously returned recipient names visible in historical records.
- [ ] **Step 4: Disable duplicate submissions while the resend request is pending and map backend validation errors to user-facing messages.
- [ ] **Step 5: Run focused tests and frontend build/type-check.

### Task 6: End-to-end verification and documentation

**Files:**
- Test: backend focused suite and frontend focused suite
- Verify: `docs/superpowers/specs/2026-09-23-skill-job-notification-design.md`
- Verify: `docs/superpowers/plans/2026-09-23-skill-job-notification.md`

- [ ] **Step 1: Run backend tests covering create/edit, scheduled delivery, manual resend, permissions, personnel validation, and notification status persistence.
- [ ] **Step 2: Run frontend tests plus type-check/build.
- [ ] **Step 3: Exercise the API contract manually with one configured recipient, an empty configuration, and a manual resend to a different personnel-list user.
- [ ] **Step 4: Confirm no changes were made to `skill_flow_notification` and no new notification table was introduced.
- [ ] **Step 5: Record any unrelated pre-existing failures separately; do not alter unrelated behavior.
