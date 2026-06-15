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
package com.agentscopea2a.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.session.ListHashUtil;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * MySQL-backed implementation of the {@link Session} interface.
 *
 * <p>Uses a single {@code session_state_list} table to store both single state objects
 * and lists of state objects, with incremental append support for lists via hash-based
 * change detection (same algorithm as {@code JsonSession}).
 *
 * <p>Storage conventions:
 * <ul>
 *   <li>Single state: one row with {@code item_order=0}, full replacement on save</li>
 *   <li>List state: multiple rows ordered by {@code item_order}, with incremental
 *       append when only new items are added</li>
 * </ul>
 *
 * <p>Required table:
 * <pre>{@code
 * CREATE TABLE session_state_list (
 *     id          BIGINT AUTO_INCREMENT PRIMARY KEY,
 *     session_id  VARCHAR(255) NOT NULL,
 *     state_key   VARCHAR(255) NOT NULL,
 *     item_order  INT          NOT NULL,
 *     item_json   MEDIUMTEXT   NOT NULL,
 *     list_hash   VARCHAR(64)  DEFAULT NULL,
 *     created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
 *     UNIQUE KEY uk_session_key_order (session_id, state_key, item_order),
 *     INDEX idx_session_key (session_id, state_key)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 * }</pre>
 */
public class MySQLSession implements Session {

    private static final Logger log = LoggerFactory.getLogger(MySQLSession.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    private volatile boolean tableEnsured = false;

    public MySQLSession(DataSource dataSource) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // Table creation deferred to first use — allows Spring context to start
        // without a running MySQL instance (e.g. local skeleton verification).
        // See ensureTableUsing().
    }

    // ==================== Single State ====================

    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionId = sessionKey.toIdentifier();
        try (Connection conn = getConnection()) {
            deleteByKey(conn, sessionId, key);
            insertSingle(conn, sessionId, key, value);
        } catch (Exception e) {
            // Fail-soft: a flaky/unavailable DB shouldn't kill the agent call. The next
            // restart simply won't see this state — same semantics as a brand-new session.
            log.warn(
                    "Failed to save state '{}' for session {}: {}", key, sessionId, e.getMessage());
        }
    }

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionId = sessionKey.toIdentifier();
        String sql =
                "SELECT item_json FROM session_state_list"
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_order LIMIT 1";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(objectMapper.readValue(rs.getString("item_json"), type));
                }
            }
        } catch (Exception e) {
            // Fail-soft: treat unreachable DB as "no prior state". HarnessAgent's session
            // hook already tolerates Optional.empty() — it just won't restore conversation.
            log.warn("Failed to get state '{}' for session {}: {}", key, sessionId, e.getMessage());
        }
        return Optional.empty();
    }

    // ==================== List State (incremental append) ====================

    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            delete(sessionKey, key);
            return;
        }

        String sessionId = sessionKey.toIdentifier();
        String currentHash = ListHashUtil.computeHash(values);

        try (Connection conn = getConnection()) {
            int existingCount = getExistingCount(conn, sessionId, key);
            String storedHash = getStoredHash(conn, sessionId, key);

            if (ListHashUtil.needsFullRewrite(values, storedHash, existingCount)) {
                deleteByKey(conn, sessionId, key);
                insertItems(conn, sessionId, key, values, 0, currentHash);
                log.debug(
                        "Full rewrite for session={}, key={}, count={}",
                        sessionId,
                        key,
                        values.size());
            } else if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList(existingCount, values.size());
                insertItems(conn, sessionId, key, newItems, existingCount, currentHash);
                updateHash(conn, sessionId, key, currentHash);
                log.debug(
                        "Appended {} items for session={}, key={}",
                        newItems.size(),
                        sessionId,
                        key);
            } else {
                log.debug("No change for session={}, key={}", sessionId, key);
            }
        } catch (Exception e) {
            log.warn("Failed to save list '{}' for session {}: {}", key, sessionId, e.getMessage());
        }
    }

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionId = sessionKey.toIdentifier();
        String sql =
                "SELECT item_json FROM session_state_list"
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_order";
        List<T> result = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(objectMapper.readValue(rs.getString("item_json"), itemType));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get list '{}' for session {}: {}", key, sessionId, e.getMessage());
        }
        return result;
    }

    // ==================== Session lifecycle ====================

    @Override
    public boolean exists(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String sql = "SELECT 1 FROM session_state_list WHERE session_id = ? LIMIT 1";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("Failed to check session existence for {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public void delete(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String sql = "DELETE FROM session_state_list WHERE session_id = ?";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void delete(SessionKey sessionKey, String key) {
        String sessionId = sessionKey.toIdentifier();
        try (Connection conn = getConnection()) {
            deleteByKey(conn, sessionId, key);
        } catch (SQLException e) {
            log.warn(
                    "Failed to delete state '{}' for session {}: {}",
                    key,
                    sessionId,
                    e.getMessage());
        }
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        String sql = "SELECT DISTINCT session_id FROM session_state_list";
        Set<SessionKey> keys = new HashSet<>();
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(SimpleSessionKey.of(rs.getString("session_id")));
            }
        } catch (SQLException e) {
            log.warn("Failed to list session keys: {}", e.getMessage());
        }
        return keys;
    }

    // ==================== Internal helpers ====================

    private void ensureTableUsing(Connection conn) throws SQLException {
        String sql =
                """
                CREATE TABLE IF NOT EXISTS session_state_list (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id  VARCHAR(255) NOT NULL,
                    state_key   VARCHAR(255) NOT NULL,
                    item_order  INT          NOT NULL,
                    item_json   MEDIUMTEXT   NOT NULL,
                    list_hash   VARCHAR(64)  DEFAULT NULL,
                    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_session_key_order (session_id, state_key, item_order),
                    INDEX idx_session_key (session_id, state_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void deleteByKey(Connection conn, String sessionId, String key) throws SQLException {
        String sql = "DELETE FROM session_state_list WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    private void insertSingle(Connection conn, String sessionId, String key, State value)
            throws Exception {
        String sql =
                "INSERT INTO session_state_list (session_id, state_key, item_order, item_json)"
                        + " VALUES (?, ?, 0, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            ps.setString(3, objectMapper.writeValueAsString(value));
            ps.executeUpdate();
        }
    }

    private void insertItems(
            Connection conn,
            String sessionId,
            String key,
            List<? extends State> items,
            int startOrder,
            String hash)
            throws Exception {
        String sql =
                "INSERT INTO session_state_list"
                        + " (session_id, state_key, item_order, item_json, list_hash)"
                        + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < items.size(); i++) {
                ps.setString(1, sessionId);
                ps.setString(2, key);
                ps.setInt(3, startOrder + i);
                ps.setString(4, objectMapper.writeValueAsString(items.get(i)));
                ps.setString(5, hash);
                ps.addBatch();
                if (i > 0 && i % 50 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void updateHash(Connection conn, String sessionId, String key, String hash)
            throws SQLException {
        String sql =
                "UPDATE session_state_list SET list_hash = ?"
                        + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, sessionId);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    private int getExistingCount(Connection conn, String sessionId, String key)
            throws SQLException {
        String sql =
                "SELECT COUNT(*) FROM session_state_list"
                        + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String getStoredHash(Connection conn, String sessionId, String key)
            throws SQLException {
        String sql =
                "SELECT list_hash FROM session_state_list"
                        + " WHERE session_id = ? AND state_key = ? AND list_hash IS NOT NULL"
                        + " LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        if (!tableEnsured) {
            ensureTableUsing(conn);
            tableEnsured = true;
        }
        return conn;
    }
}
