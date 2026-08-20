# Script Exec Renderable Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both chat SSE endpoints expose only fenced ECharts/HTML blocks from `script_exec`, without changing model output.

**Architecture:** Keep parsing in `ScriptExecOutputExtractor` and route the completed tool result in the shared `ToolCallTrackingHook`. Treat `script_exec` separately from ordinary tools: it emits only `script_output` when renderable blocks exist and never emits its full `tool_output`.

**Tech Stack:** Java 17, Spring `SseEmitter`, AgentScope hooks, JUnit 5, Maven

---

### Task 1: Define shared tool-output routing

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHook.java`
- Create: `src/test/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHookRoutingTest.java`

- [ ] **Step 1: Write the failing routing tests**

Add tests for a package-visible routing method with these assertions:

```java
assertEquals(SCRIPT_OUTPUT, ToolCallTrackingHook.outputEventFor("script_exec"));
assertEquals(TOOL_OUTPUT, ToolCallTrackingHook.outputEventFor("sql_exec"));
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn "-Dtest=ToolCallTrackingHookRoutingTest" test
```

Expected: compilation failure because `outputEventFor` and its event-kind constants do not exist.

- [ ] **Step 3: Implement minimal routing**

Introduce a small package-visible event-kind method and make `handlePostActing` branch on it:

```java
if ("script_exec".equals(toolName)) {
    String renderable = ScriptExecOutputExtractor.extractRenderableBlocks(output);
    if (!renderable.isBlank()) {
        sendScriptOutputSseEvent(ctx, toolUse.getId(), toolName, renderable);
    }
} else if (!scriptOutputOnly) {
    sendToolOutputSseEvent(ctx, toolUse.getId(), toolName, output);
}
```

This removes full `tool_output` and raw stdout `script_output` for `script_exec` on both endpoints. It leaves non-script tools and all model events unchanged.

- [ ] **Step 4: Run the routing tests and verify GREEN**

Run the same Maven command. Expected: 2 tests pass.

### Task 2: Cover extraction and request context

**Files:**
- Modify: `src/test/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractorTest.java`
- Verify: `src/main/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractor.java`
- Verify: `src/main/java/com/agentscopea2a/v2/service/impl/ChatStreamServiceImpl.java`
- Verify: `src/test/java/com/agentscopea2a/v2/service/impl/ChatStreamServiceImplTest.java`

- [ ] **Step 1: Add the no-renderable-block regression test**

```java
assertEquals("", ScriptExecOutputExtractor.extractRenderableBlocks(
        "─── stdout ───\n普通摘要和 Markdown 表格\n─── stderr ───\nINFO"));
```

- [ ] **Step 2: Run focused backend verification**

Run:

```powershell
mvn "-Dtest=ToolCallTrackingHookRoutingTest,ScriptExecOutputExtractorTest,ChatStreamServiceImplTest" test
mvn -DskipTests compile
```

Expected: all focused tests pass and compilation succeeds.

- [ ] **Step 3: Check scope and whitespace**

Run:

```powershell
git diff --check
git diff --name-only -- frontend frontend-pm src/main/resources/workspace/skills
```

Expected: no whitespace errors and no frontend or Skill changes caused by this task.

- [ ] **Step 4: Commit only backend implementation and tests**

```powershell
git add src/main/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractor.java `
        src/main/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHook.java `
        src/main/java/com/agentscopea2a/v2/service/impl/ChatStreamServiceImpl.java `
        src/test/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractorTest.java `
        src/test/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHookRoutingTest.java `
        src/test/java/com/agentscopea2a/v2/service/impl/ChatStreamServiceImplTest.java
git commit -m "fix: restrict script output SSE to renderable blocks"
```
