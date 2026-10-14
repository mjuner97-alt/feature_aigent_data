package com.agentscopea2a.v2.skills;

import java.util.List;

/** Result of selecting model-visible Skill candidates for one user question. */
public record SkillCandidateSelection(
        List<String> skillNames,
        boolean explicitNameMatched,
        boolean confident,
        boolean fallbackExpanded,
        List<String> matchedKeywords) {

    public SkillCandidateSelection {
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
    }

    /** Legacy 4-arg shape (no matched keywords) for existing call sites. */
    public SkillCandidateSelection(List<String> skillNames, boolean explicitNameMatched,
                                   boolean confident, boolean fallbackExpanded) {
        this(skillNames, explicitNameMatched, confident, fallbackExpanded, List.of());
    }
}
