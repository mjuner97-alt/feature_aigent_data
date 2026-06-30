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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Comparator;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<float[]> FLOAT_ARRAY = new TypeReference<>() {};

    private final DataSource dataSource;
    private final EpisodicMemoryConfig config;
    private final EmbeddingClient embeddingClient;
    private volatile boolean initialized;

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
        this.initialized = false;
        this.dataSource = dataSource;
        this.config = config;
        this.embeddingClient = embeddingClient;
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
                                            + " (session_id, role, content, embedding) VALUES (?, ?, ?, ?)";
                            try (Connection conn = getConnection();
                                    PreparedStatement stmt = conn.prepareStatement(sql)) {
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
                                    stmt.setString(2, msg.getRole().name());
                                    stmt.setString(3, content);
                                    // Embedding: best-effort, async-friendly
                                    String embeddingJson = embedContent(content);
                                    stmt.setString(4, embeddingJson);
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
                            // Try vector search first when enabled and embedding client is available
                            if (config.isVectorSearchEnabled() && embeddingClient != null) {
                                List<EpisodicResult> vectorHits = vectorSearch(query, limit);
                                if (!vectorHits.isEmpty()) return vectorHits;
                            }
                            // Fallback to FTS
                            return ftsSearch(query, limit);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<EpisodicResult> ftsSearch(String query, int limit) {
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
                    results.add(buildResult(rs));
                }
            }
        } catch (SQLException e) {
            log.error("FTS search failed: {}", e.getMessage());
            throw new RuntimeException("Failed to search episodic memory", e);
        }
        return results;
    }

    private List<EpisodicResult> vectorSearch(String query, int limit) {
        float[] queryVec = embeddingClient.embed(query);
        if (queryVec == null || queryVec.length == 0) return List.of();
        String sql =
                "SELECT session_id, content, embedding, created_at FROM "
                        + this.config.getTableName()
                        + " WHERE embedding IS NOT NULL ORDER BY id DESC LIMIT ?";
        List<EpisodicResult> candidates = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit * 10); // sample a wider pool
            try (ResultSet rs = stmt.executeQuery()) {
                float qNorm = norm(queryVec);
                if (qNorm == 0f) return List.of();
                while (rs.next()) {
                    String json = rs.getString("embedding");
                    if (json == null || json.isBlank()) continue;
                    float[] vec;
                    try {
                        vec = MAPPER.readValue(json, FLOAT_ARRAY);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (vec.length != queryVec.length) continue;
                    float cos = cosine(queryVec, vec, qNorm);
                    if (cos >= config.getVectorMinCosine()) {
                        String sessionId = rs.getString("session_id");
                        String content = rs.getString("content");
                        Timestamp ts = rs.getTimestamp("created_at");
                        LocalDateTime timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                        String snippet = content != null && content.length() > SNIPPET_MAX_LEN
                                ? content.substring(0, SNIPPET_MAX_LEN) + "..."
                                : content;
                        candidates.add(new EpisodicResult(sessionId, snippet, cos, timestamp));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Vector search failed, falling back to FTS: {}", e.getMessage());
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble(EpisodicResult::relevance).reversed());
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    private static float norm(float[] v) {
        double s = 0d;
        for (float x : v) s += x * x;
        return (float) Math.sqrt(s);
    }

    private static float cosine(float[] a, float[] b, float aNorm) {
        double dot = 0d;
        double bNorm = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            bNorm += b[i] * b[i];
        }
        double denom = aNorm * Math.sqrt(bNorm);
        return denom == 0d ? 0f : (float) (dot / denom);
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

    /** Best-effort embedding of content; returns null when embedding is disabled or fails. */
    private String embedContent(String content) {
        if (!config.isVectorSearchEnabled() || embeddingClient == null) return null;
        if (content == null || content.isBlank()) return null;
        try {
            float[] vec = embeddingClient.embed(content);
            if (vec == null || vec.length == 0) return null;
            return MAPPER.writeValueAsString(vec);
        } catch (Exception ex) {
            log.debug("Embedding failed for content ({} chars): {}", content.length(), ex.getMessage());
            return null;
        }
    }

    private static EpisodicResult buildResult(ResultSet rs) throws SQLException {
        String sessionId = rs.getString("session_id");
        String content = rs.getString("content");
        double score = rs.getDouble("score");
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
        String snippet = content != null && content.length() > SNIPPET_MAX_LEN
                ? content.substring(0, SNIPPET_MAX_LEN) + "..."
                : content;
        return new EpisodicResult(sessionId, snippet, score, timestamp);
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
                        + "  embedding LONGTEXT DEFAULT NULL,"
                        + "  status VARCHAR(16) DEFAULT 'active',"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  FULLTEXT INDEX ft_content (content),"
                        + "  INDEX idx_embedding (embedding(255)),"
                        + "  INDEX idx_status (status)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            // Add status column if upgrading from an older schema that lacks it
            try {
                stmt.execute(
                        "ALTER TABLE " + this.config.getTableName()
                                + " ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'active'"
                                + " AFTER embedding");
            } catch (SQLException ignored) {
                // IF NOT EXISTS not supported in all MySQL 5.x; fallback: ignore "duplicate column"
                String msg = ignored.getMessage();
                if (msg != null && msg.contains("Duplicate column")) {
                    // expected on upgrade — column already exists
                } else {
                    log.debug("ALTER TABLE ADD COLUMN status skipped: {}", ignored.getMessage());
                }
            }
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
