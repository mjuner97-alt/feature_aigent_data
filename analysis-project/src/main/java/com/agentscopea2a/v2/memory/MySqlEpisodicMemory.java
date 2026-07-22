/*
 * Source code recreated from a .class file by IntelliJ IDEA
 * (powered by Fernflower decompiler), then locally maintained.
 */
package com.agentscopea2a.v2.memory;

import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.EpisodicResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.agentscopea2a.v2.exception.MemoryPersistenceException;
import com.agentscopea2a.v2.skills.EmbeddingClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-backed implementation of {@link EpisodicMemory}.
 *
 * <p>Two construction modes:
 *
 * <ul>
 *   <li>{@link #MySqlEpisodicMemory(DataSource)} / {@link #MySqlEpisodicMemory(DataSource,
 *       EpisodicMemoryConfig)} - Spring-managed pool. Preferred.
 *   <li>{@link #MySqlEpisodicMemory(EpisodicMemoryConfig)} - driver-manager fallback when the
 *       config carries an explicit {@code jdbcUrl}/{@code username}/{@code password}.
 * </ul>
 *
 * <p><b>P2-1 split:</b> DDL extracted to {@link EpisodicTableInitializer}, FTS/vector search
 * to {@link EpisodicSearcher}. This class keeps the public API, record-session transactions,
 * connection management, and the {@link EpisodicMemoryTools} tool surface.
 */
public class MySqlEpisodicMemory implements EpisodicMemory {
    private static final Logger log = LoggerFactory.getLogger(MySqlEpisodicMemory.class);
    private static final String NAME = "episodic";

    private final DataSource dataSource;
    private final EpisodicMemoryConfig config;
    private final EmbeddingClient embeddingClient;
    private final EpisodicTableInitializer tableInitializer;
    private final EpisodicSearcher searcher;

    public MySqlEpisodicMemory(DataSource dataSource) {
        this(dataSource, EpisodicMemoryConfig.builder().jdbcUrl("datasource-provided").build(), null);
    }

    public MySqlEpisodicMemory(EpisodicMemoryConfig config) {
        this(null, config, null);
    }

    public MySqlEpisodicMemory(DataSource dataSource, EpisodicMemoryConfig config) {
        this(dataSource, config, null);
    }

    public MySqlEpisodicMemory(DataSource dataSource, EpisodicMemoryConfig config, EmbeddingClient embeddingClient) {
        this.dataSource = dataSource;
        this.config = config;
        this.embeddingClient = embeddingClient;
        this.tableInitializer = new EpisodicTableInitializer(this::getConnection, config.getTableName());
        this.searcher = new EpisodicSearcher(this::getConnection, config, embeddingClient);
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
        // No caller-supplied id -> fall back to thread+timestamp so each turn lands in a unique
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
                            tableInitializer.ensureInitialized();
                            String userId = EpisodicTableInitializer.parseUserIdFromSessionId(sessionId);
                            String sql =
                                    "INSERT INTO "
                                            + this.config.getTableName()
                                            + " (session_id, user_id, role, content, embedding) VALUES (?, ?, ?, ?, ?)";
                            // Manual transaction so executeBatch() is atomic - partial-batch
                            // failures (e.g. one row violates a constraint) roll back the
                            // whole batch instead of leaving some rows committed. Connection.close
                            // rolls back if we never call commit().
                            try (Connection conn = getConnection()) {
                                conn.setAutoCommit(false);
                                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                                    for (Msg msg : messages) {
                                        if (msg == null) continue;
                                        if (msg.getRole() != MsgRole.USER
                                                && msg.getRole() != MsgRole.ASSISTANT
                                                && msg.getRole() != MsgRole.TOOL) {
                                            continue;
                                        }
                                        String content = msg.getTextContent();
                                        if (content == null || content.isEmpty()) {
                                            // For TOOL messages, try extracting ToolResultBlock text
                                            if (msg.getRole() == MsgRole.TOOL) {
                                                content = extractToolResultText(msg);
                                            }
                                            if (content == null || content.isEmpty()) continue;
                                        }
                                        stmt.setString(1, sessionId);
                                        stmt.setString(2, userId);
                                        stmt.setString(3, msg.getRole().name());
                                        stmt.setString(4, content);
                                        // Embedding: best-effort, async-friendly
                                        String embeddingJson = searcher.embedContent(content);
                                        stmt.setString(5, embeddingJson);
                                        stmt.addBatch();
                                    }
                                    stmt.executeBatch();
                                    conn.commit();
                                    log.debug(
                                            "Recorded session {} (user={}) with {} messages",
                                            sessionId, userId, messages.size());
                                } catch (SQLException e) {
                                    conn.rollback();
                                    throw e;
                                }
                            } catch (SQLException e) {
                                log.error(
                                        "Failed to record session {}: {}",
                                        sessionId,
                                        e.getMessage());
                                throw new MemoryPersistenceException("Failed to record session", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<EpisodicResult>> search(String query, int limit) {
        return search(null, query, limit);
    }

    @Override
    public Mono<List<EpisodicResult>> search(String userId, String query, int limit) {
        return Mono.fromCallable(
                        () -> {
                            tableInitializer.ensureInitialized();
                            // Try vector search first when enabled and embedding client is available
                            if (config.isVectorSearchEnabled() && embeddingClient != null) {
                                List<EpisodicResult> vectorHits = searcher.vectorSearch(userId, query, limit);
                                if (!vectorHits.isEmpty()) return vectorHits;
                            }
                            // Fallback to FTS
                            return searcher.ftsSearch(userId, query, limit);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Extract tool name and output text from a TOOL-role message's ToolResultBlock content.
     * Formats as: "[TOOL: tool_name] output_text..."
     * Returns empty string when no text can be extracted.
     */
    private String extractToolResultText(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof ToolResultBlock trb) {
                String name = trb.getName();
                if (name != null && !name.isBlank()) {
                    sb.append("[TOOL: ").append(name).append("] ");
                }
                for (var output : trb.getOutput()) {
                    if (output instanceof TextBlock tb) {
                        sb.append(tb.getText());
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Like {@link #recordSession}, but also stores a tool_call_details JSON blob alongside
     * the session for later use by skill distillation (paths B and C).
     */
    public Mono<Void> recordSessionWithToolContext(String sessionId, List<Msg> messages,
                                                    String toolCallDetailsJson) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            tableInitializer.ensureInitialized();
                            boolean hasToolContext = toolCallDetailsJson != null && !toolCallDetailsJson.isBlank();
                            String userId = EpisodicTableInitializer.parseUserIdFromSessionId(sessionId);
                            String sql;
                            if (hasToolContext) {
                                sql = "INSERT INTO " + this.config.getTableName()
                                        + " (session_id, user_id, role, content, embedding, tool_call_details) VALUES (?, ?, ?, ?, ?, ?)";
                            } else {
                                sql = "INSERT INTO " + this.config.getTableName()
                                        + " (session_id, user_id, role, content, embedding) VALUES (?, ?, ?, ?, ?)";
                            }
                            // Manual transaction so executeBatch() is atomic - see recordSession.
                            try (Connection conn = getConnection()) {
                                conn.setAutoCommit(false);
                                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                                    for (Msg msg : messages) {
                                        if (msg == null) continue;
                                        if (msg.getRole() != MsgRole.USER
                                                && msg.getRole() != MsgRole.ASSISTANT
                                                && msg.getRole() != MsgRole.TOOL) {
                                            continue;
                                        }
                                        String content = msg.getTextContent();
                                        if (content == null || content.isEmpty()) {
                                            // For TOOL messages, try extracting ToolResultBlock text
                                            if (msg.getRole() == MsgRole.TOOL) {
                                                content = extractToolResultText(msg);
                                            }
                                            if (content == null || content.isEmpty()) continue;
                                        }
                                        stmt.setString(1, sessionId);
                                        stmt.setString(2, userId);
                                        stmt.setString(3, msg.getRole().name());
                                        stmt.setString(4, content);
                                        // Embedding: best-effort, async-friendly
                                        String embeddingJson = searcher.embedContent(content);
                                        stmt.setString(5, embeddingJson);
                                        // Write tool_call_details only on first row of the session
                                        if (hasToolContext && msg.getRole() == MsgRole.USER) {
                                            stmt.setString(6, toolCallDetailsJson);
                                        } else if (hasToolContext) {
                                            stmt.setString(6, null);
                                        }
                                        stmt.addBatch();
                                    }
                                    stmt.executeBatch();
                                    conn.commit();
                                    log.debug(
                                            "Recorded session {} (user={}) with {} messages",
                                            sessionId, userId, messages.size());
                                } catch (SQLException e) {
                                    conn.rollback();
                                    throw e;
                                }
                            } catch (SQLException e) {
                                log.error(
                                        "Failed to record session {}: {}",
                                        sessionId,
                                        e.getMessage());
                                throw new MemoryPersistenceException("Failed to record session", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Search for the most recent session whose content matches the given fingerprint text and
     * has non-null tool_call_details. Returns the tool_call_details JSON, or empty string.
     * Used by SkillSynthesisRunner (Path B) to provide context-aware distillation without
     * needing a separate storage table.
     */
    public Mono<String> searchToolContextByFingerprint(String fingerprintText) {
        if (fingerprintText == null || fingerprintText.isBlank()) return Mono.just("");
        return Mono.fromCallable(
                        () -> {
                            tableInitializer.ensureInitialized();
                            String sql = "SELECT tool_call_details FROM " + this.config.getTableName()
                                    + " WHERE content LIKE ?"
                                    + " AND tool_call_details IS NOT NULL"
                                    + " AND tool_call_details != ''"
                                    + " ORDER BY id DESC LIMIT 1";
                            try (Connection conn = getConnection();
                                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                                stmt.setString(1, "%" + fingerprintText + "%");
                                try (ResultSet rs = stmt.executeQuery()) {
                                    if (rs.next()) {
                                        String details = rs.getString("tool_call_details");
                                        return details != null ? details : "";
                                    }
                                }
                            } catch (SQLException e) {
                                log.debug("searchToolContextByFingerprint failed: {}", e.getMessage());
                            }
                            return "";
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<Msg>> getSession(String sessionId) {
        return Mono.fromCallable(
                        () -> {
                            tableInitializer.ensureInitialized();
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

        /** Single fence delimiter - keep stable so prompt edits don't drift. */
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
