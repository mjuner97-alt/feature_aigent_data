/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.EpisodicResult;
import com.agentscopea2a.v2.tools.CaptureSkillSaveTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Distills an exemplar user question (+ optional recent trace) into the body of a SKILL.md.
 *
 * <p>PR2 - keeps the call tiny on purpose. We feed the LLM a single prompt asking for a name
 * + one-line description + the markdown body, and parse out three sections. No JSON schema, no
 * function-calling - this runs off the request hot path so latency doesn't matter; correctness
 * and simplicity do.
 *
 * <p>EpisodicMemory is optional. When wired, we pull the last few snippets matching the
 * exemplar question and stitch them into the prompt as "recent reference"; when missing or
 * the lookup fails, the distiller falls back to question-only mode.
 *
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2SkillConfig} -
 * not component-scanned. The {@code @Component} annotation has been removed in favor
 * of explicit construction in the config class.
 *
 * <p><b>P2-1 split:</b> Prompt templates and parsing logic have been extracted to
 * {@link SkillDistillerPrompts} and {@link SkillDistillerParser}. This class keeps the
 * distillation flow, model invocation, and subagent builder.
 */
public class SkillDistiller {

    private static final Logger log = LoggerFactory.getLogger(SkillDistiller.class);

    /** Lazy because EpisodicMemory may not exist yet at hook-injection time. */
    private final ObjectProvider<EpisodicMemory> episodicProvider;

    private final MetricClassificationService metricClassifier;

    private final Model model;

    /** Workspace root path, used by {@link #buildDistillAgent} to load subagent specs. */
    private final Path workspace;

    /** How many episodic snippets to mix into the distillation prompt. */
    private static final int TRACE_SNIPPETS = 3;

    public SkillDistiller(Model model, ObjectProvider<EpisodicMemory> episodicProvider,
                          MetricClassificationService metricClassifier,
                          Path workspace) {
        this.model = model;
        this.episodicProvider = episodicProvider;
        this.metricClassifier = metricClassifier;
        this.workspace = workspace;
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
     * parse failure - the hook treats that as "reject this candidate" rather than producing
     * a malformed SKILL.md.
     *
     * <p>On first parse failure, retries once with a short corrective prompt asking the LLM to
     * re-emit only the four required sections. If both attempts fail, returns null.
     */
    public Mono<DistilledSkill> distill(String exemplarQuestion, String fingerprintHint) {
        return distill(exemplarQuestion, fingerprintHint, null);
    }

    /**
     * Build a {@link DistilledSkill} with optional metric tag context. When {@code metricTag}
     * is non-null and non-blank, the distillation prompt includes a metric hint so the LLM
     * produces a metric-specific skill rather than a generic one.
     */
    public Mono<DistilledSkill> distill(String exemplarQuestion, String fingerprintHint, String metricTag) {
        String metricContext = metricHint(metricTag);
        return searchTraceSnippets(exemplarQuestion)
                .defaultIfEmpty("")
                .flatMap(trace -> callModel(exemplarQuestion, fingerprintHint, trace, metricContext))
                .map(SkillDistillerParser::parseLenient)
                .flatMap(skill -> {
                    if (skill != null) return Mono.just(skill);
                    // Retry once with corrective prompt
                    log.info("Distill parse failed for q='{}', retrying with corrective prompt", exemplarQuestion);
                    return searchTraceSnippets(exemplarQuestion)
                            .defaultIfEmpty("")
                            .flatMap(trace -> callModel(exemplarQuestion, fingerprintHint, trace + SkillDistillerPrompts.RETRY_PROMPT_SUFFIX, metricContext))
                            .map(SkillDistillerParser::parseLenient);
                })
                .onErrorResume(
                        ex -> {
                            log.warn("Distillation failed after retry: {}", ex.getMessage());
                            return Mono.empty();
                        });
    }

    /**
     * PR4 - rewrites an existing SKILL.md body so it avoids the recent failure mode.
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
                .map(SkillDistillerParser::parse)
                .map(d -> {
                    if (d == null) return null;
                    // Reject any LLM-initiated rename - fingerprint / file binding requires
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

    // -------- Subagent-based distillation (Path A improvement) --------

    /**
     * Build a temporary {@link HarnessAgent} that runs the {@code generate_skill} subagent spec
     * for auto-distillation. The agent uses a {@link CaptureSkillSaveTool} in place of the real
     * {@link com.agentscopea2a.v2.tools.SkillSaveTool} so that the save_skill call is captured
     * in memory rather than written to disk - the caller ({@link SkillSynthesisRunner}) handles
     * CAS, disk write, and embedding after the subagent finishes.
     *
     * <p>The system prompt is assembled from:
     * <ol>
     *   <li>{@code workspace/agent-subagents/generate_skill.md} - the same spec used by Path B</li>
     *   <li>Tool-call context (enriched by {@link SkillSynthesisRunner#buildEnrichedContext})</li>
     *   <li>Metric-tag context (from {@link #metricHint})</li>
     * </ol>
     *
     * @param question       the exemplar user question (included in the user message, not the system prompt)
     * @param toolCallContext pre-formatted tool-call chain details (may be empty)
     * @param metricTag      optional metric classification tag
     * @param captureTool    the capture tool that will record the save_skill call
     * @return a fully-built HarnessAgent ready to call
     */
    public HarnessAgent buildDistillAgent(
            String question, String toolCallContext, String metricTag,
            CaptureSkillSaveTool captureTool) {

        // 1. Load generate_skill.md as system prompt base
        Path specPath = workspace.resolve("agent-subagents").resolve("generate_skill.md");
        String sysPrompt;
        try {
            sysPrompt = Files.readString(specPath);
        } catch (Exception e) {
            log.warn("Failed to load generate_skill.md from {}, using fallback prompt: {}",
                    specPath, e.getMessage());
            sysPrompt = "你是技能生成助手。请根据用户问题和工具调用链路，蒸馏为可复用的 SKILL.md，并调用 save_skill 保存。";
        }

        // 2. Inject tool-call context (same as Path B)
        if (toolCallContext != null && !toolCallContext.isBlank()) {
            sysPrompt += "\n\n" + toolCallContext;
        }

        // 3. Inject metric-tag context
        String metricContext = metricHint(metricTag);
        if (!metricContext.isEmpty()) {
            sysPrompt += "\n" + metricContext;
        }

        // 4. Build toolkit (only save_skill)
        Toolkit tk = new Toolkit();
        tk.registerTool(captureTool);

        // 5. Build subagent
        // disableMemoryHooks: the distill subagent is ephemeral - it doesn't need
        // MemoryFlushHook or MemoryMaintenanceHook. These hooks trigger extra LLM calls
        // and filesystem writes per PostCall, which can crash on Windows when userId
        // contains path-illegal chars (e.g. ':'). Consistent with how SupervisorService
        // builds subagents (line 555: ".disableMemoryHooks()").
        return HarnessAgent.builder()
                .name("skill_distiller")
                .model(model)
                .workspace(workspace)
                .toolkit(tk)
                .sysPrompt(sysPrompt)
                .maxIters(5)
                .enablePendingToolRecovery(true)
                .disableMemoryHooks()
                .build();
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
        return distillWithContext(exemplarQuestion, fingerprintHint, toolCallContext, null);
    }

    /**
     * Context-aware distillation with optional metric tag context.
     * When {@code metricTag} is non-null, the distillation prompt includes a metric hint
     * so the LLM generates a metric-specific skill.
     */
    public Mono<DistilledSkill> distillWithContext(
            String exemplarQuestion,
            String fingerprintHint,
            String toolCallContext,
            String metricTag) {
        String metricContext = metricHint(metricTag);
        return searchTraceSnippets(exemplarQuestion)
                .defaultIfEmpty("")
                .flatMap(trace -> callModelWithContext(exemplarQuestion, fingerprintHint, toolCallContext, trace, metricContext))
                .map(SkillDistillerParser::parseLenient)
                .flatMap(skill -> {
                    if (skill != null) return Mono.just(skill);
                    log.info("Context-distill parse failed for q='{}', retrying", exemplarQuestion);
                    return searchTraceSnippets(exemplarQuestion)
                            .defaultIfEmpty("")
                            .flatMap(trace -> callModelWithContext(
                                    exemplarQuestion, fingerprintHint, toolCallContext, trace + SkillDistillerPrompts.RETRY_PROMPT_SUFFIX, metricContext))
                            .map(SkillDistillerParser::parseLenient);
                })
                .onErrorResume(
                        ex -> {
                            log.warn("Context-distillation failed after retry: {}", ex.getMessage());
                            return Mono.empty();
                        });
    }

    private Mono<String> callModelWithContext(
            String question, String fingerprintHint, String toolCallContext, String traceBlock, String metricContext) {
        String prompt = SkillDistillerPrompts.distillWithContextPrompt(
                question, fingerprintHint, toolCallContext, traceBlock, metricContext);
        return streamPrompt(prompt, 2000);
    }

    // -------- internals --------

    /**
     * Builds a metric context hint string for the distillation prompt. When the question has been
     * classified under a specific metric tag (e.g. "defect_density"), this hint tells the LLM
     * to focus on that metric - producing a more specific skill instead of a generic catch-all.
     * Returns empty string when metricTag is null/blank.
     *
     * <p>Delegates to {@link MetricClassificationService#getMetricHint(String)} for the
     * Chinese hint text, which is loaded from {@code workspace/knowledge/metric-categories.yaml}.
     */
    public String metricHint(String metricTag) {
        if (metricTag == null || metricTag.isBlank()) return "";
        String chineseHint = metricClassifier.getMetricHint(metricTag);
        return "\n**指标分类**: " + metricTag + " (" + chineseHint + ")"
                + "\n请围绕[" + chineseHint + "]这一指标类别来编写 skill,确保生成的 skill 针对该指标而非泛化描述。\n";
    }

    private Mono<String> searchTraceSnippets(String question) {
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

    private Mono<String> callModel(String question, String fingerprintHint, String traceBlock, String metricContext) {
        String prompt = SkillDistillerPrompts.distillPrompt(question, fingerprintHint, metricContext, traceBlock);
        return streamPrompt(prompt, 4000);
    }

    /**
     * PR4 - single-shot evolve prompt. Keep the LLM tightly constrained:
     * <ul>
     *   <li>name must equal the original skill name (we check on parse and reject mismatches)
     *   <li>old body is given in full as the starting point - LLM is asked to surgically fix,
     *       not rewrite from scratch
     *   <li>failed trace should be a structured context from tool_call_details (input/output),
     *       already truncated to ~500 chars by the caller
     * </ul>
     */
    private Mono<String> callEvolveModel(
            String skillName, String oldBody, String exemplarQuestion, String failedTrace) {
        String prompt = SkillDistillerPrompts.evolvePrompt(skillName, oldBody, exemplarQuestion, failedTrace);
        return streamPrompt(prompt, 2000);
    }

    /**
     * Shared helper - wraps a prompt string into a USER Msg, streams it through the model
     * with the given maxTokens, and reduces the response chunks into a single string.
     */
    private Mono<String> streamPrompt(String prompt, int maxTokens) {
        Msg msg = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build();
        return model.stream(List.of(msg), List.of(),
                GenerateOptions.builder()
                        .maxTokens(maxTokens)
                        .temperature(0.1)
                        .build())
                .reduce(new StringBuilder(), SkillDistiller::appendChunk)
                .map(StringBuilder::toString);
    }
}
