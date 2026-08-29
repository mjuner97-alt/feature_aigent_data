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

import com.agentscopea2a.v2.capability.CapabilityRepository;
import com.agentscopea2a.v2.capability.CapabilityRouter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    private static final String MEETING_MATERIAL_DOMAIN = "例会材料";

    private final SkillRoutingMetadataRepository routingMetadataRepository;
    private final SkillCandidateSelector candidateSelector;
    private final boolean enabled;
    private final CapabilityRepository capabilityRepository;
    private final CapabilityRouter capabilityRouter;

    public SkillVectorIndexVisibilityFilter(
            SkillRoutingMetadataRepository routingMetadataRepository,
            SkillCandidateSelector candidateSelector,
            boolean enabled) {
        this(routingMetadataRepository, candidateSelector, enabled, null, null);
    }

    public SkillVectorIndexVisibilityFilter(
            SkillRoutingMetadataRepository routingMetadataRepository,
            SkillCandidateSelector candidateSelector,
            boolean enabled,
            CapabilityRepository capabilityRepository,
            CapabilityRouter capabilityRouter) {
        this.routingMetadataRepository = routingMetadataRepository;
        this.candidateSelector = candidateSelector;
        this.enabled = enabled;
        this.capabilityRepository = capabilityRepository;
        this.capabilityRouter = capabilityRouter;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (!enabled || ctx == null) {
            return all;
        }
        String question = ctx.get("lastQuestion", String.class);
        if (question == null || question.isBlank()) {
            return all;
        }
        List<SkillRoutingMetadata> metadata = routingMetadataRepository.findActive();
        if (metadata.isEmpty()) {
            log.warn("No active skill routing metadata; leaving {} skills visible", all.size());
            return all;
        }
        boolean explicitSkill = metadata.stream().anyMatch(m -> contains(question, m.skillName())
                || (m.aliases() != null && m.aliases().stream().anyMatch(a -> contains(question, a))));
        if (!explicitSkill) {
            boolean meetingMaterialRequest = contains(question, MEETING_MATERIAL_DOMAIN);
            metadata = metadata.stream()
                    .filter(m -> hasExactTag(m.domainTags(), MEETING_MATERIAL_DOMAIN)
                            == meetingMaterialRequest)
                    .toList();
        }
        if (!explicitSkill && capabilityRepository != null && capabilityRouter != null) {
            var recalled = capabilityRouter.recallSkillNames(question,
                    capabilityRepository.findActive(), capabilityRepository.findActiveSkillBindings());
            if (!recalled.isEmpty()) {
                metadata = metadata.stream().filter(m -> recalled.contains(m.skillName())).toList();
            }
        }
        SkillCandidateSelection selection = candidateSelector.select(all, metadata, question);
        if (selection.skillNames().isEmpty()) {
            log.warn("No routed Skill candidates after visibility/domain filtering");
            return List.of();
        }
        Map<String, AgentSkill> byName = all.stream()
                .collect(Collectors.toMap(AgentSkill::getName, skill -> skill, (left, right) -> left));
        List<AgentSkill> result = selection.skillNames().stream()
                .map(byName::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        log.debug("Skill candidate selection: all={}, selected={}, explicit={}, confident={}, fallback={}",
                all.size(), result.size(), selection.explicitNameMatched(), selection.confident(),
                selection.fallbackExpanded());
        return result;
    }

    private static boolean contains(String question, String value) {
        if (question == null || value == null) return false;
        return question.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT).trim());
    }

    private static boolean hasExactTag(List<String> tags, String expected) {
        String normalizedExpected = expected.toLowerCase(Locale.ROOT).trim();
        return tags != null && tags.stream()
                .filter(java.util.Objects::nonNull)
                .map(tag -> tag.toLowerCase(Locale.ROOT).trim())
                .anyMatch(normalizedExpected::equals);
    }
}
