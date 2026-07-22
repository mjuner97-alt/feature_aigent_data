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
package com.agentscopea2a.v2.verify;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * Best-effort Decision Trace extractor (V3.0 design §6.2). Pulls a compact decision summary out of
 * a {@code PostReasoningEvent.getReasoningMessage()} - never stores the full chain-of-thought
 * (cost / privacy / vendor constraints).
 *
 * <p>Phase 1 keeps this deliberately simple: the reasoning text (truncated) becomes the decision
 * field. Decision-points / assumptions parsing can be refined later - the trace is for audit and
 * replay, not for pass/fail, so an empty-list entry is acceptable when the model emits no usable
 * structured reasoning.
 */
public final class DecisionTraceExtractor {

    private static final int MAX_DECISION_LEN = 200;

    private DecisionTraceExtractor() {
    }

    public static DecisionTraceEntry extract(String agentId, Msg reasoningMsg) {
        if (reasoningMsg == null) {
            return new DecisionTraceEntry(agentId, List.of(), List.of(), List.of(), "", 0.5);
        }
        String text = reasoningMsg.getTextContent();
        if (text == null) {
            text = "";
        }
        text = text.trim();
        String decision = text.length() > MAX_DECISION_LEN
                ? text.substring(0, MAX_DECISION_LEN) + "..."
                : text;
        return new DecisionTraceEntry(agentId, List.of(), List.of(), List.of(), decision, 0.5);
    }
}
