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
import java.util.Set;
import java.util.function.Function;

/**
 * Compacts previously-consumed tool results in the LLM input to reduce context bloat
 * without losing execution-critical information.
 *
 * <p>Problem: tools like {@code load_skill_through_path} return multi-K-char payloads
 * (e.g. full SKILL.md). The first LLM call needs the full text to plan the next step,
 * but every subsequent ReAct iteration re-injects the same old ToolResultBlock - pure
 * token waste for a small internal-LLM. Naive truncation (keep first N chars) breaks
 * the LLM's ability to execute later steps because field-mapping tables, formulas and
 * python_exec templates are scattered through the middle of the document.
 *
 * <p>Strategy: on every {@code onReasoning}, find the <em>last</em> {@link ToolResultBlock}
 * in the message list (the one the LLM is about to consume this round) and leave it intact.
 * Any earlier {@code ToolResultBlock} whose tool name is in {@link #compactTools} gets
 * its text output compacted by {@link #compactMarkdown(String)} - which keeps all
 * structured markdown elements (frontmatter / code blocks / tables / section headers /
 * bullet & numbered lists / {@code filters:} lines) and drops only descriptive paragraphs
 * and quote blocks. SKILL.md authors are expected to express hard rules as bullet lists,
 * not as {@code >} quote blocks, so dropping quote blocks is safe.
 *
 * <p>Memory is untouched - the original ToolResultBlock stays in agent state. Each new
 * reasoning round rebuilds the input from memory, so the compaction is reapplied fresh
 * every iteration. This naturally gives "first round full, subsequent rounds compacted"
 * semantics without any per-round bookkeeping.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2InfraConfig}. Wired on both the
 * main agent (via Spring's {@code List<MiddlewareBase>} injection in HarnessA2aRunnerV2)
 * and subagents (via {@link com.agentscopea2a.v2.runner.SubagentRegistrar}).
 */
public class ToolResultTruncationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolResultTruncationMiddleware.class);

    /** Suffix appended to compacted tool results so the LLM can tell the content was shortened. */
    private static final String COMPACTION_MARKER =
            "\n\n...(compacted: dropped descriptive paragraphs; structured elements preserved)";

    private final Set<String> compactTools;
    private final boolean enabled;

    public ToolResultTruncationMiddleware(Set<String> compactTools, boolean enabled) {
        this.compactTools = compactTools != null ? Set.copyOf(compactTools) : Set.of();
        this.enabled = enabled;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!enabled || compactTools.isEmpty() || input.messages() == null || input.messages().isEmpty()) {
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
            Msg replaced = compactIfMatched(msg);
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
     * If {@code msg} contains a {@link ToolResultBlock} for a tool in {@link #compactTools},
     * return a copy with that block's text output compacted via {@link #compactMarkdown(String)}.
     * Returns {@code null} if no rewrite is needed (msg left untouched).
     */
    private Msg compactIfMatched(Msg msg) {
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
            if (toolName == null || !compactTools.contains(toolName)) {
                continue;
            }
            String origText = extractText(trb.getOutput());
            String compacted = compactMarkdown(origText);
            if (compacted.length() >= origText.length()) {
                // No reduction (e.g. content was already all structured). Skip rewrite.
                continue;
            }
            String finalText = compacted + COMPACTION_MARKER;
            ToolResultBlock replacement = new ToolResultBlock(
                    trb.getId(),
                    trb.getName(),
                    List.of(TextBlock.builder().text(finalText).build()),
                    trb.getMetadata(),
                    trb.getState() != null ? trb.getState() : ToolResultState.RUNNING);
            if (newContent == null) {
                newContent = new ArrayList<>(origContent);
            }
            newContent.set(i, replacement);
            log.debug("Compacted tool result: tool={} id={} origLen={} newLen={} saved={}",
                    toolName, trb.getId(), origText.length(), finalText.length(),
                    origText.length() - finalText.length());
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

    /**
     * Compact a markdown document by keeping structured elements and dropping descriptive
     * paragraphs and quote blocks.
     *
     * <p>Kept elements:
     * <ul>
     *   <li>Frontmatter ({@code ---...---})</li>
     *   <li>Code blocks ({@code ```...```})</li>
     *   <li>Table rows ({@code | ... |})</li>
     *   <li>Section headers ({@code #}, {@code ##}, {@code ###} ...)</li>
     *   <li>Bullet list items ({@code -} or {@code *})</li>
     *   <li>Numbered list items ({@code 1.} etc.)</li>
     *   <li>{@code filters:} lines (key-value parameter examples)</li>
     * </ul>
     *
     * <p>Dropped: plain paragraphs, quote blocks ({@code >}), and the descriptive prose
     * between structured elements. SKILL.md authors must express hard rules as bullet lists,
     * not quote blocks, to survive compaction.
     */
    static String compactMarkdown(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length());
        boolean inFrontmatter = false;
        boolean inCodeBlock = false;
        boolean prevWasBlank = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Frontmatter delimiters: --- on its own line toggles state.
            if (trimmed.equals("---")) {
                inFrontmatter = !inFrontmatter;
                appendLine(out, line, false);
                prevWasBlank = false;
                continue;
            }
            if (inFrontmatter) {
                appendLine(out, line, false);
                prevWasBlank = false;
                continue;
            }

            // Code block fences toggle state - everything inside is kept verbatim.
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                appendLine(out, line, false);
                prevWasBlank = false;
                continue;
            }
            if (inCodeBlock) {
                appendLine(out, line, false);
                prevWasBlank = false;
                continue;
            }

            // Blank line: collapse consecutive blanks into one, keep as separator.
            if (trimmed.isEmpty()) {
                if (!prevWasBlank) {
                    out.append("\n");
                    prevWasBlank = true;
                }
                continue;
            }

            // Section header.
            if (trimmed.startsWith("#")) {
                appendLine(out, line, prevWasBlank);
                prevWasBlank = false;
                continue;
            }

            // Table row (must start and end with |, including the |---| separator row).
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1) {
                appendLine(out, line, prevWasBlank);
                prevWasBlank = false;
                continue;
            }

            // Bullet list item.
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                appendLine(out, line, prevWasBlank);
                prevWasBlank = false;
                continue;
            }

            // Numbered list item: "1." through "999." prefix.
            if (trimmed.length() >= 3 && Character.isDigit(trimmed.charAt(0))) {
                int i = 1;
                while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) {
                    i++;
                }
                if (i + 1 <= trimmed.length()
                        && trimmed.charAt(i) == '.'
                        && (i + 1 == trimmed.length() || Character.isWhitespace(trimmed.charAt(i + 1)))) {
                    appendLine(out, line, prevWasBlank);
                    prevWasBlank = false;
                    continue;
                }
            }

            // filters: line (key-value parameter example, common in skill files).
            if (trimmed.startsWith("filters:") || trimmed.startsWith("filters：")) {
                appendLine(out, line, prevWasBlank);
                prevWasBlank = false;
                continue;
            }

            // Otherwise: descriptive paragraph or quote block - drop.
        }
        return out.toString().trim();
    }

    private static void appendLine(StringBuilder out, String line, boolean prependBlank) {
        if (prependBlank && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append("\n");
        }
        out.append(line).append("\n");
    }
}
