package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 人员台账仓储（人员台账维度方案 §2）：Q1 归属合并/TTL + 负缓存 / Q2 重名去重合并 /
 *  Q3 字典 TTL + 失败关扫描 / displaySuffix。 */
class PersonLedgerRepositoryTest {

    /** Q1 行：{部门, 统计组, 应用, 产品线, 姓名, 统一认证号, 岗位角色}。 */
    private static void stubQuery(JdbcTemplate jdbc, String sqlFragment, String[][] rows) {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (String[] row : rows) {
                ResultSet rs = mock(ResultSet.class);
                stubCol(rs, "部门", row[0]);
                stubCol(rs, "统计组", row[1]);
                stubCol(rs, "应用", row[2]);
                stubCol(rs, "产品线", row[3]);
                stubCol(rs, "姓名", row[4]);
                stubCol(rs, "统一认证号", row[5]);
                stubCol(rs, "岗位角色", row[6]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains(sqlFragment),
                any(RowCallbackHandler.class), any(Object[].class));
    }

    private static void stubCol(ResultSet rs, String col, String value) throws Exception {
        org.mockito.Mockito.doReturn(value).when(rs).getString(col);
    }

    /** Q2 行：{部门, 统计组, 姓名, 统一认证号, 岗位角色}。 */
    private static void stubNameQuery(JdbcTemplate jdbc, String[][] rows) {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (String[] row : rows) {
                ResultSet rs = mock(ResultSet.class);
                stubCol(rs, "部门", row[0]);
                stubCol(rs, "统计组", row[1]);
                stubCol(rs, "姓名", row[2]);
                stubCol(rs, "统一认证号", row[3]);
                stubCol(rs, "岗位角色", row[4]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains("\"姓名\" = ? AND"),
                any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void byUserIdParsesRowAndTtlCaches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, "\"统一认证号\" = ? AND", new String[][]{
                {"杭州开发三部", "数据智能组", "F-demo", " quality产品线", "徐三金", "555153289", "开发工程师"}});
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);

        PersonLedgerRepository.PersonLedgerRow row = repo.byUserId("555153289");

        assertEquals(List.of("杭州开发三部"), row.depts());
        assertEquals(List.of("数据智能组"), row.groups());
        assertEquals(List.of("F-demo"), row.apps());
        assertEquals(List.of("quality产品线"), row.productLines());
        assertEquals("徐三金", row.name());
        assertEquals("555153289", row.userId());

        repo.byUserId("555153289");
        repo.byUserId("555153289");
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void byUserIdMergesMultiRowAffiliations() {
        // 兼岗多行（拍板①）：各列 distinct 多值合并，姓名取第一个非空
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, "\"统一认证号\" = ? AND", new String[][]{
                {"杭州开发三部", "数据智能组", null, null, "徐三金", "555153289", "开发工程师"},
                {"杭州开发二部", "质量保障组", null, null, null, "555153289", "测试经理"}});
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);

        PersonLedgerRepository.PersonLedgerRow row = repo.byUserId("555153289");

        assertEquals(List.of("杭州开发三部", "杭州开发二部"), row.depts());
        assertEquals(List.of("数据智能组", "质量保障组"), row.groups());
        assertTrue(row.apps().isEmpty());
        assertEquals(List.of("开发工程师", "测试经理"), row.roles());
        assertEquals("徐三金", row.name());
    }

    @Test
    void byUserIdUnknownReturnsNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, "\"统一认证号\" = ? AND", new String[][]{});
        assertNull(new PersonLedgerRepository(jdbc, 1800).byUserId("nobody"));
    }

    @Test
    void byUserIdFailureNegativeCachesAndDoesNotRequery() {
        // 失败降级（§3.5）+ 负缓存：同一用户第二次不再打库
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(jdbc).query(anyString(),
                any(RowCallbackHandler.class), any(Object[].class));
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);

        assertNull(repo.byUserId("555153289"));
        assertNull(repo.byUserId("555153289"));
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void byUserIdBlankShortCircuitsWithoutQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);

        assertNull(repo.byUserId(null));
        assertNull(repo.byUserId("  "));
        verifyNoInteractions(jdbc);
    }

    @Test
    void byNameDedupesByPerNoAndMergesAffiliations() {
        // 同 perNo 多行 = 兼岗合并；不同 perNo = 重名候选
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubNameQuery(jdbc, new String[][]{
                {"杭州开发三部", "数据智能组", "张三", "111", "开发工程师"},
                {"杭州开发二部", null, "张三", "111", "测试经理"},
                {"杭州开发一部", "个人住房组", "张三", "222", null}});
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);

        List<PersonLedgerRepository.PersonIdentity> identities = repo.byName("张三");

        assertEquals(2, identities.size());
        PersonLedgerRepository.PersonIdentity first = identities.get(0);
        assertEquals("111", first.perNo());
        assertEquals(List.of("杭州开发三部", "杭州开发二部"), first.depts());
        assertEquals(List.of("数据智能组"), first.groups());
        assertEquals(List.of("开发工程师", "测试经理"), first.roles());
        assertEquals("222", identities.get(1).perNo());
    }

    @Test
    void byNameSkipsBlankPerNoAndHandlesFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubNameQuery(jdbc, new String[][]{
                {null, null, "张三", null, null}});
        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);
        assertTrue(repo.byName("张三").isEmpty());

        JdbcTemplate broken = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(broken).query(anyString(),
                any(RowCallbackHandler.class), any(Object[].class));
        assertTrue(new PersonLedgerRepository(broken, 1800).byName("张三").isEmpty());
        assertTrue(new PersonLedgerRepository(mock(JdbcTemplate.class), 1800).byName(null).isEmpty());
    }

    @Test
    void nameDictionaryTtlCachesAndFailureDisablesScan() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (String name : new String[]{"徐三金", "杨三君", null, " "}) {
                ResultSet rs = mock(ResultSet.class);
                stubCol(rs, "姓名", name);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);
        assertEquals(Set.of("徐三金", "杨三君"), repo.nameDictionary());
        repo.nameDictionary();
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));

        JdbcTemplate broken = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(broken).query(anyString(),
                any(RowCallbackHandler.class));
        // 失败 → 空 Set（人名扫描整体关闭）不抛错
        assertTrue(new PersonLedgerRepository(broken, 1800).nameDictionary().isEmpty());
    }

    @Test
    void identityDisplaySuffixJoinsAffiliations() {
        assertEquals("张三（杭州开发三部 / 数据智能组）",
                new PersonLedgerRepository.PersonIdentity("张三", "111",
                        List.of("杭州开发三部"), List.of("数据智能组")).displaySuffix());
        assertEquals("张三",
                new PersonLedgerRepository.PersonIdentity("张三", "111",
                        List.of(), List.of()).displaySuffix());
    }

    // ==================== Q4 岗位角色字典 + displaySuffix 角色 (岗位角色内置维度方案 §4.1) ====================

    @Test
    void roleDictionaryParsesTtlCachesAndFailsSafe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (String role : new String[]{"开发工程师", "测试经理", null, " "}) {
                ResultSet rs = mock(ResultSet.class);
                stubCol(rs, "岗位角色", role);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        PersonLedgerRepository repo = new PersonLedgerRepository(jdbc, 1800);
        assertEquals(Set.of("开发工程师", "测试经理"), repo.roleDictionary());
        repo.roleDictionary();
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));

        JdbcTemplate broken = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(broken).query(anyString(),
                any(RowCallbackHandler.class));
        // 失败 → 空 Set (路径B 字典扫描自然禁用, fail-safe)
        assertTrue(new PersonLedgerRepository(broken, 1800).roleDictionary().isEmpty());
    }

    @Test
    void identityDisplaySuffixIncludesRoles() {
        assertEquals("徐三金（杭州开发三部 / 测试经理）",
                new PersonLedgerRepository.PersonIdentity("徐三金", "111",
                        List.of("杭州开发三部"), List.of(), List.of("测试经理")).displaySuffix());
    }
}
