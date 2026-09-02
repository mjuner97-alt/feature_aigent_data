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
    void requestWithoutRegisteredDomainExcludesDomainTaggedSkills() {
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

        assertTrue(result.isEmpty());
    }

    @Test
    void actualRegisteredDomainValueGatesSkillVisibility() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata weeklyMeeting = new SkillRoutingMetadata(
                "demo_company_quality", "quality", List.of(), List.of("Q2-1"), List.of("检出率"),
                List.of("杭研周例会"), List.of(), 0, true, null);
        SkillRoutingMetadata ordinary = new SkillRoutingMetadata(
                "ordinary_quality", "quality", List.of(), List.of("Q2-1"), List.of("检出率"),
                List.of(), List.of(), 0, true, null);
        when(repository.findAll()).thenReturn(List.of(weeklyMeeting, ordinary));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);

        RuntimeContext ordinaryContext = RuntimeContext.empty();
        ordinaryContext.put("lastQuestion", "杭州开发一部七月 Q2-1 检出率");
        List<AgentSkill> ordinaryResult = filter.filter(
                List.of(skill("demo_company_quality"), skill("ordinary_quality")), ordinaryContext);
        assertEquals(List.of("ordinary_quality"), names(ordinaryResult));

        RuntimeContext domainContext = RuntimeContext.empty();
        domainContext.put("lastQuestion", "杭研周例会，杭州开发一部七月 Q2-1 检出率");
        List<AgentSkill> domainResult = filter.filter(
                List.of(skill("demo_company_quality"), skill("ordinary_quality")), domainContext);
        assertEquals(List.of("demo_company_quality"), names(domainResult));
    }

    @Test
    void explicitSkillNameOverridesRegisteredDomainGate() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata weeklyMeeting = new SkillRoutingMetadata(
                "demo_company_quality", "quality", List.of(), List.of(), List.of(),
                List.of("杭研周例会"), List.of(), 0, true, null);
        when(repository.findAll()).thenReturn(List.of(weeklyMeeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10, 0.65d, 0.10d), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "请使用 demo_company_quality 查询指标");

        List<AgentSkill> result = filter.filter(List.of(skill("demo_company_quality")), context);

        assertEquals(List.of("demo_company_quality"), names(result));
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

    private static List<String> names(List<AgentSkill> skills) {
        return skills.stream().map(AgentSkill::getName).toList();
    }
}
