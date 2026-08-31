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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Metadata-driven {@link SkillVisibilityFilter} that narrows the skill catalogue the
 * LLM sees to the Top-K candidates routed by {@link SkillCandidateSelector} from
 * {@code skill_routing_metadata} (aliases/keywords/tags/priority), with capability
 * coarse recall via {@link CapabilityRouter}.
 *
 * <p>Availability rules (routing must never make skills unreachable):
 * <ul>
 *   <li>Skills with no routing metadata row (e.g. a freshly hot-loaded SKILL.md before
 *       {@code BuiltinSkillRegistrar} runs) are always appended to the visible set, so
 *       the 2026/07/29 hot-loading regression cannot recur. New skills get an active
 *       name-derived draft row from the registrar, so they route immediately.</li>
 *   <li>If the selected candidate set ends up empty, the filter falls back to all
 *       skills: a routing miss costs precision, not availability.</li>
 *   <li>Skills explicitly disabled by an administrator ({@code active=false}) stay
 *       hidden - "no row" and "disabled row" are distinguished via
 *       {@link SkillRoutingMetadataRepository#findAll()}.</li>
 * </ul>
 *
 * <p>2026/07/29-30 history: the filter was briefly a pure pass-through after the
 * embedding-based vector filter was removed (it blocked hot-loading and was dead
 * weight with retrieval disabled). The deterministic metadata routing replaced it
 * on 2026/08/31.
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
        List<SkillRoutingMetadata> allMetadata = routingMetadataRepository.findAll();
        if (allMetadata.isEmpty()) {
            log.warn("No skill routing metadata configured; leaving {} skills visible", all.size());
            return all;
        }
        List<SkillRoutingMetadata> metadata = allMetadata.stream().filter(SkillRoutingMetadata::active).toList();
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
        Map<String, AgentSkill> byName = all.stream()
                .collect(Collectors.toMap(AgentSkill::getName, skill -> skill, (left, right) -> left));
        List<AgentSkill> result = new java.util.ArrayList<>();
        for (String name : selection.skillNames()) {
            AgentSkill skill = byName.get(name);
            if (skill != null) result.add(skill);
        }
        // Skills without a routing metadata row are always visible so that hot-loaded
        // SKILL.md files surface before an administrator configures them. Rows with
        // active=false are "configured and disabled" and stay hidden.
        Set<String> configuredNames = allMetadata.stream()
                .map(SkillRoutingMetadata::skillName).collect(Collectors.toSet());
        for (AgentSkill skill : all) {
            if (!configuredNames.contains(skill.getName())) {
                result.add(skill);
            }
        }
        if (result.isEmpty()) {
            log.warn("No routed Skill candidates; falling back to all {} skills", all.size());
            return all;
        }
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
