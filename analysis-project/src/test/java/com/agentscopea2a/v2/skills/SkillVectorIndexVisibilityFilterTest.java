package com.agentscopea2a.v2.skills;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillVectorIndexVisibilityFilterTest {

    @Test
    void emptyDomainGatedSelectionDoesNotRestoreAllSkills() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of(), List.of(), List.of("达标率"),
                List.of("例会材料"), List.of(), 0, true, null);
        when(repository.findActive()).thenReturn(List.of(meeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "查询达标率");

        List<AgentSkill> result = filter.filter(List.of(skill("meeting")), context);

        assertTrue(result.isEmpty());
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name).skillContent("rules").build();
    }
}
