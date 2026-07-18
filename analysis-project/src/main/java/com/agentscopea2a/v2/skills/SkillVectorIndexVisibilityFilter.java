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
package com.agentscopea2a.v2.skills;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges {@link SkillVectorIndex} into the v2 skill curator pipeline as a
 * {@link SkillVisibilityFilter}.
 *
 * <p>During agent execution, the curator asks all registered
 * {@link SkillVisibilityFilter}s to trim the full skill catalogue down to the
 * ones relevant for the current request. This filter:
 *
 * <ol>
 *   <li>Extracts the user question from the {@link RuntimeContext} (via the
 *       dimension state that the {@code DimensionStateMiddleware} stored).</li>
 *   <li>Generates an embedding for the question using the configured
 *       {@link com.agentscopea2a.v2.skills.EmbeddingClient}.</li>
 *   <li>Calls {@link SkillVectorIndex#topK(float[], int, float)} to find the
 *       most semantically similar skills.</li>
 *   <li>Returns only those skills whose names appear in the top-K results.</li>
 * </ol>
 *
 * <p>Fallback: if embedding generation fails or the index is empty, passes all
 * skills through unchanged (the existing full-injection path).
 */
public class SkillVectorIndexVisibilityFilter implements SkillVisibilityFilter {

    private static final Logger log = LoggerFactory.getLogger(SkillVectorIndexVisibilityFilter.class);

    private final SkillVectorIndex index;
    private final EmbeddingClient embeddingClient;
    private final int topK;
    private final float minCosine;

    /**
     * @param index            the vector/fingerprint index for L1/L2 skill lookup
     * @param embeddingClient   client for generating question embeddings
     * @param topK              maximum number of skills to return from vector search
     * @param minCosine         minimum cosine similarity threshold for L2 search
     */
    public SkillVectorIndexVisibilityFilter(SkillVectorIndex index,
                                             EmbeddingClient embeddingClient,
                                             int topK,
                                             float minCosine) {
        this.index = index;
        this.embeddingClient = embeddingClient;
        this.topK = topK;
        this.minCosine = minCosine;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        // Extract the user question from RuntimeContext (stored by DimensionStateMiddleware)
        String question = extractQuestion(ctx);
        if (question == null || question.isBlank()) {
            log.debug("No question in RuntimeContext, passing all {} skills through", all.size());
            return all;
        }

        // Generate embedding for the question
        float[] queryVec;
        try {
            queryVec = embeddingClient.embed(question);
        } catch (Exception e) {
            log.warn("Embedding generation failed for question ({} chars), passing all skills: {}",
                    question.length(), e.getMessage());
            return all;
        }

        if (queryVec == null || queryVec.length == 0) {
            log.debug("Empty embedding returned, passing all {} skills through", all.size());
            return all;
        }

        // L2 vector search for top-K matching skills
        List<SkillVectorIndex.SkillHit> hits = index.topK(queryVec, topK, minCosine);
        if (hits.isEmpty()) {
            log.debug("No skill hits from vector search for question ({} chars), passing all skills", question.length());
            return all;
        }

        // Filter: keep only skills whose names appear in the hit list
        java.util.Set<String> hitNames = new java.util.HashSet<>();
        for (SkillVectorIndex.SkillHit hit : hits) {
            hitNames.add(hit.name());
        }

        List<AgentSkill> filtered = new ArrayList<>();
        for (AgentSkill skill : all) {
            if (hitNames.contains(skill.getName())) {
                filtered.add(skill);
            }
        }

        log.debug("SkillVectorIndexVisibilityFilter: {} → {} skills (topK={} minCosine={})",
                all.size(), filtered.size(), topK, minCosine);

        // If filtering removed everything (unlikely but possible), fall back to all
        return filtered.isEmpty() ? all : filtered;
    }

    private String extractQuestion(RuntimeContext ctx) {
        // Try to get the question from RuntimeContext state (stored by DimensionStateMiddleware)
        if (ctx == null) return null;

        // The DimensionStateMiddleware stores the processed question as a typed attribute
        String question = ctx.get("lastQuestion", String.class);
        if (question != null && !question.isBlank()) {
            return question;
        }

        // Fallback: try untyped attribute
        Object questionObj = ctx.get("lastQuestion");
        if (questionObj instanceof String q && !q.isBlank()) {
            return q;
        }

        return null;
    }
}