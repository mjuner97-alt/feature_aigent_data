package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Database-backed runtime Skill usage policy. */
@Service
public class DatabaseSkillUsageResolver implements SkillUsageResolver {
    private final SkillMapper skillMapper;

    public DatabaseSkillUsageResolver(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    @Override
    public Set<String> findUsableRetrievalNames(String userId) {
        if (userId == null || userId.isBlank()) {
            return Set.of();
        }
        var names = skillMapper.selectActiveRetrievalNamesByUser(userId);
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        var result = new LinkedHashSet<String>();
        names.stream().filter(n -> n != null && !n.isBlank()).forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    @Override
    public boolean canUseManagedSkill(Long skillId, String userId) {
        return skillId != null && userId != null && !userId.isBlank()
                && skillMapper.selectSkillAvailableForUser(skillId, userId);
    }
}
