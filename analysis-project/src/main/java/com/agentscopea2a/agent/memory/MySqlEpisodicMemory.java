/*
 * Source code recreated from a .class file by IntelliJ IDEA
 * (powered by Fernflower decompiler), then locally maintained.
 */
package com.agentscopea2a.agent.memory;

import io.agentscope.core.memory.EpisodicMemory;
import io.agentscope.core.memory.EpisodicResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-backed implementation of {@link EpisodicMemory}.
 *
 * <p>Two construction modes:
 *
 * <ul>
 *   <li>{@link #MySqlEpisodicMemory(DataSource)} / {@link #MySqlEpisodicMemory(DataSource,
 *       EpisodicMemoryConfig)} — Spring-managed pool. Preferred.
 *   <li>{@link #MySqlEpisodicMemory(EpisodicMemoryConfig)} — driver-manager fallback when the
 *       config carries an explicit {@code jdbcUrl}/{@code username}/{@code password}.
 * </ul>
 */
public class MySqlEpisodicMemory implements EpisodicMemory {
    private static final Logger log = LoggerFactory.getLogger(MySqlEpisodicMemory.class);
    private static final String NAME = "episodic";
    private static final int SNIPPET_MAX_LEN = 200;

    private final DataSource dataSource;
    private final EpisodicMemoryConfig config;
    private volatile boolean initialized;

    public MySqlEpisodicMemory(DataSource dataSource) {
        this(dataSource, EpisodicMemoryConfig.builder().jdbcUrl("datasource-provided").build());
    }

    public MySqlEpisodicMemory(EpisodicMemoryConfig config) {
        this(null, config);
    }

    public MySqlEpisodicMemory(DataSource dataSource, EpisodicMemoryConfig config) {
        this.initialized = false;
        this.dataSource = dataSource;
        this.config = config;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String systemPromptBlock() {
        return "You have access to episodic memory that stores past conversation history. "
                + "You can search through previous conversations using the episodic_search tool "
                + "to recall what was discussed in earlier sessions.";
    }

    @Override
    public Mono<String> prefetch(String query) {
        return search(query, this.config.getSearchLimit())
                .map(
                        results -> {
                            if (results.isEmpty()) {
                                return "";
                            }
                            StringBuilder sb = new StringBuilder();
                            sb.append("## Past Conversations\n\n");
                            for (EpisodicResult result : results) {
                                sb.append("**Session ")
                                        .append(result.sessionId())
                                        .append("** (")
                                        .append(result.timestamp())
                                        .append("):\n");
                                sb.append(result.snippet()).append("\n\n");
                            }
                            return sb.toString();
                        });
    }

    @Override
    public Mono<Void> syncTurn(Msg userMsg, Msg assistantMsg) {
        List<Msg> messages = new ArrayList<>();
        if (userMsg != null) {
            messages.add(userMsg);
        }
        if (assistantMsg != null) {
            messages.add(assistantMsg);
        }
        if (messages.isEmpty()) {
            return Mono.empty();
        }
        // No caller-supplied id → fall back to thread+timestamp so each turn lands in a unique
        // logical session. Callers that care about session continuity should use
        // recordSession(sessionId, ...) directly (the LongTermMemory adapter does this).
        String sessionId =
                Thread.currentThread().getName() + "_" + System.currentTimeMillis();
        return recordSession(sessionId, messages);
    }

    @Override
    public Mono<Void> recordSession(String sessionId, List<Msg> messages) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            ensureInitialized();
                            String sql =
                                    "INSERT INTO "
                                            + this.config.getTableName()
                                            + " (session_id, role, content) VALUES (?, ?, ?)";
                            try (Connection conn = getConnection();
                                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                                for (Msg msg : messages) {
                                    if (msg == null) continue;
                                    if (msg.getRole() != MsgRole.USER
                                            && msg.getRole() != MsgRole.ASSISTANT) {
                                        continue;
                                    }
                                    String content = msg.getTextContent();
                                    if (content == null || content.isEmpty()) continue;
                                    stmt.setString(1, sessionId);
                                    stmt.setString(2, msg.getRole().name());
                                    stmt.setString(3, content);
                                    stmt.addBatch();
                                }
                                stmt.executeBatch();
                                log.debug(
                                        "Recorded session {} with {} messages",
                                        sessionId,
                                        messages.size());
                            } catch (SQLException e) {
                                log.error(
                                        "Failed to record session {}: {}",
                                        sessionId,
                                        e.getMessage());
                                throw new RuntimeException("Failed to record session", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<EpisodicResult>> search(String query, int limit) {
        return Mono.fromCallable(
                        () -> {
                            ensureInitialized();
                            String sql =
                                    "SELECT session_id, content, "
                                            + "MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) AS score, "
                                            + "created_at FROM "
                                            + this.config.getTableName()
                                            + " WHERE MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) "
                                            + "ORDER BY score DESC LIMIT ?";
                            List<EpisodicResult> results = new ArrayList<>();
                            try (Connection conn = getConnection();
                                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                                stmt.setString(1, query);
                                stmt.setString(2, query);
                                stmt.setInt(3, limit);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    while (rs.next()) {
                                        String sessionId = rs.getString("session_id");
                                        String content = rs.getString("content");
                                        double score = rs.getDouble("score");
                                        Timestamp ts = rs.getTimestamp("created_at");
                                        LocalDateTime timestamp =
                                                ts != null
                                                        ? ts.toLocalDateTime()
                                                        : LocalDateTime.now();
                                        String snippet =
                                                content != null
                                                                && content.length() > SNIPPET_MAX_LEN
                                                        ? content.substring(0, SNIPPET_MAX_LEN)
                                                                + "..."
                                                        : content;
                                        results.add(
                                                new EpisodicResult(
                                                        sessionId, snippet, score, timestamp));
                                    }
                                }
                                return results;
                            } catch (SQLException e) {
                                log.error(
                                        "Failed to search episodic memory: {}", e.getMessage());
                                throw new RuntimeException(
                                        "Failed to search episodic memory", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<Msg>> getSession(String sessionId) {
        return Mono.fromCallable(
                        () -> {
                            ensureInitialized();
                            String sql =
                                    "SELECT role, content FROM "
                                            + this.config.getTableName()
                                            + " WHERE session_id = ? ORDER BY id ASC";
                            List<Msg> messages = new ArrayList<>();
                            try (Connection conn = getConnection();
                                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                                stmt.setString(1, sessionId);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    while (rs.next()) {
                                        String role = rs.getString("role");
                                        String content = rs.getString("content");
                                        Msg msg =
                                                Msg.builder()
                                                        .role(MsgRole.valueOf(role))
                                                        .content(
                                                                TextBlock.builder()
                                                                        .text(content)
                                                                        .build())
                                                        .build();
                                        messages.add(msg);
                                    }
                                }
                                return messages;
                            } catch (SQLException e) {
                                log.error(
                                        "Failed to get session {}: {}",
                                        sessionId,
                                        e.getMessage());
                                throw new RuntimeException("Failed to get session", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public List<Object> getToolObjects() {
        return List.of(new EpisodicMemoryTools());
    }

    private synchronized void ensureInitialized() {
        if (!this.initialized) {
            createTableIfNotExists();
            this.initialized = true;
        }
    }

    private void createTableIfNotExists() {
        String sql =
                "CREATE TABLE IF NOT EXISTS "
                        + this.config.getTableName()
                        + " (  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  session_id VARCHAR(255) NOT NULL,"
                        + "  role VARCHAR(50) NOT NULL,"
                        + "  content TEXT NOT NULL,"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  FULLTEXT INDEX ft_content (content)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info(
                    "Ensured episodic memory table '{}' exists", this.config.getTableName());
        } catch (SQLException e) {
            log.error("Failed to create episodic memory table: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize episodic memory table", e);
        }
    }

    private Connection getConnection() throws SQLException {
        if (this.dataSource != null) {
            return this.dataSource.getConnection();
        }
        return DriverManager.getConnection(
                this.config.getJdbcUrl(),
                this.config.getUsername(),
                this.config.getPassword());
    }

    /**
     * Tool surface exposed to the agent. Wraps {@link MySqlEpisodicMemory#search(String, int)} as
     * a fenced markdown block so the model can't confuse retrieved content with its own
     * instructions.
     */
    private class EpisodicMemoryTools {

        /** Single fence delimiter — keep stable so prompt edits don't drift. */
        private static final String FENCE = "<<<EPISODIC_MEMORY";

        private static final String FENCE_END = "EPISODIC_MEMORY>>>";

        @Tool(
                name = "episodic_search",
                description =
                        "Search past conversation history across all sessions. Use this to "
                                + "recall what was discussed in earlier conversations with the user.")
        public Mono<String> searchEpisodic(
                @ToolParam(name = "query", description = "Search query keywords") String query,
                @ToolParam(
                                name = "limit",
                                description = "Maximum number of results to return",
                                required = false)
                        Integer limit) {
            int effectiveLimit =
                    limit != null
                            ? limit
                            : MySqlEpisodicMemory.this.config.getSearchLimit();
            return MySqlEpisodicMemory.this
                    .search(query, effectiveLimit)
                    .map(
                            results -> {
                                if (results.isEmpty()) {
                                    return "No past conversations found for: " + query;
                                }
                                StringBuilder sb = new StringBuilder();
                                sb.append(FENCE).append('\n');
                                for (EpisodicResult result : results) {
                                    sb.append("[Session: ")
                                            .append(result.sessionId())
                                            .append(", ")
                                            .append(result.timestamp())
                                            .append("]\n");
                                    sb.append(result.snippet()).append("\n\n");
                                }
                                sb.append(FENCE_END);
                                return sb.toString();
                            });
        }
    }
}
