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

import com.agentscopea2a.v2.tools.ToolCallCollector;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Records every L1 (supervisor-level) tool invocation into the request-scoped
 * {@link ToolCallCollector} via {@link PreActingEvent} (tool name + input) and
 * {@link PostActingEvent} (output/result).
 *
 * <p><b>Context propagation:</b> The collector is stored on the per-call
 * {@link RuntimeContext} under key {@link #COLLECTOR_CTX_KEY} by
 * {@link com.agentscopea2a.v2.service.V2ChatStreamServiceImpl}. This hook implements
 * {@link RuntimeContextAware} so the framework pushes the current call's context before
 * the call starts and clears it after. This replaces the previous ThreadLocal approach,
 * which broke because reactive streams cross thread boundaries (the hook fires on
 * reactor scheduler threads, not the executor thread where the ThreadLocal was bound).
 *
 * <p>Priority 45 - after framework-internal acting hooks but before
 * SkillEvolutionHook(60).
 *
 * <p><b>Note:</b> Uses deprecated Hook/PreActingEvent/PostActingEvent API because
 * v2 MiddlewareBase doesn't offer both pre- and post-tool-call hooks in a single unit.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2InfraConfig}.
 */
@SuppressWarnings("deprecation")
public class ToolCallTrackingHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTrackingHook.class);

    /** Key under which the per-request ToolCallCollector is stored on RuntimeContext. */
    public static final String COLLECTOR_CTX_KEY = "toolCallCollector";

    private volatile RuntimeContext currentCtx;

    /**
     * No-arg constructor - the per-request collector is retrieved from the
     * {@link RuntimeContext} pushed by the framework via {@link RuntimeContextAware}.
     */
    public ToolCallTrackingHook() {
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        RuntimeContext ctx = this.currentCtx;
        if (ctx == null) {
            return Mono.just(event);
        }
        ToolCallCollector collector = ctx.get(COLLECTOR_CTX_KEY);
        if (collector == null) {
            return Mono.just(event);
        }

        if (event instanceof PreActingEvent pre) {
            handlePreActing(pre, collector);
        } else if (event instanceof PostActingEvent post) {
            handlePostActing(post, collector);
        }
        return Mono.just(event);
    }

    // -------- PreActing: record tool name + input --------

    private void handlePreActing(PreActingEvent event, ToolCallCollector collector) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null) return;

        String toolName = toolUse.getName();
        if (toolName == null) return;

        String input = formatInput(toolUse.getInput());
        log.info("[ToolCallTracking] PreActing tool={} inputLen={}", toolName, input.length());
        collector.recordL1(toolName, input, "");
    }

    // -------- PostActing: fill in the output --------

    private void handlePostActing(PostActingEvent event, ToolCallCollector collector) {
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock result = event.getToolResult();
        if (toolUse == null || result == null) return;

        String toolName = toolUse.getName();
        if (toolName == null) return;

        String output = extractText(result.getOutput());
        log.info("[ToolCallTracking] PostActing tool={} outputLen={} blank={}",
                toolName, output.length(), output.isBlank());
        if (output.isBlank()) return;

        collector.updateLastL1Output(toolName, output);
    }

    private static String formatInput(Object input) {
        if (input == null) return "";
        if (input instanceof java.util.Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        }
        String s = input.toString();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
