/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.MySqlEpisodicMemory;
import com.agentscopea2a.v2.tools.CaptureSkillSaveTool;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared "count → maybe distill" pipeline for both the Cache MISS path
 * ({@code SkillSynthesisHook}) and the Cache HIT path ({@code ResponseCacheHook}).
 *
 * <p>Centralising the distillation logic prevents drift between the two call sites — both
 * end up bumping {@code skill_candidate.hit_count} and, when threshold is crossed, dispatching
 * an async distill+save. {@link SkillCandidateRepository#markSynthesized} is the atomic CAS
 * that prevents the two paths (or two JVMs) from racing.
 *
 * <p>When {@code harness.skills.auto-synth.via-subagent} is {@code true} (default), the
 * distillation runs through a {@code generate_skill} subagent rather than directly calling
 * the LLM — this avoids thinking-tag pollution and produces higher-quality skills. When
 * {@code false}, falls back to the legacy {@link SkillDistiller#distill} path.
 *
 * <p>Workspace path is captured at construction; everything else is shared injected beans.
 * Vector index + embedding client are optional (PR3 may be off).
 *
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2SkillConfig} -
 * not component-scanned. The {@code @Component} annotation has been removed in favor
 * of explicit construction in the config class.
 */
public class SkillSynthesisRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesisRunner.class);

    private final SkillCandidateRepository candidateRepo;
    private final SkillIndexRepository indexRepo;
    private final SkillDistiller distiller;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final EpisodicMemory episodicMemory;
    private final Path skillsDir;
    private final boolean enabled;
    private final int hitThreshold;
    private final boolean viaSubagent;

    public SkillSynthesisRunner(
            SkillCandidateRepository candidateRepo,
            SkillIndexRepository indexRepo,
            SkillDistiller distiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<EpisodicMemory> episodicMemoryProvider,
            Path skillsDir,
            boolean enabled,
            int hitThreshold,
            boolean viaSubagent) {
        this.candidateRepo = candidateRepo;
        this.indexRepo = indexRepo;
        this.distiller = distiller;
        this.vectorIndex = vectorIndexProvider.getIfAvailable();
        this.embeddingClient = embeddingClientProvider.getIfAvailable();
        this.episodicMemory = episodicMemoryProvider.getIfAvailable();
        this.skillsDir = skillsDir;
        this.enabled = enabled;
        this.hitThreshold = hitThreshold;
        this.viaSubagent = viaSubagent;
    }

    public boolean enabled() {
        return enabled;
    }

    public int threshold() {
        return hitThreshold;
    }

    /**
     * Bump the candidate row, then — if the bumped row is pending and over threshold — fire an
     * async distill+save on {@link Schedulers#boundedElastic()}. Safe to call from any hook;
     * the {@code markSynthesized} CAS guarantees at-most-once distillation across paths/JVMs.
     *
     * @return the candidate row after the bump (may be empty when MySQL is unreachable)
     */
    public java.util.Optional<SkillCandidate> bumpAndMaybeSynthesize(
            String fingerprint, String userId, String question, String traceId) {
        if (!enabled) return java.util.Optional.empty();
        java.util.Optional<SkillCandidate> bumped =
                candidateRepo.incrementHit(fingerprint, userId, question, traceId);
        bumped.ifPresent(c -> maybeDispatch(fingerprint, userId, question, c));
        return bumped;
    }

    /**
     * Variant for callers that have already bumped the counter (e.g. an earlier PreCall) and
     * just want the threshold check + async dispatch to run. Idempotent — the CAS in
     * {@link SkillCandidateRepository#markSynthesized} ensures double-calls are harmless.
     */
    public void maybeSynthesize(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        if (!enabled) return;
        if (candidate == null) return;
        maybeDispatch(fingerprint, userId, question, candidate);
    }

    private void maybeDispatch(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        if (!SkillCandidate.STATUS_PENDING.equals(candidate.status())) return;
        if (candidate.hitCount() < hitThreshold) return;
        reactor.core.publisher.Mono.fromRunnable(
                        () -> distillAndSave(fingerprint, userId, question, candidate))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        ex -> log.warn("Async synthesis crashed: {}", ex.getMessage()));
    }

    private void distillAndSave(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        log.info("Skill synthesis triggered: fingerprint={} hit={} user={}",
                fingerprint, candidate.hitCount(), userId);

        // Try to fetch tool call context from episodic memory for context-aware distillation
        String toolCallContext = "";
        if (episodicMemory instanceof MySqlEpisodicMemory mem) {
            try {
                toolCallContext = mem.searchToolContextByFingerprint(fingerprint).block();
                if (toolCallContext != null && !toolCallContext.isBlank()) {
                    log.info("Found tool call context for fingerprint={} ({} chars)",
                            fingerprint, toolCallContext.length());
                }
            } catch (Exception e) {
                log.debug("Failed to fetch tool call context for {}: {}", fingerprint, e.getMessage());
            }
        }

        String metricTag = candidate.metricTag();

        if (viaSubagent) {
            distillViaSubagent(fingerprint, userId, question, candidate, toolCallContext, metricTag);
        } else {
            distillViaDirectLlm(fingerprint, userId, question, candidate, toolCallContext, metricTag);
        }
    }

    // -------- Subagent-based distillation (new Path A) --------

    /**
     * Distill via a {@code generate_skill} subagent that calls {@code save_skill} internally.
     * The result is captured by {@link CaptureSkillSaveTool} rather than written to disk —
     * the runner handles CAS, disk write, and embedding afterwards.
     *
     * <p>Runs synchronously on the current (boundedElastic) thread. The subagent uses
     * {@code maxIters=3}, which gives the LLM enough turns to think and then call save_skill.
     */
    private void distillViaSubagent(
            String fingerprint, String userId, String question,
            SkillCandidate candidate, String toolCallContext, String metricTag) {

        // [2] Build distill subagent
        CaptureSkillSaveTool captureTool = new CaptureSkillSaveTool();
        HarnessAgent distillAgent;
        try {
            String enrichedContext = buildEnrichedContext(question, toolCallContext);
            distillAgent = distiller.buildDistillAgent(question, enrichedContext, metricTag, captureTool);
        } catch (Exception e) {
            log.warn("Failed to build distill agent: {}", e.getMessage());
            candidateRepo.markRejected(fingerprint, "subagent_build_failed");
            return;
        }

        // [3] Build user message (metricContext already injected into system prompt)
        String task = buildDistillTask(question, fingerprint, toolCallContext);
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(task).build())
                .build();

        // [4] Run subagent (synchronous — already on boundedElastic thread)
        // Sanitize fingerprint for use in paths — characters like |, :, <, >, "
        // are illegal in Windows file paths and cause InvalidPathException / IOError.
        // Use a fixed userId "_distill" so the framework doesn't create a per-user
        // directory tree (e.g. u_u-test2/agents/skill_distiller/) under workspace.
        // MemoryFlushHooks are already disabled; the only filesystem side-effect
        // would be SessionTree creating an empty agent directory + _sweep.marker.
        String safeFingerprint = fingerprint.replaceAll("[|<>:\"?*\\\\/]", "_");
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("distill-" + safeFingerprint)
                .userId("_distill")
                .build();
        try {
            log.info("Starting distill subagent call for fingerprint={}", fingerprint);
            distillAgent.call(List.of(userMsg), ctx).block();
            log.info("Distill subagent completed for fingerprint={}", fingerprint);
        } catch (Exception e) {
            log.warn("Distill subagent failed: {}", e.getMessage());
            candidateRepo.markRejected(fingerprint, "subagent_failed");
            return;
        }

        // [5] Capture result
        if (!captureTool.hasCaptured()) {
            log.warn("Distill subagent did not call save_skill (fingerprint={})", fingerprint);
            candidateRepo.markRejected(fingerprint, "subagent_no_save_skill");
            return;
        }

        CaptureSkillSaveTool.CapturedSkill captured = captureTool.getCaptured();
        String name = sanitizeName(captured.name());
        String description = captured.description();
        // Loop-strip all frontmatter blocks — LLM may generate multiple or
        // malformed YAML blocks despite the tool description saying "no frontmatter".
        String body = captured.content();
        while (body.startsWith("---")) {
            String stripped = SkillSaveTool.stripFrontmatter(body);
            if (stripped.equals(body)) break;  // no more frontmatter to strip
            body = stripped;
        }
        List<String> samples = SkillDistiller.parseSamples(body);

        SkillDistiller.DistilledSkill distilled =
                new SkillDistiller.DistilledSkill(name, description, body, samples);

        // [6] metricTag stamp
        if (metricTag != null && !metricTag.isBlank()) {
            distilled = withMetricTag(distilled, metricTag);
            log.info("Distilled skill '{}' tagged with metric_tag={}", distilled.name(), metricTag);
        }

        // [7] CAS + write to disk + embedding (same as direct LLM path)
        saveDistilledSkill(distilled, fingerprint, metricTag);
    }

    // -------- Direct LLM distillation (fallback Path A) --------

    /**
     * Legacy distillation path — directly calls the LLM via {@link SkillDistiller}
     * and parses the structured output with regex. Retained as a fallback when
     * {@code via-subagent=false}.
     */
    private void distillViaDirectLlm(
            String fingerprint, String userId, String question,
            SkillCandidate candidate, String toolCallContext, String metricTag) {

        SkillDistiller.DistilledSkill distilled;
        if (toolCallContext != null && !toolCallContext.isBlank()) {
            // Use context-aware distillation with metric tag hint
            String enrichedContext = buildEnrichedContext(question, toolCallContext);
            distilled = distiller.distillWithContext(question, fingerprint, enrichedContext, metricTag).block();
        } else {
            // Fall back to standard distillation with metric tag hint
            distilled = distiller.distill(question, fingerprint, metricTag).block();
        }
        if (distilled == null) {
            candidateRepo.markRejected(fingerprint, "distiller_returned_null");
            return;
        }
        // Inject metric_tag into SKILL.md body frontmatter
        if (metricTag != null && !metricTag.isBlank()) {
            distilled = withMetricTag(distilled, metricTag);
            log.info("Distilled skill '{}' tagged with metric_tag={}", distilled.name(), metricTag);
        }

        saveDistilledSkill(distilled, fingerprint, metricTag);
    }

    // -------- Shared save logic --------

    /**
     * Common save path: CAS claim → write SKILL.md → stamp embedding vector.
     * Used by both subagent and direct-LLM distillation paths.
     */
    public void saveDistilledSkill(
            SkillDistiller.DistilledSkill distilled, String fingerprint, String metricTag) {
        // Atomic claim — only one path/JVM moves the row from pending → synthesized.
        if (!candidateRepo.markSynthesized(fingerprint, distilled.name())) {
            log.info("Candidate {} already claimed by another writer; skipping save", fingerprint);
            return;
        }
        try {
            // Pass null embeddingClient — the runner stamps the embedding+fingerprint
            // synchronously below with the richer (desc + sample_questions) text. Letting
            // SkillSaveTool's async path also embed would race and overwrite the canonical
            // fingerprint with null.
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            // Use saveSkillWithMetricTag so the metric_tag field is stamped into the
            // frontmatter rather than being stripped by saveSkill's stripFrontmatter.
            boolean saved = saver.saveSkillWithMetricTag(
                    distilled.name(), distilled.description(), distilled.body(), metricTag);
            if (!saved) {
                log.warn("SkillSaveTool.saveSkillWithMetricTag returned false for '{}'", distilled.name());
            }
            // Stamp the canonical fingerprint synchronously — embedding text includes
            // sample_questions so bge-zh has enough lexical surface to spread cosine across
            // L2's min-cosine threshold (PR3.7).
            if (vectorIndex != null && embeddingClient != null) {
                String embedText = buildEmbedText(distilled);
                float[] vec = embeddingClient.embed(embedText);
                if (vec != null) vectorIndex.upsertVector(distilled.name(), fingerprint, vec);
            }
            log.info("Auto-synthesised skill '{}' from fingerprint {}", distilled.name(), fingerprint);
        } catch (Exception ex) {
            log.warn("SkillSaveTool failed for '{}': {}", distilled.name(), ex.getMessage());
        }
    }

    // -------- Distill task builder --------

    /**
     * Build the user message for the distillation subagent.
     * Metric context is already injected into the system prompt by
     * {@link SkillDistiller#buildDistillAgent}, so we don't repeat it here.
     */
    private String buildDistillTask(String question, String fingerprint, String toolCallContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下用户问题的处理流程蒸馏为一个可复用的 skill：\n\n");
        sb.append("**用户问题**: ").append(question).append("\n");
        sb.append("**Fingerprint**: ").append(fingerprint).append("\n");
        if (toolCallContext != null && !toolCallContext.isBlank()) {
            sb.append(toolCallContext).append("\n");
        }
        sb.append("\n请按以下步骤操作：\n");
        sb.append("1. 分析用户问题和工具调用链路，提取核心工作流程\n");
        sb.append("2. 用英文小写+下划线给技能命名（如 quality_query_analysis）\n");
        sb.append("3. 写一句话中文描述\n");
        sb.append("4. 把工作流程整理为 SKILL.md 正文\n");
        sb.append("5. 调用 save_skill 工具保存\n");
        sb.append("\n**重要 — SKILL.md 正文必须包含以下章节，每个章节都要详细（总长度至少 60 行）：**\n\n");
        sb.append("```markdown\n");
        sb.append("# <技能中文名>\n");
        sb.append("<一句话场景说明 — 什么类型的问题会触发此技能>\n\n");
        sb.append("## 父智能体派单逻辑\n");
        sb.append("1. 意图识别：识别出用户请求属于哪类指标查询\n");
        sb.append("2. 参数提取：从用户问题中提取时间、部门、指标等参数\n");
        sb.append("3. agent_spawn 入参示例（JSON）\n\n");
        sb.append("## 子智能体处理步骤\n");
        sb.append("### 步骤 1: 查阅 tool_index 选择 toolId\n");
        sb.append("- 入参 JSON 示例\n");
        sb.append("- 返回结果格式\n\n");
        sb.append("### 步骤 2: 调用 toolMetaInfo 获取参数定义\n");
        sb.append("- 入参 JSON 示例\n");
        sb.append("- 返回结果格式\n\n");
        sb.append("### 步骤 3: 调用 router_tool 执行查询\n");
        sb.append("- 入参 JSON 示例\n");
        sb.append("- 返回结果格式\n\n");
        sb.append("## 调用顺序图\n");
        sb.append("Supervisor → 子智能体 → tool_index → toolMetaInfo → router_tool\n\n");
        sb.append("## 参数标准化约束\n");
        sb.append("- 时间格式转换规则\n");
        sb.append("- 区域名称匹配规则\n");
        sb.append("- 数据类型校验规则\n\n");
        sb.append("## 异常处理\n");
        sb.append("- 工具未找到：如何处理\n");
        sb.append("- 参数缺失：如何追问用户\n");
        sb.append("- 查询超时或失败：重试策略\n");
        sb.append("- 空结果集：如何告知用户\n\n");
        sb.append("## 输出格式\n");
        sb.append("- 返回字段说明\n");
        sb.append("```\n\n");
        sb.append("**注意**：\n");
        sb.append("- 工具名只能用真实名称（tool_index / toolMetaInfo / router_tool），不要使用泛化名称\n");
        sb.append("- 不要在 content 中包含 YAML frontmatter，系统会自动生成\n");
        sb.append("- 参考上面的**工具调用链路详情**来编写，确保入参和出参与实际一致\n");
        sb.append("- 正文必须达到 60 行以上，内容要详细完整");
        return sb.toString();
    }

    /**
     * Sanitize a skill name: lowercase, replace non-alphanumeric chars with underscores.
     * Falls back to a hash-based name if input is null or blank.
     */
    public String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "skill_" + Integer.toHexString(name.hashCode()).substring(0, 8);
        }
        return name.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    // -------- Metric tag injection --------

    /**
     * 方案 B: 把 metric_tag 注入 SKILL.md body 的 frontmatter,使生成的 skill 自带指标语义标签。
     * 如果 body 已经有 frontmatter,在第一个 {@code ---} 块里追加 {@code metric_tag} 字段;
     * 否则在 body 开头插入一个新的 frontmatter 块。
     * <p>
     * 安全措施:
     * 1. 验证尾部有换行符再拼接
     * 2. 检查是否已存在相同 key(防重复)
     * 3. 确保 YAML 格式正确(key: value\n)
     */
    public static SkillDistiller.DistilledSkill withMetricTag(SkillDistiller.DistilledSkill skill, String metricTag) {
        String body = skill.body() == null ? "" : skill.body();
        String newBody;
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("---")) {
            // 已有 frontmatter,在结尾 --- 之前插入 metric_tag
            int second = trimmed.indexOf("\n---", 3);
            if (second > 0) {
                String before = body.substring(0, second);
                String after = body.substring(second);
                // 检查是否已存在该字段(防重复)
                if (before.contains("metric_tag:")) {
                    log.warn("Skipping duplicate metric_tag injection for skill '{}'", skill.name());
                    return skill;
                }
                newBody = before + "\nmetric_tag: " + metricTag + after;
            } else {
                newBody = body; // frontmatter 残缺,不动
            }
        } else {
            // 没有 frontmatter,在开头插入一个
            newBody = "---\nmetric_tag: " + metricTag + "\n---\n\n" + body;
        }
        return new SkillDistiller.DistilledSkill(
                skill.name(), skill.description(), newBody, skill.sampleQuestions());
    }

    // -------- Embedding text builder --------

    /**
     * Embedding source text. Concatenates description with the distilled sample_questions so
     * short Chinese skills get enough lexical breadth for bge-zh-v1.5 to produce a
     * discriminative vector. Falls back to {@code name + description} when samples are
     * missing (legacy distill output / parse failure).
     */
    public static String buildEmbedText(SkillDistiller.DistilledSkill d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.description() == null ? "" : d.description().trim());
        if (d.sampleQuestions() != null && !d.sampleQuestions().isEmpty()) {
            sb.append("\n\n");
            for (String q : d.sampleQuestions()) {
                if (q == null || q.isBlank()) continue;
                sb.append("- ").append(q.trim()).append('\n');
            }
        } else {
            // Old path — description alone tends to be too short for bge-zh; prefix the
            // skill name to add at least one more semantic token.
            sb.insert(0, (d.name() == null ? "" : d.name()) + " ");
        }
        return sb.toString().trim();
    }

    // -------- Enriched context builder --------

    // ==================== Night-time digestion entry point ====================

    /**
     * Night-time distillation entry point for Phase 3 (SkillFlowEvolver).
     *
     * <p>Unlike the online path ({@link #bumpAndMaybeSynthesize}), this method:
     * <ul>
     *   <li>Does NOT use the {@code skill_candidate} CAS ({@code markSynthesized}), because
     *       night-time digestion reads from {@code user_trace_summary} — there are no candidate
     *       rows to claim. Instead, it uses {@link SkillIndexRepository#findByName} as a
     *       simple dedup guard (if the skill name already exists, skip).</li>
     *   <li>Writes the skill directly to disk via {@link SkillSaveTool} and stamps the embedding
     *       synchronously (same as the online path, but without the async fire-and-forget).</li>
     *   <li>Stamps {@code tool_sequence_fingerprint} into the dedicated column rather than
     *       polluting the primary {@code fingerprint} column used by L1 lookup.</li>
     * </ul>
     *
     * @param toolSeqFp       the tool-sequence fingerprint from TraceMiner (e.g. "agent_spawn|tool_index|router_tool")
     * @param runtimeFp       the runtime metric fingerprint for L1 lookup (e.g. "_global|query|defect_density")
     * @param userQuery       the exemplar user question
     * @param toolCallContext pre-formatted tool call details for the distillation prompt
     * @param metricTag       optional metric classification tag
     * @return the distilled skill name on success, null on failure or when the subagent didn't
     *         call save_skill
     */
    public String distillForDigestion(String toolSeqFp, String runtimeFp,
                                        String userQuery, String toolCallContext,
                                        String metricTag) {
        if (!enabled) {
            log.debug("Skill synthesis disabled; skipping digestion distill for fp={}", toolSeqFp);
            return null;
        }

        // Dedup: if an auto-synthesized skill with this runtime fingerprint already exists, skip.
        // Source-scoped so a user skill with a colliding fingerprint (theoretical - user saves
        // don't compute one) doesn't block auto distillation.
        if (runtimeFp != null && !runtimeFp.isBlank()) {
            java.util.Optional<String> existing =
                    indexRepo.findNameByFingerprint(runtimeFp, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            if (existing.isPresent()) {
                log.info("Skill already exists for runtime fingerprint {}; skipping distill", runtimeFp);
                return null;
            }
        }

        CaptureSkillSaveTool captureTool = new CaptureSkillSaveTool();
        HarnessAgent distillAgent;
        try {
            String enrichedContext = buildEnrichedContext(userQuery, toolCallContext);
            distillAgent = distiller.buildDistillAgent(userQuery, enrichedContext, metricTag, captureTool);
        } catch (Exception e) {
            log.warn("Failed to build distill agent for digestion: {}", e.getMessage());
            return null;
        }

        // Build user message
        String task = buildDistillTask(userQuery, toolSeqFp, toolCallContext);
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(task).build())
                .build();

        // Run subagent (synchronous on the caller's thread — already off the request path)
        String safeFingerprint = toolSeqFp.replaceAll("[|<>:\"?*\\\\/]", "_");
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("digest-" + safeFingerprint)
                .userId("_digest")
                .build();
        try {
            log.info("Starting digestion distill subagent for toolSeqFp={}", toolSeqFp);
            distillAgent.call(List.of(userMsg), ctx).block();
            log.info("Digestion distill subagent completed for toolSeqFp={}", toolSeqFp);
        } catch (Exception e) {
            log.warn("Digestion distill subagent failed for fp={}: {}", toolSeqFp, e.getMessage());
            return null;
        }

        // Capture result
        if (!captureTool.hasCaptured()) {
            log.warn("Digestion distill subagent did not call save_skill (fp={})", toolSeqFp);
            return null;
        }

        CaptureSkillSaveTool.CapturedSkill captured = captureTool.getCaptured();
        String name = sanitizeName(captured.name());
        String description = captured.description();
        String body = captured.content();
        // Loop-strip all frontmatter blocks
        while (body.startsWith("---")) {
            String stripped = SkillSaveTool.stripFrontmatter(body);
            if (stripped.equals(body)) break;
            body = stripped;
        }
        List<String> samples = SkillDistiller.parseSamples(body);

        SkillDistiller.DistilledSkill distilled =
                new SkillDistiller.DistilledSkill(name, description, body, samples);

        // metricTag stamp
        if (metricTag != null && !metricTag.isBlank()) {
            distilled = withMetricTag(distilled, metricTag);
            log.info("Digestion distilled skill '{}' tagged with metric_tag={}", distilled.name(), metricTag);
        }

        // Second dedup: check by skill name (another path may have created it)
        java.util.Optional<SkillEntry> byName = indexRepo.findByName(distilled.name());
        if (byName.isPresent()) {
            log.info("Skill '{}' already exists in skill_index; skipping save", distilled.name());
            return null;
        }

        // Save to disk + upsert index row (no candidate CAS — night-time path)
        try {
            // Pass null embeddingClient — we stamp the embedding ourselves below with richer text
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            boolean saved = saver.saveSkillWithMetricTag(
                    distilled.name(), distilled.description(), distilled.body(), metricTag);
            if (!saved) {
                log.warn("SkillSaveTool.saveSkillWithMetricTag returned false for '{}' (digestion)", distilled.name());
                return null;
            }

            // Stamp the canonical runtime fingerprint + embedding synchronously
            if (vectorIndex != null && embeddingClient != null) {
                String embedText = buildEmbedText(distilled);
                float[] vec = embeddingClient.embed(embedText);
                if (vec != null) {
                    // Use runtimeFp as the canonical fingerprint for L1 retrieval
                    vectorIndex.upsertVector(distilled.name(), runtimeFp, vec);
                }
            }

            // Write tool_sequence_fingerprint to dedicated column (not the primary fingerprint column)
            if (toolSeqFp != null && !toolSeqFp.isBlank()) {
                indexRepo.upsertToolSequenceFingerprint(distilled.name(), toolSeqFp);
            }

            log.info("Digestion-synthesised skill '{}' from toolSeqFp={}, runtimeFp={}",
                    distilled.name(), toolSeqFp, runtimeFp);
            return distilled.name();
        } catch (Exception ex) {
            log.warn("Digestion save failed for '{}': {}", distilled.name(), ex.getMessage());
            return null;
        }
    }

    /**
     * Build an LLM-friendly text block from the tool call details JSON.
     * Renders as a numbered step list with tool name, level, input, and output.
     */
    public static String buildEnrichedContext(String userQuery, String toolCallDetailsJson) {
        if (toolCallDetailsJson == null || toolCallDetailsJson.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<com.agentscopea2a.v2.tools.ToolCallCollector.ToolCallDetail> details =
                    mapper.readValue(toolCallDetailsJson,
                            new com.fasterxml.jackson.core.type.TypeReference<List<com.agentscopea2a.v2.tools.ToolCallCollector.ToolCallDetail>>() {});
            if (details.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n\n**工具调用链路详情**:\n");
            int step = 1;
            for (var d : details) {
                sb.append("\n").append(step).append(". [").append(d.level()).append("] ")
                        .append(d.tool()).append("\n");
                if (d.input() != null && !d.input().isBlank()) {
                    sb.append("   - 输入: ").append(d.input()).append("\n");
                }
                if (d.output() != null && !d.output().isBlank()) {
                    sb.append("   - 输出: ").append(d.output()).append("\n");
                }
                step++;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}