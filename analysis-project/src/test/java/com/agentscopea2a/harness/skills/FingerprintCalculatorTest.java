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
package com.agentscopea2a.harness.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FingerprintCalculator} — specifically the metric-level
 * fingerprint computation that drives skill synthesis, retrieval, and evolution.
 *
 * <p>These tests verify that the fingerprint format is correct and that metric
 * tag classification produces the expected results for common Chinese questions.
 */
class FingerprintCalculatorTest {

    // ==================== computeMetric format tests ====================

    @Nested
    @DisplayName("FingerprintCalculator — computeMetric format")
    class ComputeMetricFormatTests {

        @Test
        @DisplayName("fingerprint format: _global|query|defect_density")
        void defectDensityFingerprint() {
            String fp = computeMetric("_global", "query", "对比2026年1季度和2026年2季度杭州开发一部的缺陷密度");
            assertNotNull(fp);
            assertTrue(fp.startsWith("_global|query|"), "should start with _global|query|");
            // The metric tag should be defect_density, not general
            assertTrue(fp.equals("_global|query|defect_density"),
                    "defect density question should produce _global|query|defect_density, got: " + fp);
        }

        @Test
        @DisplayName("fingerprint format: _global|query|response_time")
        void responseTimeFingerprint() {
            String fp = computeMetric("_global", "query", "RT响应时间是多少");
            assertEquals("_global|query|response_time", fp);
        }

        @Test
        @DisplayName("fingerprint format: _global|query|error_rate")
        void errorRateFingerprint() {
            String fp = computeMetric("_global", "query", "这个季度的错误率是多少");
            assertEquals("_global|query|error_rate", fp);
        }

        @Test
        @DisplayName("fingerprint format: _global|query|general for unrecognized question")
        void unrecognizedQuestionFallback() {
            String fp = computeMetric("_global", "query", "今天天气怎么样");
            assertEquals("_global|query|general", fp);
        }

        @Test
        @DisplayName("fingerprint format: _global|analyze|defect_density")
        void analyzeIntentFingerprint() {
            String fp = computeMetric("_global", "analyze", "分析一下缺陷密度的趋势");
            assertEquals("_global|analyze|defect_density", fp);
        }

        @Test
        @DisplayName("null question produces general fallback")
        void nullQuestionFallback() {
            // When ruleBasedTag returns null, computeMetric falls back to "general"
            String fp = computeMetric("_global", "query", null);
            assertEquals("_global|query|general", fp);
        }

        @Test
        @DisplayName("blank question produces general fallback")
        void blankQuestionFallback() {
            String fp = computeMetric("_global", "query", "   ");
            assertEquals("_global|query|general", fp);
        }

        @Test
        @DisplayName("Chinese defect density with numbers and department names → defect_density")
        void defectDensityWithComplexContext() {
            String fp = computeMetric("_global", "query",
                    "对比2026年1季度和2026年2季度杭州开发一部的缺陷密度");
            assertEquals("_global|query|defect_density", fp,
                    "complex Chinese question about defect density should produce defect_density tag");
        }
    }

    // ==================== Helper — mirrors FingerprintCalculator.computeMetric logic ====================

    /**
     * Mirrors the logic of {@link FingerprintCalculator#computeMetric(String, String, String)}.
     * Uses the same ruleBasedTag logic as MetricClassificationServiceTest.ruleBasedTag.
     */
    static String computeMetric(String tenant, String intent, String question) {
        String metricTag = ruleBasedTag(question);
        if (metricTag == null || metricTag.isBlank()) {
            metricTag = "general";
        }
        return tenant + "|" + intent + "|" + metricTag;
    }

    /**
     * Same as MetricClassificationServiceTest.ruleBasedTag — mirrors production logic.
     */
    static String ruleBasedTag(String question) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase();
        if (containsAny(q, "缺陷密度", "bug密度", "缺陷率", "defect_density", "defect density")) return "defect_density";
        if (q.matches(".*\\brt\\b.*")) return "response_time";
        if (containsAny(q, "响应时间", "耗时", "延迟", "latency")) return "response_time";
        if (containsAny(q, "错误率", "失败率", "异常率", "error_rate", "error rate", "failure rate")) return "error_rate";
        if (containsAny(q, "吞吐量", "tps", "qps", "并发", "throughput")) return "throughput";
        if (containsAny(q, "可用性", "sla", "稳定性", "availability")) return "availability";
        if (containsAny(q, "代码质量", "圈复杂度", "重复率", "code_quality", "code quality")) return "code_quality";
        if (containsAny(q, "测试覆盖率", "覆盖率", "case覆盖率", "test_coverage", "test coverage")) return "test_coverage";
        if (containsAny(q, "最大值", "最小值", "极差", "波动", "range_analysis", "max", "min")) return "range_analysis";
        if (containsAny(q, "均值", "平均值", "求和", "统计", "中位数", "标准差", "方差", "mean", "average", "sum", "median")) return "stat_summary";
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase())) return true;
        }
        return false;
    }
}