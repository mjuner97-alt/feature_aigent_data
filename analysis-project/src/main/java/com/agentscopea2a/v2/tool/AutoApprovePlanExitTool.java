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
package com.agentscopea2a.v2.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Project-local shadow of {@link PlanModeTools.PlanExitTool} that returns
 * {@link PermissionDecision#allow} instead of {@code ask}, so {@code plan_exit}
 * no longer triggers the framework's HITL confirmation flow.
 *
 * <h3>Why this exists</h3>
 *
 * The framework's {@code PlanExitTool.checkPermissions} always returns
 * {@code PermissionDecision.ask(...)}. The ask gates execution behind a
 * {@code RequireUserConfirmEvent} that pauses the agent until the user
 * approves via {@code Msg.METADATA_CONFIRM_RESULTS}. But this project's
 * frontend has no HITL approval UI, and there's no {@code /confirm} endpoint
 * wired to send {@code ConfirmResult} metadata back. When the user types
 * something like "确认" after the agent pauses, it's interpreted as a fresh
 * user message, not a confirmation — the framework's
 * {@code maybePatchPendingToolCalls} (enablePendingToolRecovery=true) then
 * auto-generates an error result for the pending {@code plan_exit} call, so
 * the agent never actually enters BUILD mode. The user sees the agent stop
 * right after {@code plan_exit} with "✅ 智能体完成" and no further execution.
 *
 * <p>This shadow tool replaces the JAR's {@code PlanExitTool} after
 * {@link io.agentscope.harness.agent.HarnessAgent#build()} via
 * {@code toolkit.removeTool("plan_exit") + registerTool(new AutoApprovePlanExitTool(...))}.
 * The replacement is wired in {@code HarnessA2aRunnerV2}'s constructor.
 *
 * <p>The {@code callAsync} body is identical to the JAR's — only
 * {@code checkPermissions} differs (allow vs ask). The tool name, schema,
 * and description are kept verbatim so the model's tool-selection behavior
 * doesn't change.
 *
 * <h3>Reverting to ASK behavior</h3>
 *
 * If HITL approval is needed again (e.g. a real frontend approval UI is
 * added), delete this class and remove the post-build replacement in
 * {@code HarnessA2aRunnerV2}. The JAR's original {@code PlanExitTool} will
 * take over.
 */
public class AutoApprovePlanExitTool extends ToolBase {

    private final PlanModeManager manager;

    public AutoApprovePlanExitTool(PlanModeManager manager) {
        super(
                ToolBase.builder()
                        .name(PlanModeTools.PLAN_EXIT)
                        .description(
                                "Finish planning and request permission to start executing the"
                                    + " plan. This pauses for the user to approve your plan. On"
                                    + " approval you return to BUILD mode and may modify files;"
                                    + " on rejection you stay in PLAN mode and should revise.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "summary",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Optional short summary of the plan for"
                                                                + " the user to approve."))))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.manager = manager;
    }

    /**
     * Always ALLOW — skip the HITL pause so the agent flows directly from
     * {@code plan_exit} into BUILD mode. See class javadoc for why we don't
     * use {@code ask} here.
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(
                PermissionDecision.allow(
                        "Plan auto-approved (project AutoApprovePlanExitTool: "
                                + "HITL approval UI not yet wired). Transitioning to BUILD mode."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentState state = stateOf(param);
        if (state == null) {
            return Mono.just(result(param, "Error: agent state unavailable."));
        }
        manager.exit(state);
        return Mono.just(
                result(
                        param,
                        "Plan approved. You are now in BUILD mode and may modify files and run"
                            + " commands. Start executing the plan now. Seed your task list"
                            + " with todo_write (one item per plan step), keep exactly one task"
                            + " in_progress, and update it as you go."));
    }

    private static AgentState stateOf(ToolCallParam param) {
        return io.agentscope.core.agent.RuntimeContext.resolveAgentState(
                param.getRuntimeContext(), param.getAgent());
    }

    private static ToolResultBlock result(ToolCallParam param, String text) {
        return ToolResultBlock.text(text)
                .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
    }
}