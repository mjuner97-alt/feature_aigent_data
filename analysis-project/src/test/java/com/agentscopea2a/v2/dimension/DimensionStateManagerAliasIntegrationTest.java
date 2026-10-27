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
    void analysisCarriesResolvedAliasMappingForContextInjection() {
        // 维度上下文要回显「口语词→标准名」映射，analysis 必须保留 resolved 结果
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("军队7月份版本有多少需求项");
        assertEquals(1, analysis.getAliasResolution().resolved().size());
        AliasResolver.ResolvedAlias hit = analysis.getAliasResolution().resolved().get(0);
        assertEquals("军队", hit.alias());
        assertEquals("企业资金管理系统", hit.standardName());
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
    void multipleAppCodesAreAllCollected() {
        // 应用维度多值："F-GCMS-PSN、F-ATP-INNER" 两个编码都要识别（修复前只取第一个）
        PeerDimension peer = extractPeer("杭州三部 F-GCMS-PSN、F-ATP-INNER 12月版Q1完成率是多少");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-GCMS-PSN", "F-ATP-INNER"), peer.getValues());
    }

    @Test
    void duplicateAppCodesDeduplicate() {
        PeerDimension peer = extractPeer("F-GCMS-PSN 和 F-GCMS-PSN 的对比");
        assertEquals(java.util.List.of("F-GCMS-PSN"), peer.getValues());
    }

    @Test
    void appCodeStopsBeforeQuarterDigit() {
        // 应用码纯英文+连字符: "F-FPA4季度" 是 应用F-FPA + 4季度, 修复前贪婪吃成 "F-FPA4"
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("F-FPA4季度需求项的平均生命周期");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-FPA"), peer.getValues());
        // 让出的 "4季度" 仍要落进季度维度
        assertTrue(analysis.getExplicitDimensions().getTimeDimension() != null);
    }

    @Test
    void appCodeWithDigitInsideIsNotSplit() {
        // 应用码本身以字母开头且不含数字; 若业务真出现数字编码需走 alias 词表配置
        PeerDimension peer = extractPeer("F-ATP-INNER的需求项");
        assertEquals(java.util.List.of("F-ATP-INNER"), peer.getValues());
    }

    @Test
    void bareThreePlusUpperLettersAutoPrefixedWithF() {
        // 裸应用码: 三位及以上连续大写字母自动补 F- ("FPA4季度" → 应用F-FPA + 4季度)
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("FPA4季度需求项的平均生命周期");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-FPA"), peer.getValues());
        assertTrue(analysis.getExplicitDimensions().getTimeDimension() != null);
    }

    @Test
    void bareAndPrefixedSameCodeDeduplicate() {
        // "FPA和F-FPA" 同一应用, 补前缀后去重
        PeerDimension peer = extractPeer("FPA和F-FPA的对比");
        assertEquals(java.util.List.of("F-FPA"), peer.getValues());
    }

    @Test
    void innerSegmentOfFCodeNotRePrefixed() {
        // F-ATP-INNER 的 "ATP"/"INNER" 内段不得被二次加前缀
        PeerDimension peer = extractPeer("F-ATP-INNER的需求项");
        assertEquals(java.util.List.of("F-ATP-INNER"), peer.getValues());
    }

    @Test
    void fsPrefixAppCodeRecognized() {
        // FS- 前缀应用 (FS-GBCP-EPL 智慧政法): 完整编码含两段连字符
        PeerDimension peer = extractPeer("FS-GBCP-EPL的需求项");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("FS-GBCP-EPL"), peer.getValues());
    }

    @Test
    void fsPrefixAndFPrefixCoexist() {
        PeerDimension peer = extractPeer("比较FS-GBCP-EPL和F-CBST的缺陷");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("FS-GBCP-EPL", "F-CBST"), peer.getValues());
    }

    @Test
    void lowerCaseTechWordsNotRecognizedAsApp() {
        // 技术词表拦截: 小写技术词 (sql/csv) 不误收
        assertNull(extractPeer("sql查询的需求项有多少"));
    }

    @Test
    void lowerCaseBareCodeRecognizedAsAppUppercased() {
        // 小写裸码也识别, 归一大写 ("gcmcs的风险" → F-GCMCS)
        PeerDimension peer = extractPeer("gcmcs的风险");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-GCMCS"), peer.getValues());
    }

    @Test
    void mixedCaseBareCodeNormalizedToUppercase() {
        PeerDimension peer = extractPeer("Gcmcs的风险");
        assertEquals(java.util.List.of("F-GCMCS"), peer.getValues());
    }

    @Test
    void prefixedLowerCaseCodeNormalizedToUppercase() {
        // F- 前缀码同样归一大写 ("F-gcmcs的风险" → F-GCMCS)
        PeerDimension peer = extractPeer("F-gcmcs的风险");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-GCMCS"), peer.getValues());
    }

    @Test
    void lowerCaseAndPrefixedSameCodeDeduplicate() {
        // "gcmcs和F-gcmcs" 大写归一后同串去重 → 一个应用
        PeerDimension peer = extractPeer("gcmcs和F-gcmcs的风险");
        assertEquals(java.util.List.of("F-GCMCS"), peer.getValues());
    }

    @Test
    void uppercaseTechWordAlsoBlockedByStopList() {
        // 技术词大小写都拦: 大写 SQL 不误收为 F-SQL (存量行为也如此, 词表显式固化)
        assertNull(extractPeer("SQL查询的风险"));
    }

    @Test
    void shortTwoLetterTokenNotRecognizedAsApp() {
        // 不足三位不识别 ("QI卡口" 的 QI、其他双字母缩写)
        assertNull(extractPeer("QI的需求项有多少"));
    }

    @Test
    void bareHyphenatedSegmentsAutoPrefixedWithF() {
        // "BCP-EPL" 裸段 (FS- 前缀丢失): 连字符串也补 F- → F-BCP-EPL
        PeerDimension peer = extractPeer("BCP-EPL的需求项有多少");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-BCP-EPL"), peer.getValues());
    }

    @Test
    void bareHyphenatedAndPrefixedSameCodeDeduplicate() {
        // 裸段补 F- 后与显式编码同集合去重; F-/FS- 不同前缀是不同应用不合并
        PeerDimension peer = extractPeer("BCP-EPL和F-BCP-EPL的对比");
        assertEquals(PeerDimensionType.APPLICATION, peer.getType());
        assertEquals(java.util.List.of("F-BCP-EPL"), peer.getValues());
    }

    @Test
    void bareCodesSeparatedBySpaceOrCommaAllCollected() {
        // 空格/顿号/逗号分隔的裸应用码都识别为多值; 时间前缀不干扰
        assertEquals(java.util.List.of("F-FETA", "F-MMTA"),
                extractPeer("10月版本，FETA MMTA问题号还有哪些没有关闭").getValues());
        assertEquals(java.util.List.of("F-FETA", "F-MMTA"),
                extractPeer("10月版本，FETA、MMTA问题号还有哪些没有关闭").getValues());
        assertEquals(java.util.List.of("F-FETA", "F-MMTA"),
                extractPeer("10月版本，FETA，MMTA问题号还有哪些没有关闭").getValues());
    }

    @Test
    void plainRegexFallbackStillWorksWithoutAliasHit() {
        // 无 alias 命中时，正则兜底仍工作；§13.4 起部门前缀由 span 屏蔽自然剥离
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("杭州二部金融市场自营测试组的问题");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("金融市场自营测试组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发二部"), analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void requirementItemNoFallbackStillWorks() {
        PeerDimension peer = extractPeer("I20260208-0005 这个需求项的进展");
        assertEquals(PeerDimensionType.REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("I20260208-0005"), peer.getValues());
    }

    @Test
    void multipleRequirementItemsAllCollected() {
        // "I-...、I-..." 两个需求项多值收集 (修复前只传第一个)
        PeerDimension peer = extractPeer(
                "I20261008-0044、I20260930-0021 这两个需求项的周期是多少天");
        assertEquals(PeerDimensionType.REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("I20261008-0044", "I20260930-0021"), peer.getValues());
    }

    @Test
    void multipleSubRequirementItemsAllCollected() {
        PeerDimension peer = extractPeer(
                "S20250926-000741 和 S20250927-000752 的进展对比");
        assertEquals(PeerDimensionType.SUB_REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("S20250926-000741", "S20250927-000752"), peer.getValues());
    }

    @Test
    void duplicateRequirementNumbersDeduplicate() {
        PeerDimension peer = extractPeer("I20260208-0005 和 I20260208-0005 的对比");
        assertEquals(java.util.List.of("I20260208-0005"), peer.getValues());
    }

    @Test
    void subRequirementItemNoExtractedAsSubRequirement() {
        // S 编号（S+8位日期-6位数字）独立成 SUB_REQUIREMENT，不复用 REQUIREMENT
        PeerDimension peer = extractPeer("S20250926-000741 的进展怎么样");
        assertEquals(PeerDimensionType.SUB_REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("S20250926-000741"), peer.getValues());
    }

    @Test
    void requirementNumberWinsOverSubRequirementWhenCoPresent() {
        // I/S 编号同句时按既有顺序 REQUIREMENT 先命中
        PeerDimension peer = extractPeer("I20260208-0005 下的 S20250926-000741 进展");
        assertEquals(PeerDimensionType.REQUIREMENT, peer.getType());
        assertEquals(java.util.List.of("I20260208-0005"), peer.getValues());
    }

    @Test
    void subRequirementReferenceWordDetected() {
        // "这个需求子条目" 指代词独立识别，不误判为 REQUIREMENT
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("这个需求子条目的缺陷分布");
        assertEquals(QuestionAnalysis.ReferenceType.SUB_REQUIREMENT, analysis.getReferenceType());
    }

    @Test
    void aliasHitSuppressedRequirementRegex() {
        // alias 命中后需求项正则不再兜底（peer 已由 alias 决定）
        PeerDimension peer = extractPeer("军队关于I20260208-0005的缺陷");
        assertEquals(PeerDimensionType.PRODUCT_LINE, peer.getType());
        assertEquals(java.util.List.of("企业资金管理系统"), peer.getValues());
    }

    @Test
    void sharedSuffixVersionEnumeratesAllMonths() {
        // "10、11月版" 共享后缀: 前段裸数字也收 (修复前只传 11月版)
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("杭州三部10、11月版研发周期是多少天");
        assertEquals(java.util.List.of("2026年10月份版本", "2026年11月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
        assertEquals(java.util.List.of("杭州开发三部"),
                analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void sharedSuffixVersionWithExplicitYear() {
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("2026年10和11月份版本的达标率");
        assertEquals(java.util.List.of("2026年10月份版本", "2026年11月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void individuallySuffixedVersionsUnaffected() {
        // 回归: 各自带后缀的列举 ("10月版和11月版") 仍各自归一, 不受共享后缀正则影响
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("10月版和11月版的对比");
        assertEquals(java.util.List.of("2026年10月份版本", "2026年11月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void sharedSuffixVersionWithoutBanSuffixEnumeratesAllMonths() {
        // "10、11月" (无"版"后缀): 与带版同构列举 (修复前共享正则的 版本? 要求版必选, 只剩 11月)
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("杭州一部10、11月研发周期");
        assertEquals(java.util.List.of("2026年10月份版本", "2026年11月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
        assertEquals(java.util.List.of("杭州开发一部"),
                analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void sharedSuffixVersionWithoutBanSuffixExplicitYearNoDoubleCount() {
        // 显式年份 + 无版后缀: 末段不再被 EXPLICIT_MONTH 按"无年份→补当前年"重复收一遍
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("2025年10、11月研发周期");
        assertEquals(java.util.List.of("2025年10月份版本", "2025年11月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void sharedSuffixVersionSkipsDelayedVariant() {
        // "10、11月延后版": (?!延后) 守卫生效, 共享正则不吃成普通月份版本
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("10、11月延后版本有多少需求项");
        assertEquals(java.util.List.of("2026年11月延后版"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void sharedSuffixQuarterEnumeratesAllQuarters() {
        // "3、4季度" 共享后缀: 前段裸数字也收 (与月份版本同构)
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("3、4季度的达标率");
        assertEquals(java.util.List.of("2026年3季度", "2026年4季度"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void sharedSuffixQuarterWithExplicitYear() {
        QuestionAnalysis analysis =
                manager.analyzeQuestionRuleBased("2025年3和4季度的对比");
        assertEquals(java.util.List.of("2025年3季度", "2025年4季度"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
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

    // ---- §13.4 标准格式感知的分段抽取（docs/history/dimension-alias-config-plan.md §13）----

    private final DimensionStateManager trimManager = new DimensionStateManager(
            null, new AliasResolver(DimensionAliasSeed::builtin),
            () -> java.util.Set.of("质量", "缺陷"));

    private PeerDimension extractPeerFrom(DimensionStateManager m, String question) {
        return m.analyzeQuestionRuleBased(question).getExplicitDimensions().getPeerDimension();
    }

    @Test
    void deptPrefixStrippedFromUnregisteredGroup() {
        // 未配同义词的"杭州三部数据智能组" → 剥部门前缀：TEAM=数据智能组 + DEPT=杭州开发三部
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("杭州三部数据智能组的缺陷");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("数据智能组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发三部"), analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void timeSpanMaskedBeforeGroupExtraction() {
        // "三部10月xxx组的质量" → 部门：杭州开发三部、版本计划：2026年10月份版本、组：xxx组
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("三部10月xxx组的质量");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("xxx组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发三部"), analysis.getExplicitDimensions().getDepartments());
        assertEquals(java.util.List.of("2026年10月份版本"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void groupBeforeTimeSpanAlsoMasked() {
        // "三部xxx组10月的质量"（组在时间前）→ 组：xxx组
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("三部xxx组10月的质量");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("xxx组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发三部"), analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void stopCharAndMetricWordTrimmedFromGroupCandidate() {
        // "三部10月的质量xxx组"（组在时间后）→ 屏蔽后左候选"的质量xxx组"，
        // 左侧修剪虚词"的"+指标词"质量" → 组：xxx组
        PeerDimension peer = extractPeerFrom(trimManager, "三部10月的质量xxx组");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("xxx组"), peer.getValues());
    }

    @Test
    void metricWordNotTrimmedWhenRemainderTooShort() {
        // "10月缺陷组" → 候选"缺陷组"，修剪"缺陷"只剩"组"(<2 字符) → 修剪失败保留原 token
        PeerDimension peer = extractPeerFrom(trimManager, "10月缺陷组");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("缺陷组"), peer.getValues());
    }

    @Test
    void knownTeamStandardNotTrimmed() {
        // 词表已登记的真组名（含指标词"质量"）整段不修剪 — knowsTeamStandard 守卫
        AliasResolver withQualityTeam = new AliasResolver(() -> java.util.List.of(
                new DimensionAlias(null, PeerDimensionType.TEAM, "质保组", "质量保障组", null, true, null)));
        DimensionStateManager m = new DimensionStateManager(
                null, withQualityTeam, () -> java.util.Set.of("质量"));
        PeerDimension peer = extractPeerFrom(m, "质量保障组的缺陷");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("质量保障组"), peer.getValues());
    }

    @Test
    void configuredSynonymWinsAndDeptStillCollected() {
        // 已配同义词：杭州开发三部同业客户组 → 同义词映射优先，组=同业客户组（不剥），
        // 部门维度照常独立收集
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("杭州开发三部同业客户组的缺陷");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("同业客户组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发三部"), analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void groupValueContainingAppResolvedByAliasToStandardName() {
        // 组值不规范含应用（杭州二部FMBM应用平台组）→ 词表映射出完整标准名
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("杭州二部FMBM应用平台组的缺陷");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("杭州二部FMBM应用平台组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发二部"), analysis.getExplicitDimensions().getDepartments());
    }

    @Test
    void splitFailureFallsBackToRawMatch() {
        // 拆分失败（"三部组"只剩组字无组名）→ 回退原文匹配，维持原行为
        PeerDimension peer = extractPeer("三部组的缺陷");
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("三部组"), peer.getValues());
    }

    @Test
    void multipleDeptSpansAllMasked() {
        // 部门冲突（连续两个部门 span）→ 全部占用+收集，组名取剩余
        QuestionAnalysis analysis = manager.analyzeQuestionRuleBased("杭州一部杭州二部智能运维组");
        PeerDimension peer = analysis.getExplicitDimensions().getPeerDimension();
        assertEquals(PeerDimensionType.TEAM, peer.getType());
        assertEquals(java.util.List.of("智能运维组"), peer.getValues());
        assertEquals(java.util.List.of("杭州开发一部", "杭州开发二部"),
                analysis.getExplicitDimensions().getDepartments());
    }
}
