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
 * Critic sub-agent output (V3.0 design §9.4). {@code adversarialScore} is merged into the verdict's
 * adversarial dimension by {@link TrustScoreCalculator}; {@code holes} + counterfactual flag are
 * surfaced to the dashboard / repair policy.
 */
public record CriticResult(
        int adversarialScore,
        boolean counterfactualFragile,
        String counterfactualNote,
        List<Hole> holes,
        String summary) {

    public static record Hole(String type, String description, String evidence) {
    }

    public static CriticResult unverified(String reason) {
        return new CriticResult(100, false, "", List.of(), "critic unavailable: " + reason);
    }
}
