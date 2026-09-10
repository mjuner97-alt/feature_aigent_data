package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.tools.ToolIndexTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoveryStreakResetMiddlewareTest {

    private final DiscoveryStreakResetMiddleware middleware = new DiscoveryStreakResetMiddleware();

    @Test
    void executorToolCallResetsStreakAndPassesThrough() {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(ToolIndexTool.DISCOVERY_STREAK_KEY, 3);
        AtomicInteger passedThrough = new AtomicInteger();

        call(ctx, passedThrough, new ToolUseBlock("id1", "script_exec", Map.of(), null, null, null));

        assertEquals(0, ctx.get(ToolIndexTool.DISCOVERY_STREAK_KEY, Integer.class));
        assertEquals(1, passedThrough.get());
    }

    @Test
    void nonExecutorToolCallKeepsStreak() {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(ToolIndexTool.DISCOVERY_STREAK_KEY, 3);
        AtomicInteger passedThrough = new AtomicInteger();

        call(ctx, passedThrough, new ToolUseBlock("id1", "tool_index", Map.of(), null, null, null));

        assertEquals(3, ctx.get(ToolIndexTool.DISCOVERY_STREAK_KEY, Integer.class));
        assertEquals(1, passedThrough.get());
    }

    @Test
    void emptyToolCallsKeepStreak() {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(ToolIndexTool.DISCOVERY_STREAK_KEY, 2);
        AtomicInteger passedThrough = new AtomicInteger();

        call(ctx, passedThrough);

        assertEquals(2, ctx.get(ToolIndexTool.DISCOVERY_STREAK_KEY, Integer.class));
        assertEquals(1, passedThrough.get());
    }

    private void call(RuntimeContext ctx, AtomicInteger passedThrough, ToolUseBlock... blocks) {
        middleware.onActing(null, ctx, new ActingInput(List.of(blocks)),
                input -> {
                    passedThrough.incrementAndGet();
                    return Flux.empty();
                });
    }
}
