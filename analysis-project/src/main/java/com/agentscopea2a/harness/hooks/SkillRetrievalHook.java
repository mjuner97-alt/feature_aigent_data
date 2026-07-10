/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
import com.agentscopea2a.harness.skills.SkillEntry;
import com.agentscopea2a.harness.skills.SkillIndexRepository;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.EpisodicMemory;
import io.agentscope.core.memory.EpisodicResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR3 - focused skill retrieval. Replaces "WorkspaceContextHook always injects every SKILL.md"
 * with a four-stage router that probes user skills first (priority) then falls back to auto:
 *
 * <ol>
 *   <li><b>L1 user</b> - fingerprint exact match against {@code skill_index.fingerprint} with
 *       {@code source='user_generated'}. User skills usually have no fingerprint (manual save
 *       doesn't compute one), so this typically misses and we fall through to L2 user.
 *   <li><b>L2 user</b> - vector topK with {@code source='user_generated'}. Embedding is
 *       computed once here and reused for L2 auto if we fall through.
 *   <li><b>L1 auto</b> - fingerprint match with {@code source='auto_synthesized'}.
 *   <li><b>L2 auto</b> - vector topK with {@code source='auto_synthesized'}.
 * </ol>
 *
 * <p>Strict priority: once any user skill is picked, the auto phases are skipped. This keeps
 * high quality user-authored skills from being crowded out by experimental auto-distilled ones.
 *
 * <p>Matched SKILL.md bodies are <i>appended</i> to the system message via
 * {@link HookEvent#appendSystemContent(String)}. We do NOT remove the framework-internal
 * WorkspaceContextHook's full-skill injection - that hook is JAR-internal with no disable API,
 * so PR3 ships as a <b>net-add</b> path.
 *
 * <p>Recordkeeping: every hit also calls {@link SkillIndexRepository#recordUsage(String)} so
 * PR4 (evolution) has a {@code last_used} timestamp + {@code usage_count} to drive its
 * decisions. Both user and auto skills are counted; only auto skills are eligible for evolve
 * (see {@code SkillEvolutionRunner}). Misses are silent - never log spam on every user turn.
 */
public class SkillRetrievalHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SkillRetrievalHook.class);
    private static final String INJECTED_HEADER = "\n\n<!-- skills.retrieved (PR3) -->\n";
    private static final int MAX_EPISODIC_SNIPPET_LEN = 300;
    private static final int MAX_SKILL_BODY_INJECT = 2000;

    private final SkillVectorIndex vectorIndex;
    private final SkillIndexRepository indexRepo;
    private final FingerprintCalculator fingerprintCalculator;
    private final EmbeddingClient embeddingClient;
    private final EpisodicMemory episodicMemory;
    private final Path skillsDir;
    private final Path skillsUserDir;
    private final RuntimeContext runtimeContext;
    private final boolean enabled;
    private final int topK;
    private final float minCosine;

    public SkillRetrievalHook(
            SkillVectorIndex vectorIndex,
            SkillIndexRepository indexRepo,
            FingerprintCalculator fingerprintCalculator,
            EmbeddingClient embeddingClient,
            EpisodicMemory episodicMemory,
            Path skillsDir,
            Path skillsUserDir,
            RuntimeContext runtimeContext,
            boolean enabled,
            int topK,
            float minCosine) {
        this.vectorIndex = vectorIndex;
        this.indexRepo = indexRepo;
        this.fingerprintCalculator = fingerprintCalculator;
        this.embeddingClient = embeddingClient;
        this.episodicMemory = episodicMemory;
        this.skillsDir = skillsDir;
        this.skillsUserDir = skillsUserDir;
        this.runtimeContext = runtimeContext;
        this.enabled = enabled;
        this.topK = topK;
        this.minCosine = minCosine;
    }

    @Override
    public int priority() {
        // Run before WorkspaceContextHook (its priority is +0/+10 range) so our hit is at the
        // top of additions; not strictly required because we append, but consistent ordering
        // makes log diffs easier to read.
        return -50;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled) return Mono.just(event);
        if (event instanceof PreCallEvent e) {
            try {
                inject(e);
            } catch (Exception ex) {
                // Retrieval must never block a request - log and let WorkspaceContextHook do
                // the full-injection fallback.
                log.warn("SkillRetrievalHook injection skipped: {}", ex.getMessage());
            }
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    private void inject(PreCallEvent event) {
        String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (question.isEmpty()) return;

        // name -> source so readSkillBody picks the right folder. LinkedHashMap preserves
        // insertion order: user skills first (priority), auto skills only when user missed.
        LinkedHashMap<String, String> picked = new LinkedHashMap<>();

        // ---- Phase 1: L1 user (fingerprint exact match on user_generated skills) ----
        String fingerprint = fingerprintOf(question);
        if (fingerprint != null) {
            Optional<String> l1User =
                    vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_USER_GENERATED);
            l1User.ifPresent(n -> picked.put(n, SkillEntry.SOURCE_USER_GENERATED));
        }
        log.debug("L1 user result for fp={}: picked={}", fingerprint, picked.keySet());

        // ---- Phase 2: L2 user (vector topK on user_generated skills) ----
        // Only when L1 user missed AND embedding is configured. Embedding is computed once
        // here and reused for phase 4 if needed.
        float[] vec = null;
        boolean embeddingComputed = false;
        if (picked.isEmpty() && embeddingClient != null) {
            vec = embeddingClient.embed(question);
            embeddingComputed = true;
            if (vec != null) {
                List<SkillVectorIndex.SkillHit> hits =
                        vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_USER_GENERATED);
                log.debug("L2 user topK (k={}, min={}) returned {} hit(s): {}",
                        topK, minCosine, hits.size(), hits);
                for (SkillVectorIndex.SkillHit h : hits) {
                    picked.put(h.name(), SkillEntry.SOURCE_USER_GENERATED);
                }
            } else {
                log.debug("L2 user embed returned null for question");
            }
        }

        // ---- Phase 3 + 4: auto fallback (only when user phase completely missed) ----
        // Strict user priority, auto fallback: once any user skill is picked, auto is skipped.
        if (picked.isEmpty()) {
            if (fingerprint != null) {
                Optional<String> l1Auto =
                        vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
                l1Auto.ifPresent(n -> picked.put(n, SkillEntry.SOURCE_AUTO_SYNTHESIZED));
            }
            log.debug("L1 auto result for fp={}: picked={}", fingerprint, picked.keySet());

            if (picked.isEmpty() && embeddingClient != null) {
                if (!embeddingComputed) {
                    vec = embeddingClient.embed(question);
                }
                if (vec != null) {
                    List<SkillVectorIndex.SkillHit> hits =
                            vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
                    log.debug("L2 auto topK returned {} hit(s): {}", hits.size(), hits);
                    for (SkillVectorIndex.SkillHit h : hits) {
                        picked.put(h.name(), SkillEntry.SOURCE_AUTO_SYNTHESIZED);
                    }
                }
            }
        }

        if (picked.isEmpty()) return;

        List<String> loaded = new ArrayList<>();
        StringBuilder block = new StringBuilder(INJECTED_HEADER);
        boolean hasEpisodicContext = false;
        StringBuilder episodicBlock = new StringBuilder();
        for (Map.Entry<String, String> entry : picked.entrySet()) {
            String name = entry.getKey();
            String source = entry.getValue();
            String body = readSkillBody(name, source);
            if (body == null) continue;
            // Truncate skill body to prevent system prompt inflation (see review.md §6.3.3).
            // Full body is available via the fetch_skill_detail tool when LLM needs it.
            if (body.length() > MAX_SKILL_BODY_INJECT) {
                body = body.substring(0, MAX_SKILL_BODY_INJECT) + "\n...[truncated]";
            }
            block.append("\n### Retrieved skill: ").append(name).append("\n\n").append(body).append("\n");
            loaded.add(name);
            indexRepo.recordUsage(name);

            // Fetch recent episodic memory context for this retrieved skill (P2-2).
            // Uses the skill name + description excerpt as search query to find relevant
            // past conversation snippets and append them as reference cases.
            if (episodicMemory != null && !hasEpisodicContext) {
                String refCases = queryEpisodicContext(name, body);
                if (!refCases.isEmpty()) {
                    episodicBlock.append(refCases);
                    hasEpisodicContext = true;
                }
            }
        }
        if (loaded.isEmpty()) return;

        if (hasEpisodicContext) {
            block.append("\n").append(episodicBlock);
        }

        event.appendSystemContent(block.toString());

        // PR4 plumbing - let SkillEvolutionHook attribute success/failure to these skills at
        // PostCall. RuntimeContext is per-call so this attribute dies at end-of-turn; the
        // cross-turn rejection lookback is handled separately by SkillEvolutionRunner's
        // per-session cache. Safe to write even when PR4 is disabled - the attribute is just
        // never read. Includes user skills so PR4 can count their success/failure too (user
        // skills are counted but never evolved - see SkillEvolutionRunner).
        if (runtimeContext != null) {
            try {
                runtimeContext.put("skills.retrieved", List.copyOf(loaded));
            } catch (Exception ex) {
                log.debug("Failed to publish skills.retrieved attribute: {}", ex.getMessage());
            }
        }

        log.info(
                "SkillRetrievalHook injected {} skill(s) for tenant={} fp={}: {}",
                loaded.size(),
                tenantBucket(),
                fingerprint,
                loaded);
    }

    /**
     * Queries episodic memory for recent conversation snippets related to this skill.
     * Returns a markdown-formatted "最近参考案例" block, or empty string if nothing found.
     *
     * <p>Blocks for at most 200ms - this runs on the PreCall hot path and must not add perceptible
     * latency. Episodic search is normally 5-15ms; if it ever exceeds 200ms (e.g. cold cache,
     * large index), we skip the episodic context rather than making the user wait. The previous
     * 3-second block was a regression risk on reactive scheduler threads (see review.md §2.2).
     */
    private String queryEpisodicContext(String skillName, String skillBody) {
        try {
            String searchQuery = skillName;
            if (skillBody != null) {
                // Extract first meaningful line of description for query context
                String bodyPreview = skillBody.replaceAll("(?s)---.*?---", "").trim();
                if (!bodyPreview.isEmpty()) {
                    String firstLine = bodyPreview.lines().findFirst().orElse("").trim();
                    if (firstLine.length() > 5) {
                        searchQuery = skillName + " " + firstLine;
                    }
                }
            }
            List<EpisodicResult> results = episodicMemory.search(searchQuery, 2)
                    .block(Duration.ofMillis(200));
            if (results == null || results.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("## 最近参考案例\n");
            for (EpisodicResult r : results) {
                String snippet = r.snippet();
                if (snippet != null && snippet.length() > MAX_EPISODIC_SNIPPET_LEN) {
                    snippet = snippet.substring(0, MAX_EPISODIC_SNIPPET_LEN) + "...";
                }
                sb.append("- ").append(snippet).append("\n");
            }
            return sb.toString();
        } catch (Exception ex) {
            log.debug("Episodic context query failed for skill '{}': {}", skillName, ex.getMessage());
            return "";
        }
    }

    /**
     * Compute metric-level fingerprint for L1 lookup. Uses
     * {@link FingerprintCalculator#computeMetric} so that questions about the same metric
     * (regardless of dimensional qualifiers) hit the same skill.
     *
     * <p>Never returns null - metric-level fingerprints always have a valid metricTag
     * (falling back to "general" when no metric keyword matches).
     */
    private String fingerprintOf(String question) {
        try {
            // Skill fingerprint uses _global scope - all users share the same skill.
            String intent = ResponseCacheService.classifyIntent(question);
            if (intent == null || intent.isEmpty()) return null;
            return fingerprintCalculator.computeMetric(intent, question);
        } catch (Exception ex) {
            log.debug("fingerprintOf failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Reads the SKILL.md body (including frontmatter - the LLM benefits from version /
     * usage hints anyway). Returns {@code null} on missing or unreadable file. Picks the
     * folder based on {@code source}: {@code skills-user/} for user_generated,
     * {@code skills-auto/} for auto_synthesized.
     */
    private String readSkillBody(String name, String source) {
        Path dir = SkillEntry.SOURCE_USER_GENERATED.equals(source) ? skillsUserDir : skillsDir;
        Path p = dir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.debug("Failed to read {}: {}", p, ex.getMessage());
            return null;
        }
    }

    private String tenantBucket() {
        return FingerprintCalculator.tenantBucket(runtimeContext);
    }
}
