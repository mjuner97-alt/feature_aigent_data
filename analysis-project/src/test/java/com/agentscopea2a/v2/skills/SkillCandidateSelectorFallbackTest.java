package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分组保底（docs/202610/Skill检索分组保底方案.md）：保留字「保底」自动命中——
 * L1 真命中 → L2 保底注入；L1+L2 均命中 → L3 保底注入；位次取全局表保底行。
 */
class SkillCandidateSelectorFallbackTest {

    private static SkillCandidateSelector selector(List<SkillKeywordPriority> priorities) {
        return new SkillCandidateSelector(5, 10, priorities == null ? null : () -> priorities);
    }

    /** 复刻现网问题组形态：两个具体词 skill + 一个保底 skill。 */
    private static List<SkillRoutingMetadata> problemGroup() {
        return List.of(
                levels("loop_open", List.of("问题"), List.of("未闭环"), List.of()),
                levels("legacy", List.of("问题"), List.of("遗留"), List.of()),
                levels("overview_fallback", List.of("问题", "缺陷"), List.of("保底"), List.of()));
    }

    private static List<SkillKeywordPriority> problemGroupPriorities() {
        return List.of(
                row(2, "遗留", 0),
                row(2, "未闭环", 1),
                row(2, "保底", 6));
    }

    @Test
    void l1OnlyQuestionFallsBackToMarkerSkill() {
        SkillCandidateSelection selection = selector(problemGroupPriorities()).select(
                problemGroup().stream().map(m -> skill(m.skillName())).toList(), problemGroup(),
                "问题的整体情况");

        // 具体词 skill（配了 L2 未命中）在 L2 收窄层淘汰，保底 skill 确定性胜出
        assertEquals(List.of("overview_fallback"), selection.skillNames());
        // 保留字不进命中词表（metrics 佐证源头上不含「保底」）
        assertFalse(selection.matchedKeywords().contains("保底"));
    }

    @Test
    void specificKeywordBeatsFallback() {
        SkillCandidateSelection selection = selector(problemGroupPriorities()).select(
                problemGroup().stream().map(m -> skill(m.skillName())).toList(), problemGroup(),
                "未闭环遗留问题");

        // 遗留(0) > 未闭环(1) > 保底(6)，保底不抢跑
        assertEquals(List.of("legacy", "loop_open", "overview_fallback"), selection.skillNames());
    }

    @Test
    void fallbackMarkerRequiresL1Hit() {
        SkillRoutingMetadata fallback = levels("overview_fallback", List.of("问题"), List.of("保底"), List.of());
        SkillRoutingMetadata other = levels("qi_gate", List.of("卡口"), List.of("达标率"), List.of());
        List<SkillRoutingMetadata> metadata = List.of(fallback, other);

        SkillCandidateSelection selection = selector(problemGroupPriorities()).select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "卡口达标率");

        // 问题只命中卡口组：保底 skill 的 L1 未命中 → 不注入 → L2 收窄层淘汰，不泄漏
        assertEquals(List.of("qi_gate"), selection.skillNames());
    }

    @Test
    void l3FallbackAnchorRequiresBothL1AndL2Hits() {
        // deep 不配 L1（池内无 L1 命中 → L1 收窄跳过），才能走到 L3 收窄层考察锚规则
        SkillRoutingMetadata crossGroup = levels("cross_group", List.of("问题"), List.of("达标率"), List.of("保底"));
        SkillRoutingMetadata deep = levels("deep", List.of(), List.of("达标率"), List.of("超期"));
        List<SkillRoutingMetadata> metadata = List.of(crossGroup, deep);
        List<SkillKeywordPriority> priorities = List.of(
                row(2, "达标率", 0),
                row(3, "超期", 0),
                row(3, "保底", 6));

        SkillCandidateSelection selection = selector(priorities).select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "达标率超期");

        // cross_group 只命中 L2（L1 未命中）→ L3 保底不注入 → L3 收窄层淘汰
        assertEquals(List.of("deep"), selection.skillNames());
    }

    @Test
    void l3FallbackRanksBelowRealL3Hit() {
        SkillRoutingMetadata deep = levels("deep", List.of("卡口"), List.of("达标率"), List.of("超期"));
        SkillRoutingMetadata fallback = levels("qi_fallback", List.of("卡口"), List.of("达标率"), List.of("保底"));
        List<SkillRoutingMetadata> metadata = List.of(fallback, deep);
        List<SkillKeywordPriority> priorities = List.of(
                row(2, "达标率", 0),
                row(3, "超期", 0),
                row(3, "保底", 6));

        SkillCandidateSelection selection = selector(priorities).select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "卡口达标率超期情况");

        assertEquals(List.of("deep", "qi_fallback"), selection.skillNames());
    }

    @Test
    void fallbackWinsEvenWithoutPriorityRow() {
        // 全局表没配保底行：位次 MAX，仍靠 depth 压制 + 收窄淘汰胜出
        SkillCandidateSelection selection = selector(null).select(
                problemGroup().stream().map(m -> skill(m.skillName())).toList(), problemGroup(),
                "问题的整体情况");

        assertEquals(List.of("overview_fallback"), selection.skillNames());
    }

    @Test
    void fallbackWinsWithEmptyPriorityTable() {
        SkillCandidateSelection selection = selector(List.of()).select(
                problemGroup().stream().map(m -> skill(m.skillName())).toList(), problemGroup(),
                "问题的整体情况");

        assertEquals(List.of("overview_fallback"), selection.skillNames());
    }

    @Test
    void explicitNameStillWinsOverFallback() {
        List<SkillRoutingMetadata> metadata = problemGroup();
        SkillCandidateSelection selection = selector(problemGroupPriorities()).select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata,
                "请使用 legacy 查询问题的整体情况");

        assertEquals("legacy", selection.skillNames().get(0));
        assertTrue(selection.explicitNameMatched());
    }

    @Test
    void markerWordIsNotMatchedAsQuestionText() {
        // 问题含「保底」字样但 skill 的 L1 未命中 → 保留字不参与文本匹配，不触发
        SkillRoutingMetadata fallback = levels("overview_fallback", List.of("缺陷"), List.of("保底"), List.of());
        List<SkillRoutingMetadata> metadata = List.of(fallback);

        SkillCandidateSelection selection = selector(problemGroupPriorities()).select(
                metadata.stream().map(m -> skill(m.skillName())).toList(), metadata, "什么是保底");

        assertTrue(selection.skillNames().isEmpty());
    }

    private static SkillKeywordPriority row(int level, String keyword, int sortOrder) {
        return new SkillKeywordPriority(null, level, null, keyword, sortOrder);
    }

    private static SkillRoutingMetadata levels(String name, List<String> level1,
                                               List<String> level2, List<String> level3) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of(), List.of(), List.of(), "", true, null,
                level1, level2, level3);
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name + " description").skillContent("rules").build();
    }
}
