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

import com.agentscopea2a.harness.tools.KnownEntities;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hook that validates Agent responses against captured tool result data to detect data loss
 * and hallucination.
 *
 * <p>Works in two phases:
 *
 * <ol>
 *   <li><b>Capture</b> ({@code PostActingEvent}): Records all tool result data (numbers, entity
 *       names) into a local buffer.
 *   <li><b>Validate</b> ({@code PostCallEvent}): Compares the Agent's final response against the
 *       captured data, checking for:
 *       <ul>
 *         <li><b>Completeness</b>: All entities from tool results should appear in the response
 *         <li><b>Accuracy</b>: Numbers mentioned in the response should match tool result values
 *         <li><b>Bounding</b>: Entities mentioned in the response should exist in tool results
 *       </ul>
 * </ol>
 *
 * <p>Entity sets come from {@link KnownEntities} — the same source the {@code QualityTools} use
 * for tool responses. Adding a new department/person there automatically picks it up here
 * (P2-3 in docs/enhancement-proposal.md).
 *
 * <p>Numbers are matched as decimals with one digit of precision (e.g. {@code 23.1}); integers
 * are skipped because the supervisor's reply often contains formatting integers (counts,
 * years) that are intentionally not in tool output.
 *
 * <p>When issues are detected, appends a warning annotation to the response:
 *
 * <pre>[⚠️ 数据校验：共查询14条数据，回复仅覆盖13条；缺失：部门X]</pre>
 */
public class DataGroundingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DataGroundingHook.class);

    /** Pattern to match decimal numbers in text (e.g., "23.1", "3.72", "26.1"). */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+\\.\\d+)\\b");

    /** Tolerance when comparing response numbers to tool-result numbers. */
    private static final double NUMBER_TOLERANCE = 0.01;

    /** Captured tool result data during this agent call. */
    private final List<CapturedToolResult> capturedResults = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        Mono<? extends HookEvent> result;
        if (event instanceof PostActingEvent e) {
            result = captureToolResult(e);
        } else if (event instanceof PostCallEvent e) {
            result = validateAndAnnotate(e);
        } else {
            return Mono.just(event);
        }
        return (Mono<T>) result;
    }

    @Override
    public int priority() {
        // Run after ProgressiveMemoryHook (5) and LoggingHook (10), before final output
        return 15;
    }

    // ==================== Phase 1: Capture ====================

    private Mono<PostActingEvent> captureToolResult(PostActingEvent event) {
        ToolResultBlock result = event.getToolResult();
        if (result == null) {
            return Mono.just(event);
        }

        String resultText = extractTextFromBlocks(result.getOutput());
        if (resultText.isEmpty()) {
            return Mono.just(event);
        }

        CapturedToolResult captured = new CapturedToolResult();
        captured.rawText = resultText;

        // Extract known entity names present in tool result (single source: KnownEntities).
        for (String entity : KnownEntities.all()) {
            if (resultText.contains(entity)) {
                captured.entities.add(entity);
            }
        }

        // Extract numbers present in tool result
        Matcher matcher = NUMBER_PATTERN.matcher(resultText);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                captured.numbers.add(value);
            } catch (NumberFormatException ignored) {
                // skip unparseable
            }
        }

        capturedResults.add(captured);
        log.debug(
                "Captured tool result: {} entities, {} numbers",
                captured.entities.size(),
                captured.numbers.size());

        return Mono.just(event);
    }

    // ==================== Phase 2: Validate & Annotate ====================

    private Mono<PostCallEvent> validateAndAnnotate(PostCallEvent event) {
        if (capturedResults.isEmpty()) {
            return Mono.just(event);
        }

        Msg finalMsg = event.getFinalMessage();
        if (finalMsg == null) {
            return Mono.just(event);
        }

        String responseText = extractTextFromMsg(finalMsg);

        // Aggregate all captured data
        List<String> allEntities = new ArrayList<>();
        List<Double> allNumbers = new ArrayList<>();
        for (CapturedToolResult cr : capturedResults) {
            allEntities.addAll(cr.entities);
            allNumbers.addAll(cr.numbers);
        }

        // Check completeness: entities in tool result but missing from response
        List<String> missingEntities = new ArrayList<>();
        for (String entity : allEntities) {
            if (!responseText.contains(entity)) {
                missingEntities.add(entity);
            }
        }

        // Check accuracy: decimal numbers in response that don't match any tool result.
        // Integers are NOT checked — formatting integers (counts, years, percentages) are
        // expected to appear in summaries without being directly from tool output.
        List<Double> unmatchedNumbers = new ArrayList<>();
        Matcher numberMatcher = NUMBER_PATTERN.matcher(responseText);
        while (numberMatcher.find()) {
            try {
                double responseValue = Double.parseDouble(numberMatcher.group(1));
                boolean matched = false;
                for (double toolValue : allNumbers) {
                    if (Math.abs(responseValue - toolValue) < NUMBER_TOLERANCE) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    unmatchedNumbers.add(responseValue);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }

        // Build warning if issues found
        List<String> warnings = new ArrayList<>();
        if (!missingEntities.isEmpty()) {
            warnings.add(
                    String.format(
                            "共查询%d个数据实体，回复中缺失%d个：%s",
                            allEntities.size(),
                            missingEntities.size(),
                            String.join("、", missingEntities)));
        }
        if (!unmatchedNumbers.isEmpty()) {
            warnings.add(
                    String.format(
                            "回复中包含%d个不在查询结果中的数值：%s",
                            unmatchedNumbers.size(),
                            unmatchedNumbers.stream().map(n -> String.format("%.1f", n)).toList()));
        }

        if (!warnings.isEmpty()) {
            String warningText = "\n\n---\n*⚠️ 数据校验告警：" + String.join("；", warnings) + "*";

            // Annotate the final message by appending warning text
            Msg annotated = appendTextToMsg(finalMsg, warningText);
            event.setFinalMessage(annotated);

            log.warn("Data grounding issues detected: {}", String.join("; ", warnings));
        } else {
            log.info(
                    "Data grounding check passed: {} entities, {} numbers all verified",
                    allEntities.size(),
                    allNumbers.size());
        }

        // Reset for next call
        capturedResults.clear();

        return Mono.just(event);
    }

    // ==================== Helpers ====================

    private static String extractTextFromBlocks(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String extractTextFromMsg(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        return extractTextFromBlocks(msg.getContent());
    }

    private static Msg appendTextToMsg(Msg original, String appendText) {
        List<ContentBlock> newContent = new ArrayList<>(original.getContent());
        // Find or create a TextBlock to append to
        boolean appended = false;
        List<ContentBlock> modified = new ArrayList<>();
        for (ContentBlock block : newContent) {
            if (block instanceof TextBlock textBlock && !appended) {
                modified.add(TextBlock.builder().text(textBlock.getText() + appendText).build());
                appended = true;
            } else {
                modified.add(block);
            }
        }
        if (!appended) {
            modified.add(TextBlock.builder().text(appendText).build());
        }
        return Msg.builder()
                .id(original.getId())
                .name(original.getName())
                .role(original.getRole())
                .content(modified)
                .metadata(original.getMetadata())
                .timestamp(original.getTimestamp())
                .build();
    }

    /** Stores captured data from a single tool result. */
    private static class CapturedToolResult {
        String rawText;
        final List<String> entities = new ArrayList<>();
        final List<Double> numbers = new ArrayList<>();
    }
}
