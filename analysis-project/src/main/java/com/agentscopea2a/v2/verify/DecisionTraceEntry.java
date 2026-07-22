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

import java.util.List;

/**
 * Decision Trace entry - replaces full Chain-of-Thought (V3.0 design §6.2).
 *
 * <p>Only decision points + assumptions + evidence references are stored, never the full
 * reasoning text (cost / privacy / vendor constraints). Extracted best-effort from
 * {@code PostReasoningEvent.getReasoningMessage()} by {@link DecisionTraceExtractor}; when the
 * model emits no usable reasoning text the entry may carry empty lists.
 */
public record DecisionTraceEntry(
        String agentId,
        List<String> decisionPoints,
        List<String> assumptions,
        List<String> evidenceUsed,
        String decision,
        double confidence) {
}
