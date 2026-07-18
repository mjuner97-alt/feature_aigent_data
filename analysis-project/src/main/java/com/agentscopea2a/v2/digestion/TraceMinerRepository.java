/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.digestion;

import com.agentscopea2a.v2.digestion.TraceMinerTypes.RawMessage;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.RawSession;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.TraceGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JDBC layer for {@link TraceMiner}. Extracted from {@code TraceMiner} (P2-1) so the
 * orchestration class is free of SQL strings and ResultSet mapping.
 *
 * <p>Three responsibilities:
 * <ul>
 *   <li>{@link #loadSessions} - read episodic_memory rows for a date (+ optional user filter),
 *       grouped by session_id, with synthetic TOOL messages expanded from
 *       {@code tool_call_details} JSON</li>
 *   <li>{@link #upsertGroups} - batch upsert aggregated trace groups into user_trace_summary</li>
 *   <li>{@link #ensureUserTraceSummaryTable} - idempotent DDL + ALTER for schema drift</li>
 * </ul>
 */
final class TraceMinerRepository {

    private static final Logger log = LoggerFactory.getLogger(TraceMinerRepository.class);

    /** Session prefix injected by HarnessA2aRunner.recordCacheHitToEpisodic - skip these. */
    static final String CACHE_HIT_PREFIX = "cache-hit:";

    /** Prefixes used by {@code SupervisorService.ltmBucketFor()} to encode user/session scope. */
    static final String USER_PREFIX = "user:";
    static final String SESSION_PREFIX = "session:";

    /**
     * Pattern to detect subagent dispatch from supervisor text.
     * Matches patterns like "派单给 **query_data**", "派单给 `query_data`", or "派单给 query_data".
     */
    private static final Pattern SUBAGENT_DISPATCH =
            Pattern.compile("派单给\\s*[\\*`]{0,2}([a-z_]+)[\\*`]{0,2}");

    private final DataSource dataSource;
    private final String tableName;
    private final ObjectMapper objectMapper;

    TraceMinerRepository(DataSource dataSource, String tableName, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.objectMapper = objectMapper;
    }

    /**
     * Extract the real user id from a session_id produced by
     * {@code SupervisorService.ltmBucketFor()}.
     *
     * <ul>
     *   <li>{@code "user:u-1024"} -> {@code "u-1024"}</li>
     *   <li>{@code "session:abc"}  -> {@code null} (session-scoped, no user identity)</li>
     *   <li>{@code "anonymous"}    -> {@code null}</li>
     *   <li>{@code "cache-hit:..."} -> {@code null}</li>
     *   <li>anything else          -> {@code null}</li>
     * </ul>
     */
    static String extractUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (sessionId.startsWith(USER_PREFIX)) {
            String rest = sessionId.substring(USER_PREFIX.length());
            // Handle "user:userId:conversationId" - take only the userId segment
            int colonIdx = rest.indexOf(':');
            String uid = colonIdx >= 0 ? rest.substring(0, colonIdx) : rest;
            return uid.isBlank() ? null : uid;
        }
        return null;
    }

    /**
     * Load non-cache-hit, finished (or finished-looking) sessions from the episodic memory table
     * for the given date. When {@code userId} is non-blank, filters to sessions whose
     * {@code session_id} starts with {@code "user:&lt;userId&gt;"}.
     *
     * @param sessionBuilder callback that turns (sessionId, messages) into a RawSession.
     *                       The builder is responsible for pattern-matching the messages
     *                       to extract tool IDs, classify failure, etc. Returns null to
     *                       drop the session.
     */
    List<RawSession> loadSessions(LocalDate date, String userId,
                                  java.util.function.BiFunction<String, List<RawMessage>, RawSession> sessionBuilder) {
        // Match both legacy "user:userId" and per-request "user:userId:conversationId" formats.
        // The LIKE pattern matches the exact legacy form OR any per-request variant.
        StringBuilder sqlSb = new StringBuilder(
                "SELECT id, session_id, role, content, tool_call_details, created_at FROM ")
                .append(tableName)
                .append(" WHERE DATE(created_at) = ?")
                .append(" AND session_id NOT LIKE ?")
                .append(" AND role IN ('USER', 'ASSISTANT', 'TOOL')");
        // Filter by userId when specified
        if (userId != null && !userId.isBlank()) {
            sqlSb.append(" AND (session_id = ? OR session_id LIKE ?)");
        }
        sqlSb.append(" ORDER BY session_id, id ASC");
        String sql = sqlSb.toString();

        Map<String, List<RawMessage>> grouped = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.setString(2, CACHE_HIT_PREFIX + "%");
            if (userId != null && !userId.isBlank()) {
                String exactSessionId = USER_PREFIX + userId;
                ps.setString(3, exactSessionId);
                ps.setString(4, exactSessionId + ":%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("session_id");
                    String role = rs.getString("role");
                    String content = rs.getString("content");
                    if (content == null || content.isBlank()) continue;
                    grouped.computeIfAbsent(sid, k -> new ArrayList<>())
                            .add(new RawMessage(role, content));
                    // Synthesize TOOL messages from tool_call_details JSON so that
                    // classifyTrace() sees tool results and can classify the session
                    // as successful. Auto-written episodic rows store tool calls as
                    // JSON on the USER row (not as separate TOOL role rows).
                    if ("USER".equals(role)) {
                        String detailsJson = rs.getString("tool_call_details");
                        if (detailsJson != null && !detailsJson.isBlank()) {
                            for (RawMessage toolMsg : parseToolCallDetails(detailsJson)) {
                                grouped.get(sid).add(toolMsg);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("TraceMiner.loadSessions({}, {}) failed: {}", date, userId, e.getMessage());
            return List.of();
        }
        return grouped.entrySet().stream()
                .map(e -> sessionBuilder.apply(e.getKey(), e.getValue()))
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    /**
     * Parse the {@code tool_call_details} JSON stored on the USER row of episodic
     * memory into synthetic TOOL {@link RawMessage}s. Emits {@code [TOOL: <name>]}
     * bracket format so {@code TraceMiner.buildSession} can extract tool IDs, and
     * {@code TraceClassifier} can inspect outputs for error/exit-code signals.
     */
    private List<RawMessage> parseToolCallDetails(String json) {
        List<RawMessage> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return result;
            for (JsonNode node : root) {
                String tool = node.path("tool").asText("");
                if (tool.isBlank()) continue;
                String output = node.path("output").asText("");
                String input = node.path("input").asText("");
                String level = node.path("level").asText("L1");
                StringBuilder sb = new StringBuilder("[TOOL: ").append(tool).append("] ");
                if ("agent_spawn".equals(tool)) {
                    // buildSession expects "agent_id: <name>" for subagent extraction
                    sb.append("agent_id: ").append(extractAgentId(input)).append(' ');
                }
                if (!output.isBlank()) {
                    sb.append(output);
                } else {
                    sb.append("level=").append(level).append(" input=").append(input);
                }
                result.add(new RawMessage("TOOL", sb.toString()));
            }
        } catch (IOException e) {
            log.debug("parseToolCallDetails failed: {}", e.getMessage());
        }
        return result;
    }

    /** Extract the subagent name from an agent_spawn input string like "派单给 query_data". */
    private static String extractAgentId(String input) {
        if (input == null || input.isBlank()) return "unknown";
        Matcher m = SUBAGENT_DISPATCH.matcher(input);
        if (m.find()) return m.group(1);
        return "unknown";
    }

    int upsertGroups(Map<String, TraceGroup> groups, LocalDate date, String userId) {
        String sql = "INSERT INTO user_trace_summary"
                + " (user_id, date_key, fingerprint, runtime_fingerprint, tool_sequence, success_count, failure_count,"
                + "  sample_query, user_query, tool_call_details, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')"
                + " ON DUPLICATE KEY UPDATE"
                + "  success_count = VALUES(success_count),"
                + "  failure_count = VALUES(failure_count),"
                + "  sample_query = VALUES(sample_query),"
                + "  user_query = VALUES(user_query),"
                + "  tool_call_details = VALUES(tool_call_details),"
                + "  tool_sequence = VALUES(tool_sequence),"
                + "  runtime_fingerprint = COALESCE(runtime_fingerprint, VALUES(runtime_fingerprint)),"
                + "  status = CASE WHEN status = 'pending' THEN 'pending' ELSE status END";
        int count = 0;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (TraceGroup g : groups.values()) {
                // Count actual successful vs failed sessions from per-session classification
                int totalSuccess = g.totalSuccess;
                int totalFailure = g.sessions.size() - g.totalSuccess;
                // Ensure at least 1 failure count so status stays pending for review
                totalFailure = Math.max(totalFailure, g.totalFailureCount > 0 ? 1 : 0);
                ps.setString(1, userId != null ? userId : "_batch");
                ps.setString(2, date.toString());
                ps.setString(3, g.fingerprint);
                ps.setString(4, g.runtimeFingerprint);  // may be null
                ps.setString(5, String.join(",", g.toolSequence));
                ps.setInt(6, Math.max(0, totalSuccess));
                ps.setInt(7, Math.max(1, totalFailure));
                ps.setString(8, g.sampleQuery());
                ps.setString(9, g.userQuery != null ? g.userQuery : "");
                ps.setString(10, toToolCallDetailsJson(g.details));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("TraceMiner.upsertGroups failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Ensure the {@code user_trace_summary} and {@code digestion_log} tables exist.
     * Also migrates existing tables with ALTER TABLE ADD COLUMN (idempotent).
     */
    void ensureUserTraceSummaryTable() {
        String traceDdl = "CREATE TABLE IF NOT EXISTS user_trace_summary ("
                + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "  user_id VARCHAR(128) NOT NULL,"
                + "  date_key VARCHAR(16) NOT NULL,"
                + "  fingerprint VARCHAR(255) NOT NULL,"
                + "  runtime_fingerprint VARCHAR(255) DEFAULT NULL COMMENT 'metric fingerprint for L1 skill lookup',"
                + "  tool_sequence TEXT NOT NULL,"
                + "  success_count INT NOT NULL DEFAULT 0,"
                + "  failure_count INT NOT NULL DEFAULT 0,"
                + "  failure_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,"
                + "  sample_query TEXT,"
                + "  user_query TEXT,"
                + "  tool_call_details LONGTEXT,"
                + "  status VARCHAR(16) DEFAULT 'pending',"
                + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "  UNIQUE KEY uk_user_date_fp (user_id, date_key, fingerprint),"
                + "  KEY idx_user_date (user_id, date_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        String logDdl = "CREATE TABLE IF NOT EXISTS digestion_log ("
                + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "  user_id VARCHAR(128) NOT NULL,"
                + "  date_key VARCHAR(16) NOT NULL,"
                + "  phase1_cleaned_ledger INT DEFAULT 0,"
                + "  phase2_mined_traces INT DEFAULT 0,"
                + "  phase3_skills_evolved INT DEFAULT 0,"
                + "  phase4_memory_digested TINYINT(1) DEFAULT 0,"
                + "  started_at TIMESTAMP NOT NULL,"
                + "  completed_at TIMESTAMP NULL,"
                + "  error_msg TEXT,"
                + "  KEY idx_user_date (user_id, date_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute(traceDdl);
            st.execute(logDdl);
            // Idempotent ALTER TABLE for legacy tables that lack the new columns
            String[] alterSqls = {
                "ALTER TABLE user_trace_summary ADD COLUMN user_query TEXT AFTER sample_query",
                "ALTER TABLE user_trace_summary ADD COLUMN tool_call_details LONGTEXT AFTER user_query",
                "ALTER TABLE user_trace_summary ADD COLUMN runtime_fingerprint VARCHAR(255) DEFAULT NULL AFTER fingerprint"
            };
            for (String sql : alterSqls) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    // Column already exists - ignore
                }
            }
        } catch (SQLException e) {
            log.warn("TraceMiner DDL failed: {}", e.getMessage());
        }
    }

    /**
     * Serialise tool call details to JSON for storage in user_trace_summary.tool_call_details.
     */
    String toToolCallDetailsJson(List<com.agentscopea2a.v2.digestion.TraceMinerTypes.ToolCallDetail> details) {
        if (details == null || details.isEmpty()) return "";
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.debug("toToolCallDetailsJson failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Deserialise tool call details from JSON stored in user_trace_summary.tool_call_details.
     */
    static List<com.agentscopea2a.v2.digestion.TraceMinerTypes.ToolCallDetail> fromToolCallDetailsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.agentscopea2a.v2.digestion.TraceMinerTypes.ToolCallDetail>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
