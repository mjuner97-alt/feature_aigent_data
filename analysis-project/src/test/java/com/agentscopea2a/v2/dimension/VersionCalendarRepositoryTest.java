package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Date;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 火车日历快照查询（口语值方案 §3.3）：TTL 缓存 / 聚合窗口 / LAST:{n}V 口径 / 失败降级。 */
class VersionCalendarRepositoryTest {

    /** 版本 → [start, end] 行数据（start/end 均取过去日期，避免跨天抖动）。 */
    private static final String[][] ROWS = {
            {"2026年8月份版本", "2025-07-28", "2025-08-25"},
            {"2026年9月份版本", "2025-08-25", "2025-09-22"},
            {"2026年10月份版本", "2025-09-22", "2025-10-27"},
    };

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (String[] row : ROWS) {
                ResultSet rs = mock(ResultSet.class);
                org.mockito.Mockito.doReturn(row[0]).when(rs).getString(1);
                org.mockito.Mockito.doReturn(Date.valueOf(row[1])).when(rs).getDate(2);
                org.mockito.Mockito.doReturn(Date.valueOf(row[2])).when(rs).getDate(3);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        return jdbc;
    }

    @Test
    void snapshotParsesRowsAndTtlCaches() {
        JdbcTemplate jdbc = jdbc();
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc, 1800);

        Map<String, VersionCalendarRepository.VersionWindow> snap = repo.snapshot();

        assertEquals(3, snap.size());
        assertEquals(new VersionCalendarRepository.VersionWindow("2025-09-22", "2025-10-27"),
                snap.get("2026年10月份版本"));
        repo.snapshot();
        repo.snapshot();
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void blankOrIncompleteRowsSkipped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(row(null, "2025-09-22", "2025-10-27"));
            handler.processRow(row("2026年11月份版本", null, "2025-11-24"));
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertTrue(new VersionCalendarRepository(jdbc, 1800).snapshot().isEmpty());
    }

    private ResultSet row(String version, String start, String end) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        org.mockito.Mockito.doReturn(version).when(rs).getString(1);
        org.mockito.Mockito.doReturn(start == null ? null : Date.valueOf(start)).when(rs).getDate(2);
        org.mockito.Mockito.doReturn(end == null ? null : Date.valueOf(end)).when(rs).getDate(3);
        return rs;
    }

    @Test
    void aggregateWindowTakesMinStartMaxEnd() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        VersionCalendarRepository.VersionWindow w =
                repo.aggregateWindow(List.of("2026年8月份版本", "2026年10月份版本"));

        assertEquals("2025-07-28", w.start());
        assertEquals("2025-10-27", w.end());
    }

    @Test
    void aggregateWindowPartialHitAggregatesFoundOnly() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        VersionCalendarRepository.VersionWindow w =
                repo.aggregateWindow(List.of("2026年9月份版本", "2099年1月份版本"));

        assertEquals("2025-08-25", w.start());
        assertEquals("2025-09-22", w.end());
    }

    @Test
    void aggregateWindowAllMissedReturnsNull() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        assertNull(repo.aggregateWindow(List.of("2099年1月份版本")));
        assertNull(repo.aggregateWindow(List.of()));
        assertNull(repo.aggregateWindow(null));
    }

    @Test
    void lastVersionsWithoutAnchorTakesNewestNByEndAscending() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        // 锚点缺失退化：全日历按投产日取最新 2 个 = 10月、9月，升序返回
        assertEquals(List.of("2026年9月份版本", "2026年10月份版本"),
                repo.lastVersions(2, null));
    }

    @Test
    void lastVersionsStepsBackByMonthFillingCalendarGaps() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        // 锚定在建最后一版 11月版，近6个 = 6月~11月；日历缺 6月/7月 也按月序补齐
        assertEquals(List.of("2026年6月份版本", "2026年7月份版本", "2026年8月份版本",
                        "2026年9月份版本", "2026年10月份版本", "2026年11月份版本"),
                repo.lastVersions(6, "2026年11月份版本"));
    }

    @Test
    void lastVersionsStepsAcrossYearBoundary() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        assertEquals(List.of("2025年12月份版本", "2026年1月份版本",
                        "2026年2月份版本", "2026年3月份版本"),
                repo.lastVersions(4, "2026年3月份版本"));
    }

    @Test
    void lastVersionsUnparseableAnchorFallsBackToNewest() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);

        assertEquals(List.of("2026年9月份版本", "2026年10月份版本"),
                repo.lastVersions(2, "未知版本"));
    }

    @Test
    void containsVersion() {
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc(), 1800);
        assertTrue(repo.containsVersion("2026年10月份版本"));
        assertFalse(repo.containsVersion("2026年10月延后版"));
        assertFalse(repo.containsVersion(null));
    }

    @Test
    void refreshFailureServesEmptyAndDoesNotThrow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        VersionCalendarRepository repo = new VersionCalendarRepository(jdbc, 1800);

        assertTrue(repo.snapshot().isEmpty());
        assertNull(repo.aggregateWindow(List.of("2026年10月份版本")));
        assertTrue(repo.lastVersions(3, null).isEmpty());
        assertFalse(repo.containsVersion("2026年10月份版本"));
    }
}
