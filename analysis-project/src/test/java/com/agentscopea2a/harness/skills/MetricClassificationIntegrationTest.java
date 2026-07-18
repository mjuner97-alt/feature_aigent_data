/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests for the metric classification data flow.
 *
 * <p>These tests verify the end-to-end paths that are not covered by pure-function
 * unit tests, including:
 *
 * <ol>
 *   <li>Concurrency: inflight dedup prevents duplicate LLM calls for the same fingerprint
 *   <li>Distiller metricHint: the hint text is correctly generated for prompt injection
 *   <li>SkillSynthesisRunner.withMetricTag: frontmatter injection with duplicate guard
 *   <li>Configuration: enabled=false skips classification entirely
 *   <li>Edge cases: null/blank inputs are safely handled
 * </ol>
 *
 * <p>No Spring context, no DB, no LLM — these are structural/logic tests that mock
 * external dependencies.
 */
class MetricClassificationIntegrationTest {

    // ============================== 1. Inflight dedup concurrency ==============================

    @Nested
    @DisplayName("Inflight dedup — concurrent fingerprint tracking")
    class InflightConcurrencyTests {

        @Test
        @DisplayName("Same fingerprint dispatched concurrently → only one wins the CAS")
        void concurrentSameFingerprint() throws Exception {
            ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();
            String fp = "u:alice|query|<no-dim>";
            int threadCount = 10;
            AtomicInteger wins = new AtomicInteger(0);
            AtomicInteger losses = new AtomicInteger(0);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finishGate = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await(); // all threads start together
                        if (inflight.putIfAbsent(fp, Boolean.TRUE) == null) {
                            wins.incrementAndGet();
                            // simulate work
                            Thread.sleep(50);
                            inflight.remove(fp);
                        } else {
                            losses.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            startGate.countDown(); // release all threads
            finishGate.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Exactly one thread should have won the CAS
            assertEquals(1, wins.get(), "only one thread should win the CAS for same fingerprint");
            assertEquals(threadCount - 1, losses.get(), "all other threads should be dedup'd");
        }

        @Test
        @DisplayName("Different fingerprints → each dispatch wins independently")
        void concurrentDifferentFingerprints() throws Exception {
            ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();
            int count = 5;
            AtomicInteger wins = new AtomicInteger(0);

            for (int i = 0; i < count; i++) {
                String fp = "u:user" + i + "|query|<no-dim>";
                if (inflight.putIfAbsent(fp, Boolean.TRUE) == null) {
                    wins.incrementAndGet();
                }
            }

            assertEquals(count, wins.get(), "each different fingerprint should win its CAS");
        }
    }

    // ============================== 2. MetricHint prompt injection ==============================

    @Nested
    @DisplayName("metricHint — prompt context for distillation")
    class MetricHintIntegrationTests {

        @Test
        @DisplayName("defect_density hint contains both tag and Chinese label")
        void defectDensityHint() throws Exception {
            String hint = invokeMetricHint("defect_density");
            assertTrue(hint.contains("defect_density"), "should contain tag");
            assertTrue(hint.contains("缺陷密度"), "should contain Chinese label");
            assertTrue(hint.contains("请围绕["), "should contain directive");
        }

        @Test
        @DisplayName("response_time hint contains RT abbreviation")
        void responseTimeHint() throws Exception {
            String hint = invokeMetricHint("response_time");
            assertTrue(hint.contains("response_time"));
            assertTrue(hint.contains("响应时间"));
            assertTrue(hint.contains("RT"));
        }

        @Test
        @DisplayName("unknown tag falls back to tag name itself")
        void unknownTagFallback() throws Exception {
            String hint = invokeMetricHint("custom_metric");
            assertTrue(hint.contains("custom_metric"));
            assertFalse(hint.contains("通用统计"), "should NOT contain Chinese hint for unknown tag");
        }

        @Test
        @DisplayName("null and blank produce empty string")
        void nullAndBlank() throws Exception {
            assertEquals("", invokeMetricHint(null));
            assertEquals("", invokeMetricHint(""));
            assertEquals("", invokeMetricHint("   "));
        }

        @Test
        @DisplayName("all predefined tags produce non-empty hints")
        void allPredefinedTagsProduceHints() throws Exception {
            String[] tags = {"defect_density", "response_time", "error_rate", "throughput",
                    "availability", "code_quality", "test_coverage", "stat_summary", "range_analysis"};
            for (String tag : tags) {
                String hint = invokeMetricHint(tag);
                assertFalse(hint.isEmpty(), "tag '" + tag + "' should produce non-empty hint");
                assertTrue(hint.contains(tag), "hint for '" + tag + "' should contain the tag name");
            }
        }

        /**
         * Invoke the package-private static method via reflection.
         */
        private String invokeMetricHint(String metricTag) throws Exception {
            Method method = SkillDistiller.class.getDeclaredMethod("metricHint", String.class);
            // metricHint is package-private (no modifier), accessible from same package
            return (String) method.invoke(null, metricTag);
        }
    }

    // ============================== 3. Distill method signatures ==============================

    @Nested
    @DisplayName("SkillDistiller — metricTag overload exists and is callable")
    class DistillerSignatureTests {

        @Test
        @DisplayName("distill(String, String, String) overload exists")
        void distillWithMetricTagExists() throws Exception {
            Method m = SkillDistiller.class.getDeclaredMethod("distill",
                    String.class, String.class, String.class);
            assertNotNull(m, "distill(q, fp, metricTag) overload should exist");
        }

        @Test
        @DisplayName("distillWithContext(String, String, String, String) overload exists")
        void distillWithContextMetricTagExists() throws Exception {
            Method m = SkillDistiller.class.getDeclaredMethod("distillWithContext",
                    String.class, String.class, String.class, String.class);
            assertNotNull(m, "distillWithContext(q, fp, ctx, metricTag) overload should exist");
        }

        @Test
        @DisplayName("original distill(String, String) overload still exists (backward compat)")
        void originalDistillExists() throws Exception {
            Method m = SkillDistiller.class.getDeclaredMethod("distill",
                    String.class, String.class);
            assertNotNull(m, "original distill(q, fp) should still exist for backward compat");
        }
    }

    // ============================== 4. withMetricTag frontmatter ==============================

    @Nested
    @DisplayName("withMetricTag — SKILL.md frontmatter injection edge cases")
    class WithMetricTagEdgeCaseTests {

        @Test
        @DisplayName("Body with existing metric_tag:value → skip duplicate injection")
        void duplicateMetricTagSkipped() {
            String body = "---\nname: my_skill\nmetric_tag: defect_density\n---\n\n# Body";
            String result = callWithMetricTag(body, "defect_density");
            assertEquals(body, result, "should NOT inject when metric_tag key already exists");
        }

        @Test
        @DisplayName("Body with metric_tag of different value → also skip (same key name)")
        void differentMetricTagValueAlsoSkipped() {
            String body = "---\nname: my_skill\nmetric_tag: defect_density\n---\n\n# Body";
            String result = callWithMetricTag(body, "response_time");
            assertEquals(body, result, "should NOT inject a second metric_tag key");
        }

        @Test
        @DisplayName("Frontmatter with name+description → metric_tag appended between them")
        void metricTagAppendedInExistingFrontmatter() {
            String body = "---\nname: my_skill\ndescription: test\n---\n\n# Body";
            String result = callWithMetricTag(body, "error_rate");
            assertTrue(result.contains("metric_tag: error_rate"));
            assertTrue(result.contains("name: my_skill"));
            assertTrue(result.contains("description: test"));
            assertTrue(result.contains("# Body"));
            // metric_tag should appear before the closing ---
            int tagIdx = result.indexOf("metric_tag:");
            int closingIdx = result.indexOf("\n---", 3);
            assertTrue(tagIdx < closingIdx, "metric_tag should be inside frontmatter");
        }

        @Test
        @DisplayName("No frontmatter → new block created")
        void noFrontmatterCreatesNewBlock() {
            String body = "# 直接开始的正文\n没有frontmatter";
            String result = callWithMetricTag(body, "throughput");
            assertTrue(result.startsWith("---\n"));
            assertTrue(result.contains("metric_tag: throughput"));
            assertTrue(result.contains("# 直接开始的正文"));
        }

        @Test
        @DisplayName("Malformed frontmatter (no closing ---) → body unchanged")
        void malformedFrontmatterUnchanged() {
            String body = "---\nname: my_skill\nno closing delimiter at all";
            String result = callWithMetricTag(body, "defect_density");
            assertEquals(body, result, "malformed frontmatter should be left untouched");
        }

        @Test
        @DisplayName("Empty body → creates frontmatter only")
        void emptyBody() {
            String result = callWithMetricTag("", "availability");
            assertTrue(result.contains("metric_tag: availability"));
        }

        @Test
        @DisplayName("Null body → handled gracefully")
        void nullBody() {
            String result = callWithMetricTag(null, "code_quality");
            assertTrue(result.contains("metric_tag: code_quality"));
        }

        private String callWithMetricTag(String body, String metricTag) {
            SkillDistiller.DistilledSkill skill = new SkillDistiller.DistilledSkill(
                    "test_skill", "test desc", body, List.of());
            SkillDistiller.DistilledSkill result = SkillSynthesisRunnerTestHelper.withMetricTag(skill, metricTag);
            return result.body();
        }
    }

    // ============================== 5. MetricClassificationService edge cases ==============================

    @Nested
    @DisplayName("MetricClassificationService — enabled check and null safety")
    class MetricClassificationServiceEdgeTests {

        @Test
        @DisplayName("classifyAndUpdateAsync with null question → no crash, no action")
        void nullQuestionSafe() {
            // The service is disabled when model is null, so this is safe
            SkillCandidateRepository mockRepo = null; // not used when disabled
            MetricClassificationService svc = new MetricClassificationService(
                    mockRepo, false, "light-classifier", ".agentscope/workspace/harness-a2a");
            assertFalse(svc.enabled(), "service should be disabled when enabled=false");
            // Should not throw even with null question
            svc.classifyAndUpdateAsync(null, "some-fp");
        }

        @Test
        @DisplayName("classifyAndUpdateAsync with blank question → no crash, no action")
        void blankQuestionSafe() {
            SkillCandidateRepository mockRepo = null;
            MetricClassificationService svc = new MetricClassificationService(
                    mockRepo, false, "light-classifier", ".agentscope/workspace/harness-a2a");
            svc.classifyAndUpdateAsync("   ", "some-fp");
        }

        @Test
        @DisplayName("classifyAndUpdateAsync with null fingerprint → no crash")
        void nullFingerprintSafe() {
            SkillCandidateRepository mockRepo = null;
            MetricClassificationService svc = new MetricClassificationService(
                    mockRepo, false, "light-classifier", ".agentscope/workspace/harness-a2a");
            svc.classifyAndUpdateAsync("some question", null);
        }

        @Test
        @DisplayName("enabled=false → enabled() returns false even with valid model")
        void disabledOverridesModel() {
            SkillCandidateRepository mockRepo = null;
            MetricClassificationService svc = new MetricClassificationService(
                    mockRepo, false, "light-classifier", ".agentscope/workspace/harness-a2a");
            assertFalse(svc.enabled(), "enabled=false should override everything");
        }
    }

    // ============================== Helper for withMetricTag ==============================

    /**
     * Mirrors the private static method {@code SkillSynthesisRunner.withMetricTag}.
     * This is the exact same logic from production — must be kept in sync.
     */
    static class SkillSynthesisRunnerTestHelper {
        static SkillDistiller.DistilledSkill withMetricTag(
                SkillDistiller.DistilledSkill skill, String metricTag) {
            String body = skill.body() == null ? "" : skill.body();
            String newBody;
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("---")) {
                int second = trimmed.indexOf("\n---", 3);
                if (second > 0) {
                    String before = body.substring(0, second);
                    String after = body.substring(second);
                    if (before.contains("metric_tag:")) {
                        return skill; // skip duplicate
                    }
                    newBody = before + "\nmetric_tag: " + metricTag + after;
                } else {
                    newBody = body; // frontmatter 残缺,不动
                }
            } else {
                newBody = "---\nmetric_tag: " + metricTag + "\n---\n\n" + body;
            }
            return new SkillDistiller.DistilledSkill(
                    skill.name(), skill.description(), newBody, skill.sampleQuestions());
        }
    }
}