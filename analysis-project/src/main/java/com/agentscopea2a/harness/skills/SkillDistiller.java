/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.memory.EpisodicMemory;
import io.agentscope.core.memory.EpisodicResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distills an exemplar user question (+ optional recent trace) into the body of a SKILL.md.
 *
 * <p>PR2 — keeps the call tiny on purpose. We feed the LLM a single prompt asking for a name
 * + one-line description + the markdown body, and parse out three sections. No JSON schema, no
 * function-calling — this runs off the request hot path so latency doesn't matter; correctness
 * and simplicity do.
 *
 * <p>EpisodicMemory is optional. When wired, we pull the last few snippets matching the
 * exemplar question and stitch them into the prompt as "recent reference"; when missing or
 * the lookup fails, the distiller falls back to question-only mode.
 */
@Component
public class SkillDistiller {

    private static final Logger log = LoggerFactory.getLogger(SkillDistiller.class);

    /** Lazy because EpisodicMemory may not exist yet at hook-injection time. */
    private final ObjectProvider<EpisodicMemory> episodicProvider;

    private final Model model;

    /** How many episodic snippets to mix into the distillation prompt. */
    private static final int TRACE_SNIPPETS = 3;

    private static final Pattern NAME_LINE =
            Pattern.compile("^(?:name|skill_name)\\s*[:=]\\s*([a-z0-9_]+)\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern DESC_LINE =
            Pattern.compile("^description\\s*[:=]\\s*(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /**
     * Body fence; we accept either ```md, ```markdown, or plain triple-backtick. The closing
     * fence is optional — bge-zh-style models often stream a long SKILL.md and forget to
     * terminate the block. Falling back to "everything to end-of-string" is fine because the
     * distiller's caller writes it as a SKILL.md body verbatim.
     */
    private static final Pattern BODY_FENCE =
            Pattern.compile("```(?:md|markdown)?\\s*\\R(.*?)(?:\\R```|\\z)", Pattern.DOTALL);
    /**
     * `sample_questions:` block — one or more dash-prefixed lines following the label.
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

    /** Prompt for retrying a failed parse — asks LLM to re-emit only the structure. */
    private static final String RETRY_PROMPT_SUFFIX =
            "\n\n-----\n以上输出格式不符合要求。请只输出以下四段(不要多余内容):\n"
                    + "1. `name: <英文小写下划线>`\n"
                    + "2. `description: <一句话中文描述>`\n"
                    + "3. `sample_questions:` 然后 3-5 行 `- <中文问法>`\n"
                    + "4. ```markdown 包裹的 SKILL.md 正文\n"
                    + "不要 YAML frontmatter。";

    public SkillDistiller(Model model, ObjectProvider<EpisodicMemory> episodicProvider) {
        this.model = model;
        this.episodicProvider = episodicProvider;
    }

    public record DistilledSkill(
            String name, String description, String body, List<String> sampleQuestions) {
        public DistilledSkill {
            sampleQuestions =
                    sampleQuestions == null
                            ? List.of()
                            : List.copyOf(sampleQuestions);
        }
    }

    /**
     * Build a {@link DistilledSkill} from the exemplar question. Returns {@code null} on any
     * parse failure — the hook treats that as "reject this candidate" rather than producing
     * a malformed SKILL.md.
     *
     * <p>On first parse failure, retries once with a short corrective prompt asking the LLM to
     * re-emit only the four required sections. If both attempts fail, returns null.
     */
    public Mono<DistilledSkill> distill(String exemplarQuestion, String fingerprintHint) {
        return collectTrace(exemplarQuestion)
                .defaultIfEmpty("")
                .flatMap(trace -> callModel(exemplarQuestion, fingerprintHint, trace))
                .map(SkillDistiller::parseLenient)
                .flatMap(skill -> {
                    if (skill != null) return Mono.just(skill);
                    // Retry once with corrective prompt
                    log.info("Distill parse failed for q='{}', retrying with corrective prompt", exemplarQuestion);
                    return collectTrace(exemplarQuestion)
                            .defaultIfEmpty("")
                            .flatMap(trace -> callModel(exemplarQuestion, fingerprintHint, trace + RETRY_PROMPT_SUFFIX))
                            .map(SkillDistiller::parseLenient);
                })
                .onErrorResume(
                        ex -> {
                            log.warn("Distillation failed after retry: {}", ex.getMessage());
                            return Mono.empty();
                        });
    }

    /**
     * PR4 — rewrites an existing SKILL.md body so it avoids the recent failure mode.
     * Returns {@code null} on parse failure or when the LLM emits a different {@code name}
     * (we won't rename a skill mid-evolution because L1 fingerprint binds to the file name).
     *
     * <p>Caller is responsible for the per-skill CAS that prevents two concurrent evolutions of
     * the same skill.
     *
     * @param skillName the original skill name; must match the LLM output verbatim
     * @param oldBody the current SKILL.md body (the LLM rewrites *on top of* this)
     * @param exemplarQuestion an example question that recently routed to this skill
     * @param failedTraceSnippet best-effort last-failure snippet (already truncated by the
     *     caller to ~500 chars; null/blank = LLM evolves blind)
     */
    public Mono<DistilledSkill> evolve(
            String skillName,
            String oldBody,
            String exemplarQuestion,
            String failedTraceSnippet) {
        if (skillName == null || skillName.isBlank() || oldBody == null) {
            return Mono.empty();
        }
        return callEvolveModel(skillName, oldBody, exemplarQuestion, failedTraceSnippet)
                .map(SkillDistiller::parse)
                .map(d -> {
                    if (d == null) return null;
                    // Reject any LLM-initiated rename — fingerprint / file binding requires
                    // a stable name across versions.
                    if (!skillName.equalsIgnoreCase(d.name())) {
                        log.warn(
                                "evolve(): LLM tried to rename '{}' -> '{}'; rejecting",
                                skillName,
                                d.name());
                        return null;
                    }
                    return d;
                })
                .onErrorResume(
                        ex -> {
                            log.warn("evolve({}) failed: {}", skillName, ex.getMessage());
                            return Mono.empty();
                        });
    }

    // -------- Context-aware distillation --------

    /**
     * Like {@link #distill}, but the LLM prompt includes a rich tool-call trace
     * (tool name, level L1/L2, input parameters, output summary) so the distilled
     * skill captures not just <em>what</em> tools to use but <em>how</em> to call
     * them with the right arguments.
     *
     * @param exemplarQuestion original user question
     * @param fingerprintHint  L1 fingerprint hint
     * @param toolCallContext  pre-formatted text block describing the tool chain details
     */
    public Mono<DistilledSkill> distillWithContext(
            String exemplarQuestion,
            String fingerprintHint,
            String toolCallContext) {
        return collectTrace(exemplarQuestion)
                .defaultIfEmpty("")
                .flatMap(trace -> callModelWithContext(exemplarQuestion, fingerprintHint, toolCallContext, trace))
                .map(SkillDistiller::parseLenient)
                .flatMap(skill -> {
                    if (skill != null) return Mono.just(skill);
                    log.info("Context-distill parse failed for q='{}', retrying", exemplarQuestion);
                    return collectTrace(exemplarQuestion)
                            .defaultIfEmpty("")
                            .flatMap(trace -> callModelWithContext(
                                    exemplarQuestion, fingerprintHint, toolCallContext, trace + RETRY_PROMPT_SUFFIX))
                            .map(SkillDistiller::parseLenient);
                })
                .onErrorResume(
                        ex -> {
                            log.warn("Context-distillation failed after retry: {}", ex.getMessage());
                            return Mono.empty();
                        });
    }

    private Mono<String> callModelWithContext(
            String question, String fingerprintHint, String toolCallContext, String traceBlock) {
        String prompt =
                "你正在为一个'质量数据智能助手'蒸馏可复用的 skill。下面是一个用户多次出现的同类问题:\n\n"
                        + "**用户问题**: " + question + "\n"
                        + "**Fingerprint**: " + fingerprintHint + "\n"
                        + toolCallContext
                        + traceBlock
                        + "\n\n请输出四段:\n"
                        + "1. 一行 `name: <英文小写下划线>` (例如 `quarterly_quality_compare`)\n"
                        + "2. 一行 `description: <一句话中文描述>`\n"
                        + "3. 一段 `sample_questions:`,紧跟 3-5 行 `- <同维度的中文问法>`(同一个 skill 的不同措辞,"
                        + "应覆盖该 skill 适用的几种典型问法;不要复制 exemplar question 原句)\n"
                        + "4. 一段 ```markdown 包裹的 SKILL.md 正文,描述本类问题的完整解决步骤:\n"
                        + "   - 父智能体派单给哪个子智能体(如 query_data / analyze_data)\n"
                        + "   - 子智能体内部的工具调用链路:先查阅哪个 skill/toolId → 再调哪些元工具\n"
                        + "   - 每个工具的入参与出参:输入了什么参数、返回了什么结果\n"
                        + "参考上面的**工具调用链路详情**来编写,工具名、参数格式、调用顺序必须与实际一致。\n"
                        + "不要使用'query_quality_data'或'python_exec'等泛化工具名,要用 toolMetaInfo/router_tool 等真实链路。\n"
                        + "不要输出 YAML frontmatter,系统会自动生成。";
        Msg msg = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build();
        return model.stream(List.of(msg), List.of(), null)
                .reduce(new StringBuilder(), SkillDistiller::appendChunk)
                .map(StringBuilder::toString);
    }

    // -------- internals --------

    private Mono<String> collectTrace(String question) {
        EpisodicMemory mem = episodicProvider.getIfAvailable();
        if (mem == null) return Mono.just("");
        return mem.search(question, TRACE_SNIPPETS)
                .map(SkillDistiller::renderTrace)
                .onErrorResume(
                        ex -> {
                            log.debug("EpisodicMemory.search failed: {}", ex.getMessage());
                            return Mono.just("");
                        });
    }

    private static String renderTrace(List<EpisodicResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## Recent successful traces (reference only)\n\n");
        for (EpisodicResult r : results) {
            sb.append("- ").append(r.snippet()).append('\n');
        }
        return sb.toString();
    }

    private static StringBuilder appendChunk(StringBuilder acc, ChatResponse resp) {
        if (resp == null || resp.getContent() == null) return acc;
        for (ContentBlock cb : resp.getContent()) {
            if (cb instanceof TextBlock tb && tb.getText() != null) {
                acc.append(tb.getText());
            }
        }
        return acc;
    }

    private Mono<String> callModel(String question, String fingerprintHint, String traceBlock) {
        String prompt =
                "你正在为一个'质量数据智能助手'蒸馏可复用的 skill。下面是一个用户多次出现的同类问题:\n\n"
                        + "**Exemplar question**: "
                        + question
                        + "\n**Fingerprint**: "
                        + fingerprintHint
                        + "\n"
                        + traceBlock
                        + "\n请输出四段:\n"
                        + "1. 一行 `name: <英文小写下划线>` (例如 `quarterly_quality_compare`)\n"
                        + "2. 一行 `description: <一句话中文描述>`\n"
                        + "3. 一段 `sample_questions:`,紧跟 3-5 行 `- <同维度的中文问法>`(同一个 skill 的不同措辞,"
                        + "应覆盖该 skill 适用的几种典型问法;不要复制 exemplar question 原句)\n"
                        + "4. 一段 ```markdown 包裹的 SKILL.md 正文,描述本类问题的标准解决步骤、"
                        + "用到的工具(如 query_quality_data / python_exec)、典型问法、关键约束。\n"
                        + "不要输出 YAML frontmatter,系统会自动生成。";
        Msg msg = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build();
        return model.stream(List.of(msg), List.of(), null)
                .reduce(new StringBuilder(), SkillDistiller::appendChunk)
                .map(StringBuilder::toString);
    }

    /**
     * PR4 — single-shot evolve prompt. Keep the LLM tightly constrained:
     * <ul>
     *   <li>name must equal the original skill name (we check on parse and reject mismatches)
     *   <li>old body is given in full as the starting point — LLM is asked to surgically fix,
     *       not rewrite from scratch
     *   <li>failed trace should be a structured context from tool_call_details (input/output),
     *       already truncated to ~500 chars by the caller
     * </ul>
     */
    private Mono<String> callEvolveModel(
            String skillName, String oldBody, String exemplarQuestion, String failedTrace) {
        String trace = failedTrace == null || failedTrace.isBlank()
                ? "(本次演进无具体失败 trace,LLM 凭判断修订)"
                : failedTrace.trim();
        String prompt =
                "你正在演进一个已经存在的 SKILL.md。下面是当前版本(它最近被多次使用但表现不佳,"
                        + "需要修订):\n\n"
                        + "**Skill name**: "
                        + skillName
                        + " (保持不变!如果想换名字,请直接返回原名)\n"
                        + "**用户问题**: "
                        + (exemplarQuestion == null ? "(无)" : exemplarQuestion)
                        + "\n\n**本次失败的工具调用详情(≤500字)**:\n```\n"
                        + trace
                        + "\n```\n\n**当前 SKILL.md 正文**:\n```markdown\n"
                        + oldBody
                        + "\n```\n\n请基于以上失败信号,修订正文,使其能够避免这类失败。"
                        + "注意失败详情中标注了每个工具调用的输入和输出——请分析失败原因并补充对应约束。"
                        + "请按与蒸馏相同的格式输出四段:\n"
                        + "1. 一行 `name: "
                        + skillName
                        + "` (必须与原 skill 名一致)\n"
                        + "2. 一行 `description: <一句话中文描述,可微调>`\n"
                        + "3. 一段 `sample_questions:`,紧跟 3-5 行 `- <同维度中文问法>`\n"
                        + "4. 一段 ```markdown 包裹的修订后正文,**只改需要改的地方**,"
                        + "在改动处用注释或文字说明本次修订点与失败原因的对应关系。\n"
                        + "不要输出 YAML frontmatter,系统会自动生成。";
        Msg msg = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build();
        return model.stream(List.of(msg), List.of(), null)
                .reduce(new StringBuilder(), SkillDistiller::appendChunk)
                .map(StringBuilder::toString);
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
        DistilledSkill strict = parse(llmOutput);
        if (strict != null) return strict;

        // Lenient fallback: extract whatever sections we can
        Matcher nm = NAME_LINE.matcher(llmOutput);
        Matcher dm = DESC_LINE.matcher(llmOutput);
        Matcher bm = BODY_FENCE.matcher(llmOutput);

        String name = nm.find() ? nm.group(1).trim().toLowerCase() : null;
        String desc = dm.find() ? dm.group(1).trim() : null;
        String body = bm.find() ? bm.group(1).trim() : null;

        // Strip surrounding quotes from description if any
        if (desc != null && desc.length() >= 2
                && ((desc.startsWith("\"") && desc.endsWith("\""))
                        || (desc.startsWith("'") && desc.endsWith("'")))) {
            desc = desc.substring(1, desc.length() - 1);
        }

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
     * Strict parse used by {@link #evolve} — requires all three sections.
     * Returns null on any parse failure.
     */
    static DistilledSkill parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return null;
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
        if (desc.length() >= 2
                && ((desc.startsWith("\"") && desc.endsWith("\""))
                        || (desc.startsWith("'") && desc.endsWith("'")))) {
            desc = desc.substring(1, desc.length() - 1);
        }
        String body = bm.group(1).trim();
        if (name.isEmpty() || desc.isEmpty() || body.isEmpty()) return null;
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
     * Missing both returns an empty list rather than failing the whole distill — embedding
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
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (line.find()) {
            String q = line.group(1).trim();
            // Strip surrounding quotes the LLM may have added.
            if (q.length() >= 2
                    && ((q.startsWith("\"") && q.endsWith("\""))
                            || (q.startsWith("'") && q.endsWith("'")))) {
                q = q.substring(1, q.length() - 1).trim();
            }
            if (q.isEmpty()) continue;
            String key = q.toLowerCase();
            if (seen.add(key)) {
                out.add(q);
                if (out.size() >= 8) break;
            }
        }
        return Collections.unmodifiableList(out);
    }
}
