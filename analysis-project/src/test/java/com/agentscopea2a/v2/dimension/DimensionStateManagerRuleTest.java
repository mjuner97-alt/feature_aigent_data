package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则提取：部门简称/别名必须归一化到 KNOWLEDGE.md 标准名，否则同指纹积累失效。 */
class DimensionStateManagerRuleTest {

    private final DimensionStateManager manager = new DimensionStateManager(null);

    private List<String> extractDepartments(String question) {
        return manager.analyzeQuestionRuleBased(question).getExplicitDimensions().getDepartments();
    }

    private DimensionState.TimeDimension extractTime(String question) {
        return manager.analyzeQuestionRuleBased(question).getExplicitDimensions().getTimeDimension();
    }

    private int currentYear() {
        return java.time.LocalDate.now().getYear();
    }

    @Test
    void standardFullNameStillMatches() {
        assertEquals(List.of("杭州开发五部"), extractDepartments("杭州开发五部4月版的质量"));
        assertEquals(List.of("云计算实验室"), extractDepartments("云计算实验室这个季度怎么样"));
    }

    @Test
    void shortFormsNormalizeToStandardNames() {
        assertEquals(List.of("杭州开发五部"), extractDepartments("杭州五部4月版的质量"));
        assertEquals(List.of("杭州开发一部"), extractDepartments("一部4月份版本质量如何"));
        assertEquals(List.of("杭州开发二部"), extractDepartments("看下二部的缺陷密度"));
        assertEquals(List.of("杭州技术部"), extractDepartments("技术部4月版质量"));
        assertEquals(List.of("杭州服务支持部"), extractDepartments("服务支持部这个版本怎么样"));
        assertEquals(List.of("杭州服务支持部"), extractDepartments("服务支持的缺陷情况"));
        assertEquals(List.of("云计算实验室"), extractDepartments("云计算4月版质量"));
        assertEquals(List.of("云计算实验室"), extractDepartments("实验室的质量怎么样"));
    }

    @Test
    void multipleDepartmentsInOneQuestion() {
        List<String> depts = extractDepartments("一部和二部这个版本哪个质量好");
        assertEquals(2, depts.size());
        assertTrue(depts.contains("杭州开发一部"));
        assertTrue(depts.contains("杭州开发二部"));
    }

    @Test
    void sameDepartmentDifferentSpellingsProduceSameResult() {
        assertEquals(extractDepartments("杭州开发五部4月版的质量"), extractDepartments("杭州五部4月版的质量"));
    }

    @Test
    void multipleVersionsCollected() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("一部和二部4月版和5月版的质量对比");
        assertEquals(DimensionState.TimeDimensionType.VERSION, time.getType());
        assertEquals(List.of(y + "年4月份版本", y + "年5月份版本"), time.getValues());
    }

    @Test
    void multipleFullVersionsCollected() {
        DimensionState.TimeDimension time = extractTime("2026年4月份版本和2026年5月份版本哪个好");
        assertEquals(
                List.of("2026年4月份版本", "2026年5月份版本"), time.getValues());
    }

    @Test
    void multipleQuartersCollectedWithAliases() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("Q1和二季度的缺陷情况");
        assertEquals(DimensionState.TimeDimensionType.QUARTER, time.getType());
        assertEquals(List.of(y + "年1季度", y + "年2季度"), time.getValues());
    }

    @Test
    void quarterShortAndFullDedupe() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime(y + "年1季度和Q1质量");
        assertEquals(List.of(y + "年1季度"), time.getValues());
    }

    @Test
    void versionBeatsQuarterWhenBothMentioned() {
        // 「2026年4季度11月份版本」: 版本/季度并存时版本优先 (互不换算方案 R4 的提取层前提),
        // 季度不进 TimeDimension → 双时间键脚本只传版本 (2026年11月份版本)
        DimensionState.TimeDimension time = extractTime("2026年4季度11月份版本 达标率多少");
        assertEquals(DimensionState.TimeDimensionType.VERSION, time.getType());
        assertEquals(List.of("2026年11月份版本"), time.getValues());
    }

    @Test
    void yearPrefixOverridesCurrentYear() {
        DimensionState.TimeDimension time = extractTime("2025年4月的质量怎么样");
        assertEquals(List.of("2025年4月份版本"), time.getValues());
    }

    @Test
    void versionWithoutYearPrefixFallsBackToCurrentYear() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("4月份版本的质量");
        assertEquals(List.of(y + "年4月份版本"), time.getValues());
    }

    @Test
    void monthSuffixWithoutVersionWordStillDetected() {
        // "9月份"（无"版本"后缀）也要识别为版本计划，否则反问确认轮整块维度上下文丢失
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("金融产品定价与估值系统的缺陷密度9月份");
        assertEquals(DimensionState.TimeDimensionType.VERSION, time.getType());
        assertEquals(List.of(y + "年9月份版本"), time.getValues());
    }

    @Test
    void monthSuffixAndFullVersionDedupeToSameFingerprint() {
        String y = String.valueOf(currentYear());
        assertEquals(extractTime("9月份的质量"), extractTime("9月份版本的质量"));
    }

    @Test
    void bareMonthDetectedAsVersion() {
        // "9月"（不带"份"）同样识别为版本计划
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("风险组的缺陷密度9月");
        assertEquals(DimensionState.TimeDimensionType.VERSION, time.getType());
        assertEquals(List.of(y + "年9月份版本"), time.getValues());
    }

    // ==================== 口语值方案 §3.2 字面归一 ====================

    @Test
    void cnNumMonthVersionNormalized() {
        // "十月版"/"十一月份" 归一为标准月度版本
        String y = String.valueOf(currentYear());
        assertEquals(extractTime("十月版的达标率"), extractTime(y + "年10月份版本的达标率"));
        assertEquals(extractTime("十一月份缺陷"), extractTime(y + "年11月份版本缺陷"));
        DimensionState.TimeDimension time = extractTime("2026年三月的质量");
        assertEquals(List.of("2026年3月份版本"), time.getValues());
    }

    @Test
    void compactYyyymmNormalized() {
        // "202610版本"/"202610" → 2026年10月份版本
        assertEquals(List.of("2026年10月份版本"), extractTime("202610版本的达标率").getValues());
        assertEquals(List.of("2026年10月份版本"), extractTime("202610的缺陷").getValues());
        assertEquals(extractTime("202610版本"), extractTime("10月份版本"));
    }

    @Test
    void compactYyyymmWordBoundaryNoFalseHit() {
        // F-202610 (应用编码) / 2026103 (长数字) 不得误伤
        assertTrue(extractTime("F-202610的缺陷密度") == null);
        assertTrue(extractTime("编号2026103的质量") == null);
    }

    @Test
    void delayedVersionNormalized() {
        // "10月延后"/"2026年10月延后版" → 延后版标准值（与月份版本同级的独立版本值）
        assertEquals(List.of(currentYear() + "年10月延后版"), extractTime("10月延后的达标率").getValues());
        assertEquals(List.of("2026年10月延后版"), extractTime("2026年10月延后版缺陷").getValues());
        // 不得同时产出普通月份版本
        assertEquals(1, extractTime("10月延后的达标率").getValues().size());
    }

    @Test
    void lastNVersionsProduceAnchor() {
        // 近 N 个版本/近 N 个月 → LAST:{n}V 锚点（执行期经日历展开）
        assertEquals(List.of("LAST:3V"), extractTime("近3个版本的达标率").getValues());
        assertEquals(List.of("LAST:6V"), extractTime("最近6个月的缺陷").getValues());
    }

    @Test
    void hangzhouOneCharAbbreviationsNormalize() {
        // 口语值方案 §3.2：杭一/杭二部 极简形
        assertEquals(List.of("杭州开发一部"), extractDepartments("杭一4月版的质量"));
        assertEquals(List.of("杭州开发二部"), extractDepartments("杭二部的缺陷密度"));
        assertEquals(List.of("杭州开发五部"), extractDepartments("杭五怎么样"));
    }

    // ==================== 内置键词表合并（口语值方案 §3.2） ====================

    private DimensionStateManager managerWithVocab(DimensionKeyConfig... rows) {
        List<DimensionKeyConfig> rowList = List.of(rows);
        return new DimensionStateManager(null, null, null, () -> rowList);
    }

    @Test
    void vocabAliasMergesAnchorsAndDeptValues() {
        DimensionStateManager manager = managerWithVocab(
                new DimensionKeyConfig(1L, "versionPlan", "版本计划", true, List.of(
                        new DimensionKeyConfig.AliasMapping("今年以来", "YEAR_V"),
                        new DimensionKeyConfig.AliasMapping("延后版", "DELAYED")), true, null),
                new DimensionKeyConfig(2L, "deptName", "部门", true, List.of(
                        new DimensionKeyConfig.AliasMapping("杭开一", "杭州开发一部")), true, null));

        var analysis = manager.analyzeQuestionRuleBased("今年以来杭开一的达标率");
        // 词表命中只看维度值; level 判定 dept 分支先于 TIME, 同时命中时归 DEPARTMENT
        assertEquals(QuestionAnalysis.QuestionLevel.DEPARTMENT, analysis.getLevel());
        assertEquals(List.of("YEAR_V"), analysis.getExplicitDimensions().getTimeDimension().getValues());
        assertEquals(List.of("杭州开发一部"), analysis.getExplicitDimensions().getDepartments());

        // 裸词"延后版"（DELAYED 锚点）仅走词表
        var delayed = managerWithVocab(new DimensionKeyConfig(1L, "versionPlan", "版本计划", true,
                List.of(new DimensionKeyConfig.AliasMapping("延后版", "DELAYED")), true, null))
                .analyzeQuestionRuleBased("延后版的达标率");
        assertEquals(List.of("DELAYED"), delayed.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void vocabQuarterAliasMergesIntoQuarterDimension() {
        DimensionStateManager manager = managerWithVocab(new DimensionKeyConfig(3L, "vestQuarter",
                "季度", true, List.of(new DimensionKeyConfig.AliasMapping("当季", "THIS_Q")), true, null));

        var analysis = manager.analyzeQuestionRuleBased("当季的达标率");
        assertEquals(DimensionState.TimeDimensionType.QUARTER,
                analysis.getExplicitDimensions().getTimeDimension().getType());
        assertEquals(List.of("THIS_Q"),
                analysis.getExplicitDimensions().getTimeDimension().getValues());
    }

    @Test
    void vocabHitInsideDateRangeSpanIsShielded() {
        // "10月1日到10月15日" 是 timeRange 绝对日期区间 span，词表 alias "1月" 不得在区间内误收
        DimensionStateManager manager = managerWithVocab(new DimensionKeyConfig(1L, "versionPlan",
                "版本计划", true, List.of(new DimensionKeyConfig.AliasMapping("1月", "YEAR_V")), true, null));

        var values = manager.analyzeQuestionRuleBased("10月1日到10月15日的缺陷数")
                .getExplicitDimensions().getTimeDimension();
        assertTrue(values == null || !values.getValues().contains("YEAR_V"));
    }

    @Test
    void vocabDictionaryFailureFallsBackToPureRegex() {
        DimensionStateManager manager = new DimensionStateManager(null, null, null, () -> {
            throw new RuntimeException("db down");
        });
        assertEquals(List.of("杭州开发一部"), extractDepartments("杭一4月版的质量"));
        // 与词表关闭的纯正则结果一致
        assertEquals(extractDepartments("杭一4月版的质量"),
                manager.analyzeQuestionRuleBased("杭一4月版的质量").getExplicitDimensions().getDepartments());
    }

    @Test
    void yearPrefixedHalfYearAliasUpgradesToQualifiedAnchor() {
        // "25年上半年" → HALF_V:1@2025（词表 alias"上半年"命中时检测紧邻年份前缀），
        // 不再落当前年的裸 HALF_V:1；"2025年各月" 同理 → YEAR_V@2025
        DimensionStateManager manager = managerWithVocab(new DimensionKeyConfig(1L, "versionPlan",
                "版本计划", true, List.of(
                        new DimensionKeyConfig.AliasMapping("上半年", "HALF_V:1"),
                        new DimensionKeyConfig.AliasMapping("各月", "YEAR_V")), true, null));

        var half = manager.analyzeQuestionRuleBased("杭二25年上半年数据")
                .getExplicitDimensions().getTimeDimension();
        assertTrue(half.getValues().contains("HALF_V:1@2025"));
        assertFalse(half.getValues().contains("HALF_V:1"));

        var half4 = manager.analyzeQuestionRuleBased("2025年上半年数据")
                .getExplicitDimensions().getTimeDimension();
        assertTrue(half4.getValues().contains("HALF_V:1@2025"));

        var year = manager.analyzeQuestionRuleBased("杭一2025年各月达标率")
                .getExplicitDimensions().getTimeDimension();
        assertTrue(year.getValues().contains("YEAR_V@2025"));

        // 无年份前缀仍是裸锚点
        var bare = manager.analyzeQuestionRuleBased("上半年数据")
                .getExplicitDimensions().getTimeDimension();
        assertTrue(bare.getValues().contains("HALF_V:1"));
    }

    @Test
    void nonWhitelistKeyAliasesNeverMergeIntoBuiltinDimensions() {
        // 动态键 (如 qGate) 词表 value 不进内置维度，保持双轨隔离
        DimensionStateManager manager = managerWithVocab(new DimensionKeyConfig(9L, "qGate",
                "卡口", false, List.of(new DimensionKeyConfig.AliasMapping("卡口", "Q1")), true, null));
        var analysis = manager.analyzeQuestionRuleBased("卡口达标率");
        assertTrue(analysis.getExplicitDimensions().getTimeDimension() == null);
        assertTrue(analysis.getExplicitDimensions().getDepartments() == null);
    }
}
