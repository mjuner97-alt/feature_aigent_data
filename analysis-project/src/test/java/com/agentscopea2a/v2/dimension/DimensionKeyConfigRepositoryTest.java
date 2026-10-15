package com.agentscopea2a.v2.dimension;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 维度键字典仓储: 首建 seed 内置 8 键 / aliases JSON round-trip / TTL 快照 / 内置键保护。 */
class DimensionKeyConfigRepositoryTest {

    @Test
    void freshTableSeedsEightBuiltinKeys() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement seedPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        // 探测查询抛异常 = 表不存在; DDL execute 正常返回
        when(statement.execute(org.mockito.ArgumentMatchers.startsWith("SELECT 1")))
                .thenThrow(new java.sql.SQLException("not exists"));
        when(connection.prepareStatement(anyString())).thenReturn(seedPs);
        when(seedPs.executeBatch()).thenReturn(new int[]{});
        when(seedPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 0L).findAllForAdmin();

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        // execute 调了两次: 探测判存 + 建表 DDL
        verify(statement, times(2)).execute(ddl.capture());
        assertTrue(ddl.getAllValues().get(1).contains("CREATE TABLE IF NOT EXISTS dimension_key_config"));
        verify(seedPs, times(8)).addBatch();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        // setString(1, ...) 共 9 次: 8 个内置键 seed + timeRange 保留键存在性补种 INSERT
        verify(seedPs, times(9)).setString(org.mockito.ArgumentMatchers.eq(1), key.capture());
        assertTrue(key.getAllValues().contains("versionPlan"));
        assertTrue(key.getAllValues().contains("perName"));
        assertFalse(key.getAllValues().contains("qGate")); // qGate 是用户新增键, 不在 seed 里
        assertTrue(key.getAllValues().contains("timeRange")); // timeRange 保留键补种
    }

    @Test
    void existingTableWithoutTimeRangeRowGetsReservedSeed() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        // 探测查询正常返回 = 表已存在 (存量表), 不走建表/内置键 seed
        when(statement.execute(org.mockito.ArgumentMatchers.startsWith("SELECT 1"))).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // 无 timeRange 行 → 补种

        new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 0L).findAllForAdmin();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(ps, times(2)).setString(org.mockito.ArgumentMatchers.eq(1), sql.capture());
        assertEquals("timeRange", sql.getAllValues().get(1));
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.contains("THIS_WEEK"));
        verify(ps, org.mockito.Mockito.never()).addBatch(); // 内置键 seed 不重跑
    }

    @Test
    void existingTableWithSoftDeletedTimeRangeRowDoesNotReseed() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(org.mockito.ArgumentMatchers.startsWith("SELECT 1"))).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true); // 软删行 (enabled=false) 也算存在, 不复活

        new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 0L).findAllForAdmin();

        verify(ps, org.mockito.Mockito.never()).executeUpdate();
        verify(ps, org.mockito.Mockito.never()).addBatch();
    }

    @Test
    void findAllForAdminParsesAliasesJsonAndSkipsInvalidRows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("id")).thenReturn(1L, 2L);
        when(rs.getString("key")).thenReturn("qGate", "badJson");
        when(rs.getString("label")).thenReturn("卡口", "坏数据");
        when(rs.getBoolean("builtin")).thenReturn(false, false);
        when(rs.getBoolean("enabled")).thenReturn(true, true);
        when(rs.getString("aliases"))
                .thenReturn("[{\"alias\":\"Q1卡口\",\"value\":\"Q1\"},{\"alias\":\"\",\"value\":\"x\"}]", "{broken");

        DimensionKeyConfigRepository repository =
                new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 60_000L);
        markSchemaReady(repository);

        List<DimensionKeyConfig> all = repository.findAllForAdmin();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).aliases().size());
        assertEquals("Q1卡口", all.get(0).aliases().get(0).alias());
        assertEquals("Q1", all.get(0).aliases().get(0).value());
        assertTrue(all.get(1).aliases().isEmpty()); // 坏 JSON 降级空词表, 不炸页面
    }

    @Test
    void findAllEnabledUsesTtlSnapshotAndFiltersEnabled() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("id")).thenReturn(1L, 2L);
        when(rs.getString("key")).thenReturn("qGate", "other");
        when(rs.getString("label")).thenReturn("卡口", "x");
        when(rs.getBoolean("builtin")).thenReturn(false, false);
        when(rs.getBoolean("enabled")).thenReturn(true, true);
        when(rs.getString("aliases")).thenReturn("[]", "[]");

        DimensionKeyConfigRepository repository =
                new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 60_000L);
        markSchemaReady(repository);

        assertEquals(2, repository.findAllEnabled().size());
        assertEquals(2, repository.findAllEnabled().size());
        verify(dataSource, times(1)).getConnection();

        repository.invalidateCache();
        // 重放 ResultSet 桩 (同一 mock 第二次查询, 消耗顺序从头再来)
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("id")).thenReturn(1L, 2L);
        when(rs.getString("key")).thenReturn("qGate", "other");
        when(rs.getString("label")).thenReturn("卡口", "x");
        when(rs.getBoolean("builtin")).thenReturn(false, false);
        when(rs.getBoolean("enabled")).thenReturn(true, true);
        when(rs.getString("aliases")).thenReturn("[]", "[]");
        assertEquals(2, repository.findAllEnabled().size());
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void updateAndSetEnabledNeverTouchBuiltinRows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        DimensionKeyConfigRepository repository =
                new DimensionKeyConfigRepository(dataSource, new ObjectMapper(), 60_000L);
        markSchemaReady(repository);

        // update 走 builtin=FALSE 保护
        repository.update(new DimensionKeyConfig(1L, "qGate", "卡口", false,
                List.of(new DimensionKeyConfig.AliasMapping("Q1卡口", "Q1")), true, null));
        // setEnabled 对禁用方向带 builtin=FALSE 保护 (内置键禁用 UPDATE 不命中)
        repository.setEnabled(1L, false);
        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(updateSql.capture());
        assertTrue(updateSql.getAllValues().get(0).contains("builtin=FALSE"));
        assertTrue(updateSql.getAllValues().get(1).contains("builtin=FALSE OR ?=TRUE"));

        // 序列化: 词表 JSON 落库
        verify(statement).setString(3, "[{\"alias\":\"Q1卡口\",\"value\":\"Q1\"}]");
        assertFalse(updateSql.getAllValues().get(0).contains("ON DUPLICATE"));
    }

    private static void markSchemaReady(DimensionKeyConfigRepository repository) throws Exception {
        Field field = DimensionKeyConfigRepository.class.getDeclaredField("tableEnsured");
        field.setAccessible(true);
        field.setBoolean(repository, true);
    }
}
