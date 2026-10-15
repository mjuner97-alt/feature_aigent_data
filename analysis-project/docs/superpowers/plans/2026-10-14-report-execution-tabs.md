# Report process execution tabs

**Goal:** Add 报告流程 / 流程报告记录 tabs inside the existing report-process page, as requested by the user.

**Architecture:** Reuse SkillFlowExecutionList and its detail drawer. Filter records before pagination using source=report-template (flow_id IS NULL AND report_process_id IS NOT NULL) or long-task (flow_id IS NOT NULL). No data migration is needed; existing execution snapshots identify their sources.

**Tech Stack:** Vue, TypeScript, Spring MVC, MyBatis.

- [x] Test report-page tab switching and source query requests (failure observed before implementation).
- [x] Add source filtering to list and count queries, preserving existing API calls without source.
- [x] Reuse the execution list inside the report page, with scope/trigger-person filters and refresh.
- [x] Keep long-task navigation unchanged and filter its list to long-task records.
- [x] Check frontend tests (37 passing) and production build; compile modified Java mapper/service/controller and run the actual MyBatis SQL and query-service verification harness. No live database/browser verification was performed.
