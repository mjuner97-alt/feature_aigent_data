package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import com.agentscopea2a.v2.dimension.DimensionState.TimeDimensionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 嵌入小组名内部的 alias 命中抑制："个贷数据开发组"里的"个贷"不应抢注产品线，
 * 也不应压制小组识别（aliasHit 为 true 时小组正则兜底不跑）。
 */
class DimensionStateManagerEmbeddedAliasTest {

    private final DimensionStateManager manager =
            new DimensionStateManager(null, new AliasResolver(DimensionAliasSeed::builtin));

    private DimensionState.PeerDimension analyzePeer(String question) {
        return manager.analyzeQuestionRuleBased(question)
                .getExplicitDimensions()
                .getPeerDimension();
    }

    @Test
    void aliasEmbeddedInLongerTeamNameIsSuppressedAndTeamRegexTakesOver() {
        String question = "人均适应性问题 3季度 个贷数据开发组";
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased(question);

        DimensionState.PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("个贷数据开发组"), peer.getValues());

        assertEquals(TimeDimensionType.QUARTER,
                analysis.getExplicitDimensions().getTimeDimension().getType());
        assertEquals(List.of("2026年3季度"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());

        assertEquals(0, analysis.getAliasResolution().resolved().size(),
                "嵌入命中的『个贷』不应出现在同义词解析结果里");
    }

    @Test
    void bareAliasStillResolvesToProductLine() {
        DimensionState.PeerDimension peer = analyzePeer("个贷的需求项有多少");
        assertEquals(PeerDimensionType.PRODUCT_LINE, peer.getType());
        assertEquals(List.of("个人信贷产品线"), peer.getValues());
    }

    @Test
    void tailAlignedSuffixAliasInsideTeamTokenIsKept() {
        // "应用平台组"延伸到小组 token 末尾 ⇒ 仍指该组，alias 提供标准名
        DimensionState.PeerDimension peer = analyzePeer("FMBM应用平台组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("杭州二部FMBM应用平台组"), peer.getValues());
    }

    @Test
    void domainWordEmbeddedInTeamNameYieldsTeamNotProductLine() {
        // "财政"嵌在"代理财政业务开发组"内部 ⇒ 小组，而非产品线"代理财政"
        DimensionState.PeerDimension peer = analyzePeer("代理财政业务开发组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("代理财政业务开发组"), peer.getValues());
    }

    @Test
    void exactTeamAliasSpanIsNotSuppressed() {
        // alias 与小组 span 完全相等（"个贷消费组"本身就是 alias）⇒ 保留标准名
        DimensionState.PeerDimension peer = analyzePeer("个贷消费组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("个贷消费场景组"), peer.getValues());
    }

    @Test
    void noTeamTokenMeansNoSuppression() {
        DimensionState.PeerDimension peer = analyzePeer("个贷和票据的缺陷密度对比");
        assertEquals(PeerDimensionType.PRODUCT_LINE, peer.getType());
        assertEquals(List.of("个人信贷产品线", "票据产品线"), peer.getValues());
    }

    @Test
    void aliasFollowedByBareZuSuffixIsKept() {
        // "军队"后紧跟单个"组"字 = "别名+组"口语写法（触发词机制），不能当嵌入抑制
        DimensionState.PeerDimension peer = analyzePeer("军队组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("特种业务组"), peer.getValues());
    }

    @Test
    void suppressedEmbeddedAmbiguityDoesNotTriggerClarification() {
        String question = "个贷数据开发组的缺陷密度";
        assertNull(manager.startClarificationIfAmbiguous(question), "嵌入小组名的命中已抑制，不应触发反问");
        assertEquals(List.of("个贷数据开发组"), analyzePeer(question).getValues());
    }
}
