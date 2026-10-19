# Manual flow mail recipients implementation plan

**Goal:** Manual long-task notifications initially show the latest saved ordered TO/CC groups, allow temporary edits using the editor's recipient UI, and send exactly the confirmed valid recipients.

**Architecture:** Extend MailRecipientDialog with a send mode; keep editor confirmation behavior unchanged. The notification wrapper loads current notification settings from an execution-owned endpoint, then mounts the shared picker only after defaults load. The endpoint checks execution ownership and resolves the saved flow configuration using the trusted flow owner internally. Pass explicit TO/CC lists through the existing resend API and reject empty/invalid TO rather than falling back. Existing callers without an explicit manual list retain legacy automatic behavior.

**Tech Stack:** Vue 3, node:test memory renderer, Spring Java records/services, JUnit Mockito.

## Constraints
- Temporary changes never update saved flow settings.
- Defaults use AUTO_METRIC groups with the same fallback rules as the editor, regardless of execution trigger.
- Provide group clear, clear all, restore defaults, ordered deduplication and moves, batch matching and name search.
- No send while loading/matching/sending; no silent dismissal of modified groups; display failures and permit retry.
- Reject empty TO explicitly; CC-only is not sent. A failed defaults load must not masquerade as an empty saved list.
- Guard late asynchronous responses across close, reopen, execution changes, and unmount.
- Preserve existing unrelated workspace changes; no automatic commits or external sends.

## Task 1: Backend confirmed TO/CC
- [x] Add failing tests for explicit ordered TO/CC, TO wins overlap, empty/invalid TO rejected, explicit CC empty does not reuse snapshot.
- [x] Extend ManualNotificationSendRequest with optional ccReceivers and retain two-argument compatibility constructor.
- [x] Add resend(execution, confirmedReceivers, confirmedCcReceivers); forward controller CC. Explicit lists are validated and never use automatic fallback; automatic send behavior remains.
- [x] Run focused JUnit tests including existing recipients tests.

## Task 2: Shared recipient dialog and frontend integration
- [x] Add failing behavior tests for send mode, defaults, clear/restore, batch additions, exact TO/CC request, failure and late-response protection.
- [x] Extend MailRecipientDialog using optional mode/busy/error props and one confirm event, retaining local editing logic and shared styles. Send mode emits once without closing until the wrapper reports success.
- [x] Rewrite notification wrapper to load getSkillFlowExecutionNotifySettings(id), batchUserNames(ids); preserve IDs on name lookup failure and show inline load error/retry. Use shared notification wrapper in execution detail as well as list.
- [x] Extend resendSkillFlowExecutionNotification(id, to, cc) JSON request.
- [x] Verify editing behavior, frontend API serialization, notification flows and related regression tests.

## Task 3: Review and validation
- [x] Review diffs for scope, exact-recipient behavior, async races, disabled controls and keyboard dismissal.
- [x] Run related frontend tests, focused backend tests, build, and diff whitespace checks; report material limits.

## Validation results
- Frontend: 47 related tests passed; production build passed.
- Backend: 11 focused JUnit tests passed (7 recipient service, 4 controller), via cached Surefire 3.2.5.
- Independent frontend and backend integration review approved with no actionable findings.
- Full vue-tsc still reports 62 errors in unrelated existing files; no errors reported in the changed mail components/API.
- Scoped git diff --check passed. No live email sends or commits performed.
