package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ensureTable 补种语义：seed 词软删后行仍在表中（NOT EXISTS 防重），因此空表只可能
 * 来自「建表成功但 seed 失败」的残留——空表必须补种自愈，非空表绝不补种。
 */
class SkillBypassKeywordRepositoryTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement statementPs;
    private ResultSet resultSet;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        statementPs = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(statementPs);
    }

    private SkillBypassKeywordRepository repository() {
        return new SkillBypassKeywordRepository(dataSource, 0);
    }

    @Test
    void freshTableCreatesAndSeedsAllBypassWords() throws SQLException {
        // 第1次 execute: tableExists 探测（表不存在 → 抛异常）; 第2次: createTable DDL
        when(statement.execute(anyString()))
                .thenThrow(new SQLException("relation \"skill_bypass_keyword\" does not exist"))
                .thenReturn(true);
        when(statementPs.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(repository().findAllForAdmin().isEmpty());

        verify(statement, times(2)).execute(anyString());
        verify(statementPs, times(21)).addBatch();
        verify(statementPs).executeBatch();
    }

    @Test
    void existingEmptyTableReseeds() throws SQLException {
        when(statement.execute(anyString())).thenReturn(true);
        // 第1次 executeQuery: isEmpty 探测（空表）; 第2次: findAllForAdmin 全量
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(statementPs.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false, false);

        assertTrue(repository().findAllForAdmin().isEmpty());

        verify(statementPs, times(21)).addBatch();
        verify(statementPs).executeBatch();
    }

    @Test
    void existingNonEmptyTableNeverReseeds() throws SQLException {
        when(statement.execute(anyString())).thenReturn(true);
        // 第1次 next: isEmpty 探测（有行 → 非空）; 第2、3次: findAllForAdmin 读到 1 行后结束
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(statementPs.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);

        assertTrue(repository().findAllForAdmin().size() == 1);

        verify(statementPs, never()).executeBatch();
    }
}
