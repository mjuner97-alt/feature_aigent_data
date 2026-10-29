# Long Task Failure Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Route failed long-task notifications to the trigger user and failed Python script creators, while preserving successful notification behavior and manual rerun semantics.

**Architecture:** Keep final status aggregation in `FlowCoordinator.finalizeFlow`. Extend `FlowCompletionService` with a failure-specific recipient and HTML renderer that uses node execution diagnostics and the existing contact snapshot. Leave the notification dispatcher as the delivery boundary so send failures remain logged and do not mutate execution status.

**Tech Stack:** Java, Spring, MyBatis mappers, JUnit 5, Mockito, Maven.

## Global Constraints

- Successful executions keep the existing configured business-recipient path and body.
- Any failed node suppresses configured business recipients for the initial failure notification.
- Failure recipients are deduplicated by user ID and always include `triggerUserId` when present.
- Missing script creators are diagnostic only and must not add business recipients.
- Failure notification errors must not change the original failed execution status.
- Existing manual node rerun and summary regeneration behavior remains available.

### Task 1: Add failure notification regression tests

**Files:**
- Create or modify: `src/test/java/com/agentscopea2a/v2/skillManager/service/FlowCompletionServiceTest.java`

- [ ] Write tests covering success recipients, single/multiple failed Python creators, duplicate creator/trigger IDs, missing creator fallback, failure body diagnostics/contact footer, dispatcher failure preserving status, and manual rerun compatibility.
- [ ] Run the focused test class and verify the new assertions fail for the missing behavior.

### Task 2: Implement failure recipient and body routing

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCompletionService.java`
- Modify only if required by compilation: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java`

- [x] Keep the existing success path unchanged.
- [x] For `FAILED` or `PARTIAL_SUCCESS`, collect trigger user plus creators of failed Python nodes, deduplicate IDs, filter invalid users, and log unresolved creators without falling back to configured business recipients.
- [x] Render a failure HTML body containing task, execution, each failed node, and error summary; existing sender appends the contact name/phone footer.
- [x] Keep send exceptions contained at the coordinator boundary and preserve execution status.
- [x] `mvn -q -DskipTests test-compile` passes.

### Task 3: Verify integration and rerun behavior

**Files:**
- Modify only if required by tests: `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCoordinator.java`
- Modify only if required by tests: `src/test/java/com/agentscopea2a/v2/skillManager/service/FlowCoordinatorTest.java`

- [ ] Verify final status aggregation still marks required failures as `FAILED`, optional failures as `PARTIAL_SUCCESS`, and all-success as `SUCCESS`.
- [ ] Verify manual node rerun remains callable after a failed execution and can lead to a new finalization.
- [ ] Run the focused suite, then the Maven test suite for the affected package.

## Verification Commands

- `mvn -q -Dtest=FlowCompletionServiceTest test`
- `mvn -q -Dtest='*Flow*Test' test`
- `mvn -q test`

