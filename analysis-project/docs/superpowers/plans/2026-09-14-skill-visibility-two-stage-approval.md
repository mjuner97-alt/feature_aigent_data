# Skill Visibility Two-Stage Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge Skill creation/configuration into one atomic workflow in which PERSONAL changes apply immediately while PRIVATE/PUBLIC complete all dimension reviews and one developer final review before the full candidate configuration becomes effective.

**Architecture:** Add an immutable request snapshot, one row per review dimension, and append-only restricted audit records alongside the legacy approval tables. A dedicated review service owns all state transitions and the single transaction that replaces formal Skill content, attachments, routing metadata, tool bindings, grants, and publish targets; ordinary DTOs are redacted by construction. Existing `X-User-Id` is temporarily treated as the authenticated identity by explicit product decision, behind an identity-provider abstraction so real authentication can replace it later.

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis, openGauss/Flyway SQL, Jackson, JUnit 5, Vue 3, TypeScript, Element Plus, Vite.

## Global Constraints

- `PERSONAL` saves and modifications take effect directly without approval.
- `PRIVATE` and `PUBLIC` require every target dimension to pass initial review, followed by developer final review.
- Multiple administrators for one dimension are OR-reviewers: the first successful decision closes that dimension.
- An effective non-personal Skill keeps serving its old version while a complete immutable candidate snapshot is reviewed.
- Any content, attachment, tool reference, category, business/topic/metric/dimension tag, visibility, or target change is reviewed as one snapshot; name and description are included too.
- One rejected dimension rejects the request and cancels all remaining dimension tasks.
- Final approval atomically switches Skill content, attachments, labels, tool references, visibility grants, and publish targets; any failure rolls back all changes.
- State transitions use an expected-old-state predicate and require exactly one updated row.
- Final review fails closed when `skill_final_reviewer_user_ids` is absent, malformed, or empty.
- A final reviewer cannot be the submitter or any actual dimension reviewer for that request.
- Ordinary pending/detail/history/export/error responses never expose reviewer accounts or the whitelist; real actors are only available through restricted audit.
- Legacy `skill_publish`, `skill_approval`, and operation history remain intact and existing approved visibility is not reduced.
- The current `X-User-Id` request header is temporarily treated as authenticated identity; real SSO/session/JWT integration is deferred and must be replaceable through `AuthenticatedUserProvider`.
- No version column is introduced; final activation must lock the formal Skill row and compare the complete persisted configuration hash with the approved snapshot hash.

---

### Task 1: Review schema and persistence model

**Files:**
- Create: `src/main/resources/db/migration/gauss/V20260914.1__skill_two_stage_review.sql`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/SkillTwoStageReviewMigrationTest.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillReviewRequest.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillReviewDimension.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillReviewAudit.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillReviewMapper.java`
- Create: `src/main/resources/mybatis/mapper/gauss/SkillReviewMapper.xml`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/entity/Skill.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillMapper.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillMapper.xml`

**Interfaces:**
- Produces: request states `DRAFT|DIMENSION_REVIEW|FINAL_REVIEW|APPROVED|REJECTED|WITHDRAWN`; dimension states `PENDING|APPROVED|REJECTED|CANCELLED`; conditional mapper updates returning affected-row counts and full-configuration hash checks.

- [ ] **Step 1: Write migration tests that require the three new tables, unique request-target constraint, state indexes, snapshot/hash columns, and idempotent empty whitelist seed.**

```java
assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS skill_review_request"));
assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS skill_review_dimension"));
assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS skill_review_audit"));
assertTrue(sql.contains("UNIQUE (request_id, target_type, target_id)"));
assertTrue(sql.contains("skill_final_reviewer_user_ids"));
assertTrue(sql.contains("'[]'"));
assertTrue(sql.contains("WHERE NOT EXISTS"));
```

- [ ] **Step 2: Run `mvn -q -Dtest=SkillTwoStageReviewMigrationTest test` and verify it fails because the migration is absent.**
- [ ] **Step 3: Add the migration. Store immutable snapshot JSON and SHA-256 hash, timestamps/comments, and audit actor IDs. Preserve legacy tables and backfill no destructive data.**
- [ ] **Step 4: Add entities and mapper operations for insert/select, pending-by-identity queries, actual-reviewer lookup, atomic state transitions, cancellation, and formal version compare-and-set. Every transition SQL includes `WHERE status = #{expectedStatus}`.**
- [ ] **Step 5: Run the migration test and `mvn -q -DskipTests compile`; expect both to pass.**
- [ ] **Step 6: Commit with `feat(skill-review): add two-stage review persistence model`.**

### Task 2: Identity abstraction and fail-closed reviewer configuration

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/auth/AuthenticatedUserProvider.java`
- Create: `src/main/java/com/agentscopea2a/v2/auth/HeaderAuthenticatedUserProvider.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillFinalReviewerConfig.java`
- Modify: `src/main/java/com/agentscopea2a/v2/config/AiChatRuntimeConfigKeys.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/service/SkillFinalReviewerConfigTest.java`

**Interfaces:**
- Produces: `String AuthenticatedUserProvider.requireUserId(HttpServletRequest request)` and `Set<String> SkillFinalReviewerConfig.currentReviewerIds()`.

- [ ] **Step 1: Write tests proving missing/blank identity is rejected; valid header identity is returned; missing, blank, non-array, non-string, and empty whitelist values all fail closed; a JSON string array is deduplicated and accepted.**
- [ ] **Step 2: Run `mvn -q -Dtest=SkillFinalReviewerConfigTest test`; expect failure because the classes do not exist.**
- [ ] **Step 3: Implement the provider with the temporary trusted-header decision isolated in one class. Implement direct current-value lookup through `AiChatRuntimeConfigMapper`, strict Jackson parsing, immutable sets, and warning logs that never print whitelist contents.**
- [ ] **Step 4: Run the focused test and compile; expect success.**
- [ ] **Step 5: Commit with `feat(skill-review): add reviewer identity and whitelist policy`.**

### Task 3: Immutable snapshot construction and validation

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewSubmission.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewSnapshot.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillReviewSnapshotService.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/service/SkillReviewSnapshotServiceTest.java`

**Interfaces:**
- Consumes: current formal Skill/version and submitted attachment IDs, routing tags, tool IDs, visibility, grants, and publish targets.
- Produces: canonical JSON plus lowercase SHA-256 hex; sorted/deduplicated immutable target and relation lists.

- [ ] **Step 1: Write tests for canonical ordering, duplicate removal, hash stability, invalid visibility, empty non-personal targets, missing dimension administrators, invalid attachment ownership/state, invalid tool binding, and PERSONAL direct-apply classification.**
- [ ] **Step 2: Run the focused test and verify expected failures.**
- [ ] **Step 3: Implement records with defensive copies and validation. Resolve all administrators with `SkillApproverMapper.selectByScope`; reject the entire submission before inserting anything when any target has no active administrator.**
- [ ] **Step 4: Serialize with a single configured `ObjectMapper`, hash UTF-8 canonical JSON, and never regenerate a snapshot after submission.**
- [ ] **Step 5: Run focused tests and compile; expect success.**
- [ ] **Step 6: Commit with `feat(skill-review): build immutable configuration snapshots`.**

### Task 4: Two-stage review state machine and atomic activation

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillReviewService.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillConfigurationActivator.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/exception/SkillReviewConflictException.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/exception/SkillReviewForbiddenException.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/service/SkillReviewServiceTest.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillMapper.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillMapper.xml`

**Interfaces:**
- Produces: `submit`, `approveDimension`, `rejectDimension`, `finalApprove`, `finalReject`, and `withdraw`, each taking actor identity from the provider layer rather than request bodies.

- [ ] **Step 1: Write state-machine tests for multi-dimension completion, OR administrators, self-review rejection, first-rejection cancellation, withdrawal, concurrent stale decisions, whitelist rejection, final-review separation of duties, base-version/hash recheck, and full transaction rollback.**
- [ ] **Step 2: Run `mvn -q -Dtest=SkillReviewServiceTest test`; verify failures are caused by missing behavior.**
- [ ] **Step 3: Implement submission as one transaction: reject a second active request, insert immutable request, insert all dimensions, and append `REQUEST_SUBMIT` audit. PERSONAL submissions call the activator directly and do not create approval rows.**
- [ ] **Step 4: Implement dimension decisions with authorization against all active admins, conditional updates, real actor only in audit, immediate request rejection/cancellation on any reject, and transition to `FINAL_REVIEW` only when zero non-approved dimensions remain.**
- [ ] **Step 5: Implement final decisions with current whitelist lookup and all final invariants. On approve, call the activator and append `CONFIG_EFFECTIVE` before conditionally setting `APPROVED` in the same transaction.**
- [ ] **Step 6: Implement the activator to replace formal Skill fields, file references, routing metadata, tool bindings, grants, and approved publish targets after locking the Skill row and rechecking the stored full-configuration hash. Keep retrieval-index synchronization after the database transaction succeeds.**
- [ ] **Step 7: Run focused tests, mapper tests, and compile; expect success.**
- [ ] **Step 8: Commit with `feat(skill-review): implement two-stage approval state machine`.**

### Task 5: Close direct-write bypasses and add redacted APIs

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/controller/SkillReviewController.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewRequestView.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewPendingItem.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewTimelineItem.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillReviewAuditView.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/controller/SkillManageController.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/controller/SkillFileController.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageService.java`
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/controller/SkillReviewControllerTest.java`

**Interfaces:**
- Produces the request-level endpoints from the design; bodies contain comments and candidate data but never actor IDs.

- [ ] **Step 1: Write MockMvc/service tests proving request endpoints use provider identity, reject actor fields, require comments on rejection, redact all reviewer IDs/whitelist data, and restrict raw audit. Add tests that non-personal direct update/grant/file mutations are rejected while PERSONAL changes remain direct.**
- [ ] **Step 2: Run focused tests and verify expected failures.**
- [ ] **Step 3: Add the new controller endpoints under `/api/skills/{id}/review-requests` and `/api/skill-review/**`. Map exceptions to stable messages that do not include actor or whitelist values.**
- [ ] **Step 4: Build ordinary DTOs explicitly without reviewer fields. Build restricted audit DTOs only after an audit-permission check; do not reuse persistence entities as API responses.**
- [ ] **Step 5: Guard every independent formal mutation path. For an effective non-personal Skill, instruct clients to submit a full review request; retain legacy publish/draft endpoints only for historical compatibility and prevent the new UI from calling them.**
- [ ] **Step 6: Run controller/service tests and compile; expect success.**
- [ ] **Step 7: Commit with `feat(skill-review): expose redacted review APIs and close bypasses`.**

### Task 6: Unified Skill form and request-level approval UI

**Files:**
- Modify: `frontend/src/types/skill.ts`
- Modify: `frontend/src/api/skill.ts`
- Modify: `frontend/src/pages/skill/SkillFormPage.vue`
- Modify: `frontend/src/pages/skill/SkillApprovalListPage.vue`
- Modify: `frontend/src/components/SkillFileAttachment.vue`
- Modify: `frontend/src/components/SkillGrantEditor.vue`
- Modify: `frontend/src/components/DimensionCascader.vue`
- Modify: `frontend/src/main.ts`
- Create: `frontend/src/pages/skill/SkillReviewDetailPage.vue`
- Create: `frontend/src/utils/skillReviewDiff.ts`
- Create: `frontend/src/utils/skillReviewDiff.test.ts`

**Interfaces:**
- Consumes the redacted request-level API.
- Produces one staged form submission containing the full candidate configuration and all targets; no sequential publish or immediate non-personal relation mutations.

- [ ] **Step 1: Add TypeScript tests for deterministic diff sections covering content, attachments, tools, tags, visibility/grants, and dimensions. Run the focused frontend test command configured for the repository; if no runner exists, add the smallest Vite-compatible test runner before implementation.**
- [ ] **Step 2: Add request, snapshot, pending/detail/timeline types and API functions. Ensure no ordinary frontend type contains final-reviewer IDs or whitelist data.**
- [ ] **Step 3: Refactor reusable attachment/grant components to support staged model values without server mutation. Preserve direct PERSONAL behavior.**
- [ ] **Step 4: Convert the form into the five documented stages with Save Draft and Submit Review actions, PERSONAL/non-personal guidance, active-versus-candidate diff confirmation, and withdrawal-before-edit handling. Submit exactly one request for PRIVATE/PUBLIC.**
- [ ] **Step 5: Replace the approval list with request-level dimension/final tasks and add a detail page showing full snapshot, current version, diff, dimension results, comments, and only redacted stage text. Require reject comments client-side.**
- [ ] **Step 6: Run `npm run lint` and `npm run build` from `frontend`; expect success except existing documented bundle-size warnings.**
- [ ] **Step 7: Commit with `feat(skill-review): unify configuration and approval UI`.**

### Task 7: Compatibility, migration verification, and end-to-end acceptance

**Files:**
- Create: `src/test/java/com/agentscopea2a/v2/skillManager/SkillReviewAcceptanceTest.java`
- Modify: `src/main/resources/db/migration/gauss/V20260914.1__skill_two_stage_review.sql`
- Modify: `docs/superpowers/specs/2026-09-14-skill-visibility-two-stage-approval-design.md` only if implementation-specific clarifications are required; do not rewrite requirements.

**Interfaces:**
- Verifies all public behavior and legacy preservation as one release gate.

- [ ] **Step 1: Add acceptance tests for all 13 acceptance criteria, including rollback fault injection, duplicate concurrent decisions, no-admin atomic failure, malformed whitelist, stale formal version, invalid attachments/tools at activation, and response-string scanning for reviewer identities.**
- [ ] **Step 2: Add migration verification SQL/tests that compare legacy approved ranges with the new formal range and assert a zero-difference count without deleting or rewriting legacy approval rows.**
- [ ] **Step 3: Run focused acceptance and migration tests; fix only failures introduced by this feature using test-first regression cases.**
- [ ] **Step 4: Run full verification: `mvn test`, `mvn -q -DskipTests package`, `npm run lint`, and `npm run build`. Record exact pass/fail counts and existing warnings.**
- [ ] **Step 5: Inspect `git diff --check`, migration ordering, API response DTOs, and the complete branch diff. Confirm generated frontend assets are either deliberately included by repository convention or restored to their pre-task state.**
- [ ] **Step 6: Commit with `test(skill-review): verify compatibility and two-stage approval`.**

## Deferred Security Work

The current product decision temporarily treats `X-User-Id` as trusted. This does **not** satisfy cryptographic authentication by itself. A later change must replace `HeaderAuthenticatedUserProvider` with the organization SSO/gateway/JWT principal without changing review service signatures, and final-review deployment should remain behind the trusted network boundary until then.
