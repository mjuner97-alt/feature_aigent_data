package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseSkillUsageResolverTest {

    @Test
    void returnsOnlyRuntimeUsableNamesFromExistingMapperPolicy() {
        SkillMapper mapper = mock(SkillMapper.class);
        when(mapper.selectActiveRetrievalNamesByUser("alice"))
                .thenReturn(List.of("personal-owned", "department-published", "company-published"));

        SkillUsageResolver resolver = new DatabaseSkillUsageResolver(mapper);

        assertEquals(List.of("personal-owned", "department-published", "company-published"),
                resolver.findUsableRetrievalNames("alice").stream().toList());
    }

    @Test
    void emptyUserHasNoUsableSkills() {
        SkillMapper mapper = mock(SkillMapper.class);
        SkillUsageResolver resolver = new DatabaseSkillUsageResolver(mapper);

        assertEquals(List.of(), resolver.findUsableRetrievalNames(" ").stream().toList());
    }

    @Test
    void personalSkillCanOnlyBeUsedByOwner() {
        SkillMapper mapper = mock(SkillMapper.class);
        when(mapper.selectActiveRetrievalNamesByUser("bob")).thenReturn(List.of("department-published"));

        SkillUsageResolver resolver = new DatabaseSkillUsageResolver(mapper);

        assertEquals(List.of("department-published"), resolver.findUsableRetrievalNames("bob").stream().toList());
    }

    @Test
    void managedSkillAvailabilityUsesExistingExecutionCheck() {
        SkillMapper mapper = mock(SkillMapper.class);
        when(mapper.selectSkillAvailableForUser(7L, "alice")).thenReturn(true);

        SkillUsageResolver resolver = new DatabaseSkillUsageResolver(mapper);

        org.junit.jupiter.api.Assertions.assertTrue(resolver.canUseManagedSkill(7L, "alice"));
    }
}
