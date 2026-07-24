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
package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactRef;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.artifact.TabularExtractor;
import com.agentscopea2a.v2.artifact.TabularExtractor.ColumnSchema;
import com.agentscopea2a.v2.artifact.TabularExtractor.TabularData;
import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v2 artifact handoff hook — replaces v1 {@code ArtifactHandoffHook}.
 *
 * <p>Transparently rewrites tabular tool results into CSV artifact references so downstream
 * subagents (specifically {@code code_interpreter}) can {@code pd.read_csv(handle)} instead of
 * having the LLM copy a multi-row markdown table into the next {@code agent_spawn} prompt.
 *
 * <p>Implemented as a v2 {@link Hook} (not {@link io.agentscope.core.middleware.MiddlewareBase})
 * because the tool-result-rewrite pattern requires {@link PostActingEvent#getToolResult()} /
 * {@link PostActingEvent#setToolResult(ToolResultBlock)} which only the Hook system provides.
 * The Middleware {@code onActing} interceptor receives tool calls before execution, not results
 * after execution.
 *
 * <p>Priority 12 — after cache (0), memory (5), and logging (10) hooks, so those see the
 * original tool output before it gets artifactized.
 */
@SuppressWarnings("deprecation") // Hook/PostActingEvent deprecated but still the only way to rewrite tool results
public class ArtifactHandoffHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ArtifactHandoffHook.class);

    private static final Set<String> EXCLUDED_TOOLS =
            Set.of("read_file", "write_file", "shell_execute",
                    "agent_spawn", "agent_send", "task_output", "task_list", "task_cancel",
                    "memory_search", "memory_get", "session_search", "save_skill",
                    "arith");

    private static final int PREVIEW_ROWS = 5;
    private static final int SCHEMA_SAMPLES_PER_COLUMN = 3;

    /** V3.0: RuntimeContext key under which produced ArtifactRefs are published for the verifier (B1 check). */
    private static final String VERIFICATION_ARTIFACT_REFS_KEY = "verificationArtifactRefs";

    private final ArtifactStore artifactStore;
    private final ArtifactContext fixedContext;
    /**
     * Fallback only - populated by {@link RuntimeContextAware#setRuntimeContext} for tests that
     * drive the hook synchronously without Reactor context. Production code resolves ctx from
     * Reactor's ContextView via {@link HookRuntimeContext#resolve()} to avoid the multi-user
     * cross-talk documented in optimization-analysis.md P1-2.
     */
    private volatile RuntimeContext runtimeContext;

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
            return Mono.just(event);
        }

        if (fixedContext != null) {
            applyHandoff(post, toolName, table, fixedContext);
            return Mono.just(event);
        }

        return HookRuntimeContext.resolve()
                .map(ctx -> {
                    // Priority: pinned ArtifactContext from main agent's RuntimeContext.
                    // This ensures sub-agents (whose sessionId="sub-xxx") write CSVs to the
                    // parent's artifact bucket, so code_interpreter can read them.
                    ArtifactContext pinned = ctx.get(ArtifactContext.class);
                    ArtifactContext artifactCtx = (pinned != null) ? pinned : ArtifactContext.from(ctx);
                    ArtifactRef ref = applyHandoff(post, toolName, table, artifactCtx);
                    publishArtifactRef(ctx, ref);
                    return event;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    if (runtimeContext != null) {
                        ArtifactContext pinned = runtimeContext.get(ArtifactContext.class);
                        ArtifactContext artifactCtx = (pinned != null) ? pinned : ArtifactContext.from(runtimeContext);
                        ArtifactRef ref = applyHandoff(post, toolName, table, artifactCtx);
                        publishArtifactRef(runtimeContext, ref);
                    } else {
                        log.warn("ArtifactHandoffHook: no RuntimeContext bound, skipping artifactize for tool={}", toolName);
                    }
                    return event;
                }));
    }

    private ArtifactRef applyHandoff(PostActingEvent post, String toolName, TabularData table, ArtifactContext ctx) {
        ToolUseBlock toolUse = post.getToolUse();
        String preview = table.previewMarkdown(PREVIEW_ROWS);
        List<ColumnSchema> schema = table.inferSchema(SCHEMA_SAMPLES_PER_COLUMN);
        ArtifactRef ref =
                artifactStore.save(ctx, sanitizeToolKey(toolName), table.toCsv(),
                        table.columns(), table.rowCount(), preview, schema);

        String handoff = buildHandoffMessage(toolName, table, ref, preview);
        ToolResultBlock rewritten =
                ToolResultBlock.of(toolUse.getId(), toolUse.getName(),
                        List.of(TextBlock.builder().text(handoff).build()));
        post.setToolResult(rewritten);

        // Framework's ReActAgent.notifyPostActingHook builds toolResultMsg from the ORIGINAL
        // result BEFORE hooks run, then adds toolResultMsg (not toolResult) to memory. So
        // setToolResult alone has no effect on what the LLM sees next iteration. We must also
        // replace toolResultMsg with a new Msg built from the rewritten block.
        Msg rewrittenMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .content(rewritten)
                .build();
        post.setToolResultMsg(rewrittenMsg);

        log.info("Artifactized {} result for user={}, task={}: rows={} cols={} -> {}",
                toolName, ctx.userBucket(), ctx.taskBucket(),
                table.rowCount(), table.columns().size(), ref.agentPath());
        return ref;
    }

    /**
     * V3.0 additive: publish the produced {@link ArtifactRef} onto the per-request
     * {@link RuntimeContext} so the verifier's B1 (artifact path terminal consistency) check can see
     * which paths were actually produced - without introducing a hard dependency on the verify
     * package (raw key string mirrors {@code VerificationContext.ARTIFACT_REFS_KEY}). Best-effort:
     * no-op when no verification context is bound.
     */
    @SuppressWarnings("unchecked")
    private static void publishArtifactRef(RuntimeContext ctx, ArtifactRef ref) {
        if (ctx == null || ref == null) {
            return;
        }
        try {
            Object existing = ctx.get(VERIFICATION_ARTIFACT_REFS_KEY);
            List<ArtifactRef> list;
            if (existing instanceof List) {
                list = (List<ArtifactRef>) existing;
            } else {
                list = new CopyOnWriteArrayList<>();
                ctx.put(VERIFICATION_ARTIFACT_REFS_KEY, list);
            }
            list.add(ref);
        } catch (Exception ignored) {
            // best-effort; verification context may not be bound for this request
        }
    }

    // ==================== Handoff message ====================

    private static String buildHandoffMessage(
            String toolName, TabularData table, ArtifactRef ref, String preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName).append(" 查询完成 — 共 ").append(table.rowCount());
        sb.append(" 行,列: ").append(String.join("、", table.columns())).append("\n\n");
        sb.append("前 ").append(Math.min(PREVIEW_ROWS, table.rowCount())).append(" 行预览:\n");
        sb.append(preview).append("\n\n");
        sb.append("📦 完整数据已保存为 CSV artifact:\n");
        sb.append("  路径: ").append(ref.agentPath()).append("\n");
        sb.append("  shape: (").append(table.rowCount()).append(", ");
        sb.append(table.columns().size()).append(")\n\n");
        if (ref.schema() != null && !ref.schema().isEmpty()) {
            sb.append("▶ Schema(已用启发式推断 dtype,可直接用,无需再 df.dtypes 探查):\n");
            sb.append(renderSchemaBlock(ref.schema())).append("\n\n");
        }
        sb.append("▶ 后续 Python 计算时,在 Python 中读取:\n");
        sb.append("  import pandas as pd\n");
        sb.append("  df = pd.read_csv(\"").append(ref.agentPath()).append("\")\n");
        sb.append("  # 列名 / dtype 已在上方 Schema 段给出,不要再写 df.head() / df.dtypes\n\n");
        sb.append("🚨 严禁: Python 计算时不要把上面的预览表格手工解析进代码,\n");
        sb.append("   直接 pd.read_csv(路径) 即可 — 完整数据在 csv 里。\n");
        return sb.toString();
    }

    private static String renderSchemaBlock(List<ColumnSchema> schema) {
        int colWidth = 0;
        for (ColumnSchema c : schema) {
            colWidth = Math.max(colWidth, c.name().length());
        }
        StringBuilder sb = new StringBuilder();
        for (ColumnSchema c : schema) {
            sb.append("  ");
            sb.append(padRight(c.name(), colWidth)).append("  ");
            sb.append(padRight(c.dtype(), 8)).append("  ");
            sb.append(c.nonNullCount()).append("/").append(c.totalCount()).append(" 非空");
            if (!c.samples().isEmpty()) {
                sb.append("    样本: ").append(c.samples());
            }
            if (c.min() != null && c.max() != null) {
                sb.append("    range=[").append(formatNum(c.min())).append(", ")
                        .append(formatNum(c.max())).append("]");
            }
            sb.append("\n");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    private static String formatNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return String.format("%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
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

    private static String sanitizeToolKey(String toolName) {
        String t = toolName;
        if (t.startsWith("query_")) t = t.substring("query_".length());
        if (t.length() > 12) t = t.substring(0, 12);
        return t.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}