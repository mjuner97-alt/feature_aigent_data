# Notification template and copy defaults implementation plan

**Goal:** Load and edit saved templates in manual notifications, preserve whitespace and Tab indentation, and enable copied long tasks by default.

**Architecture:** Share a Vue mail body editor between flow editing and manual sending. Extend the execution-owned defaults response and confirmed resend payload with the template. Pass a temporary override into rendering without mutating persisted flow or execution snapshots. Encode whitespace in escaped HTML consistently on both sides.

**Tech Stack:** Vue 3, node:test memory renderer, Spring Java, JUnit/Mockito.

## Constraints
- Preserve permission checks, exact TO/CC behavior, late-response protection and legacy callers.
- Null/absent override retains execution snapshot; explicit empty text selects system default.
- Preserve nonblank template bytes including spaces, tabs and line endings in storage and requests.
- Tab inserts a literal tab at the current selection; Shift+Tab remains available to leave the field.
- No real email sends, commits, or unrelated refactoring.

## Task 1: Shared body editor and copy default
- [x] Add failing behavior tests for whitespace-safe previews, cursor insertion, Tab and enabled copy payload.
- [x] Extract NotifyBodyTemplateEditor.vue with shared render utility; replace editor's inline UI.
- [x] Preserve template on submit; set copied form.enabled=true and update helper text.
- [x] Run node --test src/components/SkillFlowCopy.test.js src/components/NotifyBodyTemplateEditor.test.js.

## Task 2: Manual defaults and send integration
- [x] Add failing tests for latest template load, temporary changes, reopen, API JSON and failure retention.
- [x] Extend execution defaults with flowName and notifyBodyTemplate; retain owner validation.
- [x] Add editor slot to MailRecipientDialog and include template changes in dismissal guard.
- [x] Extend resend API/request and service with optional template override and 2000-character validation.
- [x] Run focused frontend tests and backend integration tests.

## Task 3: Backend whitespace and verification
- [x] Add failing tests for escaped whitespace rendering and definition request preservation.
- [x] Preserve nonblank template on save and encode spaces/tabs before inserting link HTML.
- [x] Run focused Java tests, related frontend regression suite, production build and git diff --check.
- [x] Review changed code for scope, security, backwards compatibility and asynchronous races.

## Validation results
- Related frontend regression suite: 68 passed, including existing editor contact/template, recipients, grants, execution list/detail and flow API tests.
- Backend: 13 JUnit tests passed after reproducing missing template defaults and whitespace failures before the fixes.
- Production Vite build passed. Full vue-tsc retains 62 existing errors (including missing declarations for outlineRows/editorNavigation); the introduced optional-template type error was fixed.
- Review caught historical report link label mismatch; manual preview now uses the same execution-based FlowReportNaming label as actual sending.
- No live mail sends or commits. Generated frontend bundles are restored after validation to keep the source change focused.
