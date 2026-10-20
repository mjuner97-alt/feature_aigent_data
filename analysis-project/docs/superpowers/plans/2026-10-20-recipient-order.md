# Recipient ordering implementation plan

**Goal:** Drag recipients within their TO/CC group, or move them one position using buttons; retain order on confirm, save and send.
**Architecture:** Shared OrderedRecipientList.vue emits fresh ordered arrays through v-model. Native drag handle starts an instance-local drag; vertical row midpoint selects before/after insertion. No cross-list drops. Existing ordered persistence remains unchanged.
**Tech Stack:** Vue 3, TypeScript, HTML drag/drop, node:test memory renderer.

- [x] Test shared list: insert last item into middle/up/down, first/last boundary disabling, disabled state, foreign/cancelled/stale drag, before/after indicator.
- [x] Implement shared component with labelled handles, insertion line and up/down controls; do not mutate props.
- [x] Replace recipient displays in MailRecipientDialog, NotificationSettingsDrawer, SkillJobFormDrawer and SkillJobNotificationDrawer, preserving existing remove/move-group actions.
- [x] Integration tests assert TO/CC confirmation, job/flow settings save/reopen and independent resend send exact reordered IDs.
- [x] Run relevant node tests, targeted lint and Vite build; review diff. Leave unrelated in-progress changes intact.

Validation: node recipient suites passed; native Chromium drag before/after, cross-group rejection, disabled controls and narrow-dialog readability passed. Targeted lint on the new component and job integrations passed. Production Vite build passed with existing bundle warnings.

Follow-up approved by user: keep every action visible (no More menu). Widen the recipient dialog to 1320px with border-box sizing and 92vh maximum height; stack TO/CC at viewport widths <=1100px. Match the loading dialog width. Replace the report-processing drawer's legacy text prompt with the shared send dialog, closing it when the execution/parent changes. Validation: 39 relevant tests and Chromium direct-action visibility, wide/narrow layout and native drag checks passed; Vite production build passed.
