/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for the YAML frontmatter helpers introduced in PR1.
 *
 * <p>No Spring, no DB — these guard the two behaviours that LLMs are most likely to break:
 * stripping LLM-supplied frontmatter (to prevent metadata drift) and rendering our managed
 * block (to keep the SKILL.md format stable across versions).
 */
class SkillSaveToolTest {

    // ============================== stripFrontmatter ==============================

    @Test
    void stripFrontmatter_removesLlmSuppliedYaml() {
        String input =
                "---\n"
                        + "name: foo\n"
                        + "description: bar\n"
                        + "version: 99\n"
                        + "---\n"
                        + "\n"
                        + "# Body\n"
                        + "some content";
        String result = SkillSaveTool.stripFrontmatter(input);
        assertEquals("# Body\nsome content", result);
    }

    @Test
    void stripFrontmatter_keepsBodyWhenNoFrontmatter() {
        String input = "# Plain body\nNo yaml at all";
        assertEquals(input, SkillSaveTool.stripFrontmatter(input));
    }

    @Test
    void stripFrontmatter_doesNotStripMidDocumentDashes() {
        // Frontmatter only counts when at offset 0. A horizontal rule mid-doc must survive.
        String input = "# Title\n\n---\nname: not_yaml\n---\n\nstill body";
        assertEquals(input, SkillSaveTool.stripFrontmatter(input));
    }

    @Test
    void stripFrontmatter_handlesEmptyInput() {
        assertEquals("", SkillSaveTool.stripFrontmatter(""));
        assertEquals("", SkillSaveTool.stripFrontmatter(null));
    }

    // ============================== renderFrontmatter ==============================

    @Test
    void renderFrontmatter_includesAllFourFields() {
        String yaml = SkillSaveTool.renderFrontmatter("quarter_compare", "季度对比", 3);
        assertTrue(yaml.startsWith("---\n"), "must start with delimiter");
        assertTrue(yaml.contains("name: quarter_compare"));
        assertTrue(yaml.contains("description: \"季度对比\""));
        assertTrue(yaml.contains("version: 3"));
        assertTrue(yaml.contains("last_evolved_at: " + LocalDate.now()));
        assertTrue(yaml.endsWith("---\n\n"), "must end with delimiter + blank line");
    }

    @Test
    void renderFrontmatter_escapesDoubleQuotesInDescription() {
        String yaml = SkillSaveTool.renderFrontmatter("x", "has \"quote\" inside", 1);
        // The literal " must be \-escaped so YAML parses it as a single string.
        assertTrue(yaml.contains("description: \"has \\\"quote\\\" inside\""));
        // And it must NOT close the YAML string early.
        assertFalse(
                yaml.contains("description: \"has \"quote"),
                "raw quote leaked — would break YAML");
    }

    @Test
    void renderFrontmatter_escapesBackslashesBeforeQuotes() {
        // A pre-existing \ must be doubled BEFORE we add escaping for "
        String yaml = SkillSaveTool.renderFrontmatter("x", "path\\to\\thing", 1);
        assertTrue(yaml.contains("description: \"path\\\\to\\\\thing\""));
    }
}
