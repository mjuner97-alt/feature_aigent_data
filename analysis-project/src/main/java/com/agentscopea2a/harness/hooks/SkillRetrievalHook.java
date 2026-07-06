/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PR3 — focused skill retrieval. Replaces "WorkspaceContextHook always injects every SKILL.md"
 * with a two-stage router:
 *
 * <ol>
 *   <li><b>L1 fingerprint</b> — {@code tenant|intent|metricTag} exact match against
 *       {@code skill_index.fingerprint} (populated by PR2's synthesis path). Sub-millisecond.
 *       Uses metric-level fingerprint so that questions about the same metric (regardless of
 *       dimensional qualifiers like time range or department) hit the same skill.
 *   <li><b>L2 vector</b> — when L1 misses and an {@link EmbeddingClient} is configured, embed
 *       the question and pick the top-K skills whose cosine is above the threshold.
 * </ol>
 *
 * <p>Matched SKILL.md bodies are <i>appended</i> to the system message via
 * {@link HookEvent#appendSystemContent(String)}. We do NOT remove the framework-internal
 * WorkspaceContextHook's full-skill injection — that hook is JAR-internal with no disable API,
 * so PR3 ships as a <b>net-add</b> path. The duplication is acceptable because:
 * <ul>
 *   <li>PR3 is disabled by default; rollout is opt-in
 *   <li>Top-K hits act as a "spotlighted" block at the bottom of the system prompt, which most
 *       LLMs anchor on
 *   <li>Once production validates accuracy gains, the JAR-level switch can land in a follow-up
 * </ul>
 *
 * <p>Recordkeeping: every hit also calls {@link SkillIndexRepository#recordUsage(String)} so
 * PR4 (evolution) has a {@code last_used} timestamp + {@code usage_count} to drive its
 * decisions. Misses are silent — never log spam on every user turn.
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
                // Retrieval must never block a request — log and let WorkspaceContextHook do
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

        Set<String> picked = new LinkedHashSet<>();

        // L1 — metric-level fingerprint exact match (never null — metricTag always present)
        String fingerprint = fingerprintOf(question);
        if (fingerprint != null) {
            Optional<String> l1 = vectorIndex.findByFingerprint(fingerprint);
            l1.ifPresent(picked::add);
        }
        log.debug("L1 result for fp={}: picked={}", fingerprint, picked);

        // L2 — vector top-K (only when L1 missed and embedding is configured)
        if (picked.isEmpty() && embeddingClient != null) {
            float[] vec = embeddingClient.embed(question);
            if (vec != null) {
                List<SkillVectorIndex.SkillHit> hits = vectorIndex.topK(vec, topK, minCosine);
                log.debug("L2 topK (k={}, min={}) returned {} hit(s): {}",
                        topK, minCosine, hits.size(), hits);
                for (SkillVectorIndex.SkillHit h : hits) {
                    picked.add(h.name());
                }
            } else {
                log.debug("L2 embed returned null for question");
            }
        }

        if (picked.isEmpty()) return;

        List<String> loaded = new ArrayList<>();
        StringBuilder block = new StringBuilder(INJECTED_HEADER);
        boolean hasEpisodicContext = false;
        StringBuilder episodicBlock = new StringBuilder();
        for (String name : picked) {
            String body = readSkillBody(name);
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

        // PR4 plumbing — let SkillEvolutionHook attribute success/failure to these skills at
        // PostCall. RuntimeContext is per-call so this attribute dies at end-of-turn; the
        // cross-turn rejection lookback is handled separately by SkillEvolutionRunner's
        // per-session cache. Safe to write even when PR4 is disabled — the attribute is just
        // never read.
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
     * <p>Blocks for at most 200ms — this runs on the PreCall hot path and must not add perceptible
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
     * <p>Never returns null — metric-level fingerprints always have a valid metricTag
     * (falling back to "general" when no metric keyword matches).
     */
    private String fingerprintOf(String question) {
        try {
            // Skill fingerprint uses _global scope — all users share the same skill.
            String intent = ResponseCacheService.classifyIntent(question);
            if (intent == null || intent.isEmpty()) return null;
            return fingerprintCalculator.computeMetric(intent, question);
        } catch (Exception ex) {
            log.debug("fingerprintOf failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Reads the SKILL.md body (including frontmatter — the LLM benefits from version /
     * usage hints anyway). Returns {@code null} on missing or unreadable file.
     */
    private String readSkillBody(String name) {
        Path p = skillsDir.resolve(name).resolve("SKILL.md");
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
