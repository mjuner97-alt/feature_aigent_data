package com.agentscopea2a.v2.skills;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillVectorIndexVisibilityFilterTest {

    @Test
    void emptySelectionFallsBackToAllSkills() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of(), List.of(), List.of("达标率"),
                List.of("例会材料"), List.of(), 0, true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "随便聊聊");

        List<AgentSkill> result = filter.filter(List.of(skill("meeting")), context);

        assertEquals(1, result.size());
        assertEquals("meeting", result.get(0).getName());
    }

    @Test
    void unconfiguredSkillIsAlwaysVisible() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata routed = new SkillRoutingMetadata(
                "routed", "routed", List.of(), List.of("达标率"), List.of(), List.of(),
                List.of(), 0, true, null);
        when(repository.findAll()).thenReturn(List.of(routed));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "查询达标率");

        List<AgentSkill> result = filter.filter(
                List.of(skill("routed"), skill("freshly_dropped_skill")), context);

        assertTrue(result.stream().anyMatch(s -> s.getName().equals("freshly_dropped_skill")));
    }

    @Test
    void adminDisabledSkillHiddenWhenOthersVisible() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata disabled = new SkillRoutingMetadata(
                "disabled", "disabled", List.of(), List.of(), List.of(), List.of(),
                List.of(), 0, false, null);
        SkillRoutingMetadata routed = new SkillRoutingMetadata(
                "routed", "routed", List.of(), List.of("达标率"), List.of(), List.of(),
                List.of(), 0, true, null);
        when(repository.findAll()).thenReturn(List.of(disabled, routed));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "查询达标率");

        List<AgentSkill> result = filter.filter(
                List.of(skill("disabled"), skill("routed")), context);

        assertTrue(result.stream().anyMatch(s -> s.getName().equals("routed")));
        assertFalse(result.stream().anyMatch(s -> s.getName().equals("disabled")));
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name).skillContent("rules").build();
    }
}
