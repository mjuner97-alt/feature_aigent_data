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

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract compliance checker (V3.0 design §7.4 B4, P0). Deterministically judges the candidate
 * conclusion against the {@link SemanticContracts.Snapshot}:
 *
 * <ul>
 *   <li><b>Aggregation violation</b> (hard fail): the conclusion applies a forbidden aggregation
 *       (e.g. {@code sum} on {@code quality_score} whose contract denies sum). This is unambiguous,
 *       so it short-circuits the LLM and dispatches {@link RepairType#SEMANTIC_FIX}.</li>
 *   <li><b>Direction violation</b> (advisory): the conclusion reads a {@code higher=worse} metric
 *       as "higher is better". Direction reading is fuzzy (e.g. "缺陷密度最低=最好" is correct), so
 *       these are <b>not</b> hard-failed - they're passed to the Semantic Engine as a contract hint
 *       for the LLM to confirm (V3.0 design §11.4 "模糊处交 Semantic Engine").</li>
 * </ul>
 */
@Component
public class ContractComplianceChecker {

    public record ContractViolation(
            String metricId,
            String errorType,
            String severity,
            String description,
            String evidence,
            boolean hardFail) {
    }

    public List<ContractViolation> check(String conclusion, SemanticContracts.Snapshot snapshot) {
        List<ContractViolation> violations = new ArrayList<>();
        if (conclusion == null || conclusion.isBlank() || snapshot == null || snapshot.metrics() == null) {
            return violations;
        }
        for (SemanticContracts.MetricContract m : snapshot.metrics()) {
            if (!mentionsMetric(conclusion, m)) {
                continue;
            }
            // Aggregation: forbidden sum on a deny-sum metric.
            if (deniesSum(m) && assertsSumAggregation(conclusion)) {
                violations.add(new ContractViolation(
                        m.metricId(), "SEMANTIC_MISMATCH", "HIGH",
                        "指标 " + m.metricName() + " 禁止 sum 聚合(契约 deny=sum), 结论却做了求和",
                        "contract:" + m.metricId() + ".aggregation.deny=sum",
                        true));
            }
            // Direction: higher=worse metric read as "higher is better" - advisory (fuzzy).
            if ("worse".equalsIgnoreCase(m.directionHigher()) && assertsHigherIsBetter(conclusion)) {
                violations.add(new ContractViolation(
                        m.metricId(), "SEMANTIC_MISMATCH", "HIGH",
                        "指标 " + m.metricName() + " 越高越差(direction=worse), 结论疑似把高分当作更好",
                        "contract:" + m.metricId() + ".direction=worse",
                        false));
            }
        }
        return violations;
    }

    /** True if any contract violation is a hard fail (aggregation). */
    public boolean hasHardFail(List<ContractViolation> violations) {
        if (violations == null) return false;
        for (ContractViolation v : violations) {
            if (v.hardFail()) return true;
        }
        return false;
    }

    private boolean mentionsMetric(String text, SemanticContracts.MetricContract m) {
        if (text == null) return false;
        return (m.metricId() != null && text.contains(m.metricId()))
                || (m.metricName() != null && text.contains(m.metricName()));
    }

    private boolean deniesSum(SemanticContracts.MetricContract m) {
        if (m.aggregationDeny() == null) return false;
        for (String d : m.aggregationDeny()) {
            if ("sum".equalsIgnoreCase(d)) return true;
        }
        return false;
    }

    private boolean assertsSumAggregation(String text) {
        return text.contains("总和") || text.contains("合计") || text.contains("相加")
                || text.contains("求和") || text.toLowerCase().contains("sum(");
    }

    private boolean assertsHigherIsBetter(String text) {
        return text.contains("越高越好") || text.contains("分越高") && text.contains("好")
                || (text.contains("最好") || text.contains("最优") || text.contains("最佳"));
    }
}
