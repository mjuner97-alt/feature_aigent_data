package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCandidateSelectorTest {

    private final SkillCandidateSelector selector = new SkillCandidateSelector(5, 10);

    @Test
    void explicitSkillNameIsAlwaysFirstCandidate() {
        SkillRoutingMetadata q21 = metadata("q2_1_by_dept_version_metrics", List.of("达标率"));
        SkillRoutingMetadata trace = metadata("trace_recent_metrics", List.of("追踪", "达标率", "数据", "报告", "审计", "质量"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(trace.skillName()), skill(q21.skillName())),
                List.of(q21, trace),
                "请使用 q2_1_by_dept_version_metrics 查询杭州开发二部");

        assertEquals("q2_1_by_dept_version_metrics", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
        assertTrue(selection.confident());
    }

    @Test
    void noKeywordOrNameHitReturnsEmptySelection() {
        List<SkillRoutingMetadata> metadata = List.of(
                metadata("q2", List.of("达标率")),
                metadata("trace", List.of("追踪")),
                metadata("report", List.of("报告")));

        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "完全未知的问题");

        assertTrue(selection.skillNames().isEmpty());
        assertFalse(selection.explicitNameMatched());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void keywordHitReturnsSkill() {
        SkillRoutingMetadata q21 = metadata("q2", List.of("达标率"));
        SkillRoutingMetadata trace = metadata("trace", List.of("追踪"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(trace.skillName()), skill(q21.skillName())), List.of(q21, trace), "Q2-1 达标率是多少");

        assertEquals(List.of("q2"), selection.skillNames());
    }

    @Test
    void moreKeywordHitsRankFirst() {
        SkillRoutingMetadata twoHits = metadata("two_hits", List.of("达标率", "版本"));
        SkillRoutingMetadata oneHit = metadata("one_hit", List.of("达标率"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(oneHit.skillName()), skill(twoHits.skillName())),
                List.of(twoHits, oneHit), "按版本统计达标率");

        assertEquals("two_hits", selection.skillNames().get(0));
        assertEquals("one_hit", selection.skillNames().get(1));
    }

    @Test
    void topicTagsDoNotAffectMatching() {
        SkillRoutingMetadata qiGate = metadataWithTopic("qi_gate", List.of(), List.of("QI卡口"));
        SkillRoutingMetadata unrelated = metadataWithTopic("unrelated", List.of(), List.of("质量度量"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(unrelated.skillName()), skill(qiGate.skillName())),
                List.of(qiGate, unrelated), "查询 QI卡口 的质量情况");

        assertTrue(selection.skillNames().isEmpty());
    }

    @Test
    void domainRequestKeepsDomainAndGenericSkillsExcludingOtherDomains() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata otherDomain = metadataWithDomain("other_domain", List.of("达标率"), List.of("质量管理"));
        SkillRoutingMetadata generic = metadataWithDomain("generic", List.of("达标率"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(otherDomain.skillName()), skill(generic.skillName())),
                List.of(meeting, otherDomain, generic), "生成例会材料，包含达标率");

        assertTrue(selection.skillNames().contains("meeting"));
        assertTrue(selection.skillNames().contains("generic"));
        assertTrue(selection.skillNames().stream().noneMatch("other_domain"::equals));
    }

    @Test
    void domainMatchedSkillRanksAboveGenericSkills() {
        SkillRoutingMetadata domain = metadataWithDomain("domain_skill", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata generic = metadataWithDomain("generic", List.of("达标率", "数据", "统计"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(generic.skillName()), skill(domain.skillName())),
                List.of(domain, generic), "生成例会材料，包含达标率");

        assertEquals(2, selection.skillNames().size());
        assertEquals("domain_skill", selection.skillNames().get(0));
        assertEquals("generic", selection.skillNames().get(1));
    }

    @Test
    void noDomainHitIncludesDomainTaggedSkillsOnKeywordMatch() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata ordinary = metadataWithDomain("ordinary", List.of("达标率"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(ordinary.skillName())),
                List.of(meeting, ordinary), "查询杭州开发二部达标率");

        assertEquals(2, selection.skillNames().size());
        assertTrue(selection.skillNames().contains("meeting"));
        assertTrue(selection.skillNames().contains("ordinary"));
    }

    @Test
    void explicitSkillNameOverridesDomainGate() {
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata ordinary = metadataWithDomain("ordinary", List.of("达标率"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(ordinary.skillName())),
                List.of(meeting, ordinary), "请使用 meeting 查询达标率");

        assertEquals("meeting", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void multipleDomainTagsUseOrSemantics() {
        SkillRoutingMetadata multiDomain = metadataWithDomain(
                "multi_domain", List.of("数据"), List.of("质量管理", "QI卡口"));
        SkillRoutingMetadata qualityOnly = metadataWithDomain(
                "quality_only", List.of("数据"), List.of("质量管理"));
        SkillRoutingMetadata qiOnly = metadataWithDomain(
                "qi_only", List.of("数据"), List.of("QI卡口"));

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
    void domainTagsAloneWithoutKeywordHitStillEmpty() {
        SkillRoutingMetadata multiDomain = metadataWithDomain(
                "multi_domain", List.of(), List.of("质量管理", "QI卡口"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(multiDomain.skillName())), List.of(multiDomain), "查询质量管理相关数据");

        assertTrue(selection.skillNames().isEmpty());
    }

    @Test
    void inactiveOrUnavailableSkillsExcluded() {
        SkillRoutingMetadata inactive = new SkillRoutingMetadata(
                "inactive", "inactive 摘要", List.of("达标率"), List.of(), List.of(), "", false, null);
        SkillRoutingMetadata unavailable = metadata("unavailable", List.of("达标率"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(inactive.skillName())), List.of(inactive, unavailable), "查询达标率");

        assertTrue(selection.skillNames().isEmpty());
    }

    private static SkillRoutingMetadata metadata(String name, List<String> keywords) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), "", true, null);
    }

    private static SkillRoutingMetadata metadataWithDomain(String name, List<String> keywords,
                                                           List<String> domainTags) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, domainTags, List.of(), "", true, null);
    }

    private static SkillRoutingMetadata metadataWithTopic(String name, List<String> keywords,
                                                          List<String> topicTags) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), topicTags, "", true, null);
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name + " description").skillContent("rules").build();
    }
}
