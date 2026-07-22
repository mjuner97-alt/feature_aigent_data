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
 * Semantic Contract models (V3.0 design §11.3, P0). Business semantics are stripped out of the
 * prompt into machine-executable contracts so the Rule Engine can deterministically judge metric
 * direction / aggregation (B4) instead of relying on LLM guessing.
 */
public final class SemanticContracts {

    private SemanticContracts() {
    }

    /**
     * @param directionHigher "worse" (higher value = worse quality, e.g. quality_score) or "better"
     *                        (higher = better, e.g. sales_amount). Drives B4 direction checks.
     */
    public record MetricContract(
            String metricId,
            String metricName,
            String businessDefinition,
            String formula,
            String unit,
            String directionHigher,
            List<String> aggregationAllow,
            List<String> aggregationDeny,
            String owner,
            String version) {
    }

    public record DimensionContract(
            String dimension,
            List<String> allowedValues,
            String version) {
    }

    public record BusinessRuleContract(
            String ruleId,
            String condition,
            String description,
            String version) {
    }

    /** Immutable snapshot of the contracts relevant to one verification (fed into verify.md). */
    public record Snapshot(
            List<MetricContract> metrics,
            List<DimensionContract> dimensions,
            List<BusinessRuleContract> rules) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of());
        }
    }
}
