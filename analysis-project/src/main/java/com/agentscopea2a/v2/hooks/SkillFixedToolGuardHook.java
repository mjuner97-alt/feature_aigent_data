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

import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical guard backing the "Skill 固定流程优先" prompt rules (AGENTS.md /
 * skills/_common/SKILL.md). Prompt discipline alone proved unreliable on the
 * intranet small-parameter models: with a Skill body that fixes
 * {@code sqlId}/{@code scriptId}, the model still hallucinated a different ID,
 * got rejected by sql_registry_exec, then wandered into tool_index discovery
 * and burned the request (2026/09/14 incident, 5m29s watchdog timeout).
 *
 * <p>How it works:
 * <ol>
 *   <li>PostActing on a successful {@code load_skill_through_path}: the skill
 *       body is scanned for fixed {@code sqlId=...} / {@code scriptId=...} /
 *       {@code toolId=...} assignments. If any are found, they are published to
 *       the per-request {@link RuntimeContext} under {@link #FIXED_IDS_CTX_KEY}.</li>
 *   <li>PostActing on {@code tool_index} / {@code toolMetaInfo} while fixed IDs
 *       are recorded: the discovery result is REPLACED with a corrective message
 *       echoing the fixed IDs (via setToolResult + setToolResultMsg — the same
 *       proven pattern as ArtifactHandoffHook; setToolResult alone never reaches
 *       the LLM because ReActAgent adds toolResultMsg to memory before hooks run).
 *       The discovery tools are read-only, so letting them execute and discarding
 *       the output is side-effect-free.</li>
 * </ol>
 *
 * <p>Skills without fixed IDs in their body are unaffected — the guard only arms
 * itself when a fixed executor was actually declared, so dynamic-discovery skills
 * keep working. {@code router_tool} is deliberately NOT intercepted: on subagents
 * it is the only executor for API-type tools, so a skill fixing an API toolId
 * still needs it; discovery there goes through tool_index/toolMetaInfo anyway.
 *
 * <p>Scope is per-request: the RuntimeContext (and thus the guard) dies with the
 * request, matching the load-skill → execute → reply lifecycle of a skill flow.
 *
 * <p>Priority 14 — after ArtifactHandoffHook (12) and PythonExecRetryHook (13),
 * before ToolCallTrackingHook (45) and the trace collector (47), so both the SSE
 * activity feed and the trace record the overridden corrective message.
 */
@SuppressWarnings("deprecation") // Hook/PostActingEvent deprecated but still the only way to rewrite tool results
public class SkillFixedToolGuardHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SkillFixedToolGuardHook.class);

    /** RuntimeContext key under which the active request's fixed tool IDs are published. */
    public static final String FIXED_IDS_CTX_KEY = "skillFixedToolIds";

    private static final Set<String> DISCOVERY_TOOLS = Set.of("tool_index", "toolMetaInfo");

    private static final String SKILL_LOADER_TOOL = "load_skill_through_path";

    /**
     * Matches fixed ID assignments in skill bodies, e.g. {@code sqlId="q2_1_metrics"}.
     * The {@code [:=]} separator requirement keeps prose that merely mentions
     * "`sqlId`、`scriptId`" (rule text in _common/SKILL.md, AGENTS.md and the
     * discipline sections of skills themselves) from false-positive arming.
     */
    private static final Pattern SQL_ID_PATTERN =
            Pattern.compile("(?:sqlId|sql_id)\\s*[:=]\\s*[\"'`]?([A-Za-z0-9_][A-Za-z0-9_.\\-]*)");

    private static final Pattern SCRIPT_ID_PATTERN =
            Pattern.compile("(?:scriptId|script_id)\\s*[:=]\\s*[\"'`]?([A-Za-z0-9_][A-Za-z0-9_.\\-]*)");

    private static final Pattern TOOL_ID_PATTERN =
            Pattern.compile("(?:toolId|tool_id)\\s*[:=]\\s*[\"'`]?([A-Za-z0-9_][A-Za-z0-9_.\\-]*)");

    /** Fixed tool IDs extracted from the currently loaded skill body (per request). */
    public record FixedToolIds(
            String skillName,
            Set<String> sqlIds,
            Set<String> scriptIds,
            Set<String> toolIds) {

        public boolean isEmpty() {
            return sqlIds.isEmpty() && scriptIds.isEmpty() && toolIds.isEmpty();
        }

        public boolean isArmed() {
            return !isEmpty();
        }
    }

    /**
     * Fallback only — populated by {@link RuntimeContextAware#setRuntimeContext} for tests
     * that drive the hook synchronously without Reactor context. Production resolves ctx
     * from Reactor's ContextView via {@link HookRuntimeContext#resolve()}.
     */
    private volatile RuntimeContext currentCtx;

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    public int priority() {
        return 14;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // PreActing: silently correct hallucinated executor IDs to the skill-fixed one
        // before execution (ReActAgent.notifyPreActingHooks feeds the hook-rewritten
        // ToolUseBlocks into executeToolCalls, so setToolUse takes effect).
        if (event instanceof PreActingEvent pre) {
            return HookRuntimeContext.resolve()
                    .map(ctx -> {
                        correctExecutorIdIfArmed(pre, ctx);
                        return event;
                    })
                    .switchIfEmpty(Mono.fromSupplier(() -> {
                        if (currentCtx != null) {
                            correctExecutorIdIfArmed(pre, currentCtx);
                        }
                        return event;
                    }));
        }

        if (!(event instanceof PostActingEvent post)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = post.getToolUse();
        ToolResultBlock result = post.getToolResult();
        if (toolUse == null || result == null) {
            return Mono.just(event);
        }
        String toolName = toolUse.getName();
        if (toolName == null) {
            return Mono.just(event);
        }

        if (SKILL_LOADER_TOOL.equals(toolName)) {
            return HookRuntimeContext.resolve()
                    .map(ctx -> {
                        armGuardIfFixedIds(post, ctx);
                        return event;
                    })
                    .switchIfEmpty(Mono.fromSupplier(() -> {
                        if (currentCtx != null) {
                            armGuardIfFixedIds(post, currentCtx);
                        }
                        return event;
                    }));
        }

        if (DISCOVERY_TOOLS.contains(toolName)) {
            return HookRuntimeContext.resolve()
                    .map(ctx -> {
                        overrideDiscoveryIfArmed(post, toolName, ctx);
                        return event;
                    })
                    .switchIfEmpty(Mono.fromSupplier(() -> {
                        if (currentCtx != null) {
                            overrideDiscoveryIfArmed(post, toolName, currentCtx);
                        }
                        return event;
                    }));
        }

        return Mono.just(event);
    }

    // -------- PostActing on load_skill_through_path: arm the guard --------

    /**
     * Rewrites a hallucinated executor ID (e.g. the 2026/09/14 incident's
     * {@code q2_1_metrics_by_dept_version_detail}) to the skill-fixed one BEFORE
     * execution. Only fires when the guard is armed and the fixed set for that
     * ID type is unambiguous (exactly one entry) — with multiple fixed IDs of the
     * same type we cannot know which one the skill means, so we leave the call
     * alone and let the executor's rejection message guide the retry.
     */
    private void correctExecutorIdIfArmed(PreActingEvent pre, RuntimeContext ctx) {
        FixedToolIds fixed = ctx.get(FIXED_IDS_CTX_KEY);
        if (fixed == null || !fixed.isArmed()) {
            return;
        }
        ToolUseBlock toolUse = pre.getToolUse();
        if (toolUse == null || toolUse.getInput() == null) {
            return;
        }
        String inputKey;
        Set<String> fixedIds;
        switch (toolUse.getName()) {
            case "sql_registry_exec" -> {
                inputKey = "sqlId";
                fixedIds = fixed.sqlIds();
            }
            case "script_exec" -> {
                inputKey = "scriptId";
                fixedIds = fixed.scriptIds();
            }
            default -> {
                return;
            }
        }
        if (fixedIds.size() != 1) {
            return;
        }
        String fixedId = fixedIds.iterator().next();
        Object passed = toolUse.getInput().get(inputKey);
        if (!(passed instanceof String passedId) || fixedId.equals(passedId)) {
            return;
        }
        Map<String, Object> corrected = new LinkedHashMap<>(toolUse.getInput());
        corrected.put(inputKey, fixedId);
        pre.setToolUse(new ToolUseBlock(toolUse.getId(), toolUse.getName(), corrected));
        log.warn("[SkillFixedToolGuard] corrected hallucinated {} '{}' -> skill-fixed '{}' (skill '{}')",
                inputKey, passedId, fixedId, fixed.skillName());
    }

    private void armGuardIfFixedIds(PostActingEvent post, RuntimeContext ctx) {
        ToolResultBlock result = post.getToolResult();
        if (result.getState() == ToolResultState.ERROR || result.getState() == ToolResultState.DENIED) {
            return;
        }
        ToolUseBlock toolUse = post.getToolUse();
        Object requested = toolUse.getInput() == null ? null : toolUse.getInput().get("name");
        if (requested instanceof String reqName
                && ("_common".equalsIgnoreCase(reqName.trim())
                    || "tool_index".equalsIgnoreCase(reqName.trim()))) {
            // _common only carries shared rules, and its prose mentions toolId/sqlId;
            // tool_index loads fetch the discovery catalog, not a fixed-executor skill.
            return;
        }

        String skillBody = extractText(result.getOutput());
        Set<String> sqlIds = extractAll(SQL_ID_PATTERN, skillBody);
        Set<String> scriptIds = extractAll(SCRIPT_ID_PATTERN, skillBody);
        Set<String> toolIds = extractAll(TOOL_ID_PATTERN, skillBody);
        FixedToolIds fixed = new FixedToolIds(
                requested instanceof String s ? s.trim() : null,
                sqlIds, scriptIds, toolIds);

        if (!fixed.isArmed()) {
            // Dynamic skill — leave discovery available (a previously armed guard
            // from an earlier skill load in the same request stays in force).
            return;
        }
        ctx.put(FIXED_IDS_CTX_KEY, fixed);
        log.info("[SkillFixedToolGuard] armed by skill '{}': sqlIds={} scriptIds={} toolIds={}",
                fixed.skillName(), sqlIds, scriptIds, toolIds);
    }

    // -------- PostActing on tool_index / toolMetaInfo: override the result --------

    private void overrideDiscoveryIfArmed(PostActingEvent post, String toolName, RuntimeContext ctx) {
        FixedToolIds fixed = ctx.get(FIXED_IDS_CTX_KEY);
        if (fixed == null || !fixed.isArmed()) {
            return;
        }
        ToolUseBlock toolUse = post.getToolUse();
        String corrective = buildCorrectiveMessage(toolName, fixed);

        ToolResultBlock rewritten = ToolResultBlock.of(toolUse.getId(), toolUse.getName(),
                List.of(TextBlock.builder().text(corrective).build()));
        post.setToolResult(rewritten);
        // setToolResult alone does not update what the LLM sees — the framework
        // builds toolResultMsg from the original result before hooks run (see
        // ArtifactHandoffHook for the full analysis).
        Msg rewrittenMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .content(rewritten)
                .build();
        post.setToolResultMsg(rewrittenMsg);
        log.warn("[SkillFixedToolGuard] {} result overridden while skill '{}' has fixed IDs (corrective message injected)",
                toolName, fixed.skillName());
    }

    private static String buildCorrectiveMessage(String toolName, FixedToolIds fixed) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ ").append(toolName).append(" 已被拦截：当前请求已加载 Skill「")
                .append(fixed.skillName() == null ? "(未命名)" : fixed.skillName())
                .append("」，其正文已固定执行工具，禁止动态发现或替换。\n\n固定 ID 如下：\n");
        if (!fixed.sqlIds().isEmpty()) {
            sb.append("- sqlId (sql_registry_exec): ").append(String.join(", ", fixed.sqlIds())).append('\n');
        }
        if (!fixed.scriptIds().isEmpty()) {
            sb.append("- scriptId (script_exec): ").append(String.join(", ", fixed.scriptIds())).append('\n');
        }
        if (!fixed.toolIds().isEmpty()) {
            sb.append("- toolId (router_tool): ").append(String.join(", ", fixed.toolIds())).append('\n');
        }
        sb.append("\n请立即返回 Skill 正文，严格按其步骤顺序原样使用上述 ID 执行（参数也按正文原样传递）。")
                .append("若固定 ID 执行失败，请如实向用户报告失败原因，不要改猜其他 ID，也不要再次调用 ")
                .append(toolName).append("。");
        return sb.toString();
    }

    // -------- parsing helpers --------

    static Set<String> extractAll(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    static FixedToolIds parseFixedIds(String skillBody, String skillName) {
        return new FixedToolIds(skillName,
                extractAll(SQL_ID_PATTERN, skillBody),
                extractAll(SCRIPT_ID_PATTERN, skillBody),
                extractAll(TOOL_ID_PATTERN, skillBody));
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
