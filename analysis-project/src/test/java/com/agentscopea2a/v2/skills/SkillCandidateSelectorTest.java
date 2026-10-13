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

    @Test
    void bothLevelsHitRanksAboveSingleLevelHit() {
        SkillRoutingMetadata deep = metadataWithLevels("deep", List.of(), List.of("问题"), List.of("未闭环"), List.of());
        SkillRoutingMetadata shallow = metadataWithLevels("shallow", List.of(), List.of("问题"), List.of(), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(shallow.skillName()), skill(deep.skillName())),
                List.of(deep, shallow), "未闭环问题还有多少");

        assertEquals(2, selection.skillNames().size());
        assertEquals("deep", selection.skillNames().get(0));
        assertEquals("shallow", selection.skillNames().get(1));
    }

    @Test
    void upperLayerHitKeepsSkillVisibleWhenConfiguredDeeperLayerMisses() {
        SkillRoutingMetadata closedLoop = metadataWithLevels("closed_loop", List.of(), List.of("问题"), List.of("已闭环"), List.of());
        SkillRoutingMetadata openLoop = metadataWithLevels("open_loop", List.of(), List.of("问题"), List.of("未闭环"), List.of());

        // 问题命中 open_loop 的 L1 (问题) 但未命中其 L2 (未闭环) → L1 保底存活, 排在全链命中的 closed_loop 之后
        SkillCandidateSelection selection = selector.select(
                List.of(skill(closedLoop.skillName()), skill(openLoop.skillName())),
                List.of(closedLoop, openLoop), "已闭环问题统计");

        assertEquals(List.of("closed_loop", "open_loop"), selection.skillNames());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void deepestLayerHitAloneAdmitsSkill() {
        SkillRoutingMetadata codeQuality = metadataWithLevels("code_quality", List.of(), List.of("质量"), List.of("代码"), List.of("告警"));
        SkillRoutingMetadata generic = metadata("generic", List.of("指标"));

        // 问题只含 L3 词 "告警", 不含 L1/L2 → 任一层命中即入围, code_quality 靠 L3 (1500 分) 排最前
        SkillCandidateSelection selection = selector.select(
                List.of(skill(codeQuality.skillName()), skill(generic.skillName())),
                List.of(codeQuality, generic), "扫描告警");

        assertEquals(List.of("code_quality"), selection.skillNames());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void upperLayerHitKeepsSkillVisibleEvenWhenUnconfiguredSkillsPadThePool() {
        SkillRoutingMetadata appQuality = metadataWithLevels("app_quality", List.of("质量"), List.of("质量"), List.of("应用"), List.of());
        SkillRoutingMetadata generic = metadata("generic", List.of("质量"));

        // 关键场景: 池子里存在未配置层级的 generic skill 使每层收窄都"成功",
        // app_quality 仅命中 L1 (质量) 也必须保底可见, 不能被 L2 淘汰
        SkillCandidateSelection selection = selector.select(
                List.of(skill(appQuality.skillName()), skill(generic.skillName())),
                List.of(appQuality, generic), "质量情况");

        assertEquals(List.of("app_quality", "generic"), selection.skillNames());
    }

    @Test
    void l1HitAloneAdmitsSkillWhenL2Misses() {
        SkillRoutingMetadata single = metadataWithLevels("single", List.of(), List.of("问题"), List.of("未闭环"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(single.skillName())), List.of(single), "问题总量是多少");

        assertEquals(List.of("single"), selection.skillNames());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void upperLevelHitAloneAdmitsSkillWithoutLeafKeywords() {
        SkillRoutingMetadata levelsOnly = metadataWithLevels("levels_only", List.of(), List.of("问题"), List.of("未闭环"), List.of("严重"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(levelsOnly.skillName())), List.of(levelsOnly), "严重未闭环问题有多少");

        assertEquals(List.of("levels_only"), selection.skillNames());
    }

    @Test
    void regexTermMatchesQuestion() {
        SkillRoutingMetadata regex = metadataWithLevels("regex_skill", List.of("未闭.{0,2}环"), List.of(), List.of(), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(regex.skillName())), List.of(regex), "未闭环 的问题还有多少");

        assertEquals(List.of("regex_skill"), selection.skillNames());
    }

    @Test
    void invalidRegexFallsBackToLiteralContains() {
        SkillRoutingMetadata invalid = metadataWithLevels("invalid_regex", List.of("["), List.of(), List.of(), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(invalid.skillName())), List.of(invalid), "数组[0]取值报错");

        assertEquals(List.of("invalid_regex"), selection.skillNames());
    }

    @Test
    void preciseSelectionTruncatesToMaxVisibleSkills() {
        List<SkillRoutingMetadata> metadata = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            metadata.add(metadataWithLevels("kw_skill_" + i, List.of("达标率"), List.of(), List.of(), List.of()));
        }

        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "查达标率");

        assertEquals(5, selection.skillNames().size());
        assertTrue(selection.fallbackExpanded());
    }

    @Test
    void fallbackSelectionExpandsToFallbackVisibleSkills() {
        List<SkillRoutingMetadata> metadata = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            metadata.add(metadataWithLevels("lv_skill_" + i, List.of(), List.of("问题"), List.of("未闭环"), List.of()));
        }

        // 问题只命中 L1, 全部候选 L2 未命中 → 命中链不完整 (保底存活), 可信度低 → 放宽到 10 (而非 5)
        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "问题总量是多少");

        assertEquals(10, selection.skillNames().size());
        assertTrue(selection.fallbackExpanded());
    }

    private static SkillRoutingMetadata metadata(String name, List<String> keywords) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), "", true, null);
    }

    private static SkillRoutingMetadata metadataWithLevels(String name, List<String> keywords,
                                                           List<String> level1, List<String> level2,
                                                           List<String> level3) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), "", true, null,
                level1, level2, level3);
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
