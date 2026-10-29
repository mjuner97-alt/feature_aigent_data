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
    void domainRequestKeepsOnlyMatchingDomainSkills() {
        // 2026/10/29 收紧 (用户拍板): domain 点名时通用(未配领域标签)skill 一并出局,
        // 即使叶层关键词命中; 其他领域 skill 也出局
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata otherDomain = metadataWithDomain("other_domain", List.of("达标率"), List.of("质量管理"));
        SkillRoutingMetadata generic = metadataWithDomain("generic", List.of("达标率"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(otherDomain.skillName()), skill(generic.skillName())),
                List.of(meeting, otherDomain, generic), "生成例会材料，包含达标率");

        assertEquals(List.of("meeting"), selection.skillNames());
    }

    @Test
    void domainMatchedSkillRanksAboveGenericSkills() {
        // 2026/10/29 收紧: 通用 skill 在 domain 点名时出局, 不再与新语义兼容 (改名保留断言意图)
        SkillRoutingMetadata domain = metadataWithDomain("domain_skill", List.of("达标率"), List.of("例会材料"));
        SkillRoutingMetadata generic = metadataWithDomain("generic", List.of("达标率", "数据", "统计"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(generic.skillName()), skill(domain.skillName())),
                List.of(domain, generic), "生成例会材料，包含达标率");

        assertEquals(List.of("domain_skill"), selection.skillNames());
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
    void domainTagsAloneWithoutKeywordHitAdmitsSkill() {
        // 2026/10/28 拍板⑥ (取代原「domain 词无叶层命中 → 空」语义): 领域点名 = 归属声明,
        // domain 命中即免叶层 keyword 要求入围 (零 keyword 配置同样适用)
        SkillRoutingMetadata multiDomain = metadataWithDomain(
                "multi_domain", List.of(), List.of("质量管理", "QI卡口"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(multiDomain.skillName())), List.of(multiDomain), "查询质量管理相关数据");

        assertEquals(List.of("multi_domain"), selection.skillNames());
        assertFalse(selection.explicitNameMatched());
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
    void narrowingDropsSkillsWithConfiguredButUnhitDeeperLayer() {
        SkillRoutingMetadata closedLoop = metadataWithLevels("closed_loop", List.of(), List.of("问题"), List.of("已闭环"), List.of());
        SkillRoutingMetadata openLoop = metadataWithLevels("open_loop", List.of("问题"), List.of("问题"), List.of("未闭环"), List.of());

        // 预选两者都入池 (closed_loop: L1+L2 命中; open_loop: L1+叶层"问题"命中)。
        // L1 收窄: 双方命中 → 都留; L2 收窄: closed_loop 的 L2 "已闭环" 命中 →
        // open_loop 配置了 L2 却未命中, 即使叶层命中也出局 (2026/10/14 命中即逐层收窄定稿)。
        SkillCandidateSelection selection = selector.select(
                List.of(skill(closedLoop.skillName()), skill(openLoop.skillName())),
                List.of(closedLoop, openLoop), "已闭环问题统计");

        assertEquals(List.of("closed_loop"), selection.skillNames());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void andSatisfiedLevelSkillLeadsAndLegacyLeafSkillCoexists() {
        SkillRoutingMetadata skill1 = metadataWithLevels("skill1", List.of("未闭环"), List.of("问题"), List.of(), List.of());
        SkillRoutingMetadata skill2 = metadataWithLevels("skill2", List.of("未闭环"), List.of("研发问题"), List.of(), List.of());
        SkillRoutingMetadata skill3 = metadataWithLevels("skill3", List.of("未闭环"), List.of("测试问题"), List.of(), List.of());
        SkillRoutingMetadata skill4 = metadataWithLevels("skill4", List.of("未闭环"), List.of(), List.of(), List.of());

        // 2026/10/22 四次定稿: skill1 仅配 L1 且"问题"命中 → AND 满足; skill2/skill3
        // 配置了 L1 却未命中 → 完全出局; skill4 三层全空走存量轨, 叶层"未闭环"命中并存
        // (双轨并存, 不再被拍板⑥挤出), 层级轨排前
        SkillCandidateSelection selection = selector.select(
                List.of(skill(skill1.skillName()), skill(skill2.skillName()),
                        skill(skill3.skillName()), skill(skill4.skillName())),
                List.of(skill1, skill2, skill3, skill4), "三部10月未闭环问题");

        assertEquals(List.of("skill1", "skill4"), selection.skillNames());
        assertFalse(selection.fallbackExpanded());
    }

    @Test
    void explicitNameSurvivesNarrowing() {
        SkillRoutingMetadata named = metadataWithLevels("named_skill", List.of(), List.of("研发问题"), List.of(), List.of());
        SkillRoutingMetadata other = metadataWithLevels("other", List.of("未闭环"), List.of("问题"), List.of(), List.of());

        // named_skill 显式点名入池但 L1"研发问题"未命中; other 靠 L1+叶层入池。
        // L1 收窄由 other 触发, named_skill 配置了 L1 却未命中 —— 显式点名豁免收窄淘汰。
        SkillCandidateSelection selection = selector.select(
                List.of(skill(named.skillName()), skill(other.skillName())),
                List.of(named, other), "请使用 named_skill 查询未闭环问题");

        assertEquals("named_skill", selection.skillNames().get(0));
        assertTrue(selection.skillNames().contains("other"));
    }

    @Test
    void deepLayerWordAloneNoLongerAdmitsSkill() {
        SkillRoutingMetadata codeQuality = metadataWithLevels("code_quality", List.of(), List.of("质量"), List.of("代码"), List.of("告警"));
        SkillRoutingMetadata generic = metadata("generic", List.of("指标"));

        // 2026/10/22 四次定稿: 问题只含 L3 词 "告警", L1/L2 配置了却未命中 → AND 不满足,
        // code_quality 完全出局 (深层词单独不再入围); generic 叶层"指标"也未命中 → 空目录
        SkillCandidateSelection selection = selector.select(
                List.of(skill(codeQuality.skillName()), skill(generic.skillName())),
                List.of(codeQuality, generic), "扫描告警");

        assertTrue(selection.skillNames().isEmpty());
    }

    @Test
    void configuredL3MissDropsSkillAndLegacyLeafSkillSurvives() {
        SkillRoutingMetadata appQuality = metadataWithLevels("app_quality", List.of("质量"), List.of("质量"), List.of("质量"), List.of("应用"));
        SkillRoutingMetadata generic = metadata("generic", List.of("质量"));

        // app_quality 配置了 L3"应用"却未命中 → AND 不满足完全出局;
        // generic 三层全空走存量轨, 叶层"质量"命中照常入围
        SkillCandidateSelection selection = selector.select(
                List.of(skill(appQuality.skillName()), skill(generic.skillName())),
                List.of(appQuality, generic), "质量情况");

        assertEquals(List.of("generic"), selection.skillNames());
    }

    @Test
    void configuredL2MissDropsSkill() {
        SkillRoutingMetadata single = metadataWithLevels("single", List.of(), List.of("问题"), List.of("未闭环"), List.of());

        // 仅 L1"问题"命中, 配置了的 L2"未闭环"未命中 → AND 不满足, 完全出局
        SkillCandidateSelection selection = selector.select(
                List.of(skill(single.skillName())), List.of(single), "问题总量是多少");

        assertTrue(selection.skillNames().isEmpty());
    }

    @Test
    void upperLevelHitAloneAdmitsSkillWithoutLeafKeywords() {
        SkillRoutingMetadata levelsOnly = metadataWithLevels("levels_only", List.of(), List.of("问题"), List.of("未闭环"), List.of("严重"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(levelsOnly.skillName())), List.of(levelsOnly), "严重未闭环问题有多少");

        assertEquals(List.of("levels_only"), selection.skillNames());
    }

    @Test
    void leafOnlySkillCoexistsWhenLevelSkillQualified() {
        SkillRoutingMetadata leafOnly = metadataWithLevels("leaf_only", List.of("质量"), List.of(), List.of(), List.of());
        SkillRoutingMetadata l3Only = metadataWithLevels("l3_only", List.of(), List.of(), List.of(), List.of("质量"));

        // 2026/10/22 四次定稿 (双轨并存, 取代拍板⑥): l3_only 仅配 L3 且命中 → AND 满足;
        // leaf_only 三层全空走存量轨, 叶层"质量"命中并存, 层级轨排前
        SkillCandidateSelection selection = selector.select(
                List.of(skill(l3Only.skillName()), skill(leafOnly.skillName())),
                List.of(leafOnly, l3Only), "质量");

        assertEquals(List.of("l3_only", "leaf_only"), selection.skillNames());
    }

    @Test
    void leafOnlySkillsStillWorkWhenNoLevelHitExists() {
        SkillRoutingMetadata leafOnly = metadataWithLevels("leaf_only", List.of("达标率"), List.of(), List.of(), List.of());
        SkillRoutingMetadata l3Only = metadataWithLevels("l3_only", List.of(), List.of(), List.of(), List.of("未闭环"));

        // 阶段一空 (无任何层级命中) → 阶段二旧行为: 叶层命中入围, 命中数多者前
        SkillCandidateSelection selection = selector.select(
                List.of(skill(l3Only.skillName()), skill(leafOnly.skillName())),
                List.of(leafOnly, l3Only), "查达标率");

        assertEquals(List.of("leaf_only"), selection.skillNames());
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
            // L1"问题"命中但叶层"未闭环"未命中 → 命中链不完整 (partial), 可信度低 → 放宽到 10
            metadata.add(metadataWithLevels("lv_skill_" + i, List.of("未闭环"), List.of("问题"), List.of(), List.of()));
        }

        SkillCandidateSelection selection = selector.select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "问题总量是多少");

        assertEquals(10, selection.skillNames().size());
        assertTrue(selection.fallbackExpanded());
    }

    @Test
    void phaseOneDeeperLayerWinsOverShallowerRegardlessOfName() {
        SkillRoutingMetadata a = metadataWithLevels("a_skill", List.of(), List.of("问题"), List.of(), List.of());
        SkillRoutingMetadata z = metadataWithLevels("z_skill", List.of(), List.of(), List.of("问题"), List.of());

        // 漏斗 (拍板⑧): 同命中 L1 词"问题", z_skill 层深 (L2) > a_skill (L1), name 字典序不干预
        SkillCandidateSelection selection = selector.select(
                List.of(skill(a.skillName()), skill(z.skillName())),
                List.of(a, z), "问题总量是多少");

        assertEquals(List.of("z_skill", "a_skill"), selection.skillNames());
    }

    @Test
    void globalKeywordPriorityRankBreaksSameLayerTie() {
        SkillRoutingMetadata openLoop = metadataWithLevels("open_loop", List.of(), List.of(), List.of("未闭环"), List.of());
        SkillRoutingMetadata longTime = metadataWithLevels("long_time", List.of(), List.of(), List.of("长时效"), List.of());
        // 拍板⑧ 定稿案例: 未闭环/长时效 同层 (L2), 全局词序 长时效(sort 0) 在 未闭环(sort 1) 前
        SkillCandidateSelector prioritized = new SkillCandidateSelector(5, 10, () -> List.of(
                new SkillKeywordPriority(null, 2, "缺陷", "长时效", 0),
                new SkillKeywordPriority(null, 2, "缺陷", "未闭环", 1)));

        SkillCandidateSelection selection = prioritized.select(
                List.of(skill(openLoop.skillName()), skill(longTime.skillName())),
                List.of(openLoop, longTime), "未闭环长时效问题有哪些");

        assertEquals(List.of("long_time", "open_loop"), selection.skillNames());
    }

    @Test
    void unconfiguredPriorityTermRanksAfterConfiguredOnes() {
        SkillRoutingMetadata configured = metadataWithLevels("configured", List.of(), List.of(), List.of("长时效"), List.of());
        SkillRoutingMetadata unconfigured = metadataWithLevels("unconfigured", List.of(), List.of(), List.of("未闭环"), List.of());
        SkillCandidateSelector prioritized = new SkillCandidateSelector(5, 10, () -> List.of(
                new SkillKeywordPriority(null, 2, "缺陷", "长时效", 0)));

        // 未入全局表的词排已配置词之后 (再按 name 兜底)
        SkillCandidateSelection selection = prioritized.select(
                List.of(skill(unconfigured.skillName()), skill(configured.skillName())),
                List.of(unconfigured, configured), "未闭环长时效问题有哪些");

        assertEquals(List.of("configured", "unconfigured"), selection.skillNames());
    }

    @Test
    void explicitNameStillBeatsPriorityAndDepth() {
        SkillRoutingMetadata named = metadataWithLevels("named_skill", List.of(), List.of("问题"), List.of(), List.of());
        SkillRoutingMetadata deep = metadataWithLevels("z_deep", List.of(), List.of(), List.of("问题"), List.of("未闭环"));
        SkillCandidateSelector prioritized = new SkillCandidateSelector(5, 10, () -> List.of(
                new SkillKeywordPriority(null, 1, "", "问题", 0)));

        SkillCandidateSelection selection = prioritized.select(
                List.of(skill(named.skillName()), skill(deep.skillName())),
                List.of(named, deep), "请使用 named_skill 查询未闭环问题");

        assertEquals("named_skill", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void strictAndAllConfiguredLevelsMustHit() {
        SkillRoutingMetadata qi = metadataWithLevels("wenshu_qi", List.of("wenshu", "dabiaolv"),
                List.of("Qi", "卡口", "Q1", "Q2"), List.of("达标率", "通过率", "完成率"), List.of());

        // 三层各命中一词 (L1"Qi" + L2"达标率") → AND 满足, 叶层拼音词未命中不拦
        SkillCandidateSelection hit = selector.select(
                List.of(skill(qi.skillName())), List.of(qi), "Qi 各阶段达标率怎么样");
        assertEquals(List.of("wenshu_qi"), hit.skillNames());
        assertFalse(hit.fallbackExpanded());

        // 配置了的 L2 未命中 → 完全出局 (即使 L1 命中)
        SkillCandidateSelection l2Miss = selector.select(
                List.of(skill(qi.skillName())), List.of(qi), "Qi 卡口整体情况怎么样");
        assertTrue(l2Miss.skillNames().isEmpty());
    }

    @Test
    void onlyL1ConfiguredRequiresL1Hit() {
        SkillRoutingMetadata l1Only = metadataWithLevels("l1_only", List.of(), List.of("部门例会"), List.of(), List.of());

        SkillCandidateSelection hit = selector.select(
                List.of(skill(l1Only.skillName())), List.of(l1Only), "部门例会材料整理");
        assertEquals(List.of("l1_only"), hit.skillNames());

        SkillCandidateSelection miss = selector.select(
                List.of(skill(l1Only.skillName())), List.of(l1Only), "项目周报汇总");
        assertTrue(miss.skillNames().isEmpty());
    }

    @Test
    void legacyDomainKeywordSkillUnaffectedByLevelSkillHits() {
        // 复刻现网形态: wenshu_qi 配 L1+L2 层级关键词; dept 例会 skill 仅领域标签+叶层关键词
        SkillRoutingMetadata qi = metadataWithLevels("wenshu_qi", List.of(),
                List.of("Qi", "卡口", "Q1", "Q2"), List.of("达标率", "通过率", "完成率"), List.of());
        SkillRoutingMetadata deptQ1 = metadataWithDomain("dept_q1", List.of("Q1", "需求卡口"), List.of("部门例会"));

        // 层级 skill L2 未命中 → 出局; 存量 skill 叶层"Q1/需求卡口"命中, 不被层级命中挤出
        SkillCandidateSelection legacyAlone = selector.select(
                List.of(skill(qi.skillName()), skill(deptQ1.skillName())),
                List.of(qi, deptQ1), "Q1 部门例会需求卡口不达标情况");
        assertEquals(List.of("dept_q1"), legacyAlone.skillNames());

        // 双轨并存: 层级 skill AND 满足排前, 存量 skill 叶层命中跟随
        SkillCandidateSelection both = selector.select(
                List.of(skill(qi.skillName()), skill(deptQ1.skillName())),
                List.of(qi, deptQ1), "Q1 达标率情况");
        assertEquals(List.of("wenshu_qi", "dept_q1"), both.skillNames());
    }

    @Test
    void topFromLevelTrackTrueWhenHierarchicalCandidateIsTop() {
        // 层级轨 AND 命中 (未闭环+A号) 与存量轨叶层命中同池: depth 排序保证层级轨 top-1
        SkillRoutingMetadata level = metadataWithLevels("a_skill", List.of(),
                List.of("未闭环"), List.of("A号"), List.of());
        SkillRoutingMetadata legacy = metadata("b_skill", List.of("未闭环"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(level.skillName()), skill(legacy.skillName())),
                List.of(level, legacy), "三部10月未闭环的A号数据");

        assertEquals("a_skill", selection.skillNames().get(0));
        assertTrue(selection.topFromLevelTrack());
    }

    // ════════════ domain 命中优先于直接执行 (docs/202610/domain_tags命中优先于直接执行重排序方案.md §3.3/§3.4) ════════════

    @Test
    void domainHitAloneAdmitsSkillIntoPool() {
        // 拍板⑥ (现网形态复刻): 例会 skill 叶层词是窄词「告警」, 「部门例会」domain 命中但
        // 叶层零命中 —— 原 keywordHits==0 continue 会挡在池外, 现在免叶层要求入围
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("告警"),
                List.of("部门例会", "杭研周例会"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName())), List.of(meeting), "部门例会情况怎么样");

        assertEquals(List.of("meeting"), selection.skillNames());
    }

    @Test
    void domainHitExcludesUntaggedLevelTrackSkill() {
        // 2026/10/29 收紧 (用户实证): 「部门例会 三部遗留问题」— 无领域标签的层级轨 skill
        // 即使 AND 命中也不入池; 目录只留命中领域的 skill
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("告警"), List.of("部门例会"));
        SkillRoutingMetadata level = metadataWithLevels("app_quality", List.of(),
                List.of("问题"), List.of("未闭环"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(level.skillName())),
                List.of(meeting, level), "部门例会 三部遗留问题");

        assertEquals(List.of("meeting"), selection.skillNames());
        assertFalse(selection.topFromLevelTrack());
    }

    @Test
    void domainTaggedLevelSkillStillEntersOnDomainHit() {
        // 配了命中领域标签的层级 skill 照常入池, 且按拍板② 排到存量轨领域 skill 之后
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("告警"), List.of("部门例会"));
        SkillRoutingMetadata taggedLevel = metadataWithDomainAndLevels("tagged_level",
                List.of("部门例会"), List.of("问题"), List.of("未闭环"));

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(taggedLevel.skillName())),
                List.of(meeting, taggedLevel), "部门例会上提到的未闭环问题");

        assertEquals(List.of("meeting", "tagged_level"), selection.skillNames());
    }

    @Test
    void noDomainHitKeepsLevelTrackAboveLegacy() {
        // 回归锚: 无 domain 词 → domainHit 恒 false, 层级轨照旧排存量轨前 (10/27 漏斗零变化);
        // 例会 skill 叶层「告警」未命中 → 也不入池
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("告警"), List.of("部门例会"));
        SkillRoutingMetadata level = metadataWithLevels("app_quality", List.of(),
                List.of("问题"), List.of("未闭环"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(level.skillName())),
                List.of(meeting, level), "未闭环问题还有多少");

        assertEquals(List.of("app_quality"), selection.skillNames());
        assertTrue(selection.topFromLevelTrack());
    }

    @Test
    void explicitNameStillBeatsDomainHit() {
        // 拍板①: 显式点名 > domainHit (豁免 domain 让位的目录侧对齐)
        SkillRoutingMetadata meeting = metadataWithDomain("meeting", List.of("告警"), List.of("部门例会"));
        SkillRoutingMetadata level = metadataWithLevels("app_quality", List.of(),
                List.of("问题"), List.of("未闭环"), List.of());

        SkillCandidateSelection selection = selector.select(
                List.of(skill(meeting.skillName()), skill(level.skillName())),
                List.of(meeting, level), "请使用 app_quality 查部门例会相关的未闭环问题");

        assertEquals("app_quality", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void topFromLevelTrackFalseForLegacyExplicitAndEmpty() {
        // 存量轨 top-1
        SkillRoutingMetadata legacy = metadata("legacy", List.of("达标率"));
        SkillCandidateSelection legacySel = selector.select(
                List.of(skill(legacy.skillName())), List.of(legacy), "查询达标率");
        assertFalse(legacySel.topFromLevelTrack());

        // 显式点名 top-1 (非层级轨)
        SkillCandidateSelection explicitSel = selector.select(
                List.of(skill(legacy.skillName())), List.of(legacy), "请使用 legacy 查询");
        assertTrue(explicitSel.explicitNameMatched());
        assertFalse(explicitSel.topFromLevelTrack());

        // 空结果
        SkillCandidateSelection emptySel = selector.select(
                List.of(skill(legacy.skillName())), List.of(legacy), "完全未知的问题");
        assertTrue(emptySel.skillNames().isEmpty());
        assertFalse(emptySel.topFromLevelTrack());
    }

    // ════════════ 维度实体 span 屏蔽（docs/202610/检索关键词与维度值冲突治理方案.md §4） ════════════

    private static DimensionSpanScanner riskAliasScanner() {
        return new DimensionSpanScanner(
                () -> List.of(new com.agentscopea2a.v2.dimension.DimensionAlias(1L,
                        com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType.TEAM,
                        "风险组", "风险资产业务组", null, true, null)),
                () -> null, 30000);
    }

    @Test
    void spanShieldSuppressesKeywordHitInsideEntitySpan() {
        // §1.1 实证 case: 唯一词条"风险"命中"风险组"内部 = 假主题信号 → 出局
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, riskAliasScanner());
        SkillRoutingMetadata risk = metadata("risk_metrics", List.of("风险"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(risk.skillName())), List.of(risk),
                "杭州四部风险组10月版测试问题归属哪个开发经理");
        assertTrue(selection.skillNames().isEmpty(), "实体 span 内的假主题命中应屏蔽");
    }

    @Test
    void spanShieldKeepsStandaloneThemeHit() {
        // "风险"独立出现（span 外）→ 真主题信号，不误杀
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, riskAliasScanner());
        SkillRoutingMetadata risk = metadata("risk_metrics", List.of("风险"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(risk.skillName())), List.of(risk), "风险项的达标率有多少");
        assertEquals(List.of("risk_metrics"), selection.skillNames());
    }

    @Test
    void spanShieldMixedHitsKeepSkill() {
        // "风险"被"风险组"屏蔽，"缺陷"在 span 外真命中 → 混合场景入围
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, riskAliasScanner());
        SkillRoutingMetadata mixed = metadata("defect_metrics", List.of("风险", "缺陷"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(mixed.skillName())), List.of(mixed), "风险组的缺陷");
        assertEquals(List.of("defect_metrics"), selection.skillNames());
    }

    @Test
    void explicitNameSurvivesSpanShield() {
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, riskAliasScanner());
        SkillRoutingMetadata risk = metadata("risk_metrics", List.of("风险"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(risk.skillName())), List.of(risk),
                "请使用 risk_metrics 查询风险组的数据");
        assertEquals(List.of("risk_metrics"), selection.skillNames());
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void teamTokenShieldsEvenWithoutAlias() {
        // P3: 未配 alias 的"XX组"也走 S3 组名 token 兜底
        DimensionSpanScanner emptyAlias = new DimensionSpanScanner(List::of, () -> null, 30000);
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, emptyAlias);
        SkillRoutingMetadata risk = metadata("risk_metrics", List.of("风险"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(risk.skillName())), List.of(risk), "杭州四部风险组的问题项");
        assertTrue(selection.skillNames().isEmpty(), "组名 token 兜底屏蔽");
    }

    @Test
    void levelTrackCollidedLayerCountsAsMiss() {
        // P4/层级轨: L1"风险"被屏蔽, L2"达标率"真命中 → AND 不满足 → 出局
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, riskAliasScanner());
        SkillRoutingMetadata lv = metadataWithLevels("lv_metrics", List.of(),
                List.of("风险"), List.of("达标率"), List.of());
        SkillCandidateSelection blocked = shielded.select(
                List.of(skill(lv.skillName())), List.of(lv), "杭州四部风险组10月版达标率");
        assertTrue(blocked.skillNames().isEmpty(), "碰撞层按未命中计, AND 出局");

        // 两层都真命中 → 照常入围
        SkillCandidateSelection passed = shielded.select(
                List.of(skill(lv.skillName())), List.of(lv), "风险项的达标率");
        assertEquals(List.of("lv_metrics"), passed.skillNames());
    }

    @Test
    void timeSpanShieldsKeywordInsideTimeMention() {
        // S4: "10月版本"是时间 span, 词条"月版"落在其中被屏蔽
        DimensionSpanScanner scanner = new DimensionSpanScanner(List::of, () -> null, 30000);
        SkillCandidateSelector shielded = new SkillCandidateSelector(5, 10, null, scanner);
        SkillRoutingMetadata timeKw = metadata("time_kw", List.of("月版"));
        SkillCandidateSelection selection = shielded.select(
                List.of(skill(timeKw.skillName())), List.of(timeKw), "10月版本的达标率");
        assertTrue(selection.skillNames().isEmpty(), "时间 span 内的命中应屏蔽");

        SkillRoutingMetadata normal = metadata("normal_kw", List.of("达标率"));
        SkillCandidateSelection kept = shielded.select(
                List.of(skill(normal.skillName())), List.of(normal), "10月版本的达标率");
        assertEquals(List.of("normal_kw"), kept.skillNames());
    }

    @Test
    void nullScannerBehavesAsBefore() {
        // 回归锚: 无 scanner = 行为与 2026/10/28 前一致（span 内命中照常计分）
        SkillRoutingMetadata risk = metadata("risk_metrics", List.of("风险"));
        SkillCandidateSelection selection = selector.select(
                List.of(skill(risk.skillName())), List.of(risk),
                "杭州四部风险组10月版测试问题归属哪个开发经理");
        assertEquals(List.of("risk_metrics"), selection.skillNames());
    }

    private static SkillRoutingMetadata metadata(String name, List<String> keywords) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), "", true, null);
    }    private static SkillRoutingMetadata metadataWithLevels(String name, List<String> keywords,
                                                           List<String> level1, List<String> level2,
                                                           List<String> level3) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), List.of(), "", true, null,
                level1, level2, level3);
    }

    private static SkillRoutingMetadata metadataWithDomain(String name, List<String> keywords,
                                                           List<String> domainTags) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, domainTags, List.of(), "", true, null);
    }

    private static SkillRoutingMetadata metadataWithDomainAndLevels(String name, List<String> domainTags,
                                                                    List<String> level1, List<String> level2) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of(), domainTags, List.of(), "", true, null,
                level1, level2, List.of());
    }

    private static SkillRoutingMetadata metadataWithTopic(String name, List<String> keywords,
                                                          List<String> topicTags) {
        return new SkillRoutingMetadata(name, name + " 摘要", keywords, List.of(), topicTags, "", true, null);
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name + " description").skillContent("rules").build();
    }
}
