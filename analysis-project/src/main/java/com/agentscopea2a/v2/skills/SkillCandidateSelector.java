package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic, keyword-driven selector for model-visible Skill candidates.
 *
 * <p>匹配语义: 用户问题显式点名 skill 名称, 或命中其任一 keyword, 才进入候选;
 * 全部未命中返回空集合 (LLM 直接走工具)。领域标签 {@code domainTags} 仅做互斥域
 * 过滤 (问题命中领域时排除其他领域的 skill, 通用 skill 始终保留) 与排序加成
 * (领域命中的 skill 排在通用 skill 之前); {@code topicTags} 仅内部管理归类,
 * 不参与匹配; 指标标签与 priority 已随评分体系简化移除。
 */
public class SkillCandidateSelector {

    private static final int EXPLICIT_NAME_SCORE = 10_000;
    /** 高于关键词命中分上限 (1500), 保证领域命中的 skill 排在所有通用 skill 之前。 */
    private static final int DOMAIN_MATCH_SCORE = 3_000;
    private static final int KEYWORD_SCORE_PER_HIT = 500;
    private static final int KEYWORD_SCORE_CAP = 1_500;

    private final int maxVisibleSkills;
    private final int fallbackVisibleSkills;

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills) {
        this.maxVisibleSkills = Math.max(1, maxVisibleSkills);
        this.fallbackVisibleSkills = Math.max(this.maxVisibleSkills, fallbackVisibleSkills);
    }

    public SkillCandidateSelection select(List<AgentSkill> allSkills,
                                           List<SkillRoutingMetadata> metadata,
                                           String question) {
        if (allSkills == null || allSkills.isEmpty() || metadata == null || metadata.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }
        Set<String> availableNames = allSkills.stream().map(AgentSkill::getName).collect(Collectors.toSet());
        String normalizedQuestion = normalize(question);
        Set<String> requestedDomains = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::domainTags);

        List<ScoredSkill> scored = new ArrayList<>();
        boolean explicitAny = false;
        for (SkillRoutingMetadata entry : metadata) {
            if (!entry.active() || !availableNames.contains(entry.skillName())) {
                continue;
            }
            boolean explicit = contains(normalizedQuestion, entry.skillName());
            if (explicit) {
                explicitAny = true;
            }
            int keywordHits = explicit ? 0 : matchedTerms(normalizedQuestion, entry.keywords());
            if (!explicit && keywordHits == 0) {
                continue;
            }
            if (!explicit && !requestedDomains.isEmpty()
                    && hasDomainTags(entry.domainTags())
                    && !hasAnyDomain(entry.domainTags(), requestedDomains)) {
                continue;
            }
            int domainScore = requestedDomains.isEmpty() || !hasDomainTags(entry.domainTags())
                    ? 0 : DOMAIN_MATCH_SCORE;
            int keywordScore = Math.min(KEYWORD_SCORE_CAP, KEYWORD_SCORE_PER_HIT * keywordHits);
            scored.add(new ScoredSkill(entry.skillName(),
                    (explicit ? EXPLICIT_NAME_SCORE : 0) + domainScore + keywordScore,
                    keywordHits, domainScore > 0, explicit));
        }
        scored.sort(Comparator.comparing(ScoredSkill::explicit).reversed()
                .thenComparing(Comparator.comparingInt(ScoredSkill::score).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::keywordHits).reversed())
                .thenComparing(ScoredSkill::name));
        if (scored.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }
        int limit = fallbackVisibleSkills;
        List<String> selected = scored.stream().limit(limit).map(ScoredSkill::name).toList();
        return new SkillCandidateSelection(selected, explicitAny, true, scored.size() > limit);
    }

    private static int matchedTerms(String question, List<String> terms) {
        if (terms == null) return 0;
        return (int) terms.stream().filter(term -> contains(question, term)).count();
    }

    private static boolean hasDomainTags(List<String> tags) {
        return tags != null && tags.stream().anyMatch(tag -> tag != null && !tag.trim().isEmpty());
    }

    private static boolean hasAnyDomain(List<String> tags, Set<String> domains) {
        return tags != null && tags.stream().filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).anyMatch(domains::contains);
    }

    private static boolean contains(String question, String value) {
        String normalizedValue = normalize(value);
        return !normalizedValue.isEmpty() && question.contains(normalizedValue);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static Set<String> matchedTags(String question, List<SkillRoutingMetadata> metadata,
                                           java.util.function.Function<SkillRoutingMetadata, List<String>> tags) {
        return metadata.stream().flatMap(entry -> tags.apply(entry).stream()).filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).filter(tag -> !tag.isEmpty() && question.contains(tag))
                .collect(Collectors.toSet());
    }

    private record ScoredSkill(String name, int score, int keywordHits, boolean domainMatched, boolean explicit) {}
}
