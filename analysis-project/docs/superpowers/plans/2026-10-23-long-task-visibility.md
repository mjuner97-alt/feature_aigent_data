# 长任务可见范围 Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with verification checkpoints.

**Goal:** Make long-task definitions visible to authorized executors in “我的”, while execution records in “我的” remain limited to records triggered by the current user.

**Architecture:** Keep scope enforcement in backend SQL/service parameters. Reuse the existing `skill_flow_executor` relation for definition visibility and `trigger_user_id` for execution visibility. Keep editor authorization and owner-only mutation checks unchanged.

**Tech Stack:** Spring Boot, MyBatis XML, Vue 3 + TypeScript, Maven, Vitest/Node tests.

## Global Constraints

- `mine` definitions use owner-or-authorized-executor semantics.
- `mine` executions use trigger-user semantics.
- `all` removes the user scope predicate.
- `createdBy` remains an explicit exact user filter.

---

### Task 1: Normalize backend scope queries

**Files:**
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowQueryService.java`
- Test: existing mapper/service tests under `src/test/java/com/agentscopea2a/v2/skillManager`

- [ ] Verify both paged and legacy definition queries pass the owner user only for `mine`, and both use the owner-or-executor predicate.
- [ ] Verify execution queries pass the current user only for `mine`, and use `trigger_user_id` consistently.
- [ ] Preserve `createdBy` as an independent exact filter.
- [ ] Add or update tests for owner, executor, unrelated user, and trigger-user cases.

### Task 2: Verify editor authorization behavior

**Files:**
- Modify only if needed: `frontend/src/components/SkillFlowEditorDrawer.vue`, `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- Test: `frontend/src/components/SkillFlowExecutors.test.js`, related backend tests

- [ ] Confirm the editor sends the `executors` list.
- [ ] Confirm save replaces executor rows atomically and rejects non-owner mutation.
- [ ] Add regression coverage that an authorized executor can see/run but cannot edit/delete.

### Task 3: Verify frontend scope propagation

**Files:**
- Modify: `frontend/src/components/SkillFlowList.vue`
- Modify: `frontend/src/components/SkillFlowExecutionList.vue`
- Test: related component tests

- [ ] Ensure scope changes reset pagination and reload the current list.
- [ ] Ensure refresh, polling, page-size, and pagination retain the active scope.
- [ ] Add tests that assert `scope=mine/all` is sent and data is rendered without client-side re-filtering.

### Task 4: Run verification

- [ ] Run targeted backend tests with Maven.
- [ ] Run frontend component tests and type/build checks.
- [ ] Inspect the final diff for accidental permission changes.
