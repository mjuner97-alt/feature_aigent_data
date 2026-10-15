package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 时间范围锚点解析: 白名单校验 / 拍板区间语义 / expandKeys 后缀透出 (§6.1.2, §6.1.9)。 */
class TimeRangeResolverTest {

    // 2026-10-14 是周三
    private static final LocalDate WED = LocalDate.of(2026, 10, 14);

    @Test
    void thisWeekIsMondayToToday() {
        assertArrayEquals(new String[]{"2026-10-12", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_WEEK", WED));
    }

    @Test
    void thisWeekOnMondayIsSingleDay() {
        assertArrayEquals(new String[]{"2026-10-12", "2026-10-12"},
                TimeRangeResolver.resolve("THIS_WEEK", LocalDate.of(2026, 10, 12)));
    }

    @Test
    void lastWeekIsCompletePreviousWeek() {
        assertArrayEquals(new String[]{"2026-10-05", "2026-10-11"},
                TimeRangeResolver.resolve("LAST_WEEK", WED));
    }

    /** 上周跨月: 2026-09-29(周二) 问上周 = 09-21 ~ 09-27。 */
    @Test
    void lastWeekAcrossMonthBoundary() {
        assertArrayEquals(new String[]{"2026-09-21", "2026-09-27"},
                TimeRangeResolver.resolve("LAST_WEEK", LocalDate.of(2026, 9, 29)));
    }

    @Test
    void recentDaysIncludeToday() {
        assertArrayEquals(new String[]{"2026-10-08", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:7d", WED));
        assertArrayEquals(new String[]{"2026-10-14", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:1d", WED));
        assertArrayEquals(new String[]{"2026-09-15", "2026-10-14"},
                TimeRangeResolver.resolve("LAST:30d", WED));
    }

    @Test
    void calendarAnchorsResolve() {
        assertArrayEquals(new String[]{"2026-10-01", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_MONTH", WED));
        assertArrayEquals(new String[]{"2026-01-01", "2026-10-14"},
                TimeRangeResolver.resolve("THIS_YEAR", WED));
    }

    @Test
    void versionAndQuarterAnchorsUnresolvedThisPhase() {
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
        assertTrue(TimeRangeResolver.isValidConfigAnchor("THIS_MONTH"));
        assertTrue(TimeRangeResolver.isValidConfigAnchor("THIS_YEAR"));
        // 暂缓与提取层专属锚点禁止人工配置
        assertFalse(TimeRangeResolver.isValidConfigAnchor("LAST:3V"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("LAST:3Q"));
        assertFalse(TimeRangeResolver.isValidConfigAnchor("DATE:2026-10-01~2026-10-15"));
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
}
