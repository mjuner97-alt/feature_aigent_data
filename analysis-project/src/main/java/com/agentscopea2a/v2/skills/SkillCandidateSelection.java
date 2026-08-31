package com.agentscopea2a.v2.skills;

import java.util.List;

/** Result of selecting model-visible Skill candidates for one user question. */
public record SkillCandidateSelection(
        List<String> skillNames,
        boolean explicitNameMatched,
        boolean confident,
        boolean fallbackExpanded) {

    public SkillCandidateSelection {
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
    }
}
