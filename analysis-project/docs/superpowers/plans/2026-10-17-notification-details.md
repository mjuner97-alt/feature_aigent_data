# Notification details implementation plan

**Goal:** Inspect long-task notification requests locally without sending mail, and retain real gateway outcomes.

**Architecture:** Reuse skill_flow_notification. Persist the final request before handing delivery to a bounded executor. Store SIMULATE/REAL in channel, the exact body in request_json, and response/timing in response_json. No new table or columns are required. Existing SENT records remain readable; new outcomes are SIMULATED, ACCEPTED or FAILED. ACCEPTED never claims mailbox delivery.

**Tech stack:** Java 17, Spring, MyBatis, JUnit/Mockito, Vue 3, Node test runner.

## Tasks

- [x] Add failing backend tests for durable-before-async delivery, simulation without transport, accepted/rejected responses, duplicate keys and queue rejection. Run `mvn -o -Dtest=FlowNotificationDispatcherTest,MailGatewayResponseTest test`.
- [x] Extend NotificationSender with backward-compatible request preparation and prepared delivery; make the HTTP sender preserve raw gateway responses and propagate failures. Keep other notification call sites compatible.
- [x] Add FlowNotificationDispatcher, persist immutable final request snapshots, enqueue after transaction commit when applicable, record timing and errors, and shut down its bounded executor with Spring. Do not automatically resend ambiguous attempts after restart.
- [x] Route FlowCompletionService through the dispatcher while preserving existing recipient selection and deduplication.
- [x] Add frontend tests and a notification history/detail component to the long-task execution drawer. Show final TO/CC, title, sandboxed HTML preview, report link, mode, status, timing and response; tolerate legacy rows. Refresh pending notifications even when task execution is terminal.
- [x] Configure simulation as the default for long-task notifications, with explicit `SKILL_FLOW_NOTIFICATION_MODE=REAL` for deployment. Document configuration, failure semantics and manual verification.
- [x] Run focused Java tests, frontend tests/build/type checks; review modified files and report actual results and limitations.

## Constraints

Never store authentication headers or credentials in notification snapshots. Local simulation must not invoke mail or fallback notice channels. A failed transport or explicit gateway rejection cannot become ACCEPTED. The existing table is the durable boundary; persistence failures must prevent dispatch. Pending/sending rows after a crash remain visible for operator review; automatic recovery is outside this change.

## Verification results

- Full Java source/test compilation passed.
- 17 focused JUnit 5 tests passed using Surefire 3.2.5 and an explicitly loaded Byte Buddy agent, including temporary loopback HTTP gateway tests.
- 9 focused frontend tests passed. Production frontend build passed (existing dependency annotation/chunk-size warnings).
- Full frontend type checking reports errors in other project files; no diagnostics remain in the notification detail component, drawer or types modified here.
- Code review findings about HTTP response retention, transaction callbacks and terminal-state polling were corrected and covered by regression tests.
- No real notification was sent. No database migration is required. Git metadata is unavailable in this workspace, so changes were left as local files.
