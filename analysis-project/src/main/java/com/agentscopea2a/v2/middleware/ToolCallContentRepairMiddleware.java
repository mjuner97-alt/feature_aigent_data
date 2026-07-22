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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Repairs {@link ToolUseBlock#getContent()} when it is null but {@link ToolUseBlock#getInput()}
 * has valid parameters.
 *
 * <p>Some LLM providers (notably DeepSeek in streaming mode) return tool call chunks where the
 * {@code arguments} field is empty or null in the first chunk. The framework's
 * {@code ToolCallsAccumulator} may leave {@code content} as null even though the {@code input}
 * Map was correctly parsed from subsequent chunks. When {@code ToolValidator.validateInput()}
 * receives null content, it throws a schema validation error:
 * {@code "Parameter validation failed for tool 'python_exec': Schema validation error: argument "content" is null"}.
 *
 * <p>This middleware runs at priority {@code -100} (earliest) so the repair happens before any
 * other middleware inspects or modifies the tool calls.
 *
 * <p>Additionally, other middleware ({@link ArtifactAccessMiddleware},
 * {@link PythonExecAccessMiddleware}) that create replacement {@code ToolUseBlock} instances using
 * the 3-arg constructor {@code new ToolUseBlock(id, name, newInput)} also produce null content.
 * This middleware repairs those as well, since it runs first in the chain.
 */
public class ToolCallContentRepairMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolCallContentRepairMiddleware.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(input);
        }

        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolUseBlock toolUse = toolCalls.get(i);
            // Repair: if content is null/empty but input has data, serialize input as content
            if ((toolUse.getContent() == null || toolUse.getContent().isBlank())
                    && toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
                try {
                    String jsonContent = MAPPER.writeValueAsString(toolUse.getInput());
                    ToolUseBlock replacement = ToolUseBlock.builder()
                            .id(toolUse.getId())
                            .name(toolUse.getName())
                            .input(toolUse.getInput())
                            .content(jsonContent)
                            .metadata(toolUse.getMetadata())
                            .state(toolUse.getState())
                            .build();
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(toolCalls);
                    }
                    rewritten.set(i, replacement);
                    log.debug("Repaired null content for tool call: name={}, id={}, contentLen={}",
                            toolUse.getName(), toolUse.getId(), jsonContent.length());
                } catch (Exception e) {
                    log.warn("Failed to serialize input for tool call {}: {}",
                            toolUse.getName(), e.getMessage());
                }
            }
        }

        if (rewritten != null) {
            ActingInput modified = new ActingInput(rewritten);
            return next.apply(modified);
        }
        return next.apply(input);
    }
}