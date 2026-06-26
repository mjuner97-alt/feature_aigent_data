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
package com.agentscopea2a.harness.cache;

import com.agentscopea2a.agent.dimension.DimensionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL-backed response cache service that enables query result reuse within the same day.
 *
 * <p>Cache keys are generated from {@link DimensionState} (produced by {@code
 * DimensionStateManager.analyzeQuestionRuleBased()}), eliminating duplicate dimension extraction
 * logic. This service only handles MySQL read/write; dimension extraction is the sole responsibility
 * of {@code DimensionStateManager}.
 *
 * <p>Cache expiry is aligned to the current day boundary: entries created anytime today expire at
 * 23:59:59 today. A new day starts fresh.
 */
public class ResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheService.class);

    // ==================== Intent Keywords ====================

    private static final String[] ANALYZE_KEYWORDS = {"分析", "报告", "趋势", "对比", "环比", "归因", "建议", "原因", "为什么"};
    private static final String[] SKILL_KEYWORDS = {"保存", "skill", "技能", "工作流"};

    /**
     * Intents whose answers depend on more than just the dimension set — comparisons, trend, and
     * cause-style questions can carry the same dimensions yet ask very different things ("查 Q1
     * 数据" vs "对比 Q1 与 Q4"). For these we mix a hash of the user question into the cache key so
     * differently-worded analytical asks never collide.
     */
    private static final Set<String> QUESTION_HASHED_INTENTS = Set.of("analyze");

    private final DataSource dataSource;
    private volatile boolean tableEnsured = false;

    public ResponseCacheService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== Cache Read/Write ====================

    /**
     * Retrieves a cached response for the given cache key.
     *
     * @param cacheKey the generated cache key
     * @return cached response text if found and not expired, empty otherwise
     */
    public Optional<String> get(String cacheKey) {
        ensureTable();
        String sql =
                "SELECT response FROM response_cache WHERE cache_key = ? AND expire_at > NOW()";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String response = rs.getString("response");
                    log.info("Response cache HIT for key={}", cacheKey);
                    return Optional.of(response);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to read cache for key={}: {}", cacheKey, e.getMessage());
        }
        log.debug("Response cache MISS for key={}", cacheKey);
        return Optional.empty();
    }

    /**
     * Stores a response in the cache. The entry expires at the end of the current day (23:59:59).
     *
     * @param cacheKey the generated cache key
     * @param question the original user question
     * @param responseText the agent's complete response text
     */
    public void put(String cacheKey, String question, String responseText) {
        ensureTable();
        // Delete any existing entry for this key (overwrite from today)
        String deleteSql = "DELETE FROM response_cache WHERE cache_key = ?";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, cacheKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete old cache for key={}: {}", cacheKey, e.getMessage());
            return;
        }

        String insertSql =
                "INSERT INTO response_cache (cache_key, question, response, expire_at) VALUES (?,"
                        + " ?, ?, ?)";
        LocalDateTime expireAt = LocalDateTime.of(LocalDate.now(), LocalTime.of(23, 59, 59));
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, cacheKey);
            ps.setString(2, question);
            ps.setString(3, responseText);
            ps.setTimestamp(4, Timestamp.valueOf(expireAt));
            ps.executeUpdate();
            log.info("Response cached for key={}, expires at {}", cacheKey, expireAt);
        } catch (SQLException e) {
            log.warn("Failed to write cache for key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Removes expired cache entries. Can be called periodically or on startup.
     */
    public void cleanExpired() {
        ensureTable();
        String sql = "DELETE FROM response_cache WHERE expire_at < NOW()";
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Cleaned {} expired cache entries", deleted);
            }
        } catch (SQLException e) {
            log.warn("Failed to clean expired cache: {}", e.getMessage());
        }
    }

    // ==================== Cache Key Generation ====================

    /**
     * Generates a deterministic cache key from a pre-extracted {@link DimensionState} and intent.
     *
     * <p>Dimension extraction is done by {@code DimensionStateManager.analyzeQuestionRuleBased()};
     * this method only serializes the result into a cache key string.
     *
     * <p>Returns empty if the state has no dimensions — non-dimensional questions (e.g., "数据服务表清单下载")
     * are not cacheable because different questions would collide on the same key.
     *
     * <p>For analytical intents (compare / trend / cause), the same dimension set can map to many
     * differently-worded asks ("查 Q1" vs "对比 Q1 与 Q4"). To prevent those from colliding, we mix
     * an SHA-256 hash of the trimmed user question into the key.
     *
     * @param intent classified intent (query/analyze/skill)
     * @param state dimension state extracted by DimensionStateManager
     * @param question original user question — used to disambiguate analytical asks
     * @return a deterministic cache key if dimensions were found, empty otherwise
     */
    public Optional<String> generateCacheKey(String intent, DimensionState state, String question) {
        if (state == null || !state.hasDimensions()) {
            log.debug("No dimensions in state, skipping cache");
            return Optional.empty();
        }

        String stateKey = state.toCacheKey();
        StringBuilder cacheKey = new StringBuilder(intent).append("|").append(stateKey);
        if (QUESTION_HASHED_INTENTS.contains(intent) && question != null && !question.isBlank()) {
            cacheKey.append("|q=").append(shortHash(question));
        }
        return Optional.of(cacheKey.toString());
    }

    /** Backwards-compatible overload — analytical intents will not be question-hashed. */
    public Optional<String> generateCacheKey(String intent, DimensionState state) {
        return generateCacheKey(intent, state, null);
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.trim().getBytes(StandardCharsets.UTF_8));
            // 8 bytes = 16 hex chars — collision odds negligible at our cache scale.
            byte[] head = new byte[8];
            System.arraycopy(digest, 0, head, 0, 8);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this branch is unreachable on conformant runtimes.
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * Classifies the intent of a user question based on simple keyword matching.
     *
     * <p>This is independent of dimension extraction — it only determines the question type for cache
     * key differentiation.
     *
     * @param question the user's question text
     * @return "query", "analyze", or "skill"
     */
    public static String classifyIntent(String question) {
        String lower = question.toLowerCase();
        for (String keyword : SKILL_KEYWORDS) {
            if (lower.contains(keyword)) {
                return "skill";
            }
        }
        for (String keyword : ANALYZE_KEYWORDS) {
            if (question.contains(keyword)) {
                return "analyze";
            }
        }
        return "query";
    }

    /**
     * Extracts the user question text from a list of messages (takes the last user message).
     *
     * <p>Skips synthetic memory-recall messages that AgentScope's episodic memory injects as a
     * USER-role message at the tail (recognized by the canonical "Below is content retrieved from
     * the long-term memory" prefix). Without this skip, multi-turn calls would fingerprint /
     * intent-extract on the memory recall blob instead of the user's actual input.
     */
    public static String extractUserQuestion(List<io.agentscope.core.message.Msg> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            io.agentscope.core.message.Msg msg = messages.get(i);
            if (msg.getRole() == io.agentscope.core.message.MsgRole.USER) {
                String text = msg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    if (text.startsWith("Below is content retrieved from the long-term memory")) {
                        continue;
                    }
                    return text;
                }
            }
        }
        return "";
    }

    // ==================== DB Helpers ====================

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            String sql =
                    """
                    CREATE TABLE IF NOT EXISTS response_cache (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        cache_key   VARCHAR(512)  NOT NULL,
                        question    VARCHAR(1024) NOT NULL,
                        response    MEDIUMTEXT    NOT NULL,
                        created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                        expire_at   TIMESTAMP     NOT NULL,
                        UNIQUE KEY uk_cache_key (cache_key),
                        INDEX idx_expire (expire_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """;
            try (Connection conn = getConnection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                tableEnsured = true;
                log.info("response_cache table ensured");
            } catch (SQLException e) {
                log.warn("Failed to create response_cache table: {}", e.getMessage());
            }
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
