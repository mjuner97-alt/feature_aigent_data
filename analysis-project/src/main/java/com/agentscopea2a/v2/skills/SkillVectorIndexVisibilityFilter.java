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
 * Pass-through {@link SkillVisibilityFilter} that lets the JAR
 * {@code HarnessSkillMiddleware} surface all {@code skills/} entries in the
 * catalogue; the LLM picks by name+description and loads body on demand.
 *
 * <p>2026/07/29: filter body trimmed to pass-through. Original logic narrowed
 * the catalogue by question embedding top-K, but that blocked hot-loading: a
 * newly-dropped {@code SKILL.md} had no {@code skill_index} row and was filtered
 * out before the LLM ever saw it. With retrieval already disabled
 * ({@code harness.skills.retrieval.enabled=false}) and the analyze_data workflow
 * using explicit {@code load_skill_through_path}, the vector filter was dead
 * weight.
 * <p>During agent execution, the curator asks all registered
 * {@link SkillVisibilityFilter}s to trim the full skill catalogue down to the
 * ones relevant for the current request. This filter:
 *
 * <p>2026/07/30: embedding/vector dependencies removed entirely. The class is
 * kept as a no-arg bean because {@code HarnessA2aRunnerV2} wraps it in a
 * {@code CompositeFilter} via the {@code SkillVisibilityFilter} type.
 */
public class SkillVectorIndexVisibilityFilter implements SkillVisibilityFilter {

    private static final Logger log = LoggerFactory.getLogger(SkillVectorIndexVisibilityFilter.class);

    public SkillVectorIndexVisibilityFilter() {
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
