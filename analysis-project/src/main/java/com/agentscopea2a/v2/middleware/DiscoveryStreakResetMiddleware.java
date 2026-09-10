package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.tools.ToolIndexTool;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Companion to {@link ToolIndexTool}'s discovery-streak circuit breaker: resets the
 * consecutive-discovery counter whenever an executor tool is invoked.
 *
 * <p>Rationale: a legitimate flow alternates discovery and execution (tool_index 1-3 calls ->
 * execute -> maybe discover again). A probe loop only ever calls tool_index. Without this reset,
 * multi-metric analyses that legitimately issue several discovery rounds over one run would be
 * cut off by the streak cap; with it, only runs that never execute anything are stopped.
 *
 * <p>Reset happens in {@link #onActing} (before execution) - the intent to execute is enough.
 * Both this middleware and {@code ToolIndexTool} receive the same RuntimeContext instance within
 * one agent run, so the counter is shared. Subagents get a cloned context at spawn time, which
 * isolates each agent's streak as desired.
 */
public class DiscoveryStreakResetMiddleware implements MiddlewareBase {

    private static final Set<String> EXECUTOR_TOOLS = Set.of(
            "router_tool", "sql_registry_exec", "script_exec", "python_exec");

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        if (ctx != null && containsExecutor(input)) {
            ctx.put(ToolIndexTool.DISCOVERY_STREAK_KEY, 0);
        }
        return next.apply(input);
    }

    private static boolean containsExecutor(ActingInput input) {
        List<ToolUseBlock> toolCalls = input == null ? null : input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }
        for (ToolUseBlock toolUse : toolCalls) {
            if (toolUse != null && toolUse.getName() != null
                    && EXECUTOR_TOOLS.contains(toolUse.getName())) {
                return true;
            }
        }
        return false;
    }
}
