package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCandidateSelectorTest {

    private final SkillCandidateSelector selector = new SkillCandidateSelector(5, 10, 0.65d, 0.10d);

    @Test
    void explicitSkillNameIsAlwaysFirstCandidate() {
        SkillRoutingMetadata q21 = metadata("q2_1_by_dept_version_metrics", List.of("达标率"), 0);
        SkillRoutingMetadata trace = metadata("trace_recent_metrics", List.of("追踪"), 10);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(trace.skillName()), skill(q21.skillName())),
                List.of(q21, trace),
                "请使用 q2_1_by_dept_version_metrics 查询杭州开发二部");

        assertEquals("q2_1_by_dept_version_metrics", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
        assertTrue(selection.confident());
    }

    @Test
    void lowConfidenceExpandsToTenActualSkillsWithoutGenericEntry() {
        List<SkillRoutingMetadata> metadata = List.of(
                metadata("q2", List.of("达标率"), 10),
                metadata("trace", List.of("追踪"), 9),
                metadata("report", List.of("报告"), 8),
                metadata("data", List.of("数据"), 7),
                metadata("audit", List.of("审计"), 6),
                metadata("quality", List.of("质量"), 5));

        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "完全未知的问题");

        assertEquals(6, selection.skillNames().size());
        assertTrue(selection.fallbackExpanded());
        assertTrue(selection.skillNames().stream().noneMatch(name -> name.contains("generic")));
    }

    @Test
    void keywordMatchRanksRelatedSkillBeforeHigherPriorityUnrelatedSkill() {
        SkillRoutingMetadata q21 = metadata("q2", List.of("达标率", "打分率"), 0);
        SkillRoutingMetadata trace = metadata("trace", List.of("追踪"), 100);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(trace.skillName()), skill(q21.skillName())), List.of(q21, trace), "Q2-1 达标率是多少");

        assertEquals("q2", selection.skillNames().get(0));
    }

    @Test
    void topicMatchRanksRelatedSkillBeforeHigherPriorityUnrelatedSkill() {
        SkillRoutingMetadata qiGate = new SkillRoutingMetadata(
                "qi_gate", "QI gate", List.of(), List.of(), List.of("QI卡口"), List.of(),
                "", 0, true, null);
        SkillRoutingMetadata unrelated = new SkillRoutingMetadata(
                "unrelated", "unrelated", List.of(), List.of(), List.of("质量度量"), List.of(),
                "", 20, true, null);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(unrelated.skillName()), skill(qiGate.skillName())),
                List.of(qiGate, unrelated), "查询 QI卡口 的质量情况");

        assertEquals("qi_gate", selection.skillNames().get(0));
    }

    @Test
    void matchedTopicHardFiltersUnrelatedSkillsAfterDomainGate() {
        SkillRoutingMetadata qiGate = new SkillRoutingMetadata(
                "qi_gate", "QI gate", List.of(), List.of(), List.of("QI卡口"), List.of(),
                "", 0, true, null);
        SkillRoutingMetadata unrelated = new SkillRoutingMetadata(
                "unrelated", "unrelated", List.of(), List.of(), List.of("质量度量"), List.of(),
                "", 100, true, null);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(unrelated.skillName()), skill(qiGate.skillName())),
                List.of(qiGate, unrelated), "查询 QI卡口 的质量情况");

        assertEquals(List.of("qi_gate"), selection.skillNames());
    }

    @Test
    void matchedMetricHardFiltersUnrelatedSkillsAfterTopicGate() {
        SkillRoutingMetadata q21 = new SkillRoutingMetadata(
                "q2_1", "q2_1", List.of(), List.of(), List.of("QI卡口"), List.of("Q2-1"),
                "", 0, true, null);
        SkillRoutingMetadata q11 = new SkillRoutingMetadata(
                "q1_1", "q1_1", List.of(), List.of(), List.of("QI卡口"), List.of("Q1-1"),
                "", 100, true, null);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(q11.skillName()), skill(q21.skillName())),
                List.of(q21, q11), "查询 QI卡口 的 Q2-1 结果");

        assertEquals(List.of("q2_1"), selection.skillNames());
    }

    @Test
    void meetingMaterialRequestOnlyUsesMeetingMaterialDomain() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"), 0);
        SkillRoutingMetadata ordinary = metadataWithDomain("ordinary", List.of("达标率"), List.of(), 100);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(ordinary.skillName())),
                List.of(meeting, ordinary), "生成例会材料，包含达标率");

        assertEquals(List.of("meeting"), selection.skillNames());
    }

    @Test
    void nonMeetingRequestExcludesMeetingMaterialDomain() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"), 100);
        SkillRoutingMetadata ordinary = metadataWithDomain("ordinary", List.of("达标率"), List.of(), 0);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(ordinary.skillName())),
                List.of(meeting, ordinary), "查询杭州开发二部达标率");

        assertEquals(List.of("ordinary"), selection.skillNames());
    }

    @Test
    void explicitSkillNameOverridesMeetingMaterialDomainGate() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"), 0);
        SkillRoutingMetadata ordinary = metadataWithDomain("ordinary", List.of("达标率"), List.of(), 0);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(ordinary.skillName())),
                List.of(meeting, ordinary), "请使用 meeting 查询达标率");

        assertEquals("meeting", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void arbitraryRegisteredDomainIsNotVisibleUnlessQuestionContainsIt() {
        SkillRoutingMetadata weeklyMeeting = metadataWithDomain(
                "demo_company_quality", List.of("检出率"), List.of("杭研周例会"), 100);
        SkillRoutingMetadata ordinary = metadataWithDomain(
                "ordinary", List.of("检出率"), List.of(), 0);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(weeklyMeeting.skillName()), skill(ordinary.skillName())),
                List.of(weeklyMeeting, ordinary), "杭州开发一部七月 Q2-1 检出率");

        assertEquals(List.of("ordinary"), selection.skillNames());
    }

    @Test
    void multipleDomainTagsUseOrSemantics() {
        SkillRoutingMetadata multiDomain = metadataWithDomain(
                "multi_domain", List.of(), List.of("质量管理", "QI卡口"), 0);
        SkillRoutingMetadata qualityOnly = metadataWithDomain(
                "quality_only", List.of(), List.of("质量管理"), 0);
        SkillRoutingMetadata qiOnly = metadataWithDomain(
                "qi_only", List.of(), List.of("QI卡口"), 0);

        List<AgentSkill> skills = List.of(
                skill(multiDomain.skillName()), skill(qualityOnly.skillName()), skill(qiOnly.skillName()));
        List<SkillRoutingMetadata> metadata = List.of(multiDomain, qualityOnly, qiOnly);

        SkillCandidateSelection qualitySelection = selector.select(skills, metadata, "查询质量管理相关数据");
        SkillCandidateSelection qiSelection = selector.select(skills, metadata, "查询QI卡口相关数据");

        assertTrue(qualitySelection.skillNames().contains("multi_domain"));
        assertTrue(qiSelection.skillNames().contains("multi_domain"));
        assertTrue(qualitySelection.skillNames().contains("quality_only"));
        assertTrue(qiSelection.skillNames().contains("qi_only"));
        assertTrue(qualitySelection.skillNames().stream().noneMatch("qi_only"::equals));
        assertTrue(qiSelection.skillNames().stream().noneMatch("quality_only"::equals));
    }

    @Test
    void multipleDomainTagsRemainHardGatedWhenQuestionMatchesNeither() {
        SkillRoutingMetadata multiDomain = metadataWithDomain(
                "multi_domain", List.of(), List.of("质量管理", "QI卡口"), 0);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(multiDomain.skillName())), List.of(multiDomain), "查询普通数据");

        assertTrue(selection.skillNames().isEmpty());
    }

    private static SkillRoutingMetadata metadata(String name, List<String> keywords, int priority) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), List.of(),
                "", priority, true, null);
    }

    private static SkillRoutingMetadata metadataWithDomain(String name, List<String> metricTags,
                                                           List<String> domainTags, int priority) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of(), domainTags, List.of(), metricTags,
                "", priority, true, null);
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name + " description").skillContent("rules").build();
    }
}
