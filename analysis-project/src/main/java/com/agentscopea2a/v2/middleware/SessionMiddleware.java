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
package com.agentscopea2a.v2.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * v2 middleware that sanitizes tool call input values by replacing potentially
 * problematic regex patterns with spaces.
 *
 * <p>Replaces v1's {@code SessionHook} (deprecated Hook API) with a proper v2
 * middleware that intercepts tool calls via {@link #onActing}.
 *
 * <p>This is a safety measure: some LLM-generated tool calls may include "regex" as a
 * parameter value (e.g., when the LLM tries to use regex in a data query tool), which
 * can cause unexpected behavior. This middleware strips those patterns before execution.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2InfraConfig}.
 */
public class SessionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionMiddleware.class);

    /** Pattern to match "regex" as a standalone value or within tool call inputs. */
    private static final Pattern REGEX_PATTERN = Pattern.compile("\\bregex\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(input);
        }

        // Check if any tool call input contains "regex" — if not, return early
        boolean needsSanitization = false;
        for (ToolUseBlock toolUse : toolCalls) {
            Map<String, Object> toolInput = toolUse.getInput();
            if (toolInput != null) {
                for (Map.Entry<String, Object> entry : toolInput.entrySet()) {
                    Object value = entry.getValue();
                    if (value != null && REGEX_PATTERN.matcher(value.toString()).find()) {
                        needsSanitization = true;
                        break;
                    }
                }
            }
            if (needsSanitization) break;
        }

        if (!needsSanitization) {
            return next.apply(input);
        }

        log.debug("[SessionMiddleware] Sanitizing regex patterns in tool call inputs");
        List<ToolUseBlock> rewritten = new ArrayList<>(toolCalls);
        for (int i = 0; i < rewritten.size(); i++) {
            ToolUseBlock toolUse = rewritten.get(i);
            Map<String, Object> original = toolUse.getInput();
            if (original == null || original.isEmpty()) {
                continue;
            }
            Map<String, Object> sanitized = new HashMap<>();
            boolean changed = false;
            for (Map.Entry<String, Object> entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value != null) {
                    String strValue = value.toString();
                    String cleaned = REGEX_PATTERN.matcher(strValue).replaceAll(" ");
                    if (!cleaned.equals(strValue)) {
                        changed = true;
                    }
                    sanitized.put(entry.getKey(), cleaned);
                } else {
                    sanitized.put(entry.getKey(), value);
                }
            }
            if (changed) {
                rewritten.set(i, new ToolUseBlock(toolUse.getId(), toolUse.getName(), sanitized));
            }
        }

        return next.apply(new ActingInput(rewritten));
    }
}