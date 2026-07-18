/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.skills.SkillDistiller.DistilledSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-function parser for {@link SkillDistiller} LLM output. Extracted from
 * {@code SkillDistiller} (P2-1) so the prompt-building / model-calling concerns
 * in {@code SkillDistiller} and {@link SkillDistillerPrompts} stay separate
 * from the regex-driven parse logic.
 *
 * <p>All methods are static and side-effect free except for {@code log.warn}
 * on parse failure. Thread-safe.
 */
final class SkillDistillerParser {

    private static final Logger log = LoggerFactory.getLogger(SkillDistillerParser.class);

    private static final Pattern NAME_LINE =
            Pattern.compile("^(?:name|skill_name)\\s*[:=]\\s*([a-z0-9_]+)\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern DESC_LINE =
            Pattern.compile("^description\\s*[:=]\\s*(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /**
     * Body fence; we accept either ```md, ```markdown, or plain triple-backtick.
     * Match the entire content between the first opening fence and the LAST closing fence
     * (or end-of-string). Greedy match is needed because LLM often nests ``` blocks
     * inside the SKILL.md body (e.g. for JSON examples), and a non-greedy match would
     * truncate at the first inner ```.
     */
    private static final Pattern BODY_FENCE =
            Pattern.compile("```(?:md|markdown)?\\s*\\R(.*)`{0,2}\\s*$", Pattern.DOTALL);
    /**
     * `sample_questions:` block - one or more dash-prefixed lines following the label.
     * Used by PR3.7 to enrich the embedded text on bge-zh so cosine spreads out enough to
     * clear the L2 min-cosine threshold.
     */
    private static final Pattern SAMPLES_BLOCK =
            Pattern.compile(
                    "^sample_questions\\s*[:=]\\s*\\R((?:[ \\t]*-\\s*.+(?:\\R|$))+)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /**
     * Markdown body fallback for sample questions: an h2 like "## 典型问法" / "## 示例问法" /
     * "## 样例问法" followed by dash-bulleted lines. LLMs strongly prefer this idiomatic form
     * over a separate {@code sample_questions:} block, so we accept either.
     */
    private static final Pattern SAMPLES_H2_BLOCK =
            Pattern.compile(
                    "^#{2,3}\\s*(?:典型问法|示例问法|样例问法|样例提问|常见问法|问法示例)\\s*\\R+"
                            + "((?:[ \\t]*-\\s*.+(?:\\R|$))+)",
                    Pattern.MULTILINE);
    private static final Pattern SAMPLE_LINE =
            Pattern.compile("^[ \\t]*-\\s*(.+?)\\s*$", Pattern.MULTILINE);

    /** Matches <think>...</think> blocks, including multiline content. */
    private static final Pattern THINKING_BLOCK_PATTERN =
            Pattern.compile("<think>.*?</think>\\s*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Matches <thinking>...</thinking> blocks, including multiline content. */
    private static final Pattern THINKING_BLOCK2_PATTERN =
            Pattern.compile("<thinking>.*?</thinking>\\s*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SkillDistillerParser() {
    }

    /**
     * Strips LLM thinking/reasoning tags that some models (Qwen3, DeepSeek-R1, etc.) emit
     * before the actual answer. These tags (e.g. {@code <think>...</think>},
     * {@code <thinking>...</thinking>}) contaminate the distilled SKILL.md body and cause
     * malformed output when saved to disk.
     *
     * <p>Also strips the standalone {@code </think>} closing tag that appears when the
     * model emits the opening tag in a previous stream chunk but the closing tag lands in
     * the same chunk as the real content.
     */
    static String stripThinkingTags(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return llmOutput;
        String result = THINKING_BLOCK_PATTERN.matcher(llmOutput).replaceAll("");
        result = THINKING_BLOCK2_PATTERN.matcher(result).replaceAll("");
        return result.trim();
    }

    /**
     * Strict parse used by {@link SkillDistiller#evolve} - requires all three sections.
     * Returns null on any parse failure.
     */
    static DistilledSkill parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return null;
        llmOutput = stripThinkingTags(llmOutput);
        Matcher nm = NAME_LINE.matcher(llmOutput);
        Matcher dm = DESC_LINE.matcher(llmOutput);
        Matcher bm = BODY_FENCE.matcher(llmOutput);
        if (!nm.find() || !dm.find() || !bm.find()) {
            log.warn(
                    "Distiller output missing required sections; nameFound={} descFound={} bodyFound={} raw=[{}]",
                    nm.reset().find(),
                    dm.reset().find(),
                    bm.reset().find(),
                    llmOutput.length() > 1500 ? llmOutput.substring(0, 1500) + "..." : llmOutput);
            return null;
        }
        String name = nm.group(1).trim().toLowerCase();
        String desc = dm.group(1).trim();
        // Strip surrounding quotes the LLM may have added around the description.
        desc = stripSurroundingQuotes(desc);
        String body = bm.group(1).trim();
        if (name.isEmpty() || desc.isEmpty() || body.isEmpty()) return null;
        List<String> samples = parseSamples(llmOutput);
        return new DistilledSkill(name, desc, body, samples);
    }

    /**
     * Lenient parse: extracts name, description, body, and sample questions from LLM output.
     * Unlike the strict {@link #parse}, this method:
     * <ul>
     *   <li>Falls back to fingerprint-hash-based name when name section is missing
     *   <li>Falls back to name as description when description section is missing
     *   <li>Only returns null when the body (core skill content) is missing
     * </ul>
     */
    static DistilledSkill parseLenient(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return null;
        llmOutput = stripThinkingTags(llmOutput);
        DistilledSkill strict = parse(llmOutput);
        if (strict != null) return strict;

        // Lenient fallback: extract whatever sections we can
        Matcher nm = NAME_LINE.matcher(llmOutput);
        Matcher dm = DESC_LINE.matcher(llmOutput);
        Matcher bm = BODY_FENCE.matcher(llmOutput);

        String name = nm.find() ? nm.group(1).trim().toLowerCase() : null;
        String desc = dm.find() ? dm.group(1).trim() : null;
        String body = bm.find() ? bm.group(1).trim() : null;

        desc = stripSurroundingQuotes(desc);

        if (body == null || body.isEmpty()) {
            log.warn("parseLenient: body missing for output=[{}]",
                    llmOutput.length() > 500 ? llmOutput.substring(0, 500) + "..." : llmOutput);
            return null;
        }
        if (name == null || name.isEmpty()) {
            // Fallback: use a hash of the output as name
            name = "skill_" + Integer.toHexString(llmOutput.hashCode()).substring(0, 8);
            log.info("parseLenient: name missing, generated fallback '{}'", name);
        }
        if (desc == null || desc.isEmpty()) {
            desc = name;
            log.info("parseLenient: description missing, using name '{}' as fallback", name);
        }
        List<String> samples = parseSamples(llmOutput);
        return new DistilledSkill(name, desc, body, samples);
    }

    /**
     * Best-effort extraction of sample questions. Accepts two forms:
     * <ul>
     *   <li>A top-level {@code sample_questions:} block (the prompt's preferred form).
     *   <li>A markdown {@code ## 典型问法} (or equivalent) h2 section inside the SKILL.md body.
     *       This is the form LLMs idiomatically produce when asked for "典型问法" in the prompt,
     *       so accepting both forms keeps the rich-embed path working in practice.
     * </ul>
     * Missing both returns an empty list rather than failing the whole distill - embedding
     * will fall back to description-only text. Dedupes case-insensitively and trims, capped
     * at 8 entries so a runaway LLM can't bloat the embedded text.
     */
    static List<String> parseSamples(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return List.of();
        String region = null;
        Matcher block = SAMPLES_BLOCK.matcher(llmOutput);
        if (block.find()) {
            region = block.group(1);
        } else {
            Matcher h2 = SAMPLES_H2_BLOCK.matcher(llmOutput);
            if (h2.find()) region = h2.group(1);
        }
        if (region == null) return List.of();
        Matcher line = SAMPLE_LINE.matcher(region);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (line.find()) {
            String q = line.group(1).trim();
            q = stripSurroundingQuotes(q);
            if (q.isEmpty()) continue;
            String key = q.toLowerCase();
            if (seen.add(key)) {
                out.add(q);
                if (out.size() >= 8) break;
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String stripSurroundingQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
