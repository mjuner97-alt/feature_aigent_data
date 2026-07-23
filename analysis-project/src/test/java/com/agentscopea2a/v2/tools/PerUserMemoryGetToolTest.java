/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.memory.MysqlMemoryStore.LedgerRow;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PerUserMemoryGetTool}, focusing on per-user isolation:
 * the tool must read from {@link MysqlMemoryStore} (DB, keyed by user_id) and
 * never fall back to any shared filesystem source.
 */
class PerUserMemoryGetToolTest {

    private MysqlMemoryStore mysqlMemoryStore;
    private PerUserMemoryGetTool tool;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        mysqlMemoryStore = Mockito.spy(new MysqlMemoryStore(dataSource));
        tool = new PerUserMemoryGetTool(mysqlMemoryStore);
    }

    @Test
    void memoryGet_userNotIdentified_returnsError() {
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        String result = tool.memoryGet(ctx, "MEMORY.md", 1, 10);
        assertTrue(result.startsWith("Error: user not identified"), "Should reject null userId");
    }

    @Test
    void memoryGet_blankUserId_returnsError() {
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("").build();
        String result = tool.memoryGet(ctx, "MEMORY.md", 1, 10);
        assertTrue(result.startsWith("Error: user not identified"), "Should reject blank userId");
    }

    @Test
    void memoryGet_noMemoryForUser_returnsNotFound() throws Exception {
        when(mysqlMemoryStore.read("alice", MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md"))
                .thenReturn(Optional.empty());
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "MEMORY.md", 1, 10);
        assertTrue(result.startsWith("Error: file not found"), "Should return not-found for user with no memory");
    }

    @Test
    void memoryGet_returnsPerUserMemoryFromDB() throws Exception {
        String aliceMemory = "# MEMORY.md\n## 质量分定义\nQ1 数据";
        when(mysqlMemoryStore.read("alice", MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md"))
                .thenReturn(Optional.of(aliceMemory));
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "MEMORY.md", 1, 10);
        assertTrue(result.contains("1|# MEMORY.md"), "Should return line-numbered content");
        assertTrue(result.contains("Q1 数据"), "Should return alice's memory content");
    }

    @Test
    void memoryGet_bobDoesNotSeeAliceMemory() throws Exception {
        String aliceMemory = "# MEMORY.md\n## 质量分定义\nAlice's Q1 data";
        when(mysqlMemoryStore.read("alice", MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md"))
                .thenReturn(Optional.of(aliceMemory));
        when(mysqlMemoryStore.read("bob", MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md"))
                .thenReturn(Optional.empty());
        RuntimeContext aliceCtx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        RuntimeContext bobCtx = RuntimeContext.builder().sessionId("s2").userId("bob").build();
        String aliceResult = tool.memoryGet(aliceCtx, "MEMORY.md", 1, 10);
        String bobResult = tool.memoryGet(bobCtx, "MEMORY.md", 1, 10);
        assertTrue(aliceResult.contains("Alice's Q1 data"), "Alice should see her own data");
        assertTrue(bobResult.startsWith("Error: file not found"), "Bob should NOT see alice's data");
    }

    @Test
    void memoryGet_paginationWorks() throws Exception {
        String memory = "line1\nline2\nline3\nline4\nline5";
        when(mysqlMemoryStore.read("alice", MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md"))
                .thenReturn(Optional.of(memory));
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "MEMORY.md", 2, 4);
        assertTrue(result.contains("2|line2"), "Should start at line 2");
        assertTrue(result.contains("3|line3"), "Should include line 3");
        assertTrue(result.contains("4|line4"), "Should include line 4");
        assertFalse(result.contains("1|line1"), "Should NOT include line 1");
        assertFalse(result.contains("5|line5"), "Should NOT include line 5");
    }

    @Test
    void memoryGet_unsupportedPath_returnsNotFound() throws Exception {
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "some/random/path.md", 1, 10);
        assertTrue(result.startsWith("Error: file not found"),
                "Should reject paths outside MEMORY.md and memory/YYYY-MM-DD.md");
    }

    @Test
    void memoryGet_dailyLedgerPath_readsFromLedgerTable() throws Exception {
        List<LedgerRow> rows = List.of(
                new LedgerRow("alice", "2026-07-23", "host", "first entry", LocalDateTime.now()),
                new LedgerRow("alice", "2026-07-23", "host", "second entry", LocalDateTime.now()));
        when(mysqlMemoryStore.readLedgerForDate("alice", "2026-07-23")).thenReturn(rows);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "memory/2026-07-23.md", 1, 10);
        assertTrue(result.contains("first entry"), "Should read from ledger table");
        assertTrue(result.contains("second entry"), "Should read all rows for the date");
    }

    @Test
    void memoryGet_emptyPath_returnsError() {
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String result = tool.memoryGet(ctx, "", 1, 10);
        assertTrue(result.startsWith("Error: path is required"));
    }
}
