# Script Exec Renderable Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both chat endpoints expose only fenced ECharts/HTML blocks from `script_exec`, without changing model output.

**Architecture:** Keep parsing in `ScriptExecOutputExtractor` and route the completed tool result in the shared `ToolCallTrackingHook`. Treat `script_exec` separately from ordinary tools: it emits only `script_output` when renderable blocks exist and never emits its full `tool_output`.

**Tech Stack:** Java 17, Spring `SseEmitter`, AgentScope hooks, JUnit 5, Maven

---

### Task 1: Define shared tool-output routing

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHook.java`
- Test: `src/test/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractorTest.java`

- [ ] **Step 1: Write the failing extraction tests**

Add tests that retain only fenced ECharts/HTML blocks and return empty for ordinary output:

```java
assertEquals("```echarts\n{}\n```", extractRenderableBlocks(scriptOutput));
assertEquals("", extractRenderableBlocks(scriptOutputWithoutRenderableBlocks));
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn "-Dtest=ScriptExecOutputExtractorTest" test
```

Expected: compilation failure because `extractRenderableBlocks` does not exist.

- [ ] **Step 3: Implement minimal routing**

Make `handlePostActing` branch directly on `script_exec`:

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

This removes full `tool_output` and raw stdout `script_output` for `script_exec` on both endpoints. It leaves all model events unchanged.

- [ ] **Step 4: Run the routing tests and verify GREEN**

Run the same Maven command. Expected: all extractor tests pass.

### Task 2: Cover extraction and request context

**Files:**
- Modify: `src/test/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractorTest.java`
- Verify: `src/main/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractor.java`

- [ ] **Step 1: Add the no-renderable-block regression test**

```java
assertEquals("", ScriptExecOutputExtractor.extractRenderableBlocks(
        "─── stdout ───\n普通摘要和 Markdown 表格\n─── stderr ───\nINFO"));
```

- [ ] **Step 2: Run focused backend verification**

Run:

```powershell
mvn "-Dtest=ScriptExecOutputExtractorTest" test
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
        src/test/java/com/agentscopea2a/v2/hooks/ScriptExecOutputExtractorTest.java
git commit -m "fix: restrict script output SSE to renderable blocks"
```
