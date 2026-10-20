package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 版本/季度锚点解析（docs/202610/版本季度部门口语值处理方案.md §3.1/§3.3）：
 * 锚点白名单 / 保存期分型校验 / 键全集展开 / 缓存绕过判定 / 执行期展开与窗口解析。
 * 拍板：季度是独立维度不展开为版本（THIS_Q 只出季度值，窗口按季度内月度版本聚合）。
 */
class VersionPlanAnchorResolverTest {

    private static final String[][] ROWS = {
            {"2026年10月份版本", "2025-09-22", "2025-10-27"},
            {"2026年11月份版本", "2025-10-27", "2025-11-24"},
            {"2026年12月份版本", "2025-11-24", "2025-12-22"},
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

    private VersionCalendarRepository calendar() {
        return new VersionCalendarRepository(jdbc(), 1800);
    }

    private VersionCalendarRepository failingCalendar() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        return new VersionCalendarRepository(jdbc, 1800);
    }

    // ==================== 静态判定 ====================

    @Test
    void isAnchorWhitelist() {
        assertTrue(VersionPlanAnchorResolver.isAnchor("THIS_V"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("THIS_Q"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("YEAR_V"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("HALF_V:1"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("HALF_V:2"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("HALF_V:1@2025"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("YEAR_V@2025"));
        assertFalse(VersionPlanAnchorResolver.isAnchor("HALF_V:1@20"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("LAST:3V"));
        assertTrue(VersionPlanAnchorResolver.isAnchor("DELAYED"));
        assertFalse(VersionPlanAnchorResolver.isAnchor("2026年10月份版本"));
        assertFalse(VersionPlanAnchorResolver.isAnchor("LAST:0V"));
        assertFalse(VersionPlanAnchorResolver.isAnchor("THIS_W"));
        assertFalse(VersionPlanAnchorResolver.isAnchor(null));
    }

    @Test
    void configValueValidationPerKey() {
        // versionPlan: 版本标准值 / 延后版标准值 / 版本锚点 (THIS_Q 不允许)
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "2026年10月份版本"));
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "2026年10月延后版"));
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "YEAR_V"));
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "LAST:6V"));
        assertFalse(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "THIS_Q"));
        assertFalse(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", "今年以来"));
        // vestQuarter: 季度标准值 / THIS_Q
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("vestQuarter", "2026年4季度"));
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("vestQuarter", "THIS_Q"));
        assertFalse(VersionPlanAnchorResolver.isValidConfigValue("vestQuarter", "2026年10月份版本"));
        // deptName: 标准枚举
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("deptName", "杭州开发一部"));
        assertFalse(VersionPlanAnchorResolver.isValidConfigValue("deptName", "杭一"));
        // 其他键不校验
        assertTrue(VersionPlanAnchorResolver.isValidConfigValue("qGate", "随便什么"));
        assertFalse(VersionPlanAnchorResolver.isValidConfigValue("versionPlan", " "));
    }

    @Test
    void expandKeysAddsSuffixFormsOnlyForVersionKeys() {
        var keys = VersionPlanAnchorResolver.expandKeys(java.util.Set.of(
                "deptName", "versionPlan", "vestQuarter", "timeRange"));
        assertTrue(keys.contains("versionPlan.start"));
        assertTrue(keys.contains("versionPlan.end"));
        assertTrue(keys.contains("vestQuarter.start"));
        assertTrue(keys.contains("vestQuarter.end"));
        assertFalse(keys.contains("timeRange.start")); // timeRange 后缀由 TimeRangeResolver 负责
        assertFalse(keys.contains("deptName.start"));
        assertEquals(8, keys.size());
    }

    @Test
    void anchorPhraseHitBypassesCacheStandardValueDoesNot() {
        assertTrue(VersionPlanAnchorResolver.containsAnchorPhrase("近3个版本的达标率", null));
        assertTrue(VersionPlanAnchorResolver.containsAnchorPhrase("最近6个月的质量", null));
        assertTrue(VersionPlanAnchorResolver.containsAnchorPhrase("10月延后的缺陷数", null));
        assertTrue(VersionPlanAnchorResolver.containsAnchorPhrase("2026年10月份延后的质量", null));
        // 标准值命中不绕过
        assertFalse(VersionPlanAnchorResolver.containsAnchorPhrase("2026年10月份版本的达标率", null));
        // 词表 alias 原词命中 (value 是锚点)
        var rows = List.of(new DimensionKeyConfig(1L, "versionPlan", "版本计划", true, List.of(
                new DimensionKeyConfig.AliasMapping("今年以来", "YEAR_V"),
                new DimensionKeyConfig.AliasMapping("全年", "YEAR_V")), true, null));
        assertTrue(VersionPlanAnchorResolver.containsAnchorPhrase("今年以来杭一的达标率", rows));
        // alias value 是标准值 → 不绕过
        var stdRows = List.of(new DimensionKeyConfig(1L, "versionPlan", "版本计划", true, List.of(
                new DimensionKeyConfig.AliasMapping("十月版本", "2026年10月份版本")), true, null));
        assertFalse(VersionPlanAnchorResolver.containsAnchorPhrase("十月版本的达标率", stdRows));
        assertFalse(VersionPlanAnchorResolver.containsAnchorPhrase(null, rows));
    }

    // ==================== 执行期展开 ====================

    @Test
    void thisVExpandsToCurrentVersionsStandardPassesThrough() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(
                calendar(), () -> List.of("2026年10月份版本", "2026年11月份版本"));

        assertEquals(List.of("2026年10月份版本", "2026年11月份版本"),
                resolver.expandVersions(List.of("THIS_V")));
        // 标准值透传
        assertEquals(List.of("2026年10月份版本"),
                resolver.expandVersions(List.of("2026年10月份版本")));
        // 混合去重保序
        assertEquals(List.of("2026年10月份版本", "2026年11月份版本"),
                resolver.expandVersions(List.of("THIS_V", "2026年10月份版本")));
    }

    @Test
    void unresolvableAnchorDropped() {
        // 在建版本查询失败 → THIS_V 展开为空被丢弃
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());
        assertEquals(List.of("2026年10月份版本"),
                resolver.expandVersions(List.of("THIS_V", "2026年10月份版本")));
        // 日历不可用 → LAST:{n}V 展开为空丢弃
        VersionPlanAnchorResolver broken = new VersionPlanAnchorResolver(failingCalendar(), () -> List.of());
        assertTrue(broken.expandVersions(List.of("LAST:99V")).isEmpty());
        assertTrue(broken.expandVersions(List.of("THIS_V")).isEmpty());
    }

    @Test
    void resolveFirstStandardVersionReturnsCalendarWindow() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());

        VersionPlanAnchorResolver.VersionResolution r =
                resolver.resolveFirst(List.of("2026年10月份版本"));
        assertEquals(List.of("2026年10月份版本"), r.versions());
        assertEquals("2025-09-22", r.start());
        assertEquals("2025-10-27", r.end());
    }

    @Test
    void resolveFirstVersionNotInCalendarReturnsNull() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());
        assertNull(resolver.resolveFirst(List.of("2099年1月份版本")));
        assertNull(resolver.resolveFirst(List.of()));
    }

    @Test
    void quarterStandardAggregatesQuarterlyMonthVersions() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());

        // 2026年4季度 → 10/11/12 三个月度版本聚合窗口（季度独立维度不展开为版本值传参，
        // 仅窗口口径按月度版本聚合）
        VersionPlanAnchorResolver.VersionResolution r =
                resolver.resolveFirst(List.of("2026年4季度"));
        assertEquals(List.of("2026年10月份版本", "2026年11月份版本", "2026年12月份版本"), r.versions());
        assertEquals("2025-09-22", r.start());
        assertEquals("2025-12-22", r.end());
    }

    @Test
    void thisQParamIsQuarterWindowAggregatesMonths() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(
                calendar(), () -> List.of("2026年10月份版本"));

        VersionPlanAnchorResolver.VersionResolution r = resolver.resolveFirst(List.of("THIS_Q"));
        // 拍板：季度是独立维度，裸键传参是季度值本身
        assertEquals(List.of("2026年4季度"), r.versions());
        // 窗口按季度内月度版本聚合
        assertEquals("2025-09-22", r.start());
        assertEquals("2025-12-22", r.end());
    }

    @Test
    void lastNVersionsFromCalendar() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());

        // 在建锚点缺失（供应商为空）→ 退化为日历最新版本往前推 2 个
        assertEquals(List.of("2026年11月份版本", "2026年12月份版本"),
                resolver.expandVersions(List.of("LAST:2V")));
    }

    @Test
    void lastNVersionsAnchoredAtLastInBuildExcludesNewerCalendarVersions() {
        // 在建 = 10月/11月，日历里还有未来的 12月 → 锚定 11月按月序前推 6 个，12月 不混入
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(
                calendar(), () -> List.of("2026年10月份版本", "2026年11月份版本"));

        assertEquals(List.of("2026年6月份版本", "2026年7月份版本", "2026年8月份版本",
                        "2026年9月份版本", "2026年10月份版本", "2026年11月份版本"),
                resolver.expandVersions(List.of("LAST:6V")));
    }

    @Test
    void yearQualifiedHalfAndYearAnchorsExpandByQualifierYear() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), () -> List.of());

        assertEquals(List.of("2025年1月份版本", "2025年2月份版本", "2025年3月份版本",
                        "2025年4月份版本", "2025年5月份版本", "2025年6月份版本"),
                resolver.expandVersions(List.of("HALF_V:1@2025")));
        List<String> year = resolver.expandVersions(List.of("YEAR_V@2025"));
        assertEquals(12, year.size());
        assertTrue(year.contains("2025年1月份版本"));
        assertTrue(year.contains("2025年12月份版本"));
    }

    @Test
    void delayedLocatesCurrentMonthDelayedVersion() {
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        String currentDelayed = today.getYear() + "年" + today.getMonthValue() + "月延后版";

        VersionCalendarRepository cal = mock(VersionCalendarRepository.class);
        org.mockito.Mockito.when(cal.containsVersion(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> currentDelayed.equals(inv.getArgument(0)));
        org.mockito.Mockito.when(cal.aggregateWindow(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new VersionCalendarRepository.VersionWindow("2025-09-22", "2025-10-27"));
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(cal, () -> List.of());

        // 裸词"延后版"按当前在建月份定位（拍板②）
        assertEquals(List.of(currentDelayed), resolver.expandVersions(List.of("DELAYED")));

        // 日历查无当前月延后版 → DELAYED 丢弃
        VersionCalendarRepository emptyCal = mock(VersionCalendarRepository.class);
        org.mockito.Mockito.when(emptyCal.containsVersion(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        VersionPlanAnchorResolver broken = new VersionPlanAnchorResolver(emptyCal, () -> List.of());
        assertTrue(broken.expandVersions(List.of("DELAYED")).isEmpty());
    }

    @Test
    void halfYearAndYearAnchorsExpandByToday() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(
                calendar(), () -> List.of());
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        int y = today.getYear();

        List<String> year = resolver.expandVersions(List.of("YEAR_V"));
        assertEquals(12, year.size());
        assertTrue(year.contains(y + "年1月份版本"));
        assertTrue(year.contains(y + "年12月份版本"));
        List<String> h1 = resolver.expandVersions(List.of("HALF_V:1"));
        assertEquals(6, h1.size());
        assertTrue(h1.contains(y + "年1月份版本"));
        assertFalse(h1.contains(y + "年7月份版本"));
        List<String> h2 = resolver.expandVersions(List.of("HALF_V:2"));
        assertEquals(6, h2.size());
        assertTrue(h2.contains(y + "年7月份版本"));
        assertTrue(h2.contains(y + "年12月份版本"));
    }

    @Test
    void resolverNullSupplierTreatedAsNoCurrentVersions() {
        VersionPlanAnchorResolver resolver = new VersionPlanAnchorResolver(calendar(), null);
        assertTrue(resolver.expandVersions(List.of("THIS_V")).isEmpty());
    }
}
