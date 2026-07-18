/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the metric classification rule engine, tag parsing, and inflight dedup.
 *
 * <p>These are pure-function tests — no Spring context, no DB, no LLM. They verify
 * the correctness of the rule-based keyword matcher, the LLM response parser,
 * and the inflight fingerprint dedup logic.
 *
 * <p>The helper class mirrors the private static methods of
 * {@link com.agentscopea2a.harness.skills.MetricClassificationService} so tests
 * exercise the exact same logic without reflection. If the production code changes,
 * the helper must be updated in lockstep.
 */
class MetricClassificationServiceTest {

    // ============================== ruleBasedTag ==============================

    @Nested
    @DisplayName("ruleBasedTag — keyword classification")
    class RuleBasedTagTests {

        @Test
        @DisplayName("缺陷密度 → defect_density")
        void defectDensity() {
            assertEquals("defect_density", ruleBasedTag("算一下缺陷密度的均值"));
        }

        @Test
        @DisplayName("Bug密度 → defect_density")
        void bugDensity() {
            assertEquals("defect_density", ruleBasedTag("这个版本的Bug密度是多少"));
        }

        @Test
        @DisplayName("缺陷率 → defect_density")
        void defectRate() {
            assertEquals("defect_density", ruleBasedTag("缺陷率达标了吗"));
        }

        @Test
        @DisplayName("defect density (English) → defect_density")
        void defectDensityEnglish() {
            assertEquals("defect_density", ruleBasedTag("what is the defect density"));
        }

        @Test
        @DisplayName("RT as standalone word → response_time (word boundary)")
        void rtStandalone() {
            assertEquals("response_time", ruleBasedTag("RT 中位数是多少"));
        }

        @Test
        @DisplayName("rt lowercase standalone → response_time")
        void rtLowercase() {
            assertEquals("response_time", ruleBasedTag("rt 的均值"));
        }

        @Test
        @DisplayName("'report' should NOT match response_time (rt is substring, not word)")
        void reportNotResponseTime() {
            String tag = ruleBasedTag("生成报告report");
            assertFalse("response_time".equals(tag),
                    "'rt' in 'report' should not match response_time");
        }

        @Test
        @DisplayName("'export' should NOT match response_time")
        void exportNotResponseTime() {
            String tag = ruleBasedTag("导出export数据");
            assertFalse("response_time".equals(tag),
                    "'rt' in 'export' should not match response_time");
        }

        @Test
        @DisplayName("'sort' should NOT match response_time")
        void sortNotResponseTime() {
            String tag = ruleBasedTag("sort排序");
            assertFalse("response_time".equals(tag),
                    "'rt' in 'sort' should not match response_time");
        }

        @Test
        @DisplayName("响应时间 → response_time")
        void responseTime() {
            assertEquals("response_time", ruleBasedTag("算响应时间的平均值"));
        }

        @Test
        @DisplayName("延迟 → response_time")
        void latency() {
            assertEquals("response_time", ruleBasedTag("延迟是多少"));
        }

        @Test
        @DisplayName("错误率 → error_rate")
        void errorRate() {
            assertEquals("error_rate", ruleBasedTag("这个季度的错误率"));
        }

        @Test
        @DisplayName("吞吐量 → throughput")
        void throughput() {
            assertEquals("throughput", ruleBasedTag("吞吐量均值"));
        }

        @Test
        @DisplayName("可用性 → availability")
        void availability() {
            assertEquals("availability", ruleBasedTag("SLA可用性"));
        }

        @Test
        @DisplayName("代码质量 → code_quality")
        void codeQuality() {
            assertEquals("code_quality", ruleBasedTag("代码质量得分"));
        }

        @Test
        @DisplayName("测试覆盖率 → test_coverage (not stat_summary)")
        void testCoverageNotStatSummary() {
            assertEquals("test_coverage", ruleBasedTag("测试覆盖率平均值"));
        }

        @Test
        @DisplayName("最大值 → range_analysis (not stat_summary)")
        void maxValueNotStatSummary() {
            assertEquals("range_analysis", ruleBasedTag("最大值是多少"));
        }

        @Test
        @DisplayName("均值 → stat_summary")
        void meanStatSummary() {
            assertEquals("stat_summary", ruleBasedTag("求均值"));
        }

        @Test
        @DisplayName("缺陷密度均值 → defect_density (specific wins over generic)")
        void specificWinsOverGeneric() {
            // "缺陷密度" is more specific than "均值"
            assertEquals("defect_density", ruleBasedTag("缺陷密度均值"));
        }

        @Test
        @DisplayName("complex Chinese question about defect density → defect_density")
        void complexChineseDefectDensity() {
            // This is the exact question reported in the bug: fingerprint was _global|query|general
            // instead of _global|query|defect_density
            assertEquals("defect_density",
                    ruleBasedTag("对比2026年1季度和2026年2季度杭州开发一部的缺陷密度"));
        }

        @Test
        @DisplayName("defect density in mixed Chinese-English context → defect_density")
        void defectDensityMixedContext() {
            assertEquals("defect_density",
                    ruleBasedTag("请分析一下defect density的趋势"));
        }

        @Test
        void responseTimeSpecificWins() {
            assertEquals("response_time", ruleBasedTag("响应时间均值"));
        }

        @Test
        @DisplayName("null input → null")
        void nullInput() {
            assertNull(ruleBasedTag(null));
        }

        @Test
        @DisplayName("blank input → null")
        void blankInput() {
            assertNull(ruleBasedTag(""));
            assertNull(ruleBasedTag("   "));
        }

        @Test
        @DisplayName("unrecognized input → null (falls through to LLM)")
        void unrecognizedInput() {
            assertNull(ruleBasedTag("今天天气怎么样"));
        }

        @Test
        @DisplayName("覆盖率 without 测试 prefix → test_coverage")
        void coverageWithoutTestPrefix() {
            assertEquals("test_coverage", ruleBasedTag("覆盖率下降了吗"));
        }

        @Test
        @DisplayName("中文 RT 混合 → response_time")
        void mixedLanguageRt() {
            assertEquals("response_time", ruleBasedTag("RT响应时间"));
        }

        @Test
        @DisplayName("RT at end of sentence → response_time")
        void rtAtEnd() {
            assertEquals("response_time", ruleBasedTag("查看 RT"));
        }
    }

    // ============================== parseTag ==============================

    @Nested
    @DisplayName("parseTag — LLM response parsing")
    class ParseTagTests {

        @Test
        @DisplayName("clean label → exact match")
        void cleanLabel() {
            assertEquals("defect_density", parseTag("defect_density"));
        }

        @Test
        @DisplayName("label with markdown bold → stripped to tag")
        void markdownBold() {
            assertEquals("defect_density", parseTag("**defect_density**"));
        }

        @Test
        @DisplayName("label with backticks → stripped to tag")
        void backtickLabel() {
            assertEquals("response_time", parseTag("`response_time`"));
        }

        @Test
        @DisplayName("label with explanation on next line → first line wins")
        void multilineResponse() {
            assertEquals("error_rate", parseTag("error_rate\nThis means the error rate..."));
        }

        @Test
        @DisplayName("unknown first line but known tag in body → fallback scan")
        void fallbackScan() {
            assertEquals("throughput", parseTag("I think this is about throughput metrics"));
        }

        @Test
        @DisplayName("first line is a valid tag → returns that tag even if body mentions other tags")
        void firstLineWins() {
            // First line "general" is a valid tag → returns "general"
            // The body scan only kicks in when the first line is NOT a valid tag
            assertEquals("general",
                    parseTag("general\nActually it's about defect_density"));
        }

        @Test
        @DisplayName("first line is NOT a valid tag → fallback scan finds specific tag in body")
        void fallbackFindsSpecificInBody() {
            // "I think" is not a valid tag → falls back to body scan → finds "defect_density"
            assertEquals("defect_density",
                    parseTag("I think\nActually it's about defect_density"));
        }

        @Test
        @DisplayName("fallback scans priority order: specific tags win over general mention in body")
        void fallbackPriorityOrder() {
            // "something" is not a valid tag → body scan finds "defect_density" first
            assertEquals("defect_density",
                    parseTag("something about stat_summary and defect_density"));
        }

        @Test
        @DisplayName("truly unknown response → general")
        void trulyUnknown() {
            assertEquals("general", parseTag("something completely unknown"));
        }

        @Test
        @DisplayName("null → null")
        void nullResponse() {
            assertNull(parseTag(null));
        }

        @Test
        @DisplayName("blank → null")
        void blankResponse() {
            assertNull(parseTag(""));
            assertNull(parseTag("   "));
        }

        @Test
        @DisplayName("all valid tags parse correctly")
        void allValidTags() {
            String[] validTags = {"defect_density", "response_time", "error_rate", "throughput",
                    "availability", "code_quality", "test_coverage", "stat_summary",
                    "range_analysis", "general"};
            for (String tag : validTags) {
                assertEquals(tag, parseTag(tag), "tag '" + tag + "' should parse to itself");
            }
        }

        @Test
        @DisplayName("label with surrounding whitespace → trimmed")
        void whitespaceLabel() {
            assertEquals("defect_density", parseTag("  defect_density  "));
        }
    }

    // ============================== inflight dedup ==============================

    @Nested
    @DisplayName("inflight dedup — ConcurrentHashMap-based fingerprint tracking")
    class InflightDedupTests {

        @Test
        @DisplayName("add and remove works correctly")
        void addAndRemove() {
            java.util.Set<String> inflight = java.util.concurrent.ConcurrentHashMap.newKeySet();
            assertTrue(inflight.add("fp1"));
            assertFalse(inflight.add("fp1"), "duplicate add should return false");
            inflight.remove("fp1");
            assertTrue(inflight.add("fp1"), "re-add after remove should succeed");
        }

        @Test
        @DisplayName("multiple fingerprints tracked independently")
        void multipleFingerprints() {
            java.util.Set<String> inflight = java.util.concurrent.ConcurrentHashMap.newKeySet();
            assertTrue(inflight.add("fp1"));
            assertTrue(inflight.add("fp2"));
            assertFalse(inflight.add("fp1"));
            assertFalse(inflight.add("fp2"));
            inflight.remove("fp1");
            assertTrue(inflight.add("fp1"), "fp1 re-added after remove");
            assertFalse(inflight.add("fp2"), "fp2 still in set");
        }

        @Test
        @DisplayName("inflight prevents duplicate dispatch")
        void dedupPreventsDuplicate() {
            java.util.Set<String> inflight = java.util.concurrent.ConcurrentHashMap.newKeySet();
            // First dispatch: succeeds
            assertTrue(inflight.add("u:alice|query|<no-dim>"));
            // Second dispatch for same fingerprint: blocked
            assertFalse(inflight.add("u:alice|query|<no-dim>"));
            // After completion: can dispatch again
            inflight.remove("u:alice|query|<no-dim>");
            assertTrue(inflight.add("u:alice|query|<no-dim>"));
        }
    }

    // ============================== metricHint ==============================

    @Nested
    @DisplayName("metricHint — distillation prompt context generation")
    class MetricHintTests {

        @Test
        @DisplayName("null metricTag → empty string")
        void nullMetricTag() {
            assertEquals("", invokeMetricHint(null));
        }

        @Test
        @DisplayName("blank metricTag → empty string")
        void blankMetricTag() {
            assertEquals("", invokeMetricHint(""));
            assertEquals("", invokeMetricHint("   "));
        }

        @Test
        @DisplayName("defect_density → includes Chinese hint")
        void defectDensityHint() {
            String hint = invokeMetricHint("defect_density");
            assertTrue(hint.contains("defect_density"), "should contain the tag");
            assertTrue(hint.contains("缺陷密度"), "should contain Chinese hint");
        }

        @Test
        @DisplayName("response_time → includes Chinese hint")
        void responseTimeHint() {
            String hint = invokeMetricHint("response_time");
            assertTrue(hint.contains("response_time"));
            assertTrue(hint.contains("响应时间"));
        }

        @Test
        @DisplayName("unknown tag → falls back to tag itself")
        void unknownTagFallback() {
            String hint = invokeMetricHint("custom_metric");
            assertTrue(hint.contains("custom_metric"));
        }

        private String invokeMetricHint(String metricTag) {
            if (metricTag == null || metricTag.isBlank()) return "";
            java.util.Map<String, String> metricHints = java.util.Map.ofEntries(
                    java.util.Map.entry("defect_density", "缺陷密度/Bug密度"),
                    java.util.Map.entry("response_time", "响应时间/延迟/RT"),
                    java.util.Map.entry("error_rate", "错误率/失败率/异常率"),
                    java.util.Map.entry("throughput", "吞吐量/TPS/QPS/并发"),
                    java.util.Map.entry("availability", "可用性/SLA/稳定性"),
                    java.util.Map.entry("code_quality", "代码质量/圈复杂度/重复率"),
                    java.util.Map.entry("test_coverage", "测试覆盖率/覆盖率"),
                    java.util.Map.entry("stat_summary", "通用统计汇总(均值/求和/中位数)"),
                    java.util.Map.entry("range_analysis", "极值范围分析(最大值/最小值/极差)")
            );
            String chineseHint = metricHints.getOrDefault(metricTag, metricTag);
            return "\n**指标分类**: " + metricTag + " (" + chineseHint + ")"
                    + "\n请围绕[" + chineseHint + "]这一指标类别来编写 skill,确保生成的 skill 针对该指标而非泛化描述。\n";
        }
    }

    // ============================== Helper — mirrors production logic ==============================

    /**
     * Mirrors {@link com.agentscopea2a.harness.skills.MetricClassificationService#ruleBasedTag(String)}.
     * MUST be kept in sync with the production code.
     */
    static String ruleBasedTag(String question) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase();
        // Specific metrics first (priority order) — same order as production code
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

    /**
     * Mirrors {@link com.agentscopea2a.harness.skills.MetricClassificationService#parseTag(String)}.
     * MUST be kept in sync with the production code.
     */
    static String parseTag(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.trim().toLowerCase();
        int newlineIdx = cleaned.indexOf('\n');
        String firstLine = (newlineIdx > 0 ? cleaned.substring(0, newlineIdx) : cleaned).trim();
        firstLine = firstLine.replaceAll("[^a-z0-9_]", "");
        return switch (firstLine) {
            case "defect_density", "response_time", "error_rate", "throughput",
                 "availability", "code_quality", "test_coverage", "stat_summary",
                 "range_analysis", "general" -> firstLine;
            default -> {
                String[] tags = {"defect_density", "response_time", "error_rate", "throughput",
                        "availability", "code_quality", "test_coverage", "stat_summary",
                        "range_analysis"};
                for (String t : tags) {
                    if (cleaned.contains(t)) yield t;
                }
                yield "general";
            }
        };
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase())) return true;
        }
        return false;
    }
}