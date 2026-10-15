# Monthly version implementation plan

**Goal:** Display monthly periods as 版本 and output `xxxx年x月份版本`, as approved by the user.

**Architecture:** Keep month offsets and the existing resolver. Normalize monthly editor drafts to `{year}年{month}月份版本`; allow this exact monthly format for date parameters, while retaining calendar date support.

**Tech Stack:** Vue, TypeScript, Java 17, Node test runner.

- [x] Update editor tests for version labels, monthly draft conversion, and date compatibility.
- [x] Run `node --test src/components/DynamicPeriodEditor.test.js` and verify the new expectations fail (four expected failures).
- [x] Update editor labels, monthly formatting and frontend date validation.
- [x] Support monthly version strings in backend resolver and parameter validation, including month range validation.
- [x] Verify editor, flow node and parameter utility tests (32 passed), backend version resolution across years (compiled actual resolver/validator with a standalone harness), and frontend build (passed).
