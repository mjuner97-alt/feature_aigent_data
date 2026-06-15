/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.tools.QualityTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the P3 protocol — feeds the REAL (unmodified) {@link QualityTools} output
 * through {@link ArtifactHandoffHook} and asserts:
 *
 * <ul>
 *   <li>tabular results are detected, written to per-tenant CSV, replaced with a handoff message;
 *   <li>excluded tool names ({@code read_file}, {@code agent_spawn}, ...) pass through untouched;
 *   <li>non-tabular results (small tables, prose) pass through untouched;
 *   <li>the artifact path the LLM sees in the handoff actually exists on disk;
 *   <li>the per-task pinning makes a supervisor's bucket and a subagent's bucket resolve to the
 *       same path (so produce-and-consume across spawn boundaries works).
 * </ul>
 *
 * <p>By design these tests use the production {@link QualityTools} as the input fixture — they
 * lock in that the hook works for the actual tool outputs the demo ships, not just toy strings.
 */
class ArtifactHandoffHookTest {

    private Path tempRoot;
    private ArtifactStore store;
    private QualityTools tools;
    private ArtifactContext pinnedCtx;
    private ArtifactHandoffHook hook;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("artifact-handoff-test-");
        store = new ArtifactStore(tempRoot, tempRoot.toAbsolutePath().toString(), false);
        tools = new QualityTools(); // ZERO ARTIFACT AWARENESS — the whole point of P3
        pinnedCtx = new ArtifactContext("alice", "task_unit");
        hook = new ArtifactHandoffHook(store, pinnedCtx);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempRoot != null && Files.isDirectory(tempRoot)) {
            try (Stream<Path> walk = Files.walk(tempRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Happy path: real QualityTools output → real artifact
    // -------------------------------------------------------------------------

    @Test
    void rewritesQualityToolsTabularResultIntoArtifact() throws IOException {
        // Feed the EXACT string the unmodified QualityTools produces — proves the hook works
        // against the production fixture, not a hand-crafted test string.
        ToolResultBlock raw = tools.queryByDepartmentAndQuarter("2026年1季度", null);
        PostActingEvent event = postActing("query_quality_by_department_quarter", raw);

        hook.onEvent(event).block();
        ToolResultBlock rewritten = event.getToolResult();
        String text = extractText(rewritten);

        // Handoff markers
        assertTrue(text.contains("📦"), "handoff marker missing: " + text);
        assertTrue(text.contains("pd.read_csv"), "pandas hint missing: " + text);
        assertTrue(text.contains("/alice/task_unit/"), "tenant bucket missing: " + text);
        assertTrue(text.contains("严禁"), "guardrail missing: " + text);

        // The artifact path the LLM sees must exist on disk and match the original data.
        Path csv = extractCsvPath(text);
        assertNotNull(csv, "could not lift csv path from handoff: " + text);
        assertTrue(Files.exists(csv), "csv missing on disk: " + csv);
        String csvBody = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(csvBody.startsWith("季度,部门,质量分"), "bad csv header: " + csvBody);
        // 5 known departments + header — same row count as the original markdown.
        long lines = csvBody.lines().count();
        assertEquals(6, lines, "row count mismatch — csv body:\n" + csvBody);
    }

    // -------------------------------------------------------------------------
    // Pass-through paths
    // -------------------------------------------------------------------------

    @Test
    void excludedReadFilePassesThroughUntouched() {
        // A read_file result would, if rewritten, send the LLM in circles.
        ToolResultBlock raw =
                new ToolResultBlock(
                        "u-1",
                        "read_file",
                        List.of(TextBlock.builder().text(largeMarkdownTable()).build()),
                        null);
        PostActingEvent event = postActing("read_file", raw);
        hook.onEvent(event).block();
        // Same object reference back — proves no rewrite happened.
        assertEquals(raw, event.getToolResult());
    }

    @Test
    void excludedAgentSpawnPassesThroughUntouched() {
        ToolResultBlock raw =
                new ToolResultBlock(
                        "u-2",
                        "agent_spawn",
                        List.of(TextBlock.builder().text(largeMarkdownTable()).build()),
                        null);
        PostActingEvent event = postActing("agent_spawn", raw);
        hook.onEvent(event).block();
        assertEquals(raw, event.getToolResult());
    }

    @Test
    void excludedShellExecutePassesThroughUntouched() {
        // code_interpreter's printed DataFrame must NOT be re-artifactized.
        ToolResultBlock raw =
                new ToolResultBlock(
                        "u-3",
                        "shell_execute",
                        List.of(TextBlock.builder().text(largeMarkdownTable()).build()),
                        null);
        PostActingEvent event = postActing("shell_execute", raw);
        hook.onEvent(event).block();
        assertEquals(raw, event.getToolResult());
    }

    @Test
    void smallTableBelowThresholdIsLeftInline() {
        // 3 rows is below the threshold — inline is cheaper than dereferencing a file.
        String small =
                """
                | 部门 | 质量分 |
                |--|--|
                | 一部 | 23.1 |
                | 二部 | 13.1 |
                | 三部 | 3.1 |
                """;
        ToolResultBlock raw =
                new ToolResultBlock(
                        "u-4",
                        "query_quality_by_department_quarter",
                        List.of(TextBlock.builder().text(small).build()),
                        null);
        PostActingEvent event = postActing("query_quality_by_department_quarter", raw);
        hook.onEvent(event).block();
        // Untouched — same content visible
        assertTrue(extractText(event.getToolResult()).contains("| 一部 | 23.1 |"));
        assertFalse(extractText(event.getToolResult()).contains("📦"));
    }

    @Test
    void nonTabularProsePassesThroughUntouched() {
        ToolResultBlock raw =
                new ToolResultBlock(
                        "u-5",
                        "query_quality_by_department_quarter",
                        List.of(TextBlock.builder().text("查询成功。部门一部的质量分是 23.1,二部是 13.1。").build()),
                        null);
        PostActingEvent event = postActing("query_quality_by_department_quarter", raw);
        hook.onEvent(event).block();
        assertEquals(raw, event.getToolResult());
    }

    // -------------------------------------------------------------------------
    // Multi-tenant pinning
    // -------------------------------------------------------------------------

    @Test
    void pinnedContextOverridesSubagentRuntimeContext() throws IOException {
        // The contract: even if a subagent invokes the hook with a fresh sub-uuid RuntimeContext,
        // the artifact lands under the OUTER (alice, task_unit) bucket — that's what lets
        // query_quality_data write and code_interpreter read on the same file.
        ToolResultBlock raw = tools.queryByDepartmentAndQuarter("2026年1季度", null);
        PostActingEvent event = postActing("query_quality_by_department_quarter", raw);

        // Simulate the framework binding a different (subagent) ctx.
        hook.setRuntimeContext(
                io.agentscope.core.agent.RuntimeContext.builder()
                        .sessionId("sub-9999-abcd")
                        .build());

        hook.onEvent(event).block();
        String text = extractText(event.getToolResult());
        assertTrue(
                text.contains("/alice/task_unit/"),
                "pinned bucket must win over subagent ctx; saw: " + text);
        assertFalse(text.contains("/sub-9999-abcd/"), "subagent bucket leaked: " + text);
    }

    @Test
    void unpinnedHookFallsBackToFrameworkRuntimeContext() {
        ArtifactHandoffHook unpinned = new ArtifactHandoffHook(store);
        unpinned.setRuntimeContext(
                io.agentscope.core.agent.RuntimeContext.builder()
                        .userId("bob")
                        .sessionId("task_b")
                        .build());

        ToolResultBlock raw = tools.queryByDepartmentAndQuarter("2026年1季度", null);
        PostActingEvent event = postActing("query_quality_by_department_quarter", raw);
        unpinned.onEvent(event).block();

        String text = extractText(event.getToolResult());
        assertTrue(text.contains("/bob/task_b/"), "fallback ctx not used: " + text);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String largeMarkdownTable() {
        return """
        | a | b |
        |--|--|
        | 1 | 2 |
        | 3 | 4 |
        | 5 | 6 |
        | 7 | 8 |
        | 9 | 10 |
        """;
    }

    private static String extractText(ToolResultBlock r) {
        if (r == null || r.getOutput() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.getOutput()) {
            if (b instanceof TextBlock tb) sb.append(tb.getText()).append('\n');
        }
        return sb.toString();
    }

    /** Pulls the artifact path out of the handoff message ("  路径: <path>"). */
    private static Path extractCsvPath(String handoff) {
        for (String line : handoff.split("\n")) {
            String t = line.trim();
            if (t.startsWith("路径:")) {
                return Path.of(t.substring("路径:".length()).trim());
            }
        }
        return null;
    }

    private static PostActingEvent postActing(String toolName, ToolResultBlock result) {
        Agent agent = mock(Agent.class);
        Toolkit toolkit = mock(Toolkit.class);
        ToolUseBlock use = new ToolUseBlock("u-x", toolName, Map.of());
        return new PostActingEvent(agent, toolkit, use, result);
    }
}
