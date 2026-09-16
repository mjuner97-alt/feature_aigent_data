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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Metadata-driven {@link SkillVisibilityFilter} that narrows the skill catalogue the
 * LLM sees to the Top-K candidates routed by {@link SkillCandidateSelector} from
 * {@code skill_routing_metadata} (keywords/domain filter), gated by per-user usage.
 *
 * <p>Availability rules (routing must never make skills unreachable):
 * <ul>
 *   <li>Skills with no routing metadata row (e.g. a freshly hot-loaded SKILL.md before
 *       {@code BuiltinSkillRegistrar} runs) are always appended to the visible set, so
 *       the 2026/07/29 hot-loading regression cannot recur. New skills get an active
 *       name-derived draft row from the registrar, so they route immediately.</li>
 *   <li>Domain tags are hard gates. Their values are discovered from routing metadata:
 *       a tagged Skill is visible only when the question contains one of its domains,
 *       unless the user explicitly names that Skill.</li>
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
    private final SkillRoutingMetadataRepository routingMetadataRepository;
    private final SkillCandidateSelector candidateSelector;
    private final boolean enabled;
    private final SkillUsageResolver skillUsageResolver;

    public SkillVectorIndexVisibilityFilter(
            SkillRoutingMetadataRepository routingMetadataRepository,
            SkillCandidateSelector candidateSelector,
            boolean enabled) {
        this(routingMetadataRepository, candidateSelector, enabled, null);
    }

    public SkillVectorIndexVisibilityFilter(
            SkillRoutingMetadataRepository routingMetadataRepository,
            SkillCandidateSelector candidateSelector,
            boolean enabled,
            SkillUsageResolver skillUsageResolver) {
        this.routingMetadataRepository = routingMetadataRepository;
        this.candidateSelector = candidateSelector;
        this.enabled = enabled;
        this.skillUsageResolver = skillUsageResolver;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (!enabled || ctx == null) {
            return all;
        }
        if (skillUsageResolver != null) {
            String userId = ctx.getUserId();
            if (userId == null || userId.isBlank()) return List.of();
            try {
                Set<String> usable = skillUsageResolver.findUsableRetrievalNames(userId);
                Set<String> usableNames = usable == null ? Set.of() : usable;
                all = all.stream()
                        .filter(skill -> isBuiltinWorkspaceSkill(skill)
                                || usableNames.contains(skill.getName()))
                        .toList();
                if (all.isEmpty()) return List.of();
            } catch (RuntimeException e) {
                log.warn("Skill usage resolution failed; hiding all Skills", e);
                return List.of();
            }
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
        List<SkillRoutingMetadata> activeMetadata = allMetadata.stream().filter(SkillRoutingMetadata::active).toList();
        List<SkillRoutingMetadata> metadata = activeMetadata;
        boolean explicitSkill = activeMetadata.stream().anyMatch(m -> contains(question, m.skillName()));
        if (!explicitSkill) {
            Set<String> requestedDomains = matchedDomains(question, activeMetadata);
            if (!requestedDomains.isEmpty()) {
                // 命中领域时只排除其他领域: 保留该领域 + 无领域标签(通用)的 skill
                metadata = activeMetadata.stream().filter(m -> !hasAnyDomain(m.domainTags())
                        || hasAnyDomain(m.domainTags(), requestedDomains)).toList();
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
        // 纯关键词语义: 配置了路由行的 skill 若无关键词命中 (且未被显式点名) 则不可见,
        // 返回空集合, LLM 直接走工具; 不再做 domain-gated 兜底。
        log.debug("Skill candidate selection: all={}, selected={}, explicit={}, fallback={}",
                all.size(), result.size(), selection.explicitNameMatched(), selection.fallbackExpanded());
        return result;
    }

    /**
     * Builtin skills are shipped under workspace/skills and are global capabilities, not rows in
     * the per-user skill_manage permission table. User-created skills use the user_generated
     * source and remain subject to the resolver above.
     */
    private static boolean isBuiltinWorkspaceSkill(AgentSkill skill) {
        if (skill == null || skill.getSource() == null) {
            return false;
        }
        String source = skill.getSource();
        return "workspace".equals(source)
                || "workspace-namespaced".equals(source)
                || source.startsWith("filesystem-");
    }

    private static boolean contains(String question, String value) {
        if (question == null || value == null) return false;
        return question.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT).trim());
    }

    private static boolean hasAnyDomain(List<String> tags, Set<String> domains) {
        return tags != null && tags.stream().filter(java.util.Objects::nonNull)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .anyMatch(domains::contains);
    }

    private static boolean hasAnyDomain(List<String> tags) {
        return tags != null && tags.stream().anyMatch(tag -> tag != null && !tag.trim().isEmpty());
    }

    private static Set<String> matchedDomains(String question, List<SkillRoutingMetadata> metadata) {
        return metadata.stream().flatMap(m -> m.domainTags() == null ? java.util.stream.Stream.empty()
                        : m.domainTags().stream())
                .filter(java.util.Objects::nonNull).map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isEmpty() && contains(question, tag)).collect(Collectors.toSet());
    }

}
