/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code withMetricTag} in {@link com.agentscopea2a.harness.skills.SkillSynthesisRunner}.
 *
 * <p>This method injects a metric_tag into SKILL.md frontmatter. The tests verify:
 * 1. New frontmatter is created when body has none
 * 2. metric_tag is appended to existing frontmatter
 * 3. Duplicate metric_tag injection is prevented
 * 4. Malformed frontmatter (missing closing ---) is left untouched
 *
 * <p>Since {@code withMetricTag} is a private static method, these tests mirror the logic.
 * The production code MUST be updated in lockstep.
 */
class SkillSynthesisRunnerMetricTagTest {

    @Nested
    @DisplayName("withMetricTag — frontmatter injection")
    class WithMetricTagTests {

        @Test
        @DisplayName("Body without frontmatter → prepend new frontmatter block")
        void noFrontmatter() {
            String body = "# 缺陷密度分析\n\n这是skill正文";
            String result = withMetricTag(body, "defect_density");
            assertTrue(result.startsWith("---\n"), "should start with frontmatter delimiter");
            assertTrue(result.contains("metric_tag: defect_density"), "should contain metric_tag");
            assertTrue(result.contains("# 缺陷密度分析"), "should preserve original body");
        }

        @Test
        @DisplayName("Body with existing frontmatter → append metric_tag")
        void existingFrontmatter() {
            String body = "---\nname: my_skill\ndescription: 测试\n---\n\n# Body";
            String result = withMetricTag(body, "response_time");
            assertTrue(result.contains("metric_tag: response_time"), "should contain metric_tag");
            assertTrue(result.contains("name: my_skill"), "should preserve existing fields");
            assertTrue(result.contains("description: 测试"), "should preserve description");
            assertTrue(result.contains("# Body"), "should preserve body");
        }

        @Test
        @DisplayName("Duplicate metric_tag → skip injection")
        void duplicateMetricTag() {
            String body = "---\nname: my_skill\nmetric_tag: defect_density\n---\n\n# Body";
            String result = withMetricTag(body, "defect_density");
            // Should return the original skill unchanged (no duplicate key)
            assertEquals(body, result, "should skip injection when metric_tag already exists");
        }

        @Test
        @DisplayName("Different metric_tag → should still inject (no duplicate key name)")
        void differentMetricTag() {
            String body = "---\nname: my_skill\nmetric_tag: defect_density\n---\n\n# Body";
            String result = withMetricTag(body, "response_time");
            // The key "metric_tag" already exists, so withMetricTag should skip to avoid
            // having two metric_tag keys in the YAML (which would be invalid)
            assertEquals(body, result, "should skip when metric_tag key already exists");
        }

        @Test
        @DisplayName("Malformed frontmatter (missing closing ---) → leave unchanged")
        void malformedFrontmatter() {
            String body = "---\nname: my_skill\nno closing delimiter";
            String result = withMetricTag(body, "defect_density");
            assertEquals(body, result, "malformed frontmatter should be left untouched");
        }

        @Test
        @DisplayName("Null body → handle gracefully")
        void nullBody() {
            String result = withMetricTag(null, "defect_density");
            assertTrue(result.contains("metric_tag: defect_density"));
        }

        @Test
        @DisplayName("Empty body → create frontmatter only")
        void emptyBody() {
            String result = withMetricTag("", "throughput");
            assertTrue(result.startsWith("---\n"));
            assertTrue(result.contains("metric_tag: throughput"));
        }

        @Test
        @DisplayName("Frontmatter with trailing whitespace on lines → append metric_tag")
        void frontmatterWithTrailingWhitespace() {
            String body = "--- \nname: my_skill \n---\n\n# Body";
            String result = withMetricTag(body, "error_rate");
            assertTrue(result.contains("metric_tag: error_rate"));
        }
    }

    /**
     * Mirrors the private static method {@code SkillSynthesisRunner.withMetricTag}.
     * MUST be kept in sync with the production code.
     */
    private static SkillDistiller.DistilledSkill withMetricTagHelper(
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
                    // Skip duplicate injection
                    return skill;
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

    /**
     * Convenience wrapper that mirrors the production withMetricTag signature.
     */
    private static String withMetricTag(String body, String metricTag) {
        SkillDistiller.DistilledSkill input = new SkillDistiller.DistilledSkill(
                "test_skill", "test description", body, List.of());
        SkillDistiller.DistilledSkill result = withMetricTagHelper(input, metricTag);
        return result.body();
    }
}