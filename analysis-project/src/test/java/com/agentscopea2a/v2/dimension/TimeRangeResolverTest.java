package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间范围锚点解析: 白名单校验 / 历史区间语义 (2026-10-27 拍板⑦终版, 历史含今天起点不变)
 * / expandKeys 后缀透出
 * (dimension-key-dynamic-dimension-plan.md §6.1 + time-dimension-plan.md §2/§4/§7)。
 */
class TimeRangeResolverTest {

    // 2026-10-14 是周三 (本周=10-12 ~ 10-18, 上周=10-05 ~ 10-11)
    private static final LocalDate WED = LocalDate.of(2026, 10, 14);

    @Test
    void thisWeekIsSevenDaysEndingToday() {
        // 拍板⑦终版: 本周 = 一周前的今天 ~ 今天 = today-7 ~ today, 历史含今天 (起点不变)
        assertArrayEquals(new String[]{"2026-10-07", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_WEEK", WED));
    }

    @Test
    void lastWeekIsCalendarWeek() {
        // 拍板⑦: 上周 = 日历周 (上周一 ~ 上周日), 不随本周滚动
        assertArrayEquals(new String[]{"2026-10-05", "2026-10-11"},
                TimeRangeResolver.resolve("LAST_WEEK", WED));
        // 周一当天: 本周一即今天, 上周 = 今天-7 ~ 今天-1
        assertArrayEquals(new String[]{"2026-10-12", "2026-10-18"},
                TimeRangeResolver.resolve("LAST_WEEK", LocalDate.of(2026, 10, 19)));
        // 周日当天: 本周尚未结束, 上周 = 前一个日历周
        assertArrayEquals(new String[]{"2026-10-05", "2026-10-11"},
                TimeRangeResolver.resolve("LAST_WEEK", LocalDate.of(2026, 10, 18)));
    }

    @Test
    void todayIsSingleDay() {
        assertArrayEquals(new String[]{"2026-10-14", "2026-10-14"},
                TimeRangeResolver.resolve("TODAY", WED));
    }

    @Test
    void recentDaysIncludeToday() {
        // 拍板⑦终版: 近N天 = today-N ~ today (起点不变, 历史含今天)
        assertArrayEquals(new String[]{"2026-10-07", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:7d", WED));
        assertArrayEquals(new String[]{"2026-10-13", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:1d", WED));
        assertArrayEquals(new String[]{"2026-09-14", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:30d", WED));
    }

    @Test
    void weekAnchorsRolling() {
        // 拍板②③: 下周 = 未来1周 = today ~ today+7; 未来N周含今天
        assertArrayEquals(new String[]{"2026-10-14", "2026-10-21"},
                TimeRangeResolver.resolve("NEXT:1w", WED));
        assertArrayEquals(new String[]{"2026-10-14", "2026-11-04"},
                TimeRangeResolver.resolve("NEXT:3w", WED));
        // 拍板⑦终版: 历史近N周 = today-7n ~ today (含今天, 与 LAST:{n}d 同构)
        assertArrayEquals(new String[]{"2026-09-23", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:3w", WED));
    }

    @Test
    void untilAnchorIsYearStartToDate() {
        assertArrayEquals(new String[]{"2026-01-01", "2026-10-15"},
                TimeRangeResolver.resolve("UNTIL:2026-10-15", WED));
        assertNull(TimeRangeResolver.resolve("UNTIL:2026-13-01", WED));
        assertNull(TimeRangeResolver.resolve("UNTIL:不是日期", WED));
    }

    @Test
    void calendarAnchorsResolve() {
        assertArrayEquals(new String[]{"2026-10-01", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_MONTH", WED));
        assertArrayEquals(new String[]{"2026-01-01", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_YEAR", WED));
    }

    @Test
    void passThroughAndAmbiguousAnchorsUnresolved() {
        // 透传锚点不解析日期 (裸键原样透传), 歧义锚点不进执行
        assertNull(TimeRangeResolver.resolve("T-5", WED));
        assertNull(TimeRangeResolver.resolve("R-2", WED));
        assertNull(TimeRangeResolver.resolve("交付周至投产周", WED));
        assertNull(TimeRangeResolver.resolve("投产周", WED));
        assertNull(TimeRangeResolver.resolve("AMBIGUOUS:WEEKS:3", WED));
        assertNull(TimeRangeResolver.resolve("AMBIGUOUS:PROD", WED));
        assertNull(TimeRangeResolver.resolve("LAST:3V", WED));
        assertNull(TimeRangeResolver.resolve("LAST:3Q", WED));
        assertNull(TimeRangeResolver.resolve("LAST:0d", WED));
        assertNull(TimeRangeResolver.resolve("whatever", WED));
    }

    @Test
    void configAnchorWhitelist() {
        assertTrue(TimeRangeResolver.isValidConfigAnchor("THIS_WEEK"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("LAST_WEEK"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("LAST:7d"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("LAST:3w"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("NEXT:1w"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("TODAY"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("THIS_MONTH"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("THIS_YEAR"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("AMBIGUOUS:WEEKS:3"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("AMBIGUOUS:PROD"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("交付周至投产周"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("投产周"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("T-5"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("R-12"));
        // 提取层专属锚点与非法值禁止人工配置
        assertFalse(TimeRangeResolver.isValidConfigAnchor("LAST:3V"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("LAST:3Q"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("DATE:2026-10-01~2026-10-15"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("UNTIL:2026-10-15"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("T-0"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("本周"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor(null));
    }

    @Test
    void expandKeysAddsSuffixFormsOnlyWhenReservedKeyPresent() {
        var with = TimeRangeResolver.expandKeys(java.util.Set.of("deptName", "timeRange", "qGate"));
        assertTrue(with.contains("timeRange"));
        assertTrue(with.contains("timeRange.start"));
        assertTrue(with.contains("timeRange.end"));
        assertTrue(with.contains("qGate"));

        var without = TimeRangeResolver.expandKeys(java.util.Set.of("deptName", "qGate"));
        assertEquals(2, without.size());
    }

    @Test
    void dateRangeAnchorParses() {
        assertArrayEquals(new String[]{"2026-10-01", "2026-10-15"},
                TimeRangeResolver.resolveDateRange("DATE:2026-10-01~2026-10-15"));
        assertNull(TimeRangeResolver.resolveDateRange("DATE:2026-10-15~2026-10-01")); // 起止倒置
        assertNull(TimeRangeResolver.resolveDateRange("DATE:2026-13-01~2026-13-15")); // 非法日期
        assertNull(TimeRangeResolver.resolveDateRange("THIS_WEEK"));
    }

    @Test
    void ambiguousAnchorClassification() {
        assertTrue(TimeRangeResolver.isAmbiguousAnchor("AMBIGUOUS:WEEKS:3"));
        assertTrue(TimeRangeResolver.isAmbiguousAnchor("AMBIGUOUS:PROD"));
        assertFalse(TimeRangeResolver.isAmbiguousAnchor("LAST:3w"));
        assertFalse(TimeRangeResolver.isAmbiguousAnchor(null));
    }

    @Test
    void ambiguityAskPromptText() {
        assertEquals("您说的「近3周」是指历史近N周（往前推），还是未来近N周？请回复：历史 / 未来",
                TimeRangeResolver.ambiguityAskPrompt("AMBIGUOUS:WEEKS:3"));
        assertEquals("您说的「投产前」是指投产周，还是交付周至投产周？请回复：投产周 / 交付周至投产周",
                TimeRangeResolver.ambiguityAskPrompt("AMBIGUOUS:PROD"));
        assertNull(TimeRangeResolver.ambiguityAskPrompt("T-5"));
        assertNull(TimeRangeResolver.ambiguityAskPrompt(null));
    }

    @Test
    void ambiguousReplyResolution() {
        assertEquals("LAST:3w", TimeRangeResolver.resolveAmbiguousReply("历史近3周", "AMBIGUOUS:WEEKS:3"));
        assertEquals("LAST:3w", TimeRangeResolver.resolveAmbiguousReply("往前推的", "AMBIGUOUS:WEEKS:3"));
        assertEquals("NEXT:3w", TimeRangeResolver.resolveAmbiguousReply("未来", "AMBIGUOUS:WEEKS:3"));
        assertEquals("NEXT:3w", TimeRangeResolver.resolveAmbiguousReply("接下来3周", "AMBIGUOUS:WEEKS:3"));
        assertNull(TimeRangeResolver.resolveAmbiguousReply("今天天气不错", "AMBIGUOUS:WEEKS:3"));
        assertNull(TimeRangeResolver.resolveAmbiguousReply(null, "AMBIGUOUS:WEEKS:3"));
    }

    @Test
    void ambiguousProdReplyResolution() {
        // "交付周至投产周"同时含"投产", 含"交付"优先判
        assertEquals("交付周至投产周", TimeRangeResolver.resolveAmbiguousReply("交付周至投产周", "AMBIGUOUS:PROD"));
        assertEquals("投产周", TimeRangeResolver.resolveAmbiguousReply("投产周", "AMBIGUOUS:PROD"));
        assertEquals("投产周", TimeRangeResolver.resolveAmbiguousReply("投产", "AMBIGUOUS:PROD"));
        assertNull(TimeRangeResolver.resolveAmbiguousReply("别的", "AMBIGUOUS:PROD"));
    }
}
