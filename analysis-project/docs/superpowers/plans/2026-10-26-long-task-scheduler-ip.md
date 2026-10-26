# Long Task Scheduler IP Implementation Plan

**Goal:** Restore persisted auto-trigger state and restrict AUTO_METRIC background execution to the configured local IP; MANUAL and CHAT execution remain unrestricted.

**Architecture:** Add an independent fail-closed scheduler guard backed by AiChatRuntimeConfigMapper; block automatic creation and pass authorization into scheduler SQL to exclude AUTO_METRIC before LIMIT or UPDATE. Recheck automatic authorization at node claim time.

**Tech Stack:** Java 17, Spring, MyBatis, JUnit 5, Gauss SQL migrations.

### Task 1: Persistence regression
- [x] Add a MyBatis mapping test proving enabled and disabled values round-trip through FlowMap and flowColumns.
- [x] Run and observe failure, then add auto_trigger_enabled to both mapping and columns.

### Task 2: Machine guard
- [x] Test single local IP matching, missing/empty configuration, mismatch, read/interface failures, and live configuration changes.
- [x] Implement FlowSchedulerGuard with literal local interface address matching and fail-closed logging.
- [x] Add constant and idempotent migration with empty default; document update SQL.

### Task 3: Entry point protection
- [x] Cover startup recovery, scan, queued event dispatch and automatic creation with denied-host tests.
- [x] Inject guard and check before database task mutations or worker claims.
- [x] Run focused tests and compilation; review diff and document deployment steps.

### Task 4: Restrict only automatic execution (user-approved scope correction)
- [x] Reproduce mixed-trigger SQL failures for runnable candidates, waiting flows and startup recovery.
- [x] Scope scheduler SQL and metric readiness handling to execution trigger_type, keeping manual/chat active.
- [x] Verify claim-time automatic guard and run focused regression tests (16 tests passed; test-compile succeeded).
- [x] Review changes and update deployment documentation (independent review: no findings).
