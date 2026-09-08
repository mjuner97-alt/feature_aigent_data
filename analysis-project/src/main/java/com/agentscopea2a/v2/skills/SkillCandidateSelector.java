package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministic, metadata-driven selector for model-visible Skill candidates. */
public class SkillCandidateSelector {

    private static final int EXPLICIT_NAME_SCORE = 10_000;
    private final int maxVisibleSkills;
    private final int fallbackVisibleSkills;
    private final double minConfidence;
    private final double minScoreGap;

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills,
                                  double minConfidence, double minScoreGap) {
        this.maxVisibleSkills = Math.max(1, maxVisibleSkills);
        this.fallbackVisibleSkills = Math.max(this.maxVisibleSkills, fallbackVisibleSkills);
        this.minConfidence = minConfidence;
        this.minScoreGap = minScoreGap;
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
        Set<String> requestedTopics = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::topicTags);
        Set<String> requestedMetrics = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::metricTags);
        List<ScoredSkill> scored = new ArrayList<>();
        List<SkillRoutingMetadata> eligible = metadata.stream()
                .filter(SkillRoutingMetadata::active)
                .filter(entry -> availableNames.contains(entry.skillName()))
                .toList();
        List<SkillRoutingMetadata> explicitEntries = eligible.stream()
                .filter(entry -> contains(normalizedQuestion, entry.skillName()))
                .toList();
        List<SkillRoutingMetadata> remaining = eligible.stream()
                .filter(entry -> explicitEntries.stream().noneMatch(named -> named.skillName().equals(entry.skillName())))
                .filter(entry -> requestedDomains.isEmpty()
                        ? !hasAnyDomain(entry.domainTags())
                        : hasAnyDomain(entry.domainTags(), requestedDomains))
                .toList();
        remaining = narrowByTags(remaining, requestedTopics, SkillRoutingMetadata::topicTags);
        remaining = narrowByTags(remaining, requestedMetrics, SkillRoutingMetadata::metricTags);
        for (SkillRoutingMetadata entry : explicitEntries) {
            scored.add(new ScoredSkill(entry.skillName(), EXPLICIT_NAME_SCORE, 0, 0, 0, 0, entry.priority(), true));
        }
        for (SkillRoutingMetadata entry : remaining) {
            int metricScore = Math.min(1500, 500 * matchedTerms(normalizedQuestion, entry.metricTags()));
            int topicScore = Math.min(600, 300 * matchedTerms(normalizedQuestion, entry.topicTags()));
            int keywordScore = Math.min(300, 50 * matchedTerms(normalizedQuestion, entry.keywords()));
            int priorityScore = Math.max(-100, Math.min(100, entry.priority()));
            scored.add(new ScoredSkill(entry.skillName(), metricScore + topicScore + keywordScore + priorityScore,
                    metricScore, topicScore, keywordScore, 0, entry.priority(), false));
        }
        scored.sort(Comparator.comparing(ScoredSkill::explicit).reversed()
                .thenComparing(Comparator.comparingInt(ScoredSkill::score).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::metricScore).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::topicScore).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::keywordScore).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::priority).reversed())
                .thenComparing(ScoredSkill::name));
        if (scored.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }

        boolean explicit = !explicitEntries.isEmpty();
        boolean confident = explicit || evidence(scored.get(0)) >= minConfidence;
        boolean closeScores = scored.size() > 1 && scoreGap(scored) < minScoreGap;
        boolean fallbackExpanded = !confident || closeScores;
        int limit = fallbackExpanded ? fallbackVisibleSkills : maxVisibleSkills;
        List<String> selected = scored.stream().limit(limit).map(ScoredSkill::name).toList();
        return new SkillCandidateSelection(selected, explicit, confident, fallbackExpanded);
    }

    private static int matchedTerms(String question, List<String> terms) {
        if (terms == null) return 0;
        return (int) terms.stream().filter(term -> contains(question, term)).count();
    }

    private static boolean hasAnyDomain(List<String> tags) {
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

    private static List<SkillRoutingMetadata> narrowByTags(List<SkillRoutingMetadata> pool, Set<String> requested,
                                                            java.util.function.Function<SkillRoutingMetadata, List<String>> tags) {
        if (requested.isEmpty()) return pool;
        return pool.stream().filter(entry -> intersects(tags.apply(entry), requested)).toList();
    }

    private static Set<String> matchedTags(String question, List<SkillRoutingMetadata> metadata,
                                           java.util.function.Function<SkillRoutingMetadata, List<String>> tags) {
        return metadata.stream().flatMap(entry -> tags.apply(entry).stream()).filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).filter(tag -> !tag.isEmpty() && question.contains(tag))
                .collect(Collectors.toSet());
    }

    private static boolean intersects(List<String> tags, Set<String> requested) {
        return tags != null && tags.stream().filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).anyMatch(requested::contains);
    }

    private static double evidence(ScoredSkill first) {
        return (first.metricScore() > 0 ? 0.60d : 0d)
                + (first.topicScore() > 0 ? 0.30d : 0d)
                + (first.keywordScore() > 0 ? 0.10d : 0d);
    }

    private static double scoreGap(List<ScoredSkill> scored) {
        int first = scored.get(0).score();
        if (first <= 0) return 0d;
        return (first - scored.get(1).score()) / (double) first;
    }

    private record ScoredSkill(String name, int score, int metricScore, int topicScore, int keywordScore,
                               int capabilityScore, int priority, boolean explicit) {}
}
