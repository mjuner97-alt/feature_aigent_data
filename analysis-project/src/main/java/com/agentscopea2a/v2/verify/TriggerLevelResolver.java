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

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the verification trigger level (V3.0 design §17, LOW / MEDIUM / HIGH) from the user query.
 * The level controls verification depth: LOW = Rule Engine only (no LLM), MEDIUM = Rule + Semantic,
 * HIGH = Rule + Semantic + Critic (with counterfactual).
 */
@Component
public class TriggerLevelResolver {

    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";

    private final Set<String> highKeywords;
    private final int multiTableThreshold;

    public TriggerLevelResolver(HarnessRunnerProperties properties) {
        this.highKeywords = parseCsv(properties.getVerification().getTrigger().getHighKeywords());
        this.multiTableThreshold = properties.getVerification().getTrigger().getMultiTableThreshold();
    }

    public String resolveLevel(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return MEDIUM;
        }
        String q = userQuery.toLowerCase();
        for (String kw : highKeywords) {
            if (!kw.isBlank() && userQuery.contains(kw)) {
                return HIGH;
            }
        }
        // Very short, single-intent queries get the cheap LOW path.
        if (userQuery.length() < 15 && !q.contains("对比") && !q.contains("比较") && !q.contains("趋势")) {
            return LOW;
        }
        return MEDIUM;
    }

    public boolean llmVerifyEnabled(String level) {
        return MEDIUM.equals(level) || HIGH.equals(level);
    }

    public boolean criticEnabled(String level) {
        return HIGH.equals(level);
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
