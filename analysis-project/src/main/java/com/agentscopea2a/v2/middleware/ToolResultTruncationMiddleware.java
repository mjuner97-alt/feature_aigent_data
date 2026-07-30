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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Truncates previously-consumed tool results in the LLM input to reduce context bloat.
 *
 * <p>Problem: tools like {@code load_skill_through_path} return multi-K-char payloads
 * (e.g. full SKILL.md). The first LLM call needs the full text to plan the next step,
 * but every subsequent ReAct iteration re-injects the same old ToolResultBlock into the
 * prompt - pure token waste for a small internal-LLM.
 *
 * <p>Strategy: on every {@code onReasoning}, find the <em>last</em> {@link ToolResultBlock}
 * in the message list (the one the LLM is about to consume this round) and leave it intact.
 * Any earlier {@code ToolResultBlock} whose tool name is in {@link #toolKeepChars} gets its
 * text output truncated to the configured number of chars + a {@code ...(truncated)} marker.
 *
 * <p>Memory is untouched - the original ToolResultBlock stays in agent state. Each new
 * reasoning round rebuilds the input from memory, so the truncation is reapplied fresh
 * every iteration. This naturally gives "first round full, subsequent rounds truncated"
 * semantics without any per-round bookkeeping.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2InfraConfig}. Wired on both the
 * main agent (via Spring's {@code List<MiddlewareBase>} injection in HarnessA2aRunnerV2)
 * and subagents (via {@link com.agentscopea2a.v2.runner.SubagentRegistrar}).
 */
public class ToolResultTruncationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolResultTruncationMiddleware.class);

    /** Suffix appended to truncated tool results so the LLM can tell it was shortened. */
    private static final String TRUNCATION_MARKER = "\n...(truncated, kept first ";

    private final Map<String, Integer> toolKeepChars;
    private final boolean enabled;

    public ToolResultTruncationMiddleware(Map<String, Integer> toolKeepChars, boolean enabled) {
        this.toolKeepChars = toolKeepChars != null ? Map.copyOf(toolKeepChars) : Map.of();
        this.enabled = enabled;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!enabled || toolKeepChars.isEmpty() || input.messages() == null || input.messages().isEmpty()) {
            return next.apply(input);
        }

        List<Msg> messages = input.messages();
        int lastTrIdx = findLastToolResultIdx(messages);
        if (lastTrIdx < 0) {
            return next.apply(input);
        }

        List<Msg> rewritten = null;
        for (int i = 0; i < lastTrIdx; i++) {
            Msg msg = messages.get(i);
            Msg replaced = truncateIfMatched(msg);
            if (replaced != null) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(messages);
                }
                rewritten.set(i, replaced);
            }
        }

        if (rewritten == null) {
            return next.apply(input);
        }
        ReasoningInput newInput = new ReasoningInput(rewritten, input.tools(), input.options());
        return next.apply(newInput);
    }

    /**
     * Returns the index of the last {@link Msg} whose content contains a {@link ToolResultBlock},
     * or {@code -1} if none. The LLM is about to consume this result, so it must stay intact.
     */
    private static int findLastToolResultIdx(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * If {@code msg} contains a {@link ToolResultBlock} for a tool in {@link #toolKeepChars},
     * return a copy with that block's text output truncated to the configured length.
     * Returns {@code null} if no rewrite is needed (msg left untouched).
     */
    private Msg truncateIfMatched(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return null;
        }
        List<ContentBlock> origContent = msg.getContent();
        List<ContentBlock> newContent = null;
        for (int i = 0; i < origContent.size(); i++) {
            ContentBlock block = origContent.get(i);
            if (!(block instanceof ToolResultBlock trb)) {
                continue;
            }
            String toolName = trb.getName();
            if (toolName == null) {
                continue;
            }
            Integer keep = toolKeepChars.get(toolName);
            if (keep == null || keep <= 0) {
                continue;
            }
            String origText = extractText(trb.getOutput());
            if (origText.length() <= keep) {
                continue;
            }
            String truncated = origText.substring(0, keep) + TRUNCATION_MARKER + keep + " chars)";
            ToolResultBlock replacement = new ToolResultBlock(
                    trb.getId(),
                    trb.getName(),
                    List.of(TextBlock.builder().text(truncated).build()),
                    trb.getMetadata(),
                    trb.getState() != null ? trb.getState() : ToolResultState.RUNNING);
            if (newContent == null) {
                newContent = new ArrayList<>(origContent);
            }
            newContent.set(i, replacement);
            log.debug("Truncated tool result: tool={} id={} origLen={} newLen={}",
                    toolName, trb.getId(), origText.length(), truncated.length());
        }
        if (newContent == null) {
            return null;
        }
        return msg.withContent(newContent);
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}
