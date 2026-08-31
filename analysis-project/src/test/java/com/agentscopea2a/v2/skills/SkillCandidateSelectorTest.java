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
        SkillRoutingMetadata q21 = metadata("q2_1_by_dept_version_metrics", List.of("q2_1", "q2-1"),
                List.of("达标率"), 0);
        SkillRoutingMetadata trace = metadata("trace_recent_metrics", List.of("trace"), List.of("追踪"), 10);

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
                metadata("q2", List.of("q2"), List.of("达标率"), 10),
                metadata("trace", List.of("trace"), List.of("追踪"), 9),
                metadata("report", List.of("report"), List.of("报告"), 8),
                metadata("data", List.of("data"), List.of("数据"), 7),
                metadata("audit", List.of("audit"), List.of("审计"), 6),
                metadata("quality", List.of("quality"), List.of("质量"), 5));

        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "完全未知的问题");

        assertEquals(6, selection.skillNames().size());
        assertTrue(selection.fallbackExpanded());
        assertTrue(selection.skillNames().stream().noneMatch(name -> name.contains("generic")));
    }

    @Test
    void keywordMatchRanksRelatedSkillBeforeHigherPriorityUnrelatedSkill() {
        SkillRoutingMetadata q21 = metadata("q2", List.of("q2"), List.of("达标率", "打分率"), 0);
        SkillRoutingMetadata trace = metadata("trace", List.of("trace"), List.of("追踪"), 100);

        SkillCandidateSelection selection = selector.select(
                List.of(skill(trace.skillName()), skill(q21.skillName())), List.of(q21, trace), "Q2-1 达标率是多少");

        assertEquals("q2", selection.skillNames().get(0));
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

    private static SkillRoutingMetadata metadata(String name, List<String> aliases, List<String> keywords, int priority) {
        return new SkillRoutingMetadata(name, name + " 摘要", aliases, keywords, List.of(), List.of(),
                List.of(), priority, true, null);
    }

    private static SkillRoutingMetadata metadataWithDomain(String name, List<String> metricTags,
                                                           List<String> domainTags, int priority) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of(), List.of(), metricTags, domainTags,
                List.of(), priority, true, null);
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name + " description").skillContent("rules").build();
    }
}
