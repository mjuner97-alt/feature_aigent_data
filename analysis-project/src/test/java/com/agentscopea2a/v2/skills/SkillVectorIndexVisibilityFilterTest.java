package com.agentscopea2a.v2.skills;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillVectorIndexVisibilityFilterTest {

    @Test
    void requestWithoutKeywordHitHidesConfiguredSkills() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of("达标率"), List.of("例会材料"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "随便聊聊");

        List<AgentSkill> result = filter.filter(List.of(skill("meeting")), context);

        assertTrue(result.isEmpty());
    }

    @Test
    void requestWithoutDomainKeepsDomainTaggedSkillsOnKeywordHit() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of("达标率"), List.of("例会材料"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "查询达标率");

        List<AgentSkill> result = filter.filter(List.of(skill("meeting")), context);

        assertEquals(List.of("meeting"), names(result));
    }

    @Test
    void domainQuestionKeepsDomainAndGenericSkillsExcludingOtherDomains() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata weeklyMeeting = new SkillRoutingMetadata(
                "demo_company_quality", "quality", List.of("Q2-1"), List.of("杭研周例会"), List.of(),
                "", true, null);
        SkillRoutingMetadata ordinary = new SkillRoutingMetadata(
                "ordinary_quality", "quality", List.of("Q2-1"), List.of(), List.of(),
                "", true, null);
        SkillRoutingMetadata otherDomain = new SkillRoutingMetadata(
                "other_domain_quality", "quality", List.of("Q2-1"), List.of("质量管理"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(weeklyMeeting, ordinary, otherDomain));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);

        RuntimeContext noDomainContext = RuntimeContext.empty();
        noDomainContext.put("lastQuestion", "杭州开发一部七月 Q2-1 检出率");
        List<AgentSkill> noDomainResult = filter.filter(
                List.of(skill("demo_company_quality"), skill("ordinary_quality"), skill("other_domain_quality")),
                noDomainContext);
        assertEquals(3, noDomainResult.size());

        RuntimeContext domainContext = RuntimeContext.empty();
        domainContext.put("lastQuestion", "杭研周例会，杭州开发一部七月 Q2-1 检出率");
        List<AgentSkill> domainResult = filter.filter(
                List.of(skill("demo_company_quality"), skill("ordinary_quality"), skill("other_domain_quality")),
                domainContext);
        assertTrue(names(domainResult).contains("demo_company_quality"));
        assertTrue(names(domainResult).contains("ordinary_quality"));
        assertFalse(names(domainResult).contains("other_domain_quality"));
    }

    @Test
    void explicitSkillNameOverridesRegisteredDomainGate() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata weeklyMeeting = new SkillRoutingMetadata(
                "demo_company_quality", "quality", List.of(), List.of("杭研周例会"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(weeklyMeeting));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "请使用 demo_company_quality 查询指标");

        List<AgentSkill> result = filter.filter(List.of(skill("demo_company_quality")), context);

        assertEquals(List.of("demo_company_quality"), names(result));
    }

    @Test
    void unconfiguredSkillIsAlwaysVisible() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata routed = new SkillRoutingMetadata(
                "routed", "routed", List.of("达标率"), List.of(), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(routed));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);
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
                "disabled", "disabled", List.of(), List.of(), List.of(),
                "", false, null);
        SkillRoutingMetadata routed = new SkillRoutingMetadata(
                "routed", "routed", List.of("达标率"), List.of(), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(disabled, routed));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true);
        RuntimeContext context = RuntimeContext.empty();
        context.put("lastQuestion", "查询达标率");

        List<AgentSkill> result = filter.filter(
                List.of(skill("disabled"), skill("routed")), context);

        assertTrue(result.stream().anyMatch(s -> s.getName().equals("routed")));
        assertFalse(result.stream().anyMatch(s -> s.getName().equals("disabled")));
    }

    @Test
    void usageResolverIsAFailClosedFinalVisibilityGate() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        SkillUsageResolver resolver = mock(SkillUsageResolver.class);
        when(resolver.findUsableRetrievalNames("u1")).thenReturn(Set.of("allowed"));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true, resolver);
        RuntimeContext context = RuntimeContext.builder().userId("u1").build();
        context.put("lastQuestion", "查询");

        assertEquals(List.of("allowed"), names(filter.filter(
                List.of(skill("allowed"), skill("forbidden")), context)));
    }

    @Test
    void builtinWorkspaceSkillsRemainVisibleWhenUserHasNoManagedSkills() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        SkillUsageResolver resolver = mock(SkillUsageResolver.class);
        when(resolver.findUsableRetrievalNames("u1")).thenReturn(Set.of());
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true, resolver);
        RuntimeContext context = RuntimeContext.builder().userId("u1").build();
        context.put("lastQuestion", "查询");

        List<AgentSkill> result = filter.filter(
                List.of(skill("builtin", "workspace"), skill("managed", "user_generated")), context);

        assertEquals(List.of("builtin"), names(result));
    }

    // ==================== 追问轮快照 skill 并入 (followup 方案 §3.2) ====================

    private static DirectSkillExecutor executorReturning(DirectSkillExecutor.LastDirectExec snapshot) {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        when(executor.lastExec(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(snapshot);
        return executor;
    }

    @Test
    void snapshotSkillMergedIntoEmptyCandidateSet() {
        // 裸追问 (无关键词命中) 候选集为空, 快照 skill 并入, LLM 有 load_skill 可走
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of("达标率"), List.of("例会材料"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        DirectSkillExecutor lastExecSource = executorReturning(
                new DirectSkillExecutor.LastDirectExec("查询达标率", "meeting", "script_a",
                        java.util.Map.of("dept", "杭州开发一部"), null, System.currentTimeMillis()));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true, null, null, lastExecSource);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
        context.put("lastQuestion", "随便聊聊");

        assertEquals(List.of("meeting"), names(filter.filter(List.of(skill("meeting")), context)));
    }

    @Test
    void snapshotSkillAlreadyInCandidatesNotDuplicated() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of("达标率"), List.of("例会材料"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        DirectSkillExecutor lastExecSource = executorReturning(
                new DirectSkillExecutor.LastDirectExec("查询达标率", "meeting", "script_a",
                        java.util.Map.of(), null, System.currentTimeMillis()));
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true, null, null, lastExecSource);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
        context.put("lastQuestion", "查询达标率");

        assertEquals(List.of("meeting"), names(filter.filter(List.of(skill("meeting")), context)));
    }

    @Test
    void noSnapshotLeavesCatalogUnchanged() {
        SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
        SkillRoutingMetadata meeting = new SkillRoutingMetadata(
                "meeting", "meeting", List.of("达标率"), List.of("例会材料"), List.of(),
                "", true, null);
        when(repository.findAll()).thenReturn(List.of(meeting));
        DirectSkillExecutor lastExecSource = executorReturning(null);
        SkillVectorIndexVisibilityFilter filter = new SkillVectorIndexVisibilityFilter(
                repository, new SkillCandidateSelector(5, 10), true, null, null, lastExecSource);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
        context.put("lastQuestion", "随便聊聊");

        assertTrue(filter.filter(List.of(skill("meeting")), context).isEmpty());
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name).skillContent("rules").build();
    }

    private static AgentSkill skill(String name, String source) {
        return AgentSkill.builder().name(name).description(name).skillContent("rules")
                .source(source).build();
    }

    private static List<String> names(List<AgentSkill> skills) {
        return skills.stream().map(AgentSkill::getName).toList();
    }
}
