package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间范围提取: L1 正则 (绝对区间/截至/省月形态/里程碑透传/周类) + L2 词表键内最长匹配
 * (dimension-key-dynamic-dimension-plan.md §6.1.3 + time-dimension-plan.md §3)。
 * L1 缺年补当前年 → 断言用 TimeRangeResolver.ZONE 的当前年拼接。
 */
class TimeRangeExtractorTest {

    private static final List<DimensionKeyConfig.AliasMapping> SEED_ALIASES = List.of(
            new DimensionKeyConfig.AliasMapping("本周", "THIS_WEEK"),
            new DimensionKeyConfig.AliasMapping("当周", "THIS_WEEK"),
            new DimensionKeyConfig.AliasMapping("上周", "LAST_WEEK"),
            new DimensionKeyConfig.AliasMapping("前一周", "LAST_WEEK"),
            new DimensionKeyConfig.AliasMapping("今天", "TODAY"),
            new DimensionKeyConfig.AliasMapping("今日", "TODAY"),
            new DimensionKeyConfig.AliasMapping("下周", "NEXT:1w"),
            new DimensionKeyConfig.AliasMapping("近7天", "LAST:7d"),
            new DimensionKeyConfig.AliasMapping("近30天", "LAST:30d"),
            new DimensionKeyConfig.AliasMapping("交付周到投产", "交付周至投产周"),
            new DimensionKeyConfig.AliasMapping("交付后", "交付周至投产周"),
            new DimensionKeyConfig.AliasMapping("投产周", "投产周"),
            new DimensionKeyConfig.AliasMapping("投产前一周", "投产周"),
            new DimensionKeyConfig.AliasMapping("投产前", "AMBIGUOUS:PROD"));

    private static String currentYear() {
        return String.valueOf(java.time.LocalDate.now(TimeRangeResolver.ZONE).getYear());
    }

    private static String currentMonth() {
        return String.format("%02d", java.time.LocalDate.now(TimeRangeResolver.ZONE).getMonthValue());
    }

    @Test
    void seedWordTablePhrases() {
        assertEquals(List.of("THIS_WEEK"), TimeRangeExtractor.extract("本周的缺陷数", SEED_ALIASES));
        assertEquals(List.of("THIS_WEEK"), TimeRangeExtractor.extract("当周的质量", SEED_ALIASES));
        assertEquals(List.of("LAST_WEEK"), TimeRangeExtractor.extract("前一周的达标率", SEED_ALIASES));
        assertEquals(List.of("TODAY"), TimeRangeExtractor.extract("今天的缺陷数", SEED_ALIASES));
        assertEquals(List.of("TODAY"), TimeRangeExtractor.extract("今日工时", SEED_ALIASES));
        assertEquals(List.of("NEXT:1w"), TimeRangeExtractor.extract("下周的投产计划", SEED_ALIASES));
    }

    @Test
    void longerAliasWinsOverShorter() {
        // 词表同时配了「一周→LAST:7d」(假想) 与「前一周→LAST_WEEK」: 长词先 accept, 短词重叠丢弃
        List<DimensionKeyConfig.AliasMapping> aliases = List.of(
                new DimensionKeyConfig.AliasMapping("一周", "LAST:7d"),
                new DimensionKeyConfig.AliasMapping("前一周", "LAST_WEEK"));
        assertEquals(List.of("LAST_WEEK"), TimeRangeExtractor.extract("前一周的质量", aliases));
        assertEquals(List.of("LAST:7d"), TimeRangeExtractor.extract("这一周的质量", aliases));
    }

    @Test
    void milestoneVocabLongAliasShadowsAmbiguous() {
        // 「投产前一周」(→投产周) 先占 span, 「投产前」(→歧义) 重叠丢弃
        assertEquals(List.of("投产周"), TimeRangeExtractor.extract("投产前一周的缺陷", SEED_ALIASES));
        assertEquals(List.of("AMBIGUOUS:PROD"), TimeRangeExtractor.extract("投产前的缺陷", SEED_ALIASES));
        assertEquals(List.of("交付周至投产周"),
                TimeRangeExtractor.extract("交付周到投产的进度", SEED_ALIASES));
        assertEquals(List.of("交付周至投产周"), TimeRangeExtractor.extract("交付后问题", SEED_ALIASES));
    }

    @Test
    void duplicateAnchorsDeduplicatedInQuestionOrder() {
        assertEquals(List.of("LAST_WEEK"), TimeRangeExtractor.extract("比较前一周和上周", SEED_ALIASES));
        assertEquals(List.of("THIS_WEEK", "AMBIGUOUS:WEEKS:1"),
                TimeRangeExtractor.extract("本周和近一周的对比", SEED_ALIASES));
    }

    @Test
    void chineseDateRangeDefaultsToCurrentYear() {
        String year = currentYear();
        assertEquals(List.of("DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("10月1日到10月15日的缺陷数", SEED_ALIASES));
    }

    @Test
    void chineseDateRangeWithExplicitYear() {
        assertEquals(List.of("DATE:2025-12-28~2026-01-03"),
                TimeRangeExtractor.extract("2025年12月28日至2026年1月3日的投产情况", SEED_ALIASES));
    }

    @Test
    void numericDateRangeVariants() {
        assertEquals(List.of("DATE:2026-10-01~2026-10-15"),
                TimeRangeExtractor.extract("2026-10-01~10-15 的缺陷数", SEED_ALIASES));
        assertEquals(List.of("DATE:2026-10-01~2026-10-15"),
                TimeRangeExtractor.extract("2026.10.1-10.15 的缺陷数", SEED_ALIASES));
    }

    @Test
    void invalidDatesSkipped() {
        assertTrue(TimeRangeExtractor.extract("13月1日到13月15日的缺陷数", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("2月30日到3月1日的缺陷数", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("10月15日到10月1日的缺陷数", SEED_ALIASES).isEmpty());
    }

    @Test
    void mixedOrderPreservedAndRegexSpanMasksWordTable() {
        String year = currentYear();
        assertEquals(List.of("THIS_WEEK", "DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("比较本周和10月1日到10月15日的缺陷数", SEED_ALIASES));
    }

    // ==================== 时间维度方案新增（2026-10-19） ====================

    @Test
    void untilAnchorWithYearAndMonth() {
        assertEquals(List.of("UNTIL:2026-10-15"),
                TimeRangeExtractor.extract("截至10月15日的缺陷数", SEED_ALIASES));
        assertEquals(List.of("UNTIL:2026-10-15"),
                TimeRangeExtractor.extract("截止2026年10月15日的完成情况", SEED_ALIASES));
    }

    @Test
    void untilAnchorDefaultsToCurrentYearMonth() {
        // 缺年补当前年、缺月补当前月（拍板④）
        assertEquals(List.of("UNTIL:" + currentYear() + "-" + currentMonth() + "-15"),
                TimeRangeExtractor.extract("截至15日的缺陷数", SEED_ALIASES));
    }

    @Test
    void untilAnchorInvalidDatesSkipped() {
        assertTrue(TimeRangeExtractor.extract("截至13月32日的数据", SEED_ALIASES).isEmpty());
    }

    @Test
    void sameMonthOmittedTailRange() {
        String year = currentYear();
        assertEquals(List.of("DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("10月1日到15日的缺陷数", SEED_ALIASES));
        assertEquals(List.of("DATE:2026-10-01~2026-10-15"),
                TimeRangeExtractor.extract("2026年10月1日至15号的情况", SEED_ALIASES));
    }

    @Test
    void dayRangeDefaultsToCurrentMonth() {
        String year = currentYear();
        String month = currentMonth();
        assertEquals(List.of("DATE:" + year + "-" + month + "-05~" + year + "-" + month + "-10"),
                TimeRangeExtractor.extract("5日到10日的缺陷数", SEED_ALIASES));
        assertTrue(TimeRangeExtractor.extract("20日到10日的数据", SEED_ALIASES).isEmpty()); // 起止倒置
    }

    @Test
    void tailRangeTakesPrecedenceOverDayRange() {
        // 同月省略形态先占 span, 双省月正则不重复命中
        String year = currentYear();
        assertEquals(List.of("DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("10月1日到15日", SEED_ALIASES));
    }

    @Test
    void milestonePassThroughAnchors() {
        assertEquals(List.of("T-5"), TimeRangeExtractor.extract("杭研10月版集中投产T-5周的计划变动", SEED_ALIASES));
        assertEquals(List.of("T-3"), TimeRangeExtractor.extract("T-3的投产情况", SEED_ALIASES));
        assertEquals(List.of("R-2"), TimeRangeExtractor.extract("R-2的需求项", SEED_ALIASES));
    }

    @Test
    void nearDeliveryWithChineseNumeral() {
        // 业务例句原话（中文数字）
        assertEquals(List.of("R-2"),
                TimeRangeExtractor.extract("杭州三部代理财政及国库组临近交付两周未启动的需求项有哪些", SEED_ALIASES));
        assertEquals(List.of("R-3"), TimeRangeExtractor.extract("临近交付3周内的需求", SEED_ALIASES));
    }

    @Test
    void futureWeeksAnchor() {
        assertEquals(List.of("NEXT:3w"), TimeRangeExtractor.extract("未来3周的投产计划", SEED_ALIASES));
        assertEquals(List.of("NEXT:2w"), TimeRangeExtractor.extract("下两周的安排", SEED_ALIASES));
        assertEquals(List.of("NEXT:1w"), TimeRangeExtractor.extract("今后一周的风险", SEED_ALIASES));
    }

    @Test
    void lastWeeksIsAmbiguous() {
        assertEquals(List.of("AMBIGUOUS:WEEKS:3"), TimeRangeExtractor.extract("近3周的缺陷趋势", SEED_ALIASES));
        assertEquals(List.of("AMBIGUOUS:WEEKS:2"), TimeRangeExtractor.extract("近两周的情况", SEED_ALIASES));
    }

    @Test
    void lastOneWeekIsAmbiguousPerBusinessTable() {
        // 业务表拍板: 近X周 (含"近一周") 一律走歧义反问, 不再特判 LAST:7d
        assertEquals(List.of("AMBIGUOUS:WEEKS:1"), TimeRangeExtractor.extract("近一周的工时", SEED_ALIASES));
        assertEquals(List.of("AMBIGUOUS:WEEKS:1"), TimeRangeExtractor.extract("最近一周的工时", SEED_ALIASES));
    }

    @Test
    void invalidWeekNumbersSkipped() {
        assertTrue(TimeRangeExtractor.extract("近0周的缺陷", SEED_ALIASES).isEmpty());
        assertEquals(List.of("LAST:30d"), TimeRangeExtractor.extract("近30天的缺陷", SEED_ALIASES));
    }

    @Test
    void lastDaysRegexBeyondSeedWords() {
        // 2026-10-27 补: 近N天正则, 词表外的 近58天 也能命中 (业务实测漏识)
        assertEquals(List.of("LAST:58d"), TimeRangeExtractor.extract("车贷、房贷两个组的风险近58天", SEED_ALIASES));
        assertEquals(List.of("LAST:90d"), TimeRangeExtractor.extract("近90天的缺陷趋势", SEED_ALIASES));
        // 中文数字与"最近"前缀
        assertEquals(List.of("LAST:15d"), TimeRangeExtractor.extract("最近十五天的达标率", SEED_ALIASES));
        // seed 词表条目被正则遮蔽, 结果同值
        assertEquals(List.of("LAST:7d"), TimeRangeExtractor.extract("近7天的缺陷数", SEED_ALIASES));
        // 负例: "近15日" 量词不是"天", 不命中; 零数字跳过
        assertTrue(TimeRangeExtractor.extract("近15日的交付情况", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("近0天的缺陷", SEED_ALIASES).isEmpty());
    }

    @Test
    void driftRegexCoversLastDays() {
        assertTrue(TimeRangeExtractor.matchesDriftRegex("车贷组的风险近58天"));
        assertTrue(TimeRangeExtractor.matchesDriftRegex("近90天的缺陷趋势"));
    }

    @Test
    void absoluteDateSpansIncludeNewDateRegexes() {
        String year = currentYear();
        // 版本词表屏蔽: 截至/省月形态的日期 span 也要屏蔽月份误收
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("截至10月15日的缺陷数").size());
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("10月1日到15日的缺陷数").size());
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("5日到10日的缺陷数").size());
        // 透传/周类不是日期 span
        assertTrue(TimeRangeExtractor.absoluteDateSpans("T-5周的计划").isEmpty());
        assertTrue(TimeRangeExtractor.absoluteDateSpans("未来3周的计划").isEmpty());
    }

    @Test
    void driftRegexForCacheFallback() {
        assertTrue(TimeRangeExtractor.matchesDriftRegex("未来3周的投产计划"));
        assertTrue(TimeRangeExtractor.matchesDriftRegex("近3周的缺陷趋势"));
        assertFalse(TimeRangeExtractor.matchesDriftRegex("T-5周的计划变动"));
        assertFalse(TimeRangeExtractor.matchesDriftRegex("交付后的问题清单"));
        assertFalse(TimeRangeExtractor.matchesDriftRegex("本周的缺陷数"));
    }

    @Test
    void noHitReturnsEmpty() {
        assertTrue(TimeRangeExtractor.extract("杭州三部数据智能组的缺陷密度", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("本周", null).isEmpty());
    }

    // ==================== 混合省略区间（2026-11-04，左段省"日"） ====================

    @Test
    void mixedRangeRightRepeatsMonthWithDay() {
        // 本 bug 主 case：左段"9月28"无"日"，右段重复月带日
        assertEquals(List.of("DATE:2026-09-28~2026-09-30"),
                TimeRangeExtractor.extract("9月28-9月30日之间QI卡口到期的", SEED_ALIASES));
        assertEquals(List.of("DATE:2026-09-28~2026-09-30"),
                TimeRangeExtractor.extract("杭州开发三部，10、11月版本，9月28-9月30日之间到期的", SEED_ALIASES));
    }

    @Test
    void mixedRangeRightOmittedMonth() {
        assertEquals(List.of("DATE:2026-09-28~2026-09-30"),
                TimeRangeExtractor.extract("9月28-30日之间到期", SEED_ALIASES));
        assertEquals(List.of("DATE:2026-12-28~2026-12-30"),
                TimeRangeExtractor.extract("12月28号-30号的情况", SEED_ALIASES));
    }

    @Test
    void mixedRangeRightOmittedDay() {
        assertEquals(List.of("DATE:2026-09-28~2026-09-30"),
                TimeRangeExtractor.extract("9月28-9月30到期", SEED_ALIASES));
        assertEquals(List.of("DATE:2026-09-28~2026-09-30"),
                TimeRangeExtractor.extract("9月28-30", SEED_ALIASES));
    }

    @Test
    void mixedRangeRightMissingYearInheritsStartYear() {
        assertEquals(List.of("DATE:2025-09-28~2025-10-02"),
                TimeRangeExtractor.extract("2025年9月28-10月2日到期", SEED_ALIASES));
    }

    @Test
    void mixedRangeInvalidSkipped() {
        // 非法月整段跳过
        assertTrue(TimeRangeExtractor.extract("9月28-13月5日的数据", SEED_ALIASES).isEmpty());
        // 跨年混合形态（右段缺年）补起始年后 start>end → 弃收，不做方向猜测
        assertTrue(TimeRangeExtractor.extract("2025年12月28-1月2日的投产", SEED_ALIASES).isEmpty());
        // 起止倒置
        assertTrue(TimeRangeExtractor.extract("9月30-9月28日的数据", SEED_ALIASES).isEmpty());
    }

    @Test
    void mixedRangeDoesNotSwallowVersionOrDayRangeForms() {
        // 版本列举（分隔符 、 不在区间分隔集）不产日期锚点
        assertTrue(TimeRangeExtractor.extract("10、11月版本", SEED_ALIASES).isEmpty());
        // 左段带"日"仍归 CN_RANGE / CN_RANGE_TAIL，不因 MIXED 重复产锚点
        String year = currentYear();
        assertEquals(List.of("DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("10月1日到10月15日", SEED_ALIASES));
        assertEquals(List.of("DATE:" + year + "-10-01~" + year + "-10-15"),
                TimeRangeExtractor.extract("10月1日到15日", SEED_ALIASES));
        // 裸日期（无区间上下文）2026-11-10 拍板①起出单日，见 bareDateWithEventWordsStillSingleDay
    }

    @Test
    void mixedRangeSpansShieldVersionRegex() {
        // 屏蔽联动（方案 §3.2）：MIXED span 进 absoluteDateSpans，防 EXPLICIT_VERSION 吃 "9月"
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("9月28-9月30日之间到期").size());
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("9月28-30日之间到期").size());
    }

    // ==================== 裸日期单日（2026-11-10，裸日期单日时间范围识别方案 §7） ====================

    @Test
    void bareDateSingleDay() {
        // 业务例句原话：单独日期 start=end，"日/号"均命中
        String y = currentYear();
        assertEquals(List.of("DATE:" + y + "-10-29~" + y + "-10-29"),
                TimeRangeExtractor.extract("10月29日卡口达标率 11月版", SEED_ALIASES));
        assertEquals(List.of("DATE:" + y + "-10-29~" + y + "-10-29"),
                TimeRangeExtractor.extract("10月29号卡口达标率 11月版", SEED_ALIASES));
    }

    @Test
    void bareDateWithExplicitYear() {
        assertEquals(List.of("DATE:2025-10-29~2025-10-29"),
                TimeRangeExtractor.extract("2025年10月29日卡口达标率", SEED_ALIASES));
    }

    @Test
    void bareDateWithEventWordsStillSingleDay() {
        // 拍板①②：事件词护栏取消，到期/上线/起 照出单日
        // （原"到期日≠查询时间范围"口径废弃，"12月版10月26日到期"现在同时出版本维度+timeRange 单日）
        String y = currentYear();
        assertEquals(List.of("DATE:" + y + "-09-28~" + y + "-09-28"),
                TimeRangeExtractor.extract("9月28日到期", SEED_ALIASES));
        assertEquals(List.of("DATE:" + y + "-10-26~" + y + "-10-26"),
                TimeRangeExtractor.extract("杭州三部11月版、12月版10月26日到期", SEED_ALIASES));
        assertEquals(List.of("DATE:" + y + "-10-29~" + y + "-10-29"),
                TimeRangeExtractor.extract("预计10月29日上线", SEED_ALIASES));
        assertEquals(List.of("DATE:" + y + "-10-29~" + y + "-10-29"),
                TimeRangeExtractor.extract("10月29日起的安排", SEED_ALIASES));
    }

    @Test
    void bareDateYieldsToProdDateSuffix() {
        // 拍板⑦（方案 §8.2）：后接"投产" → 投产日属性参数出口，timeRange 不出防双过滤
        assertTrue(TimeRangeExtractor.extract("10月29日投产的应用", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("预计10月29日投产", SEED_ALIASES).isEmpty());
    }

    @Test
    void bareDateSkippedWhenAdjacentToRangeSeparator() {
        // 倒置/非法区间的端点不让裸日期兜底收走单端点，维持整段弃收
        assertTrue(TimeRangeExtractor.extract("10月15日到10月1日的数据", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("2月30日到3月1日的缺陷数", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("9月30-9月28日的数据", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("2025年12月28-1月2日的投产", SEED_ALIASES).isEmpty());
    }

    @Test
    void bareDateInvalidSkipped() {
        assertTrue(TimeRangeExtractor.extract("13月32日的缺陷", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("10月0日的数据", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("10月39日的数据", SEED_ALIASES).isEmpty());
    }

    @Test
    void bareDateDoesNotOverrideRanges() {
        // 区间/截至正则先占 span，裸日期重叠丢弃
        String y = currentYear();
        assertEquals(List.of("DATE:" + y + "-10-29~" + y + "-10-31"),
                TimeRangeExtractor.extract("10月29日到10月31日的缺陷", SEED_ALIASES));
        assertEquals(List.of("UNTIL:2026-10-15"),
                TimeRangeExtractor.extract("截至10月15日的缺陷数", SEED_ALIASES));
    }

    @Test
    void bareDateSpansEnterAbsoluteDateSpans() {
        String y = currentYear();
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans("10月29日卡口达标率").size());
        assertEquals(1, TimeRangeExtractor.absoluteDateSpans(
                "杭州三部11月版、12月版10月26日到期").size());
        // 投产让位：timeRange/absoluteDateSpans 均不出；"10月"的版本屏蔽由
        // DimensionStateManager.BARE_DATE_SPAN 独立兜底，不受影响
        assertTrue(TimeRangeExtractor.absoluteDateSpans("10月29日投产的应用").isEmpty());
    }
}
