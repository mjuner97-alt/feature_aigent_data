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

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactRef;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.artifact.TabularExtractor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import com.agentscopea2a.harness.artifact.TabularExtractor.TabularData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Transparently rewrites tabular tool results into CSV artifact references so downstream
 * subagents (specifically {@code code_interpreter}) can {@code pd.read_csv(handle)} instead of
 * having the LLM copy a multi-row markdown table into the next {@code agent_spawn} prompt.
 *
 * <p><b>Why a hook (not a tool-side opt-in).</b> See {@code docs/artifact-handoff-proposal.md} §6.
 * Tools should only know about returning markdown / JSON; making each of 80+ production tools
 * carry an {@code ArtifactStore} dependency, a {@code RuntimeContext} parameter, and an
 * {@code emit(...)} call is exactly the "tools coupled to validation hooks" anti-pattern called
 * out in the project memory. Centralizing the rewrite here:
 *
 * <ul>
 *   <li>tools stay zero-aware of artifacts — they keep returning whatever they always returned;
 *   <li>adding a new markdown-table-returning tool is zero-effort (this hook catches it
 *       automatically);
 *   <li>the artifact path conventions, tenant isolation, and handoff message format live in
 *       exactly one file.
 * </ul>
 *
 * <p><b>Detection.</b> {@link TabularExtractor} parses the tool-result text. A non-null parse
 * with {@code rowCount() >= MIN_DATA_ROWS} triggers artifactization; anything else is left
 * untouched (small text / unrelated prose).
 *
 * <p><b>Excluded tools.</b> {@link #EXCLUDED_TOOLS} — same reasoning as harness's
 * {@code ToolResultEvictionConfig}: artifactizing a {@code read_file} or {@code agent_spawn}
 * result would either loop (read_file → text → re-artifactize) or destroy semantics
 * (agent_spawn returns a subagent transcript that isn't tabular data).
 *
 * <p><b>RuntimeContext source.</b> Implements {@link RuntimeContextAware}. The framework binds
 * the per-call ctx in {@link #setRuntimeContext}, and we read it from the field in
 * {@link #onEvent}. {@code PostActingEvent} fires on the dispatch thread (same thread the tool
 * just ran on), but we don't rely on ThreadLocals — the field captured by the
 * {@code RuntimeContextAware} contract is correct on every thread the framework calls us from
 * within a single call.
 *
 * <p><b>Priority.</b> 12 — after {@code ResponseCacheHook(0)} (cache hits short-circuit before
 * tool dispatch, so we'd never run anyway), after harness's {@code ProgressiveMemoryHook(5)}
 * and {@code LoggingHook(10)} (those want to log the original tool output), before
 * {@code DataGroundingHook(15)} (grounding should compare against the ARTIFACT-replaced result
 * the LLM will actually see, otherwise it warns on numbers that the LLM never had a chance to
 * copy).
 */
public class ArtifactHandoffHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ArtifactHandoffHook.class);

    /**
     * Tools whose results MUST NOT be artifactized.
     *
     * <ul>
     *   <li>{@code read_file}: artifactizing what we just read back would either re-read the
     *       artifact (infinite loop) or hijack the very file the LLM asked for.
     *   <li>{@code write_file}: write results are status messages, never tabular.
     *   <li>{@code shell_execute}: stdout from {@code code_interpreter} can contain a printed
     *       DataFrame; that's the FINAL answer the subagent prepared and should not be
     *       re-artifactized.
     *   <li>{@code agent_spawn} / {@code agent_send} / {@code task_output} / {@code task_list}:
     *       results are subagent transcripts (structurally text, not data).
     *   <li>{@code memory_search} / {@code memory_get} / {@code session_search}: results are
     *       prose summaries.
     *   <li>{@code save_skill}: status messages.
     * </ul>
     */
    private static final Set<String> EXCLUDED_TOOLS =
            Set.of(
                    "read_file",
                    "write_file",
                    "shell_execute",
                    "agent_spawn",
                    "agent_send",
                    "task_output",
                    "task_list",
                    "task_cancel",
                    "memory_search",
                    "memory_get",
                    "session_search",
                    "save_skill");

    /** Preview rows embedded in the handoff message — keep small to stay token-cheap. */
    private static final int PREVIEW_ROWS = 5;

    private final ArtifactStore artifactStore;

    /**
     * When non-null, ALL artifacts produced via this hook are written under this fixed bucket
     * regardless of the per-call {@link RuntimeContext}. This is how the {@code SupervisorService}
     * pins every subagent in one A2A request to the SAME per-request bucket (otherwise
     * {@code query_quality_data} would write under {@code sub-A} and {@code code_interpreter}
     * would look under {@code sub-B} and {@link ArtifactAccessHook} would block the read).
     *
     * <p>Null on hooks built without an outer ctx (e.g. plain tests) — those fall back to the
     * subagent's own RuntimeContext, useful for unit tests but never for production.
     */
    private final ArtifactContext fixedContext;

    /** Captured by {@link #setRuntimeContext} for the duration of one call — only used when
     * {@link #fixedContext} is null. */
    private volatile RuntimeContext runtimeContext;

    /** Test-/standalone-only ctor. Production callers should use {@link #ArtifactHandoffHook(ArtifactStore, ArtifactContext)}. */
    public ArtifactHandoffHook(ArtifactStore artifactStore) {
        this(artifactStore, null);
    }

    public ArtifactHandoffHook(ArtifactStore artifactStore, ArtifactContext fixedContext) {
        this.artifactStore = artifactStore;
        this.fixedContext = fixedContext;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.runtimeContext = context;
    }

    @Override
    public int priority() {
        return 12;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent post)) {
            return Mono.just(event);
        }
        ToolResultBlock result = post.getToolResult();
        ToolUseBlock toolUse = post.getToolUse();
        if (result == null || toolUse == null) {
            return Mono.just(event);
        }
        String toolName = toolUse.getName();
        if (toolName == null || EXCLUDED_TOOLS.contains(toolName)) {
            return Mono.just(event);
        }

        String text = extractText(result.getOutput());
        if (text.isBlank()) {
            return Mono.just(event);
        }

        TabularData table = TabularExtractor.tryParse(text);
        if (table == null) {
            // Below threshold or not tabular — inline is cheaper, leave the result alone.
            return Mono.just(event);
        }

        ArtifactContext ctx =
                fixedContext != null ? fixedContext : ArtifactContext.from(runtimeContext);
        String preview = table.previewMarkdown(PREVIEW_ROWS);
        ArtifactRef ref =
                artifactStore.save(
                        ctx,
                        sanitizeToolKey(toolName),
                        table.toCsv(),
                        table.columns(),
                        table.rowCount(),
                        preview);

        String handoff = buildHandoffMessage(toolName, table, ref, preview);
        ToolResultBlock rewritten =
                new ToolResultBlock(
                        result.getId(),
                        result.getName(),
                        List.of(TextBlock.builder().text(handoff).build()),
                        result.getMetadata());
        post.setToolResult(rewritten);

        log.info(
                "Artifactized {} result for user={}, task={}: rows={} cols={} → {}",
                toolName,
                ctx.userBucket(),
                ctx.taskBucket(),
                table.rowCount(),
                table.columns().size(),
                ref.agentPath());

        return Mono.just(event);
    }

    /** Build the user-visible "we offloaded the table; here's the handle" message. */
    private static String buildHandoffMessage(
            String toolName, TabularData table, ArtifactRef ref, String preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName).append(" 查询完成 — 共 ").append(table.rowCount());
        sb.append(" 行,列: ").append(String.join("、", table.columns())).append("\n\n");
        sb.append("前 ").append(Math.min(PREVIEW_ROWS, table.rowCount())).append(" 行预览:\n");
        sb.append(preview).append("\n\n");
        sb.append("📦 完整数据已保存为 CSV artifact:\n");
        sb.append("  路径: ").append(ref.agentPath()).append("\n\n");
        sb.append("▶ 后续派给 code_interpreter 时,在 Python 中读取:\n");
        sb.append("  import pandas as pd\n");
        sb.append("  df = pd.read_csv(\"").append(ref.agentPath()).append("\")\n\n");
        sb.append("🚨 严禁: 派单 code_interpreter 时不要把上面的预览表格复制进 task 字符串,\n");
        sb.append("   只传 csv 路径 + 计算需求即可 — 完整数据在 csv 里。\n");
        return sb.toString();
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Strip a long tool name down to a 6-char-ish prefix usable as the artifact filename key. */
    private static String sanitizeToolKey(String toolName) {
        // Drop the leading "query_" / "get_" verb and pick the first informative chunks.
        String t = toolName;
        if (t.startsWith("query_")) t = t.substring("query_".length());
        // Keep first 12 chars + sanitize for path safety
        if (t.length() > 12) t = t.substring(0, 12);
        return t.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
