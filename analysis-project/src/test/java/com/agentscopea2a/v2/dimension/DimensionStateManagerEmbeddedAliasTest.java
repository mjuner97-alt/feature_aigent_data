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
    void teamAliasStandardNameContainedInTeamTokenIsKept() {
        // "同业"→"同业客户组" 是组名 token"杭州开发三部同业客户组"的后缀子串 ⇒ 保留，出标准名+映射行
        String question = "杭州开发三部同业客户组10月版本和11月版本有几个问题号";
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased(question);

        DimensionState.PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("同业客户组"), peer.getValues());
        assertEquals(List.of("杭州开发三部"), analysis.getExplicitDimensions().getDepartments());

        assertEquals(1, analysis.getAliasResolution().resolved().size());
        assertEquals("同业", analysis.getAliasResolution().resolved().get(0).alias());
        assertEquals("同业客户组", analysis.getAliasResolution().resolved().get(0).standardName());
    }

    @Test
    void teamAliasUnrelatedToTeamTokenIsSuppressed() {
        // "分行"→"杭州服务支持部分行平台服务创新组"与 token"分行业务组"互不包含 ⇒ 抑制，防错绑
        DimensionState.PeerDimension peer = analyzePeer("分行业务组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("分行业务组"), peer.getValues());
    }

    @Test
    void fullStandardTeamNameStillNormalizesThroughAlias() {
        // 全称里嵌的"分行"：token==标准名 ⇒ 保留并归一化
        DimensionState.PeerDimension peer =
                analyzePeer("杭州服务支持部分行平台服务创新组的需求项有多少");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("杭州服务支持部分行平台服务创新组"), peer.getValues());
    }

    @Test
    void suppressedEmbeddedAmbiguityDoesNotTriggerClarification() {
        String question = "个贷数据开发组的缺陷密度";
        assertNull(manager.startClarificationIfAmbiguous(question), "嵌入小组名的命中已抑制，不应触发反问");
        assertEquals(List.of("个贷数据开发组"), analyzePeer(question).getValues());
    }

    @Test
    void teamAliasAfterDeptPrefixInsideTeamTokenIsKept() {
        // "杭州开发三部同业客户组" = 部门前缀 + 组名本体；"同业"起就是组名，
        // alias 提供标准组名"同业客户组"，不能让小组正则把带部门前缀的原文当组名
        String question = "杭州开发三部同业客户组10月版本和11月版本有几个问题号";
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased(question);

        DimensionState.PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("同业客户组"), peer.getValues());

        assertEquals(1, analysis.getAliasResolution().resolved().size(),
                "部门前缀后的 TEAM 命中应保留在同义词解析结果里");
        assertEquals(List.of("杭州开发三部"),
                analysis.getExplicitDimensions().getDepartments(),
                "部门前缀应同时归入部门维度");
    }

    @Test
    void shortDeptPrefixBeforeTeamAliasIsAlsoKept() {
        // 部门简称"三部"同样识别为部门前缀
        DimensionState.PeerDimension peer = analyzePeer("三部同业客户组的缺陷密度");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("同业客户组"), peer.getValues());
    }

    @Test
    void productLineAliasAfterDeptPrefixIsStillSuppressed() {
        // 非 TEAM 维度命中即使跟在部门名后也保持抑制（"个贷"是产品线别名修饰前缀）
        DimensionState.PeerDimension peer = analyzePeer("杭州开发三部个贷数据开发组的缺陷密度");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(List.of("杭州开发三部个贷数据开发组"), peer.getValues());
    }
}
