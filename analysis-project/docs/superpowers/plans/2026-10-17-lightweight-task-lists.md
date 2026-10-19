# Lightweight task list implementation plan

**Goal:** Server pagination and on-demand details for long tasks, report processes, independent scheduled jobs and execution records.

**Architecture:** Add explicit `/page` listing endpoints returning `{items,page,pageSize,total}` with purpose-built scalar summary DTOs. Preserve legacy detail and selector APIs for compatibility, but migrate management pages to paged APIs. Use bounded page sizes (default 20, max 100), deterministic ordering and matching count filters. No per-row detail hydration. Keep authorization and effective dependency semantics.

**Stack:** Java 17, Spring, MyBatis/Gauss SQL, Vue 3, Node test runner, JUnit 5.

## Contract

- Flow definitions: GET `/api/skill-flows/page`, existing enabled/keyword/createdBy/scope filters plus page/pageSize. Summaries include id/code/name/description/enabled/chatPublic/createdBy/createdAt/updatedAt/nodeCount/metricCount/canExecute. No nodes, outlines, script parameters, prompt templates or recipient lists.
- Report definitions: GET `/api/report-processes/page`, scope plus page/pageSize and filters needed by current page. Summaries include identity/status/owner/timestamps/copy-source fields and nodeCount. No nodes or outline.
- Jobs: GET `/api/skill-jobs/page`, filter/page arguments; independent execution center gets equivalent server pagination. Dedicated summaries retain current table columns, excluding prompt/body/parameter/report content.
- Shared flow execution list SQL selects explicit scalar display columns and calculates counts/durations for the page using grouped/batched data. List DTO excludes original prompts, templates and summaryJson; detail endpoint remains complete.
- PageResult<T> is a shared DTO. Existing unpaged list methods used by detail/selector callers remain compatible.

## Tasks and ownership

- [x] Flow/report backend: controllers/services/summary DTOs/mapper methods and SQL definition sections only; add failing tests for pagination, filters, no detail hydration, aggregate counts and scope. Flow SQL execution sections belong to coordinator.
- [x] Flow/report frontend: APIs/types, SkillFlowList.vue, ReportProcessPage.vue and related tests. Use summary counts; fetch full definition before edit/copy/preview. Server filters and page events; no client slice/all-record prefetch. Notify coordinator of contract needs.
- [x] Independent jobs: job service/controller/mapper/API/types plus SkillJobListPage.vue. Server paging for definitions and execution center, page-scoped batch decoration, lazy per-tab data, no hidden tab requests. Preserve edit detail loading and use a full update request only after fetching details if necessary.
- [x] Coordinator: shared PageResult, flow execution queries and DTO separation, regression coverage, whole-change integration/review and verification.

## Verification

Run focused Node tests covering pagination requests, totals, filters, on-demand detail fetch and lazy tabs. Run focused JUnit 5 tests using cached Surefire 3.2.5 with explicit Byte Buddy agent when needed. Verify mapper XML parsing; compile all Java sources/test sources; frontend production build and report relevant type-check diagnostics. No real notification sends or production database writes. Server timing is unavailable, so report query/payload improvements without invented speedup numbers.

## Verified results

- Full Java source and test-source compilation passed.
- 10 focused JUnit 5 service and MyBatis dynamic-SQL tests passed.
- 36 focused frontend tests passed; production build passed.
- Full frontend type checking still reports existing project diagnostics; normalized comparison against prior baseline found no new error messages.
- Review fixes covered actual execution-page navigation, scope reset and late-request protection.
- Deploy migrations and manual rollout instructions are documented in docs/task-list-pagination.md; no deployment DB writes or real notification sends occurred.
