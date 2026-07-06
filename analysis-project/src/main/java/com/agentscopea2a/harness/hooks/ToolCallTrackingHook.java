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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
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
 * <p><b>Thread safety:</b> Instead of relying on ThreadLocal (which breaks when Reactor
 * switches threads between subscription and event dispatch), this hook receives the
 * collector at construction time. The collector's {@code synchronized} methods handle
 * concurrent access from sub-agent threads if any.
 *
 * <p>Priority 45 — after framework-internal acting hooks but before
 * {@link SkillEvolutionHook}(60).
 */
public class ToolCallTrackingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTrackingHook.class);

    private final ToolCallCollector collector;

    public ToolCallTrackingHook(ToolCallCollector collector) {
        this.collector = collector;
        // Re-bind the ThreadLocal so SupervisorService's generate_skill context injection
        // (which uses ToolCallCollector.getCurrentContext()) can find the collector.
        // The collector was already bound in ChatStreamServiceV_3Impl before supervisorService.build(),
        // but Reactor may have switched threads; rebinding at hook construction (which happens
        // synchronously during build() on the original thread) reinforces it.
        if (collector != null) {
            collector.bind();
        }
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            handlePreActing(pre);
        } else if (event instanceof PostActingEvent post) {
            handlePostActing(post);
        }
        return Mono.just(event);
    }

    // -------- PreActing: record tool name + input --------

    private void handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null) return;

        String toolName = toolUse.getName();
        if (toolName == null) return;

        String input = formatInput(toolUse.getInput());
        log.info("[ToolCallTracking] PreActing tool={} inputLen={}", toolName, input.length());
        collector.recordL1(toolName, input, "");
    }

    // -------- PostActing: fill in the output --------

    private void handlePostActing(PostActingEvent event) {
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
