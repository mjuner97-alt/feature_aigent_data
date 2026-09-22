package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimension;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 集成：DimensionStateManager 以 alias 解析优先、正则降为兜底
 * （docs/dimension-alias-config-plan.md §5 / §8）。
 */
class DimensionStateManagerAliasIntegrationTest {

    private final DimensionStateManager manager =
            new DimensionStateManager(null, new AliasResolver(DimensionAliasSeed::builtin));

    private PeerDimension extractPeer(String question) {
        return manager.analyzeQuestionRuleBased(question).getExplicitDimensions().getPeerDimension();
    }

    @Test
    void colloquialArmyResolvesToStandardProductLine() {
        PeerDimension peer = extractPeer("军队的需求项有多少");
        assertEquals(PeerDimensionType.PRODUCT_LINE, peer.getType());
        assertEquals(java.util.List.of("企业资金管理系统"), peer.getValues());
    }

    @Test
    void armyGroupResolvesToStandardTeam() {
        PeerDimension peer = extractPeer("军队组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("特种业务组"), peer.getValues());
    }

    @Test
    void riskGroupIsAmbiguousAndNotHijackedByTeamRegex() {
        // 修复前：EXPLICIT_TEAM 正则把"风险组"抢注成 TEAM
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("风险组7月份版本有多少需求项");
        assertNull(analysis.getExplicitDimensions().getPeerDimension(), "歧义时不写 peer 维度");
        assertEquals(1, analysis.getAmbiguousAliases().size());
        assertEquals(2, analysis.getAmbiguousAliases().get(0).candidates().size());
    }

    @Test
    void fCodeRegexStillWinsWhenCoPresentWithAlias() {
        // F-xxx 编码优先级不变（app > 组/产品线）
        PeerDimension peer = extractPeer("比较GBC和F-CBST的缺陷");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-CBST"), peer.getValues());
    }

    @Test
    void plainRegexFallbackStillWorksWithoutAliasHit() {
        // 无 alias 命中时，原正则链保持原行为
        PeerDimension peer = extractPeer("杭州二部金融市场自营测试组的问题");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("杭州二部金融市场自营测试组"), peer.getValues());
    }

    @Test
    void requirementItemNoFallbackStillWorks() {
        PeerDimension peer = extractPeer("I20260208-0005 这个需求项的进展");
        assertEquals(PeerDimensionType.REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("I20260208-0005"), peer.getValues());
    }

    @Test
    void aliasHitSuppressedRequirementRegex() {
        // alias 命中后需求项正则不再兜底（peer 已由 alias 决定）
        PeerDimension peer = extractPeer("军队关于I20260208-0005的缺陷");
        assertEquals(PeerDimensionType.PRODUCT_LINE, peer.getType());
        assertEquals(java.util.List.of("企业资金管理系统"), peer.getValues());
    }

    @Test
    void aliasVariantsProduceIdenticalStandardState() {
        // 口语词变体（银企团队 / 银企）归一到同一标准名 → 指纹稳定
        PeerDimension viaTeam = extractPeer("银企团队的需求");
        PeerDimension direct = extractPeer("银企的需求");
        assertEquals(viaTeam.getType(), direct.getType());
        assertEquals(viaTeam.getValues(), direct.getValues());
        assertEquals("银企产品线", direct.getValues().get(0));
    }

    @Test
    void levelIsPeerForAliasOnlyQuestions() {
        // 口语词不被任何正则识别时，alias 命中也要把层级抬到 PEER
        assertEquals(QuestionAnalysis.QuestionLevel.PEER,
                manager.analyzeQuestionRuleBased("GMO的情况怎么样").getLevel());
    }

    @Test
    void withoutResolverLegacyRegexBehaviorPreserved() {
        // null resolver → 纯正则老行为（回归保护，含"风险组"被小组正则识别的老语义）
        DimensionStateManager legacy = new DimensionStateManager(null);
        PeerDimension peer = legacy
                .analyzeQuestionRuleBased("交易报价组的缺陷").getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        PeerDimension riskTeam = legacy
                .analyzeQuestionRuleBased("风险组的缺陷").getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, riskTeam.getType());
    }
}
