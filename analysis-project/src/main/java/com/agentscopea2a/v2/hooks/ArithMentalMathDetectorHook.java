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

import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects when the LLM does arithmetic in its head instead of calling the {@code arith} tool,
 * and logs a warn so the prompt-engineering team can spot drift.
 *
 * <p>Why this exists: {@link com.agentscopea2a.v2.tools.ArithTool} was added because the
 * in-house LLM makes arithmetic mistakes (see memory {@code arith_tool_design_philosophy}).
 * The AGENTS.md prompt says "all arithmetic must go through arith", but prompts drift and
 * models change. This hook is the cheap observability layer that surfaces drift without
 * blocking the response - it just logs at WARN.
 *
 * <p>Heuristic: scan the last assistant message for a digit-operator-digit pattern
 * (digit-operator-digit, including Unicode multiply and divide signs). If matched AND
 * no {@code arith} tool was called in this turn, log a warn with the offending snippet.
 * False positives (version numbers, dates) are fine - this is a soft signal, not enforcement.
 *
 * <p>Runs at PostCall so the full turn (tool calls + final assistant reply) is visible.
 */
public class ArithMentalMathDetectorHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ArithMentalMathDetectorHook.class);

    /**
     * Matches "5+3", "20% x 100", "1.5 / 2", etc. The [\d.]+ lets decimals through.
     * Uses regex Unicode escapes (× = multiplication sign, ÷ = division sign)
     * instead of literal characters to dodge javac encoding edge cases.
     */
    private static final Pattern ARITH_PATTERN =
            Pattern.compile("\\d[\\d.]*\\s*[+\\-*/\\u00D7\\u00F7%]\\s*\\d[\\d.]*");

    private static final String ARITH_TOOL_NAME = "arith";
    private static final int SNIPPET_RADIUS = 40;

    private volatile RuntimeContext currentCtx;

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    public int priority() {
        // After SkillEvolutionHook (60) so we don't block the failure-feedback loop.
        return 70;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostCallEvent e)) {
            return Mono.just(event);
        }
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> scan(e, ctx))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) scan(e, currentCtx);
                }))
                .then(Mono.just(event));
    }

    private void scan(PostCallEvent event, RuntimeContext ctx) {
        try {
            Memory mem = event.getMemory();
            if (mem == null) return;
            List<Msg> msgs = mem.getMessages();
            if (msgs == null || msgs.isEmpty()) return;

            String lastAssistantText = extractLastAssistantText(msgs);
            if (lastAssistantText == null || lastAssistantText.isEmpty()) return;

            if (!containsArithmetic(lastAssistantText)) return;

            if (calledArithThisTurn(msgs)) {
                // All good - the LLM did call arith. The arithmetic in the reply is just
                // restating the result.
                return;
            }

            String snippet = snippetAround(lastAssistantText);
            log.warn("[ARITH_MENTAL_MATH] LLM produced arithmetic without calling arith tool. "
                    + "snippet=\"{}\" session={}", snippet, ctx.getSessionId());
        } catch (Exception ex) {
            log.debug("ArithMentalMathDetectorHook scan failed: {}", ex.getMessage());
        }
    }

    private static String extractLastAssistantText(List<Msg> msgs) {
        String last = null;
        for (Msg m : msgs) {
            if (m.getRole() != MsgRole.ASSISTANT) continue;
            String text = collectText(m);
            if (!text.isBlank()) last = text;
        }
        return last;
    }

    private static String collectText(Msg m) {
        if (m == null || m.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean containsArithmetic(String text) {
        return ARITH_PATTERN.matcher(text).find();
    }

    private static boolean calledArithThisTurn(List<Msg> msgs) {
        // Walk back from the end: any arith ToolUseBlock after the last USER message
        // counts as "this turn".
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.getRole() == MsgRole.USER) break;
            if (m.getContent() == null) continue;
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ToolUseBlock tu
                        && ARITH_TOOL_NAME.equalsIgnoreCase(tu.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String snippetAround(String text) {
        java.util.regex.Matcher m = ARITH_PATTERN.matcher(text);
        if (!m.find()) return "";
        int start = Math.max(0, m.start() - SNIPPET_RADIUS);
        int end = Math.min(text.length(), m.end() + SNIPPET_RADIUS);
        return text.substring(start, end).replace('\n', ' ');
    }
}
