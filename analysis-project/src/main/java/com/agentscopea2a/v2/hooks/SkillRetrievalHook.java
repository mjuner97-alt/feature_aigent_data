/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.cache.ResponseCacheService;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.EpisodicResult;
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
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
 * v2 port of the v1 PR3 skill-retrieval hook.
 *
 * <p>Replaces the v1 constructor-injected {@link RuntimeContext} with
 * {@link RuntimeContextAware} so the hook can be a singleton bean while still
 * accessing the per-call context. The JAR pushes the context via
 * {@link #setRuntimeContext(RuntimeContext)} before each call starts.
 *
 * <p>Four-stage retrieval router (user-first, auto-fallback):
 * <ol>
 *   <li>L1 user - fingerprint exact match on {@code source='user_generated'}</li>
 *   <li>L2 user - vector topK on {@code source='user_generated'}</li>
 *   <li>L1 auto - fingerprint match on {@code source='auto_synthesized'}</li>
 *   <li>L2 auto - vector topK on {@code source='auto_synthesized'}</li>
 * </ol>
 *
 * <p>Matched SKILL.md bodies are appended to the system message via
 * {@link HookEvent#appendSystemContent(String)}.
 */
public class SkillRetrievalHook implements Hook, RuntimeContextAware {

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
    private final boolean enabled;
    private final int topK;
    private final float minCosine;

    private volatile RuntimeContext currentCtx;

    public SkillRetrievalHook(
            SkillVectorIndex vectorIndex,
            SkillIndexRepository indexRepo,
            FingerprintCalculator fingerprintCalculator,
            EmbeddingClient embeddingClient,
            EpisodicMemory episodicMemory,
            Path skillsDir,
            Path skillsUserDir,
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
        this.enabled = enabled;
        this.topK = topK;
        this.minCosine = minCosine;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    public int priority() {
        return -50;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled) return Mono.just(event);
        if (!(event instanceof PreCallEvent e)) {
            return Mono.just(event);
        }
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> {
                    try {
                        inject(e, ctx);
                    } catch (Exception ex) {
                        log.warn("SkillRetrievalHook injection skipped: {}", ex.getMessage());
                    }
                })
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) {
                        try {
                            inject(e, currentCtx);
                        } catch (Exception ex) {
                            log.warn("SkillRetrievalHook injection skipped: {}", ex.getMessage());
                        }
                    }
                }))
                .then(Mono.just(event));
    }

    private void inject(PreCallEvent event, RuntimeContext ctx) {
        String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (question.isEmpty()) return;

        LinkedHashMap<String, String> picked = new LinkedHashMap<>();

        String fingerprint = fingerprintOf(question);
        if (fingerprint != null) {
            Optional<String> l1User =
                    vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_USER_GENERATED);
            l1User.ifPresent(n -> picked.put(n, SkillEntry.SOURCE_USER_GENERATED));
        }
        log.debug("L1 user result for fp={}: picked={}", fingerprint, picked.keySet());

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
            }
        }

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
            if (body.length() > MAX_SKILL_BODY_INJECT) {
                body = body.substring(0, MAX_SKILL_BODY_INJECT) + "\n...[truncated]";
            }
            block.append("\n### Retrieved skill: ").append(name).append("\n\n").append(body).append("\n");
            loaded.add(name);
            indexRepo.recordUsage(name);

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

        try {
            ctx.put("skills.retrieved", List.copyOf(loaded));
        } catch (Exception ex) {
            log.debug("Failed to publish skills.retrieved attribute: {}", ex.getMessage());
        }

        log.info(
                "SkillRetrievalHook injected {} skill(s) for tenant={} fp={}: {}",
                loaded.size(),
                FingerprintCalculator.tenantBucket(ctx),
                fingerprint,
                loaded);
    }

    private String queryEpisodicContext(String skillName, String skillBody) {
        try {
            String searchQuery = skillName;
            if (skillBody != null) {
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

    private String fingerprintOf(String question) {
        try {
            String intent = ResponseCacheService.classifyIntent(question);
            if (intent == null || intent.isEmpty()) return null;
            return fingerprintCalculator.computeMetric(intent, question);
        } catch (Exception ex) {
            log.debug("fingerprintOf failed: {}", ex.getMessage());
            return null;
        }
    }

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
}
