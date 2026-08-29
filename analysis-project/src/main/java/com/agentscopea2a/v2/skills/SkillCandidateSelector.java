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
    private static final int TERM_SCORE = 25;
    private static final String MEETING_MATERIAL_DOMAIN = "例会材料";

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
        boolean meetingMaterialRequest = normalizedQuestion.contains(MEETING_MATERIAL_DOMAIN);
        List<ScoredSkill> scored = new ArrayList<>();
        for (SkillRoutingMetadata entry : metadata) {
            if (!entry.active() || !availableNames.contains(entry.skillName())) {
                continue;
            }
            int score = entry.priority();
            boolean explicit = contains(normalizedQuestion, entry.skillName()) || matchesAny(normalizedQuestion, entry.aliases());
            boolean meetingMaterialSkill = hasExactTag(entry.domainTags(), MEETING_MATERIAL_DOMAIN);
            if (!explicit && meetingMaterialRequest != meetingMaterialSkill) {
                continue;
            }
            if (explicit) {
                score += EXPLICIT_NAME_SCORE;
            } else {
                score += matchedTerms(normalizedQuestion, entry.keywords()) * TERM_SCORE;
                score += matchedTerms(normalizedQuestion, entry.metricTags()) * TERM_SCORE;
                score += matchedTerms(normalizedQuestion, entry.domainTags()) * TERM_SCORE;
                score += matchedTerms(normalizedQuestion, entry.dataSourceTags()) * TERM_SCORE;
            }
            scored.add(new ScoredSkill(entry.skillName(), score, explicit));
        }
        scored.sort(Comparator.comparingInt(ScoredSkill::score).reversed().thenComparing(ScoredSkill::name));
        if (scored.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }

        boolean explicit = scored.get(0).explicit();
        boolean confident = explicit || confidence(scored) >= minConfidence;
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

    private static boolean matchesAny(String question, List<String> aliases) {
        return aliases != null && aliases.stream().anyMatch(alias -> contains(question, alias));
    }

    private static boolean hasExactTag(List<String> tags, String expected) {
        String normalizedExpected = normalize(expected);
        return tags != null && tags.stream().map(SkillCandidateSelector::normalize)
                .anyMatch(normalizedExpected::equals);
    }

    private static boolean contains(String question, String value) {
        String normalizedValue = normalize(value);
        return !normalizedValue.isEmpty() && question.contains(normalizedValue);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static double confidence(List<ScoredSkill> scored) {
        return Math.min(1d, Math.max(0d, scored.get(0).score() / 100d));
    }

    private static double scoreGap(List<ScoredSkill> scored) {
        int first = scored.get(0).score();
        if (first <= 0) return 0d;
        return (first - scored.get(1).score()) / (double) first;
    }

    private record ScoredSkill(String name, int score, boolean explicit) {}
}
