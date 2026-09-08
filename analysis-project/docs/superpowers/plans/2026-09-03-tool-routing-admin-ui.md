# Tool Routing Administration UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a protected management page that scans existing SQL, API, and Python registrations into reviewable routing candidates, manages canonical tags and per-tool routing metadata, and displays the global routing rollout state.

**Architecture:** A backend scan service reads registration metadata only: SQL and SCRIPT records from their existing MyBatis mappers and API signatures from `ToolRoutersIndex`. It emits/persists disabled review candidates and never executes an underlying tool. The Vue page is added to `ScriptRegistryShell`, uses the existing Element Plus table/dialog conventions, and can configure a candidate only after metric and dimension tags are selected from the shared dictionary.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis/OpenGauss, Vue 3, TypeScript, Vue Router, Element Plus, Maven, Vite.

**Spec:** `docs/tool-metadata-unified-routing-plan.md`

## Global Constraints

- `harness.a2a.tool-routing.enabled` remains the deployment-time feature flag and is read-only in the UI.
- Scanning never runs SQL, Python, or API tools and never writes an enabled route automatically.
- The frontend does not expose SQL templates, Python source, secrets, or arbitrary API invocation parameters.
- Do not modify `metric-categories.yaml`.
- Retain `sql_list`, `script_list`, and the legacy `tool_index` Skill until the documented cutover conditions are met.

---

### Task 1: Scan Domain and API

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingScanService.java`
- Create: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingScanCandidate.java`
- Create: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingStatusResponse.java`
- Modify: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingMetadataController.java`
- Test: `src/test/java/com/agentscopea2a/v2/toolrouting/ToolRoutingScanServiceTest.java`

**Interfaces:**
- Consumes: `SqlRegistryMapper.listAllEnabled()`, `ScriptRegistryMapper.listAllEnabled()`, `ToolRoutersIndex.findApiTool(String)`/registered IDs, and persisted `ToolRoutingMetadata`.
- Produces: `scan(): List<ToolRoutingScanCandidate>` and `status(): ToolRoutingStatusResponse`.

- [ ] Write tests covering source type mapping, duplicate IDs, missing script files, already-configured entries, and exclusion of meta/infra API tools.
- [ ] Run the focused Maven test and confirm it fails because the scan service does not exist.
- [ ] Implement deterministic source scanning and collision reporting; do not persist or enable candidates.
- [ ] Add `GET /api/tool-routing/scan` and `GET /api/tool-routing/status`.
- [ ] Re-run focused tests.

### Task 2: Dictionary and Metadata Management API

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingTagDictionary.java`
- Modify: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingTagController.java`
- Modify: `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingMetadataAdminService.java`
- Test: `src/test/java/com/agentscopea2a/v2/toolrouting/ToolRoutingMetadataAdminServiceTest.java`

**Interfaces:**
- Consumes: scanned `toolId/toolType` and reviewed `ToolRoutingMetadataInput`.
- Produces: listable enabled and disabled tags, validated route metadata, and cache invalidation after saves.

- [ ] Add tests for disabled tag visibility and refusal to enable a route whose tags are not canonical.
- [ ] Run focused tests to observe the expected failure.
- [ ] Implement all-tag management reads and structured validation errors.
- [ ] Re-run focused tests.

### Task 3: Frontend API and Tool Routing Page

**Files:**
- Create: `frontend/src/api/toolRouting.ts`
- Create: `frontend/src/types/toolRouting.ts`
- Create: `frontend/src/pages/ToolRoutingPage.vue`
- Modify: `frontend/src/components/ScriptRegistryShell.vue`
- Modify: `frontend/src/main.ts`
- Test: `frontend` Vite type/build verification.

**Interfaces:**
- Consumes: `/api/tool-routing`, `/api/tool-routing/scan`, `/api/tool-routing/status`, and `/api/tool-routing/tags/{METRIC|DIMENSION}`.
- Produces: scan/review table, metadata configuration dialog, dictionary dialog, and read-only global rollout status.

- [ ] Add TypeScript types and API clients with the existing `X-User-Id` header convention.
- [ ] Build the page with two tabs: reviewable tools and tag dictionary.
- [ ] Configure route tags with multi-select controls; default routes to disabled and show scan availability/collision reasons.
- [ ] Add the `工具路由` child navigation item and route after `Skill 配置`.
- [ ] Run `npm run build` in `frontend` and inspect the result for type errors.

### Task 4: Regression Verification

**Files:**
- Test: existing tool-routing unit tests and frontend build output.

- [ ] Run the complete targeted Maven routing suite.
- [ ] Run `mvn -q -DskipTests compile`.
- [ ] Run `npm run build` in `frontend`.
- [ ] Confirm `git diff --check` is clean and that no `__pycache__` files are staged.
