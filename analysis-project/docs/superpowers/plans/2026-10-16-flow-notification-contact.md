# Flow Notification Contact Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan task by task.

**Goal:** Let a long task override the contact information displayed in notification emails, with clear inline guidance and system-default fallback.

**Architecture:** Store optional `notifyContactName` and `notifyContactPhone` on the flow definition, expose them on definition DTOs, snapshot both on execution, and carry them in NotificationPayload. The sender selects escaped custom contact HTML or the existing runtime configuration HTML. Frontend fields live with notification settings and persist with the main definition.

**Tech Stack:** Vue/TypeScript, Java 17/Spring/MyBatis, openGauss migrations, node:test and Maven/JUnit.

## Global Constraints

- Follow approved spec `../specs/2026-10-16-flow-notification-contact-design.md`, including exact Chinese help text.
- Both values empty uses existing configuration HTML; one empty is a validation error; custom phone validates trimmed length 11 only.
- HTML escape user-provided contact text; never append both custom and default contact blocks.
- Execution snapshots govern initial mail and resend; legacy executions and job callers retain default contact behavior.
- Preserve all current recipient-manager and unrelated changes. No commit/push needed for this implementation.

### Task 1: Frontend contact fields

Files: `frontend/src/types/skillFlow.ts`, `frontend/src/components/SkillFlowEditorDrawer.vue`, new `frontend/src/components/SkillFlowContact.test.js`.

- [ ] Add failing behavior tests for help text, blank/default mode, paired-field validation, 10/11/12 length, trimmed input, CC/recipient regression, copy clearing and dirty tracking.
- [ ] Add top-level optional `notifyContactName` / `notifyContactPhone` types; initialize empty, load existing values, clear copied values, normalize trimmed save payload.
- [ ] Render spec labels, descriptions, placeholders, persistent default guidance and current-mode status. Validation must block save, with existing error display and no per-keystroke popups.
- [ ] Run node tests with existing editor/recipient/copy/API regressions; build with `vite.recipient-check.config.ts` to ignored target output.

### Task 2: Backend definition, snapshots and notification rendering

Files: flow definition/entity/DTO/service/mapper, execution entity/mapper/service, NotificationPayload, StubNotificationSender, new `V20261016.2__flow_notification_contact.sql`, new focused JUnit tests.

- [ ] Write failing tests for paired validation and length-only validation, definition roundtrip, execution snapshots, custom HTML escaping/replacement, default fallback and legacy constructors.
- [ ] Add definition fields and compatible DTO constructors; normalize blank to null and reject incomplete pair or phone length other than 11. Apply validation consistently for create/update and saved-flow completeness checks.
- [ ] Add TEXT definition and execution snapshot columns via Gauss-compatible migration; wire inserts, updates and result maps.
- [ ] Snapshot custom fields on execution; append payload fields while retaining legacy constructors; use snapshots for initial mail and resend.
- [ ] Extract a small pure contact renderer if useful, choose custom HTML when both nonblank, otherwise existing `notification_contact_html`; escape custom HTML and insert it once before closing body.
- [ ] Run focused Maven tests plus recipient/mapper/migration regressions; no live mail delivery.

### Task 3: Integration review and final verification

- [ ] Independently review frontend/backend field names, clearing behavior, legacy constructors, snapshot immutability, sender fallback, and permissions.
- [ ] Resolve findings, run covering tests, and check diff whitespace.
- [ ] Record exact passing results and deployment migration; disclose any unverified live Gauss/mail integration.
