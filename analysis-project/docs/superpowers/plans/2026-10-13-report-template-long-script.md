# Report template long script implementation plan

**Goal:** Reuse persisted flow executions for template scripts longer than five minutes and asynchronously notify the person submitting the task. Chat only shows a submission notice.

**Architecture:** Keep scriptExec(scriptId, params) unchanged. The backend copies the toolkit for each execution and registers an immutable ScriptExecTool bound to that execution's user and session. Never mutate identity on the shared bean. If no session exists, generate an execution identifier. Ordinary synchronous scripts retain a 300-second cap. Flow workers use registered timeouts capped at the existing 30-minute node budget, below the 35-minute lease. No chat task cards or result polling are required.

**Tech stack:** Java 17, Spring, MyBatis, JUnit/Mockito, Vue 3.

- [x] Add a separate flow script execution adapter; route coordinator through it and allow registry UI timeouts up to 1800 seconds. Keep debug/synchronous caps at 300 seconds. A real 305-second local Python process returned complete output successfully.
- [x] Bind identity when creating the execution toolkit; preserve the two-argument method and tool schema. Add tests for missing identity, isolated users, missing session fallback and framework invocation without RuntimeContext.
- [x] Return only the template name and asynchronous notification notice. Remove chat task cards and their dedicated conversation query endpoint.
- [x] Run final targeted backend tests (14 passed, including actual toolkit invocation with no RuntimeContext argument) and frontend build; inspect the diff. Runtime mail delivery and deployment remain outside local validation.

Existing working-tree changes are preserved. No deployment, real notification sending or database mutation is part of validation.
