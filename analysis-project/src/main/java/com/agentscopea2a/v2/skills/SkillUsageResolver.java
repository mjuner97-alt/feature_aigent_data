package com.agentscopea2a.v2.skills;

import java.util.Set;

/** Resolves the Skill names that the current user may use at runtime. */
public interface SkillUsageResolver {
    Set<String> findUsableRetrievalNames(String userId);

    boolean canUseManagedSkill(Long skillId, String userId);
}
