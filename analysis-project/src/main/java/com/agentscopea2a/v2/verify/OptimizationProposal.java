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

/**
 * One optimization proposal from the {@link QualityOptimizationLoop} (V3.0 design §26, P2).
 * Proposals are <b>human-review only</b> - the loop never auto-applies; it surfaces candidates for
 * rule/contract distillation, capability-boundary marking, or threshold/weight tuning.
 *
 * @param type        RULE_CANDIDATE / CAPABILITY_BOUNDARY / THRESHOLD_TUNE
 * @param description actionable suggestion
 * @param evidence    the signal that triggered it
 * @param count       signal frequency / magnitude
 */
public record OptimizationProposal(
        String type,
        String description,
        String evidence,
        int count) {
}
