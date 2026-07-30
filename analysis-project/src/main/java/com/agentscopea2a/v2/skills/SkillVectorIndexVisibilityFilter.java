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

import java.util.List;

/**
 * Bridges {@link SkillVectorIndex} into the v2 skill curator pipeline as a
 * {@link SkillVisibilityFilter}.
 *
 * <p>During agent execution, the curator asks all registered
 * {@link SkillVisibilityFilter}s to trim the full skill catalogue down to the
 * ones relevant for the current request. This filter:
 *
 * <p>Fields/constructor kept intact so {@link com.agentscopea2a.v2.config.V2SkillConfig}
 * bean wiring and {@code CompositeFilter} wrapping in {@code HarnessA2aRunnerV2}
 * don't need to change. They're unused now and can be cleaned up in a follow-up.
 */
public class SkillVectorIndexVisibilityFilter implements SkillVisibilityFilter {

    private static final Logger log = LoggerFactory.getLogger(SkillVectorIndexVisibilityFilter.class);

    private final SkillVectorIndex index;
    private final EmbeddingClient embeddingClient;
    private final int topK;
    private final float minCosine;

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
        log.debug("SkillVectorIndexVisibilityFilter pass-through: {} skills (hot-load enabled)", all.size());
        return all;
    }
}
