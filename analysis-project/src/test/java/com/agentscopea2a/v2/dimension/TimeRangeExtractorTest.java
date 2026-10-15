package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间范围提取: L1 绝对区间正则 (缺年补当前年/非法日期跳过) + L2 词表键内最长匹配 (§6.1.3)。
 * L1 缺年补当前年 → 断言用 TimeRangeResolver.ZONE 的当前年拼接。
 */
class TimeRangeExtractorTest {

    private static final List<DimensionKeyConfig.AliasMapping> SEED_ALIASES = List.of(
            new DimensionKeyConfig.AliasMapping("本周", "THIS_WEEK"),
            new DimensionKeyConfig.AliasMapping("上周", "LAST_WEEK"),
            new DimensionKeyConfig.AliasMapping("前一周", "LAST_WEEK"),
            new DimensionKeyConfig.AliasMapping("近一周", "LAST:7d"),
            new DimensionKeyConfig.AliasMapping("最近一周", "LAST:7d"),
            new DimensionKeyConfig.AliasMapping("近7天", "LAST:7d"),
            new DimensionKeyConfig.AliasMapping("近30天", "LAST:30d"));

    private static String currentYear() {
        return String.valueOf(java.time.LocalDate.now(TimeRangeResolver.ZONE).getYear());
    }

    @Test
    void seedWordTablePhrases() {
        assertEquals(List.of("THIS_WEEK"), TimeRangeExtractor.extract("本周的缺陷数", SEED_ALIASES));
        assertEquals(List.of("LAST_WEEK"), TimeRangeExtractor.extract("前一周的达标率", SEED_ALIASES));
        assertEquals(List.of("LAST:7d"), TimeRangeExtractor.extract("近一周的工时", SEED_ALIASES));
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
    void duplicateAnchorsDeduplicatedInQuestionOrder() {
        assertEquals(List.of("LAST_WEEK"), TimeRangeExtractor.extract("比较前一周和上周", SEED_ALIASES));
        assertEquals(List.of("THIS_WEEK", "LAST:7d"),
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

    @Test
    void noHitReturnsEmpty() {
        assertTrue(TimeRangeExtractor.extract("杭州三部数据智能组的缺陷密度", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("", SEED_ALIASES).isEmpty());
        assertTrue(TimeRangeExtractor.extract("本周", null).isEmpty());
    }
}
