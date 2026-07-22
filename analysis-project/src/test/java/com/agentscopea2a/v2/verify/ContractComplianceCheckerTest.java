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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the deterministic contract core (V3.0 design §7.4 B4 + §10.2 taxonomy).
 * No Spring / no DB - validates the B4 direction/aggregation checks and repair-type alias mapping
 * that the Rule Engine + Repair Policy rely on.
 */
class ContractComplianceCheckerTest {

    private final ContractComplianceChecker checker = new ContractComplianceChecker();

    private SemanticContracts.Snapshot snapshot() {
        SemanticContracts.MetricContract qualityScore = new SemanticContracts.MetricContract(
                "quality_score", "质量评分", "质量缺陷严重程度", "defect/total", "score", "worse",
                List.of("avg", "trend"), List.of("sum"), "质量部", "v1");
        SemanticContracts.MetricContract salesAmount = new SemanticContracts.MetricContract(
                "sales_amount", "销售额", "销售总额", "sum(sales)", "currency", "better",
                List.of("sum", "avg", "trend"), List.of(), "销售部", "v1");
        return new SemanticContracts.Snapshot(List.of(qualityScore, salesAmount), List.of(), List.of());
    }

    @Test
    void aggregationViolation_isHardFail() {
        List<ContractComplianceChecker.ContractViolation> v =
                checker.check("对质量评分求和得到 100 分", snapshot());
        assertThat(v).anyMatch(c -> c.hardFail() && "quality_score".equals(c.metricId()));
        assertThat(checker.hasHardFail(v)).isTrue();
    }

    @Test
    void directionViolation_isAdvisoryNotHardFail() {
        List<ContractComplianceChecker.ContractViolation> v =
                checker.check("质量评分最高的部门质量最好", snapshot());
        assertThat(v).anyMatch(c -> "quality_score".equals(c.metricId()) && !c.hardFail());
        assertThat(checker.hasHardFail(v)).isFalse();
    }

    @Test
    void correctDirection_noViolation() {
        List<ContractComplianceChecker.ContractViolation> v =
                checker.check("销售额最高的区域是华东", snapshot());
        assertThat(checker.hasHardFail(v)).isFalse();
    }

    @Test
    void repairType_compatAliases() {
        assertThat(RepairType.fromHint("REASONING_FIX")).isEqualTo(RepairType.SEMANTIC_FIX);
        assertThat(RepairType.fromHint("ABORT")).isEqualTo(RepairType.REFUSE);
        assertThat(RepairType.fromHint("CLARIFICATION")).isEqualTo(RepairType.CLARIFY_USER);
        assertThat(RepairType.fromHint("ASK_USER")).isEqualTo(RepairType.CLARIFY_USER);
        assertThat(RepairType.fromHint("")).isEqualTo(RepairType.NONE);
        assertThat(RepairType.fromHint(null)).isEqualTo(RepairType.NONE);
    }

    @Test
    void repairPolicyRule_firstAllowedAndForbid() {
        RepairPolicyRule rule = new RepairPolicyRule("rp", "SEMANTIC_MISMATCH", "HIGH",
                List.of("SEMANTIC_FIX", "CLARIFY_USER"),
                List.of("MODIFY_RESULT", "CHANGE_CONCLUSION"), 1, 10, true);
        assertThat(rule.firstAllowed()).isEqualTo(RepairType.SEMANTIC_FIX);
        // MODIFY_RESULT maps to NONE (not in taxonomy), so it is never an actionable type -
        // anti-gaming is enforced by the taxonomy + allowed_actions, not by forbids() catching it.
        assertThat(rule.forbids(RepairType.SEMANTIC_FIX)).isFalse();
        assertThat(rule.forbids(RepairType.NONE)).isFalse();
    }
}
